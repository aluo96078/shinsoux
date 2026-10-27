/*
 * Windows confinement of the desktop sandbox helper (ADR-0002 §4.4, S3), as S0 validated it: a
 * Low Privilege AppContainer with no capabilities inside a Job object.
 *
 * A Windows process cannot put itself into an AppContainer, so ssc_os_lockdown first runs as a
 * launcher: it starts this same executable again, confined, with `--ssc-confined` as its first
 * argument, gives it the launcher's stdin/stdout and nothing else, and waits for it. The launcher
 * never reads input. The confined copy does not trust that argument: before returning it checks
 * its own token (AppContainer, no capabilities, outside ALL APPLICATION PACKAGES) and its Job.
 *
 * The Job kills the copy when its last handle closes, so killing the launcher (the host's
 * Process.destroyForcibly) kills the copy too; if the host dies, the copy's stdin ends and the
 * driver stops it.
 */
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <aclapi.h>
#include <sddl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <wchar.h>

#include "ssc_os_sandbox.h"

/* Windows 10 SDK values that older MinGW headers lack. */
#ifndef PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES
#define PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES 0x00020009
#endif
#ifndef PROC_THREAD_ATTRIBUTE_JOB_LIST
#define PROC_THREAD_ATTRIBUTE_JOB_LIST 0x0002000D
#endif
#ifndef PROC_THREAD_ATTRIBUTE_ALL_APPLICATION_PACKAGES_POLICY
#define PROC_THREAD_ATTRIBUTE_ALL_APPLICATION_PACKAGES_POLICY 0x0002000F
#endif
#ifndef PROCESS_CREATION_ALL_APPLICATION_PACKAGES_OPT_OUT
#define PROCESS_CREATION_ALL_APPLICATION_PACKAGES_OPT_OUT 0x01
#endif
#ifndef PROC_THREAD_ATTRIBUTE_MITIGATION_POLICY
#define PROC_THREAD_ATTRIBUTE_MITIGATION_POLICY 0x00020007
#endif

#ifndef SSC_WIN_DIAG
#define SSC_WIN_DIAG 0
#endif
#define CONFINED_FLAG_A "--ssc-confined"
#define CONFINED_FLAG L"--ssc-confined"
#define KEY_FLAG "--package-key"
#define PROFILE_PREFIX L"Shinsou.Sandbox."
/* Backstop above the core's own budgets (64 MiB heap, 64 MiB DOM, frame buffers, the 16 MiB stack). */
#define JOB_MEMORY_BYTES ((SIZE_T)384 << 20)
#define JOB_LIMITS (JOB_OBJECT_LIMIT_PROCESS_MEMORY | JOB_OBJECT_LIMIT_ACTIVE_PROCESS | \
                    JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_DIE_ON_UNHANDLED_EXCEPTION)
/* Read+execute on the helper's directory for every LPAC process (ordinary AppContainers are also "AC"). */
#define RESTRICTED_PACKAGES_SID L"S-1-15-2-2"

/* Process mitigations for the copy (the PROCESS_CREATION_MITIGATION_POLICY_*_ALWAYS_ON bits). */
#ifdef SSC_WIN_MITIGATIONS /* build-time override, for finding which one a Windows version rejects */
#define MITIGATIONS ((DWORD64)(SSC_WIN_MITIGATIONS))
#else
#define MITIGATIONS ((1ULL << 12) /* heap terminate on corruption */ | \
                     (1ULL << 16) /* bottom-up ASLR */ | \
                     (1ULL << 24) /* strict handle checks */ | \
                     (1ULL << 28) /* no win32k system calls: the helper has no UI */ | \
                     (1ULL << 32) /* no legacy extension points (AppInit DLLs and the like) */ | \
                     (1ULL << 36) /* no dynamic code: QuickJS has no JIT */ | \
                     (1ULL << 48) /* no non-system fonts */ | \
                     (1ULL << 52) /* no images from remote paths */ | \
                     (1ULL << 56) /* no images with a low integrity label */)
#endif

static int fail(char *err, size_t len, const char *what) {
    snprintf(err, len, "%s failed (%lu)", what, (unsigned long)GetLastError());
    return -1;
}

/* ---------------- the confined copy ---------------- */

/* 1 granted, 0 denied, -1 error: a read access check of `sddl` against this process's token. */
static int granted(HANDLE identity, const wchar_t *sddl) {
    PSECURITY_DESCRIPTOR sd = NULL;
    if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, SDDL_REVISION_1, &sd, NULL)) return -1;
    GENERIC_MAPPING map = { FILE_GENERIC_READ, FILE_GENERIC_WRITE, FILE_GENERIC_EXECUTE, FILE_ALL_ACCESS };
    PRIVILEGE_SET privileges;
    DWORD privileges_len = sizeof privileges, mask = 0;
    BOOL status = FALSE;
    BOOL ok = AccessCheck(sd, identity, FILE_GENERIC_READ, &map, &privileges, &privileges_len, &mask, &status);
    LocalFree(sd);
    return ok ? status != FALSE : -1;
}

/*
 * The LPAC marker on the token (Windows sets it for PROCESS_CREATION_ALL_APPLICATION_PACKAGES_OPT_OUT).
 * TokenSecurityAttributes returns the native layout (names are counted strings), not the CLAIM_*
 * structures of winnt.h.
 */
typedef struct { USHORT length, maximum_length; PWSTR buffer; } ssc_counted_string;
typedef struct { ssc_counted_string name; USHORT value_type, reserved; ULONG flags, value_count; void *values; } ssc_token_attribute;
typedef struct { USHORT version, reserved; ULONG count; ssc_token_attribute *attributes; } ssc_token_attributes;

static int has_no_all_packages_attribute(HANDLE token) {
    static const wchar_t marker[] = L"WIN://NOALLAPPPKG";
    const size_t marker_len = sizeof marker / sizeof marker[0] - 1;
    DWORD n = 0;
    GetTokenInformation(token, TokenSecurityAttributes, NULL, 0, &n);
    if (n < sizeof(ssc_token_attributes) || n > (1u << 16)) return 0;
    ssc_token_attributes *info = malloc(n);
    int found = 0;
    if (info && GetTokenInformation(token, TokenSecurityAttributes, info, n, &n)) {
        for (ULONG i = 0; i < info->count; i++) {
            const ssc_counted_string *name = &info->attributes[i].name;
            if (name->buffer && name->length == marker_len * sizeof(wchar_t) && _wcsnicmp(name->buffer, marker, marker_len) == 0) found = 1;
        }
    }
    free(info);
    return found;
}

/* A descriptor granting Everyone and this process's own AppContainer SID. */
static int own_container_sddl(HANDLE token, wchar_t *out, size_t cap) {
    BYTE buf[256];
    DWORD n = 0;
    if (!GetTokenInformation(token, TokenAppContainerSid, buf, sizeof buf, &n)) return -1;
    wchar_t *sid = NULL;
    if (!ConvertSidToStringSidW(((TOKEN_APPCONTAINER_INFORMATION *)buf)->TokenAppContainer, &sid)) return -1;
    int w = swprintf(out, cap, L"O:BAG:BAD:(A;;GA;;;WD)(A;;GA;;;%ls)", sid);
    LocalFree(sid);
    return w > 0 ? 0 : -1;
}

static int verify_token(char *err, size_t len) {
    HANDLE token = NULL, identity = NULL;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY | TOKEN_DUPLICATE, &token)) return fail(err, len, "OpenProcessToken");
    int rc = -1;
    DWORD app_container = 0, n = 0;
    BYTE caps_buf[256];
    if (!GetTokenInformation(token, TokenIsAppContainer, &app_container, sizeof app_container, &n) || !app_container) {
        snprintf(err, len, "not in an AppContainer");
        goto out;
    }
    /* Any capability (internetClient above all) would grant what the sandbox must not have. */
    if (!GetTokenInformation(token, TokenCapabilities, caps_buf, sizeof caps_buf, &n) || ((TOKEN_GROUPS *)caps_buf)->GroupCount != 0) {
        snprintf(err, len, "AppContainer has capabilities");
        goto out;
    }
    /*
     * LPAC: Windows grants ALL APPLICATION PACKAGES ("AC") parts of HKCU and more; an LPAC token is
     * not in that group and carries the WIN://NOALLAPPPKG attribute. The access checks need the
     * user side too (Everyone, "WD"); one that grants the container's own SID shows they work.
     */
    if (!has_no_all_packages_attribute(token)) { snprintf(err, len, "not a Low Privilege AppContainer"); goto out; }
    if (!DuplicateToken(token, SecurityIdentification, &identity)) { fail(err, len, "DuplicateToken"); goto out; }
    wchar_t own_sddl[256];
    if (own_container_sddl(token, own_sddl, 256) != 0) { fail(err, len, "TokenAppContainerSid"); goto out; }
    int ac = granted(identity, L"O:BAG:BAD:(A;;GA;;;WD)(A;;GA;;;AC)");
    int own = granted(identity, own_sddl);
    if (ac != 0 || own != 1) {
        snprintf(err, len, "ALL APPLICATION PACKAGES check failed (%d %d)", ac, own);
        goto out;
    }
    rc = 0;
out:
    if (identity) CloseHandle(identity);
    CloseHandle(token);
    return rc;
}

static int verify_job(char *err, size_t len) {
    BOOL in_job = FALSE;
    if (!IsProcessInJob(GetCurrentProcess(), NULL, &in_job) || !in_job) { snprintf(err, len, "not in a Job"); return -1; }
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION x;
    if (!QueryInformationJobObject(NULL, JobObjectExtendedLimitInformation, &x, sizeof x, NULL)) return fail(err, len, "QueryInformationJobObject");
    if ((x.BasicLimitInformation.LimitFlags & JOB_LIMITS) != JOB_LIMITS || x.BasicLimitInformation.ActiveProcessLimit != 1 ||
        x.ProcessMemoryLimit == 0 || x.ProcessMemoryLimit > JOB_MEMORY_BYTES) {
        snprintf(err, len, "Job limits missing");
        return -1;
    }
    return 0;
}

static int confined(int *argc, char ***argv, char *err, size_t len) {
    if (verify_token(err, len) != 0 || verify_job(err, len) != 0) return -1;
    /* No error dialogs or crash reports with the script's memory; the Job ends the process instead. */
    SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX);
    /* Local dates must follow the system zone, as in the host's engine. */
    _tzset();
    time_t now = time(NULL);
    struct tm ignored;
    localtime_s(&ignored, &now);
    for (int i = 1; i + 1 < *argc; i++) (*argv)[i] = (*argv)[i + 1];
    (*argc)--;
    (*argv)[*argc] = NULL;
    return SSC_OS_CONFINED;
}

/* ---------------- the launcher ---------------- */

static int valid_key(const char *k) {
    size_t n = strlen(k);
    if (n == 0 || n > 32) return 0;
    for (size_t i = 0; i < n; i++)
        if (!((k[i] >= '0' && k[i] <= '9') || (k[i] >= 'a' && k[i] <= 'z'))) return 0;
    return 1;
}

typedef HRESULT(WINAPI *create_profile_fn)(PCWSTR, PCWSTR, PCWSTR, PSID_AND_ATTRIBUTES, DWORD, PSID *);
typedef HRESULT(WINAPI *derive_sid_fn)(PCWSTR, PSID *);

/*
 * One AppContainer per package: processes of the same AppContainer may open each other. userenv is
 * loaded here rather than imported, so the confined copy never maps it.
 */
static int container_sid(const char *key, PSID *sid, char *err, size_t len) {
    wchar_t name[64];
    swprintf(name, 64, L"%ls%hs", PROFILE_PREFIX, key);
    HMODULE userenv = LoadLibraryExW(L"userenv.dll", NULL, LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!userenv) return fail(err, len, "LoadLibrary(userenv)");
    create_profile_fn create = (create_profile_fn)(void (*)(void))GetProcAddress(userenv, "CreateAppContainerProfile");
    derive_sid_fn derive = (derive_sid_fn)(void (*)(void))GetProcAddress(userenv, "DeriveAppContainerSidFromAppContainerName");
    if (!create || !derive) return fail(err, len, "GetProcAddress(AppContainer)");
    HRESULT hr = create(name, name, L"Shinsou X plugin sandbox", NULL, 0, sid);
    if (hr == HRESULT_FROM_WIN32(ERROR_ALREADY_EXISTS)) hr = derive(name, sid);
    if (FAILED(hr)) { snprintf(err, len, "AppContainer profile failed (0x%08lx)", (unsigned long)hr); return -1; }
    return 0;
}

/*
 * An AppContainer reads only what grants it access. Grant read+execute on the helper's own
 * directory (the host's private copy) to ALL RESTRICTED APPLICATION PACKAGES, once; nothing else.
 */
static int grant_directory(const wchar_t *dir, PSID packages, char *err, size_t len) {
    PACL old = NULL, acl = NULL;
    PSECURITY_DESCRIPTOR sd = NULL;
    int rc = -1;
    DWORD e = GetNamedSecurityInfoW(dir, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, NULL, NULL, &old, NULL, &sd);
    if (e != ERROR_SUCCESS) { SetLastError(e); fail(err, len, "GetNamedSecurityInfo"); goto out; }
    const DWORD want = FILE_GENERIC_READ | FILE_GENERIC_EXECUTE;
    for (DWORD i = 0; old && i < old->AceCount; i++) {
        ACCESS_ALLOWED_ACE *ace;
        if (!GetAce(old, i, (void **)&ace) || ace->Header.AceType != ACCESS_ALLOWED_ACE_TYPE) continue;
        if (EqualSid((PSID)&ace->SidStart, packages) && (ace->Mask & want) == want) { rc = 0; goto out; }
    }
    EXPLICIT_ACCESSW ea;
    memset(&ea, 0, sizeof ea);
    ea.grfAccessPermissions = want;
    ea.grfAccessMode = GRANT_ACCESS;
    ea.grfInheritance = SUB_CONTAINERS_AND_OBJECTS_INHERIT;
    ea.Trustee.TrusteeForm = TRUSTEE_IS_SID;
    ea.Trustee.TrusteeType = TRUSTEE_IS_WELL_KNOWN_GROUP;
    ea.Trustee.ptstrName = (LPWSTR)packages;
    e = SetEntriesInAclW(1, &ea, old, &acl);
    if (e != ERROR_SUCCESS) { SetLastError(e); fail(err, len, "SetEntriesInAcl"); goto out; }
    e = SetNamedSecurityInfoW((LPWSTR)dir, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, NULL, NULL, acl, NULL);
    if (e != ERROR_SUCCESS) { SetLastError(e); fail(err, len, "SetNamedSecurityInfo"); goto out; }
    rc = 0;
out:
    if (acl) LocalFree(acl);
    if (sd) LocalFree(sd);
    return rc;
}

static HANDLE make_job(char *err, size_t len) {
    HANDLE job = CreateJobObjectW(NULL, NULL);
    if (!job) { fail(err, len, "CreateJobObject"); return NULL; }
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION x;
    memset(&x, 0, sizeof x);
    x.BasicLimitInformation.LimitFlags = JOB_LIMITS;
    x.BasicLimitInformation.ActiveProcessLimit = 1; /* the copy cannot start another process */
    x.ProcessMemoryLimit = JOB_MEMORY_BYTES;
    JOBOBJECT_BASIC_UI_RESTRICTIONS ui = { JOB_OBJECT_UILIMIT_DESKTOP | JOB_OBJECT_UILIMIT_DISPLAYSETTINGS |
        JOB_OBJECT_UILIMIT_EXITWINDOWS | JOB_OBJECT_UILIMIT_GLOBALATOMS | JOB_OBJECT_UILIMIT_HANDLES |
        JOB_OBJECT_UILIMIT_READCLIPBOARD | JOB_OBJECT_UILIMIT_SYSTEMPARAMETERS | JOB_OBJECT_UILIMIT_WRITECLIPBOARD };
    /* At most one processor's worth of CPU time (units of 1/100 % of all processors), hard capped. */
    DWORD cpus = GetActiveProcessorCount(ALL_PROCESSOR_GROUPS);
    JOBOBJECT_CPU_RATE_CONTROL_INFORMATION cpu;
    memset(&cpu, 0, sizeof cpu);
    cpu.ControlFlags = JOB_OBJECT_CPU_RATE_CONTROL_ENABLE | JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP;
    cpu.CpuRate = cpus > 1 ? 10000 / cpus : 10000;
    if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &x, sizeof x) ||
        !SetInformationJobObject(job, JobObjectBasicUIRestrictions, &ui, sizeof ui) ||
        !SetInformationJobObject(job, JobObjectCpuRateControlInformation, &cpu, sizeof cpu)) {
        fail(err, len, "SetInformationJobObject");
        CloseHandle(job);
        return NULL;
    }
    return job;
}

/* Appends one argument quoted the way CommandLineToArgvW and the CRT split it back. */
static int append_arg(wchar_t *cmd, size_t cap, size_t *pos, const wchar_t *arg) {
    size_t p = *pos;
#define PUT(c) do { if (p + 2 >= cap) return -1; cmd[p++] = (c); } while (0)
    if (p) PUT(L' ');
    PUT(L'"');
    for (const wchar_t *c = arg;; c++) {
        size_t slashes = 0;
        while (*c == L'\\') { slashes++; c++; }
        if (*c == 0) { while (slashes--) { PUT(L'\\'); PUT(L'\\'); } break; }
        if (*c == L'"') { for (size_t i = 0; i < slashes * 2 + 1; i++) PUT(L'\\'); }
        else { while (slashes--) PUT(L'\\'); }
        PUT(*c);
    }
    PUT(L'"');
#undef PUT
    cmd[p] = 0;
    *pos = p;
    return 0;
}

/*
 * Paths only, nothing else of the host's: CreateProcess derives the AppContainer's own
 * LOCALAPPDATA and TEMP from these and fails with ERROR_ENVVAR_NOT_FOUND without them (S0).
 * Sorted, as an environment block must be.
 */
static wchar_t *environment_block(void) {
    static const wchar_t *const keep[] = { L"LOCALAPPDATA", L"SystemDrive", L"SystemRoot", L"TEMP", L"TMP", L"USERPROFILE" };
    size_t cap = 6 * 32768 + 1, pos = 0;
    wchar_t *env = malloc(cap * sizeof *env);
    if (!env) return NULL;
    for (size_t k = 0; k < sizeof keep / sizeof keep[0]; k++) {
        size_t name = wcslen(keep[k]);
        if (pos + name + 2 >= cap) break;
        wmemcpy(env + pos, keep[k], name);
        env[pos + name] = L'=';
        DWORD n = GetEnvironmentVariableW(keep[k], env + pos + name + 1, (DWORD)(cap - pos - name - 2));
        if (n == 0 || n >= cap - pos - name - 2) continue; /* unset or too long: leave it out */
        pos += name + 1 + n + 1;
    }
    env[pos] = 0;
    if (pos == 0) env[++pos] = 0; /* an empty block is two terminators */
    return env;
}

/*
 * The C runtime's argv (no shell32: it would map user32 into the copy, which may not touch win32k).
 * The arguments are a package key and, for the isolation probe, paths of the test's own.
 */
static int launch(int argc, char **argv, int *exit_code, char *err, size_t len) {
    int first = 1;
    const char *key = "default";
    if (argc > 2 && strcmp(argv[1], KEY_FLAG) == 0) { key = argv[2]; first = 3; }
    if (!valid_key(key)) { snprintf(err, len, "invalid package key"); return -1; }

    wchar_t exe[32768];
    DWORD exe_len = GetModuleFileNameW(NULL, exe, 32768);
    if (exe_len == 0 || exe_len >= 32768) return fail(err, len, "GetModuleFileName");
    wchar_t dir[32768];
    wcscpy(dir, exe);
    wchar_t *slash = wcsrchr(dir, L'\\');
    if (!slash) { snprintf(err, len, "helper path has no directory"); return -1; }
    *slash = 0;

    static wchar_t cmd[32768];
    size_t pos = 0;
    if (append_arg(cmd, 32768, &pos, exe) || append_arg(cmd, 32768, &pos, CONFINED_FLAG)) { snprintf(err, len, "command line too long"); return -1; }
    for (int i = first; i < argc; i++) {
        wchar_t arg[4096];
        if (!MultiByteToWideChar(CP_ACP, 0, argv[i], -1, arg, 4096) || append_arg(cmd, 32768, &pos, arg)) {
            snprintf(err, len, "argument too long");
            return -1;
        }
    }

    PSID sid = NULL;
    PSID packages = NULL;
    if (!ConvertStringSidToSidW(RESTRICTED_PACKAGES_SID, &packages)) return fail(err, len, "ConvertStringSidToSid");
    int granted_dir = container_sid(key, &sid, err, len) == 0 && grant_directory(dir, packages, err, len) == 0;
    LocalFree(packages);
    if (!granted_dir) return -1;
    if ((SSC_WIN_DIAG & 8) && grant_directory(dir, sid, err, len) != 0) return -1;
    HANDLE job = make_job(err, len);
    if (!job) return -1;

    /* Only stdin and stdout are inherited; the copy gets no stderr. */
    HANDLE in = GetStdHandle(STD_INPUT_HANDLE), out = GetStdHandle(STD_OUTPUT_HANDLE);
    if (!in || in == INVALID_HANDLE_VALUE || !out || out == INVALID_HANDLE_VALUE) { snprintf(err, len, "no stdin/stdout"); return -1; }
    if (!SetHandleInformation(in, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT) ||
        !SetHandleInformation(out, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT)) return fail(err, len, "SetHandleInformation");
    HANDLE inherit[2] = { in, out };

    SECURITY_CAPABILITIES caps;
    memset(&caps, 0, sizeof caps);
    caps.AppContainerSid = sid; /* CapabilityCount 0: in particular no internetClient */
    DWORD lpac = PROCESS_CREATION_ALL_APPLICATION_PACKAGES_OPT_OUT;
    DWORD64 mitigations = MITIGATIONS;
    SIZE_T attr_size = 0;
    InitializeProcThreadAttributeList(NULL, 5, 0, &attr_size);
    LPPROC_THREAD_ATTRIBUTE_LIST attrs = malloc(attr_size);
    if (!attrs || !InitializeProcThreadAttributeList(attrs, 5, 0, &attr_size)) return fail(err, len, "InitializeProcThreadAttributeList");
    /* Job membership is part of creation: the copy never runs, or waits suspended, outside it. */
    if (!UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES, &caps, sizeof caps, NULL, NULL) ||
        (!(SSC_WIN_DIAG & 16) && !UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_ALL_APPLICATION_PACKAGES_POLICY, &lpac, sizeof lpac, NULL, NULL)) ||
        !UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, inherit, (in == out ? 1 : 2) * sizeof(HANDLE), NULL, NULL) ||
        (!(SSC_WIN_DIAG & 32) && !UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_JOB_LIST, &job, sizeof job, NULL, NULL)) ||
        (!(SSC_WIN_DIAG & 2) && !UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_MITIGATION_POLICY, &mitigations, sizeof mitigations, NULL, NULL)))
        return fail(err, len, "UpdateProcThreadAttribute");

    STARTUPINFOEXW si;
    memset(&si, 0, sizeof si);
    si.StartupInfo.cb = sizeof si;
    si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    si.StartupInfo.hStdInput = in;
    si.StartupInfo.hStdOutput = out;
    si.lpAttributeList = attrs;
    wchar_t *env = environment_block();
    if (!env) { snprintf(err, len, "out of memory"); return -1; }
    PROCESS_INFORMATION pi;
    if (!CreateProcessW(exe, cmd, NULL, NULL, TRUE,
                        EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW,
                        env, (SSC_WIN_DIAG & 4) ? NULL : dir, &si.StartupInfo, &pi))
        return fail(err, len, "CreateProcess");
    BOOL in_job = FALSE;
    if (!((SSC_WIN_DIAG & 32) || (IsProcessInJob(pi.hProcess, job, &in_job) && in_job)) || ResumeThread(pi.hThread) == (DWORD)-1) {
        fail(err, len, "starting the confined helper");
        TerminateProcess(pi.hProcess, 1);
        return -1;
    }
    CloseHandle(pi.hThread);
    /* The copy holds the pipes now: the host sees their end when the copy ends, not when we do. */
    CloseHandle(in);
    CloseHandle(out);
    DeleteProcThreadAttributeList(attrs);
    free(attrs);
    free(env);
    FreeSid(sid);
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = 1;
    GetExitCodeProcess(pi.hProcess, &code);
    *exit_code = (int)code;
    return SSC_OS_LAUNCHED; /* the Job closes with this process */
}

int ssc_os_lockdown(int *argc, char ***argv, int *exit_code, char *err, size_t err_len) {
    if (*argc > 1 && strcmp((*argv)[1], CONFINED_FLAG_A) == 0) return confined(argc, argv, err, err_len);
    return launch(*argc, *argv, exit_code, err, err_len);
}
#endif
