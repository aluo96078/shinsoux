/*
 * ADR-0002 S0 Windows host launcher: starts a child in an AppContainer with no capabilities,
 * inside a Job Object (memory cap, one active process, kill on close, UI restrictions).
 *
 *   launcher.exe <none|job|container> <memMiB> <child.exe> [args...]
 *
 * "none" is the control (plain child), "job" adds only the Job Object, "container" adds the
 * AppContainer. The child's stdout/stderr are relayed; the child gets a minimal environment.
 * Prints `LAUNCH mode=... exit=... ms=... peak_mib=...` after the child ends.
 */
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <windows.h>
#include <aclapi.h>
#include <sddl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <userenv.h>
#include <wchar.h>

#define PROFILE_NAME L"Shinsou.Sandbox.S0"

static void die(const char *what) {
    fprintf(stderr, "launcher: %s failed (%lu)\n", what, GetLastError());
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
    return job;
}

int wmain(int argc, wchar_t **argv) {
    if (argc < 4) { fprintf(stderr, "usage: launcher <none|job|container> <memMiB> <child.exe> [args...]\n"); return 2; }
    const wchar_t *mode = argv[1];
    SIZE_T mem = (SIZE_T)_wtoi(argv[2]);
    int use_job = wcscmp(mode, L"none") != 0;
    int use_container = wcscmp(mode, L"container") == 0;

    /* Secrets that must stay out of reach: an env var and a file in the user profile. */
    SetEnvironmentVariableW(L"SHINSOU_SECRET_TOKEN", L"s0-secret-value");

    wchar_t cmd[32768] = L"";
    for (int i = 3; i < argc; i++) {
        wcscat_s(cmd, 32768, L"\"");
        wcscat_s(cmd, 32768, argv[i]);
        wcscat_s(cmd, 32768, L"\" ");
    }

    PSID sid = NULL;
    if (use_container) {
        sid = container_sid();
        wchar_t dir[MAX_PATH];
        wcscpy_s(dir, MAX_PATH, argv[3]);
        wchar_t *slash = wcsrchr(dir, L'\\');
        if (slash) *slash = 0;
        grant_read_execute(dir, sid);
    }

    SECURITY_ATTRIBUTES sa = { sizeof sa, NULL, TRUE };
    HANDLE rd, wr;
    if (!CreatePipe(&rd, &wr, &sa, 0)) die("CreatePipe");
    SetHandleInformation(rd, HANDLE_FLAG_INHERIT, 0);

    SIZE_T attr_size = 0;
    InitializeProcThreadAttributeList(NULL, 2, 0, &attr_size);
    LPPROC_THREAD_ATTRIBUTE_LIST attrs = (LPPROC_THREAD_ATTRIBUTE_LIST)HeapAlloc(GetProcessHeap(), 0, attr_size);
    if (!InitializeProcThreadAttributeList(attrs, 2, 0, &attr_size)) die("InitializeProcThreadAttributeList");
    SECURITY_CAPABILITIES caps = { 0 };
    if (use_container) {
        caps.AppContainerSid = sid; /* CapabilityCount = 0: in particular no internetClient */
        if (!UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES, &caps, sizeof caps, NULL, NULL)) die("UpdateProcThreadAttribute(caps)");
    }
    /* Only the pipe's write end is inherited. */
    HANDLE inherit[1] = { wr };
    if (!UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, inherit, sizeof inherit, NULL, NULL)) die("UpdateProcThreadAttribute(handles)");

    STARTUPINFOEXW si = { 0 };
    si.StartupInfo.cb = sizeof si;
    si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    si.StartupInfo.hStdInput = NULL;
    si.StartupInfo.hStdOutput = wr;
    si.StartupInfo.hStdError = wr;
    si.lpAttributeList = attrs;

    /* Minimal environment: nothing from the host besides what the CRT needs. */
    wchar_t sysroot[MAX_PATH] = L"";
    GetEnvironmentVariableW(L"SystemRoot", sysroot, MAX_PATH);
    wchar_t env[1024];
    int n = swprintf_s(env, 1024, L"SystemRoot=%s", sysroot);
    env[n + 1] = 0;

    HANDLE job = use_job ? make_job(mem) : NULL;
    LARGE_INTEGER f, t0, t1;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&t0);
    PROCESS_INFORMATION pi;
    if (!CreateProcessW(NULL, cmd, NULL, NULL, TRUE, EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW,
                        env, NULL, &si.StartupInfo, &pi)) die("CreateProcess");
    if (job && !AssignProcessToJobObject(job, pi.hProcess)) die("AssignProcessToJobObject");
    ResumeThread(pi.hThread);
    CloseHandle(wr);

    char buf[4096];
    DWORD got;
    while (ReadFile(rd, buf, sizeof buf, &got, NULL) && got) fwrite(buf, 1, got, stdout);
    WaitForSingleObject(pi.hProcess, INFINITE);
    QueryPerformanceCounter(&t1);
    DWORD code = 0;
    GetExitCodeProcess(pi.hProcess, &code);
    double peak = 0;
    if (job) {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION x;
        if (QueryInformationJobObject(job, JobObjectExtendedLimitInformation, &x, sizeof x, NULL))
            peak = (double)x.PeakProcessMemoryUsed / 1048576.0;
    }
    printf("LAUNCH mode=%ls exit=%lu ms=%.1f peak_mib=%.1f\n", mode, code, (double)(t1.QuadPart - t0.QuadPart) * 1000.0 / f.QuadPart, peak);
    if (sid) FreeSid(sid);
    return (int)code;
}
