/*
 * ADR-0002 S0 Windows host launcher: starts a child in an AppContainer with no capabilities,
 * inside a Job Object (memory cap, one active process, kill on close, UI restrictions).
 *
 *   launcher.exe <none|job|container|lpac> <memMiB> <child.exe> [args...]
 *
 * "none" is the control (plain child), "job" adds only the Job Object, "container" adds the
 * AppContainer, and "lpac" makes it a Low Privilege AppContainer: it opts out of the
 * ALL APPLICATION PACKAGES group, so default grants (for example parts of HKCU) no longer apply.
 * The child's stdout/stderr are relayed; the child gets a minimal environment. The child joins the
 * Job at creation (PROC_THREAD_ATTRIBUTE_JOB_LIST), so there is no window in which it runs, or sits
 * suspended, outside the Job; any failure after creation terminates it.
 * Test only: SHINSOU_S0_FAIL_AFTER_CREATE=1 forces that post-creation failure path.
 * SHINSOU_S0_CPU_RATE=<percent> adds a hard CPU rate cap to the Job.
 *
 *   launcher.exe <mode> <memMiB> --bench <helper.exe> <bootstrap.js>
 *
 * drives helper.c over a stdin/stdout pipe pair instead of relaying output, and prints BENCH lines
 * (cold start, invoke and sync-bridge round trips, helper memory).
 * Prints `LAUNCH mode=... exit=... ms=... peak_mib=...` after the child ends.
 */
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <windows.h>
#include <aclapi.h>
#include <psapi.h>
#include <stdint.h>
#include <sddl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <userenv.h>
#include <wchar.h>

#define PROFILE_NAME L"Shinsou.Sandbox.S0"

static void kill_child(void);

static void die(const char *what) {
    fprintf(stderr, "launcher: %s failed (%lu)\n", what, GetLastError());
    kill_child();
    exit(2);
}

static PSID container_sid(void) {
    PSID sid = NULL;
    HRESULT hr = CreateAppContainerProfile(PROFILE_NAME, L"Shinsou S0", L"Shinsou S0 sandbox probe", NULL, 0, &sid);
    if (hr == HRESULT_FROM_WIN32(ERROR_ALREADY_EXISTS)) hr = DeriveAppContainerSidFromAppContainerName(PROFILE_NAME, &sid);
    if (FAILED(hr)) { fprintf(stderr, "launcher: AppContainer profile failed (0x%08lx)\n", (unsigned long)hr); exit(2); }
    return sid;
}

/* An AppContainer can only read/execute files whose ACL grants its SID: grant it on one directory. */
static void grant_read_execute(const wchar_t *dir, PSID sid) {
    PACL old = NULL, acl = NULL;
    PSECURITY_DESCRIPTOR sd = NULL;
    if (GetNamedSecurityInfoW(dir, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, NULL, NULL, &old, NULL, &sd) != ERROR_SUCCESS) die("GetNamedSecurityInfo");
    EXPLICIT_ACCESSW ea = { 0 };
    ea.grfAccessPermissions = FILE_GENERIC_READ | FILE_GENERIC_EXECUTE;
    ea.grfAccessMode = GRANT_ACCESS;
    ea.grfInheritance = SUB_CONTAINERS_AND_OBJECTS_INHERIT;
    ea.Trustee.TrusteeForm = TRUSTEE_IS_SID;
    ea.Trustee.TrusteeType = TRUSTEE_IS_WELL_KNOWN_GROUP;
    ea.Trustee.ptstrName = (LPWSTR)sid;
    if (SetEntriesInAclW(1, &ea, old, &acl) != ERROR_SUCCESS) die("SetEntriesInAcl");
    if (SetNamedSecurityInfoW((LPWSTR)dir, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, NULL, NULL, acl, NULL) != ERROR_SUCCESS) die("SetNamedSecurityInfo");
    LocalFree(acl);
    LocalFree(sd);
}

static HANDLE child_process; /* terminated by die() once created */

static void kill_child(void) {
    if (!child_process) return;
    TerminateProcess(child_process, 0xDEAD);
    WaitForSingleObject(child_process, 5000);
    CloseHandle(child_process);
    child_process = NULL;
}

static HANDLE make_job(SIZE_T mem_mib) {
    HANDLE job = CreateJobObjectW(NULL, NULL);
    if (!job) die("CreateJobObject");
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION x = { 0 };
    x.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_PROCESS_MEMORY | JOB_OBJECT_LIMIT_ACTIVE_PROCESS |
        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_DIE_ON_UNHANDLED_EXCEPTION;
    x.BasicLimitInformation.ActiveProcessLimit = 1; /* the child cannot start another process */
    x.ProcessMemoryLimit = mem_mib << 20;
    if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &x, sizeof x)) die("SetInformationJobObject");
    JOBOBJECT_BASIC_UI_RESTRICTIONS ui = { JOB_OBJECT_UILIMIT_DESKTOP | JOB_OBJECT_UILIMIT_DISPLAYSETTINGS |
        JOB_OBJECT_UILIMIT_EXITWINDOWS | JOB_OBJECT_UILIMIT_GLOBALATOMS | JOB_OBJECT_UILIMIT_HANDLES |
        JOB_OBJECT_UILIMIT_READCLIPBOARD | JOB_OBJECT_UILIMIT_SYSTEMPARAMETERS | JOB_OBJECT_UILIMIT_WRITECLIPBOARD };
    if (!SetInformationJobObject(job, JobObjectBasicUIRestrictions, &ui, sizeof ui)) die("SetInformationJobObject(UI)");
    wchar_t rate[16];
    if (GetEnvironmentVariableW(L"SHINSOU_S0_CPU_RATE", rate, 16)) {
        JOBOBJECT_CPU_RATE_CONTROL_INFORMATION c = { 0 };
        c.ControlFlags = JOB_OBJECT_CPU_RATE_CONTROL_ENABLE | JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP;
        c.CpuRate = (DWORD)_wtoi(rate) * 100; /* units of 1/100 percent of all processors */
        if (!SetInformationJobObject(job, JobObjectCpuRateControlInformation, &c, sizeof c)) die("SetInformationJobObject(cpu rate)");
    }
    return job;
}

/* ---- --bench: drive helper.c (frame = type u8, len u32 LE, payload) ---- */
static HANDLE bench_to, bench_from;
static char *bench_buf; /* scratch for result payloads */

static int b_write(const void *p, DWORD n) {
    const char *c = p;
    while (n) { DWORD put = 0; if (!WriteFile(bench_to, c, n, &put, NULL) || !put) return -1; c += put; n -= put; }
    return 0;
}

static int b_read(void *p, DWORD n) {
    char *c = p;
    while (n) { DWORD got = 0; if (!ReadFile(bench_from, c, n, &got, NULL) || !got) return -1; c += got; n -= got; }
    return 0;
}

static int b_send(char type, const void *a, uint32_t alen, const void *b, uint32_t blen) {
    uint32_t len = alen + blen;
    if (b_write(&type, 1) || b_write(&len, 4)) return -1;
    if (alen && b_write(a, alen)) return -1;
    if (blen && b_write(b, blen)) return -1;
    return 0;
}

/* Waits for 'R', answering the helper's sync bridge calls ('b') with a fixed reply. */
static int b_result(uint8_t *status, uint32_t *len) {
    for (;;) {
        char type; uint32_t n;
        if (b_read(&type, 1) || b_read(&n, 4) || n > (8u << 20)) return -1;
        if (n && b_read(bench_buf, n)) return -1;
        if (type == 'b') { if (b_send('B', "\"r\"", 3, NULL, 0)) return -1; continue; }
        if (type != 'R' || n < 1) return -1;
        *status = (uint8_t)bench_buf[0];
        *len = n - 1;
        return 0;
    }
}

static int b_invoke(const char *method, const char *args, uint8_t *status, uint32_t *len) {
    if (b_send('V', method, (uint32_t)strlen(method) + 1, args, (uint32_t)strlen(args))) return -1;
    return b_result(status, len);
}

static double since_ms(LARGE_INTEGER a) {
    LARGE_INTEGER f, b;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&b);
    return (double)(b.QuadPart - a.QuadPart) * 1000.0 / f.QuadPart;
}

static int cmp_double(const void *a, const void *b) {
    double x = *(const double *)a, y = *(const double *)b;
    return x < y ? -1 : x > y;
}

/* Median of n timed invocations of method(args); -1 on any failure or a result of the wrong size. */
static double b_median(const char *method, const char *args, uint32_t expect_len, int n) {
    double t[256];
    for (int i = 0; i < n; i++) {
        uint8_t st; uint32_t len;
        LARGE_INTEGER t0; QueryPerformanceCounter(&t0);
        if (b_invoke(method, args, &st, &len) || st != 0 || len != expect_len) {
            fprintf(stderr, "bench: %s%s -> status %u len %u (want %u)\n", method, args, st, len, expect_len);
            return -1;
        }
        t[i] = since_ms(t0);
    }
    qsort(t, (size_t)n, sizeof t[0], cmp_double);
    return t[n / 2];
}

static int run_bench(const wchar_t *bootstrap_path, HANDLE proc, LARGE_INTEGER created) {
    static const char script[] =
        "var source={ok:function(){return 'ok';},big:function(n){return 'y'.repeat(n);},"
        "bridgeN:function(n){for(var i=0;i<n;i++)bridge.httpGet('x');return n;}};";
    bench_buf = malloc(8u << 20);
    FILE *f = _wfopen(bootstrap_path, L"rb");
    if (!f || !bench_buf) return -1;
    fseek(f, 0, SEEK_END); long blen = ftell(f); fseek(f, 0, SEEK_SET);
    char *boot = malloc((size_t)blen + 4 + sizeof script);
    uint32_t b32 = (uint32_t)blen;
    memcpy(boot, &b32, 4);
    if (fread(boot + 4, 1, (size_t)blen, f) != (size_t)blen) return -1;
    fclose(f);
    memcpy(boot + 4 + blen, script, sizeof script - 1);
    uint8_t st; uint32_t len;
    if (b_send('I', boot, (uint32_t)(4 + blen + sizeof script - 1), NULL, 0) || b_result(&st, &len) || st != 0) {
        fprintf(stderr, "bench: init failed (status %u)\n", st);
        return -1;
    }
    double init_ms = since_ms(created);
    if (b_invoke("ok", "[]", &st, &len) || st != 0) return -1;
    printf("BENCH cold_start_to_init_ms=%.1f to_first_invoke_ms=%.1f\n", init_ms, since_ms(created));
    /* Expected JSON sizes: "ok" = 4, a string of n chars = n + 2, the number 1000 = 4. */
    double ok = b_median("ok", "[]", 4, 200);
    printf("BENCH invoke_ok_median_ms=%.3f\n", ok);
    double r64 = b_median("big", "[65536]", 65536 + 2, 31);
    printf("BENCH result_64KiB_median_ms=%.3f\n", r64);
    double r1m = b_median("big", "[1048576]", 1048576 + 2, 15);
    printf("BENCH result_1MiB_median_ms=%.3f\n", r1m);
    double br = b_median("bridgeN", "[1000]", 4, 5);
    printf("BENCH sync_bridge_round_trip_us=%.1f\n", (br - ok) * 1000.0 / 1000.0);
    PROCESS_MEMORY_COUNTERS_EX pmc = { sizeof pmc };
    if (GetProcessMemoryInfo(proc, (PROCESS_MEMORY_COUNTERS *)&pmc, sizeof pmc))
        printf("BENCH helper_working_set_mib=%.1f private_mib=%.1f peak_working_set_mib=%.1f\n",
               pmc.WorkingSetSize / 1048576.0, pmc.PrivateUsage / 1048576.0, pmc.PeakWorkingSetSize / 1048576.0);
    b_send('Q', NULL, 0, NULL, 0);
    if (ok < 0 || r64 < 0 || r1m < 0 || br < 0) return -1;
    printf("BENCH done\n");
    return 0;
}

int wmain(int argc, wchar_t **argv) {
    if (argc < 4) { fprintf(stderr, "usage: launcher <none|job|container|lpac> <memMiB> [--bench] <child.exe> [args...]\n"); return 2; }
    int bench = wcscmp(argv[3], L"--bench") == 0;
    if (bench && argc < 6) { fprintf(stderr, "usage: launcher <mode> <memMiB> --bench <helper.exe> <bootstrap.js>\n"); return 2; }
    int child_at = bench ? 4 : 3, child_end = bench ? 5 : argc;
    const wchar_t *mode = argv[1];
    SIZE_T mem = (SIZE_T)_wtoi(argv[2]);
    int use_job = wcscmp(mode, L"none") != 0;
    int use_lpac = wcscmp(mode, L"lpac") == 0;
    int use_container = use_lpac || wcscmp(mode, L"container") == 0;

    /* Secrets that must stay out of reach: an env var and a file in the user profile. */
    SetEnvironmentVariableW(L"SHINSOU_SECRET_TOKEN", L"s0-secret-value");

    wchar_t cmd[32768] = L"";
    for (int i = child_at; i < child_end; i++) {
        wcscat_s(cmd, 32768, L"\"");
        wcscat_s(cmd, 32768, argv[i]);
        wcscat_s(cmd, 32768, L"\" ");
    }

    PSID sid = NULL;
    if (use_container) {
        sid = container_sid();
        wchar_t dir[MAX_PATH];
        wcscpy_s(dir, MAX_PATH, argv[child_at]);
        wchar_t *slash = wcsrchr(dir, L'\\');
        if (slash) *slash = 0;
        grant_read_execute(dir, sid);
    }

    SECURITY_ATTRIBUTES sa = { sizeof sa, NULL, TRUE };
    HANDLE rd, wr;
    if (!CreatePipe(&rd, &wr, &sa, 0)) die("CreatePipe");
    SetHandleInformation(rd, HANDLE_FLAG_INHERIT, 0);
    HANDLE child_in = NULL; /* --bench: the child's stdin; we keep the write end */
    if (bench) {
        if (!CreatePipe(&child_in, &bench_to, &sa, 0)) die("CreatePipe(stdin)");
        SetHandleInformation(bench_to, HANDLE_FLAG_INHERIT, 0);
        bench_from = rd;
    }

    SIZE_T attr_size = 0;
    InitializeProcThreadAttributeList(NULL, 4, 0, &attr_size);
    LPPROC_THREAD_ATTRIBUTE_LIST attrs = (LPPROC_THREAD_ATTRIBUTE_LIST)HeapAlloc(GetProcessHeap(), 0, attr_size);
    if (!InitializeProcThreadAttributeList(attrs, 4, 0, &attr_size)) die("InitializeProcThreadAttributeList");
    SECURITY_CAPABILITIES caps = { 0 };
    if (use_container) {
        caps.AppContainerSid = sid; /* CapabilityCount = 0: in particular no internetClient */
        if (!UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES, &caps, sizeof caps, NULL, NULL)) die("UpdateProcThreadAttribute(caps)");
    }
    static DWORD lpac_policy = PROCESS_CREATION_ALL_APPLICATION_PACKAGES_OPT_OUT;
    if (use_lpac &&
        !UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_ALL_APPLICATION_PACKAGES_POLICY, &lpac_policy, sizeof lpac_policy, NULL, NULL))
        die("UpdateProcThreadAttribute(lpac)");
    /* Only the pipe ends meant for the child are inherited. */
    HANDLE inherit[2] = { wr, child_in };
    if (!UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, inherit, (bench ? 2 : 1) * sizeof(HANDLE), NULL, NULL)) die("UpdateProcThreadAttribute(handles)");
    /* Job membership is part of process creation (Windows 10+): no unassigned-child window. */
    HANDLE job = use_job ? make_job(mem) : NULL;
    if (job && !UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_JOB_LIST, &job, sizeof job, NULL, NULL)) die("UpdateProcThreadAttribute(job)");

    STARTUPINFOEXW si = { 0 };
    si.StartupInfo.cb = sizeof si;
    si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    si.StartupInfo.hStdInput = child_in;
    si.StartupInfo.hStdOutput = wr;
    si.StartupInfo.hStdError = bench ? NULL : wr; /* stderr must not corrupt the bench protocol */
    si.lpAttributeList = attrs;

    /*
     * Minimal environment: paths only, no host secrets. CreateProcess for an AppContainer derives the
     * container's own LOCALAPPDATA/TEMP from these and fails with ERROR_ENVVAR_NOT_FOUND without them.
     */
    static const wchar_t *const keep[] = { L"SystemRoot", L"SystemDrive", L"USERPROFILE", L"LOCALAPPDATA", L"APPDATA", L"TEMP", L"TMP" };
    wchar_t env[8192];
    size_t pos = 0;
    for (size_t k = 0; k < sizeof keep / sizeof keep[0]; k++) {
        wchar_t value[MAX_PATH] = L"";
        if (!GetEnvironmentVariableW(keep[k], value, MAX_PATH)) continue;
        int n = swprintf_s(env + pos, 8192 - pos - 1, L"%ls=%ls", keep[k], value);
        if (n > 0) pos += (size_t)n + 1;
    }
    env[pos] = 0;

    LARGE_INTEGER f, t0, t1;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&t0);
    PROCESS_INFORMATION pi;
    if (!CreateProcessW(NULL, cmd, NULL, NULL, TRUE, EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW,
                        env, NULL, &si.StartupInfo, &pi)) die("CreateProcess");
    child_process = pi.hProcess;
    BOOL in_job = FALSE;
    if (job && (!IsProcessInJob(pi.hProcess, job, &in_job) || !in_job)) die("IsProcessInJob");
    if (GetEnvironmentVariableW(L"SHINSOU_S0_FAIL_AFTER_CREATE", NULL, 0)) { SetLastError(ERROR_INVALID_FUNCTION); die("forced post-creation failure"); }
    if (ResumeThread(pi.hThread) == (DWORD)-1) die("ResumeThread");
    CloseHandle(wr);
    int bench_failed = 0;
    if (bench) {
        CloseHandle(child_in);
        bench_failed = run_bench(argv[5], pi.hProcess, t0) != 0;
        CloseHandle(bench_to);
    }

    char buf[4096];
    DWORD got;
    while (ReadFile(rd, buf, sizeof buf, &got, NULL) && got) if (!bench) fwrite(buf, 1, got, stdout);
    WaitForSingleObject(pi.hProcess, INFINITE);
    QueryPerformanceCounter(&t1);
    DWORD code = 0;
    GetExitCodeProcess(pi.hProcess, &code);
    child_process = NULL;
    double peak = 0;
    if (job) {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION x;
        if (QueryInformationJobObject(job, JobObjectExtendedLimitInformation, &x, sizeof x, NULL))
            peak = (double)x.PeakProcessMemoryUsed / 1048576.0;
    }
    printf("LAUNCH mode=%ls exit=%lu ms=%.1f peak_mib=%.1f\n", mode, code, (double)(t1.QuadPart - t0.QuadPart) * 1000.0 / f.QuadPart, peak);
    if (sid) FreeSid(sid);
    if (bench_failed) { printf("BENCH failed\n"); return 1; }
    return (int)code;
}
