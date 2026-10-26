/*
 * ADR-0002 S0 Windows isolation probe. Run through launcher.exe; every operation below must fail
 * inside the AppContainer + Job Object, and (as the control) succeed when launched with `none`.
 *
 *   probe.exe <secret-file> <loopback-port> <host-pid>
 *   probe.exe noop                         (cold-start measurement)
 *   probe.exe hang                         (sleeps forever: Job kill-on-close test)
 *   probe.exe spin                         (busy for 2 s of wall time, prints the CPU time used)
 *
 * Prints `name=OK(unexpected)` or `name=denied(<error>)`, then `RESULT PASS|FAIL (<n> allowed)`.
 */
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int allowed;

static void report(const char *name, BOOL ok, DWORD err) {
    if (ok) {
        allowed++;
        printf("%s=OK(unexpected)\n", name);
    } else {
        printf("%s=denied(%lu)\n", name, err);
    }
}

static void tcp(const char *name, const char *ip, unsigned short port) {
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) { report(name, FALSE, (DWORD)WSAGetLastError()); return; }
    DWORD timeout = 3000;
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (const char *)&timeout, sizeof timeout);
    struct sockaddr_in a = { 0 };
    a.sin_family = AF_INET;
    a.sin_port = htons(port);
    inet_pton(AF_INET, ip, &a.sin_addr);
    int rc = connect(s, (struct sockaddr *)&a, sizeof a);
    report(name, rc == 0, rc == 0 ? 0 : (DWORD)WSAGetLastError());
    closesocket(s);
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc >= 2 && strcmp(argv[1], "noop") == 0) return 0;
    if (argc >= 2 && strcmp(argv[1], "hang") == 0) { for (;;) Sleep(1000); } /* kill-on-close test */
    if (argc >= 2 && strcmp(argv[1], "spin") == 0) { /* Job CPU rate cap test */
        ULONGLONG end = GetTickCount64() + 2000;
        volatile unsigned long long x = 0;
        while (GetTickCount64() < end) x++;
        FILETIME c, e, k, u;
        GetProcessTimes(GetCurrentProcess(), &c, &e, &k, &u);
        ULONGLONG cpu = ((((ULONGLONG)k.dwHighDateTime << 32) | k.dwLowDateTime) + (((ULONGLONG)u.dwHighDateTime << 32) | u.dwLowDateTime)) / 10000;
        printf("SPIN wall_ms=2000 cpu_ms=%llu\n", cpu);
        return 0;
    }
    if (argc < 4) { fprintf(stderr, "usage: probe <secret-file> <loopback-port> <host-pid>\n"); return 2; }
    WSADATA w;
    WSAStartup(MAKEWORD(2, 2), &w);

    tcp("connect(1.1.1.1:443)", "1.1.1.1", 443);
    tcp("connect(127.0.0.1 host listener)", "127.0.0.1", (unsigned short)atoi(argv[2]));

    HANDLE h = CreateFileA(argv[1], GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    report("open(user profile secret)", h != INVALID_HANDLE_VALUE, GetLastError());
    if (h != INVALID_HANDLE_VALUE) CloseHandle(h);

    char path[MAX_PATH];
    snprintf(path, sizeof path, "%s.write-test", argv[1]);
    h = CreateFileA(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
    report("create(user profile file)", h != INVALID_HANDLE_VALUE, GetLastError());
    if (h != INVALID_HANDLE_VALUE) { CloseHandle(h); DeleteFileA(path); }

    h = CreateFileA("C:\\Windows\\Temp\\shinsou-s0-write-test", GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
    report("create(C:\\Windows\\Temp)", h != INVALID_HANDLE_VALUE, GetLastError());
    if (h != INVALID_HANDLE_VALUE) { CloseHandle(h); DeleteFileA("C:\\Windows\\Temp\\shinsou-s0-write-test"); }

    WIN32_FIND_DATAA fd;
    HANDLE fh = FindFirstFileA("C:\\Users\\*", &fd);
    report("list(C:\\Users)", fh != INVALID_HANDLE_VALUE, GetLastError());
    if (fh != INVALID_HANDLE_VALUE) FindClose(fh);

    STARTUPINFOA si = { sizeof si };
    PROCESS_INFORMATION pi;
    char cmd[] = "C:\\Windows\\System32\\cmd.exe /c exit 0";
    BOOL spawned = CreateProcessA(NULL, cmd, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, NULL, &si, &pi);
    report("CreateProcess(cmd.exe)", spawned, GetLastError());
    if (spawned) { WaitForSingleObject(pi.hProcess, 5000); CloseHandle(pi.hProcess); CloseHandle(pi.hThread); }

    char value[128];
    DWORD len = GetEnvironmentVariableA("SHINSOU_SECRET_TOKEN", value, sizeof value);
    report("getenv(SHINSOU_SECRET_TOKEN)", len > 0, GetLastError());

    HANDLE parent = OpenProcess(PROCESS_VM_READ | PROCESS_QUERY_INFORMATION, FALSE, (DWORD)atoi(argv[3]));
    report("OpenProcess(host, VM_READ)", parent != NULL, GetLastError());
    if (parent) CloseHandle(parent);

    HKEY key;
    LONG r = RegOpenKeyExA(HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer", 0, KEY_READ, &key);
    report("RegOpenKey(HKCU Explorer)", r == ERROR_SUCCESS, (DWORD)r);
    if (r == ERROR_SUCCESS) RegCloseKey(key);

    /* Job memory cap: committing far beyond it must fail. */
    void *big = VirtualAlloc(NULL, (SIZE_T)1 << 30, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    report("VirtualAlloc(1 GiB commit)", big != NULL, GetLastError());
    if (big) VirtualFree(big, 0, MEM_RELEASE);

    /* Must keep working inside the sandbox. */
    HANDLE th = CreateThread(NULL, 0, (LPTHREAD_START_ROUTINE)GetCurrentThreadId, NULL, 0, NULL);
    printf("CreateThread=%s\n", th ? "OK" : "FAILED");
    if (th) { WaitForSingleObject(th, INFINITE); CloseHandle(th); }

    printf("RESULT %s (%d allowed)\n", allowed ? "FAIL" : "PASS", allowed);
    return allowed ? 1 : 0;
}
