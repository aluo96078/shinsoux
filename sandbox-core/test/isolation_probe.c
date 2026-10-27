/*
 * Isolation probe (ADR-0002 §13, S3; test build only): runs the helper's own startup lockdown
 * (ssc_os_lockdown), then attempts everything the sandbox must not do.
 *
 *   POSIX:   isolation_probe [--control] <tcp-port> <secret-file> <writable-dir>
 *   Windows: isolation_probe [--control] [--package-key K] <tcp-port> <secret-file> <writable-dir>
 *                            <inherited-handle> <host-pid> <sibling-pid>
 *            isolation_probe --package-key K --hang   (a confined sibling: prints its pid, waits for EOF)
 *
 * --control skips the lockdown, to show every probe can succeed. Prints "<name> BLOCKED|ALLOWED
 * <detail>" per operation and "PROBES <n>"; exits 0 only when everything forbidden was blocked and
 * threads and anonymous memory still work. macOS: socket creation itself is not gated by Seatbelt
 * (S0), so what is judged is whether anything can leave: connect, sendto, bind and AF_UNIX connect.
 */
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ssc_os_sandbox.h"

static int allowed_count, probes;

static void report(const char *name, int allowed, unsigned long err) {
    printf("%s %s error=%lu\n", name, allowed ? "ALLOWED" : "BLOCKED", allowed ? 0 : err);
    if (allowed) allowed_count++;
    probes++;
}

static DWORD WINAPI noop(LPVOID a) { return (DWORD)(uintptr_t)a; }

static void open_process(const char *name, DWORD pid) {
    HANDLE h = OpenProcess(PROCESS_VM_READ | PROCESS_QUERY_INFORMATION, FALSE, pid);
    report(name, h != NULL, GetLastError());
    if (h) CloseHandle(h);
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    int control = argc > 1 && strcmp(argv[1], "--control") == 0;
    if (!control) {
        char err[96];
        int launched_exit = 0;
        int rc = ssc_os_lockdown(&argc, &argv, &launched_exit, err, sizeof err);
        if (rc == SSC_OS_LAUNCHED) return launched_exit;
        if (rc != SSC_OS_CONFINED) { printf("lockdown failed: %s\n", err); return 3; }
    }
    int ai = control ? 2 : 1;
    if (argc > ai && strcmp(argv[ai], "--hang") == 0) {
        printf("PID %lu\n", (unsigned long)GetCurrentProcessId());
        char c;
        DWORD got;
        while (ReadFile(GetStdHandle(STD_INPUT_HANDLE), &c, 1, &got, NULL) && got) {}
        return 0;
    }
    if (argc - ai < 6) return 2;
    unsigned short port = (unsigned short)atoi(argv[ai]);
    const char *secret = argv[ai + 1], *dir = argv[ai + 2];
    HANDLE inherited = (HANDLE)(uintptr_t)_strtoui64(argv[ai + 3], NULL, 10);
    DWORD host = (DWORD)strtoul(argv[ai + 4], NULL, 10), sibling = (DWORD)strtoul(argv[ai + 5], NULL, 10);

    /* LPAC: WSAStartup itself fails (S0), which blocks every socket below. */
    WSADATA w;
    int wsa = WSAStartup(MAKEWORD(2, 2), &w);
    struct sockaddr_in local;
    memset(&local, 0, sizeof local);
    local.sin_family = AF_INET;
    local.sin_port = htons(port);
    inet_pton(AF_INET, "127.0.0.1", &local.sin_addr);
    SOCKET s = wsa == 0 ? socket(AF_INET, SOCK_STREAM, IPPROTO_TCP) : INVALID_SOCKET;
    int rc = s != INVALID_SOCKET ? connect(s, (struct sockaddr *)&local, sizeof local) : -1;
    report("connect(127.0.0.1:tcp)", rc == 0, wsa ? (unsigned long)wsa : (unsigned long)WSAGetLastError());
    if (s != INVALID_SOCKET) closesocket(s);
    s = wsa == 0 ? socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP) : INVALID_SOCKET;
    rc = s != INVALID_SOCKET ? sendto(s, "x", 1, 0, (struct sockaddr *)&local, sizeof local) : -1;
    report("sendto(127.0.0.1:udp)", rc == 1, wsa ? (unsigned long)wsa : (unsigned long)WSAGetLastError());
    if (s != INVALID_SOCKET) closesocket(s);
    s = wsa == 0 ? socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP) : INVALID_SOCKET;
    struct sockaddr_in any;
    memset(&any, 0, sizeof any);
    any.sin_family = AF_INET;
    rc = s != INVALID_SOCKET ? bind(s, (struct sockaddr *)&any, sizeof any) : -1;
    report("bind(0.0.0.0:0)", rc == 0, wsa ? (unsigned long)wsa : (unsigned long)WSAGetLastError());
    if (s != INVALID_SOCKET) closesocket(s);

    HANDLE h = CreateFileA(secret, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    report("open(user file)", h != INVALID_HANDLE_VALUE, GetLastError());
    if (h != INVALID_HANDLE_VALUE) CloseHandle(h);
    char path[MAX_PATH * 2];
    snprintf(path, sizeof path, "%s\\isolation-probe-write", dir);
    h = CreateFileA(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
    report("create(user dir)", h != INVALID_HANDLE_VALUE, GetLastError());
    if (h != INVALID_HANDLE_VALUE) { CloseHandle(h); DeleteFileA(path); }
    /* Its own directory is granted read+execute only. */
    char exe[MAX_PATH * 2];
    DWORD n = GetModuleFileNameA(NULL, exe, sizeof exe);
    char *slash = n ? strrchr(exe, '\\') : NULL;
    if (slash) strcpy(slash + 1, "isolation-probe-write");
    h = slash ? CreateFileA(exe, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL) : INVALID_HANDLE_VALUE;
    report("create(helper dir)", h != INVALID_HANDLE_VALUE, GetLastError());
    if (h != INVALID_HANDLE_VALUE) { CloseHandle(h); DeleteFileA(exe); }
    h = CreateFileA("C:\\Windows\\Temp\\shinsou-isolation-probe", GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
    report("create(C:\\Windows\\Temp)", h != INVALID_HANDLE_VALUE, GetLastError());
    if (h != INVALID_HANDLE_VALUE) { CloseHandle(h); DeleteFileA("C:\\Windows\\Temp\\shinsou-isolation-probe"); }
    WIN32_FIND_DATAA fd;
    HANDLE fh = FindFirstFileA("C:\\Users\\*", &fd);
    report("list(C:\\Users)", fh != INVALID_HANDLE_VALUE, GetLastError());
    if (fh != INVALID_HANDLE_VALUE) FindClose(fh);
    HKEY key;
    LONG r = RegOpenKeyExA(HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer", 0, KEY_READ, &key);
    report("RegOpenKey(HKCU Explorer)", r == ERROR_SUCCESS, (unsigned long)r);
    if (r == ERROR_SUCCESS) RegCloseKey(key);

    STARTUPINFOA si;
    memset(&si, 0, sizeof si);
    si.cb = sizeof si;
    PROCESS_INFORMATION pi;
    char cmd[] = "C:\\Windows\\System32\\cmd.exe /c exit 0";
    BOOL spawned = CreateProcessA(NULL, cmd, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, NULL, &si, &pi);
    report("CreateProcess(cmd.exe)", spawned, GetLastError());
    if (spawned) { WaitForSingleObject(pi.hProcess, 5000); CloseHandle(pi.hProcess); CloseHandle(pi.hThread); }

    char value[64];
    report("getenv(SHINSOU_SECRET_TOKEN)", GetEnvironmentVariableA("SHINSOU_SECRET_TOKEN", value, sizeof value) > 0, GetLastError());
    open_process("OpenProcess(host)", host);
    open_process("OpenProcess(other package's sandbox)", sibling);
    DWORD flags;
    report("inherited handle", GetHandleInformation(inherited, &flags), GetLastError());
    /* The Job's memory cap: committing far beyond it must fail. */
    void *big = VirtualAlloc(NULL, (SIZE_T)1 << 30, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    report("VirtualAlloc(1 GiB commit)", big != NULL, GetLastError());
    if (big) VirtualFree(big, 0, MEM_RELEASE);

    HANDLE t = CreateThread(NULL, 0, noop, NULL, 0, NULL);
    if (t) { WaitForSingleObject(t, INFINITE); CloseHandle(t); }
    void *m = VirtualAlloc(NULL, 1 << 20, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    printf("CreateThread %s\nVirtualAlloc(1 MiB) %s\n", t ? "OK" : "FAIL", m ? "OK" : "FAIL");
    int broken = !t || !m;
    printf("PROBES %d\n", probes);
    printf("RESULT %s (%d forbidden operations allowed)\n", allowed_count || broken ? "FAIL" : "PASS", allowed_count);
    return allowed_count || broken ? 1 : 0;
}
#else
#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#include "ssc_os_sandbox.h"

extern char **environ;
static int allowed_count, probes;

static void report(const char *name, int allowed, int err) {
    printf("%s %s errno=%d\n", name, allowed ? "ALLOWED" : "BLOCKED", allowed ? 0 : err);
    if (allowed) allowed_count++;
    probes++;
}

static void *noop(void *a) { return a; }

int main(int argc, char **argv) {
    int control = argc > 1 && strcmp(argv[1], "--control") == 0;
    int ai = control ? 2 : 1;
    if (argc - ai < 3) return 2;
    int port = atoi(argv[ai]);
    const char *secret = argv[ai + 1], *dir = argv[ai + 2];
    if (!control) {
        char err[96];
        int launched_exit = 0;
        if (ssc_os_lockdown(&argc, &argv, &launched_exit, err, sizeof err) != SSC_OS_CONFINED) { printf("lockdown failed: %s\n", err); return 3; }
    }
    setvbuf(stdout, NULL, _IOLBF, 0);

    struct sockaddr_in local = { .sin_family = AF_INET, .sin_port = htons((uint16_t)port) };
    inet_pton(AF_INET, "127.0.0.1", &local.sin_addr);
    int s = socket(AF_INET, SOCK_STREAM, 0);
    int rc = s >= 0 ? connect(s, (struct sockaddr *)&local, sizeof local) : -1;
    report("connect(127.0.0.1:tcp)", rc == 0, errno);
    if (s >= 0) close(s);
    s = socket(AF_INET, SOCK_DGRAM, 0);
    rc = s >= 0 ? (int)sendto(s, "x", 1, 0, (struct sockaddr *)&local, sizeof local) : -1;
    report("sendto(127.0.0.1:udp)", rc == 1, errno);
    if (s >= 0) close(s);
    s = socket(AF_INET, SOCK_DGRAM, 0); /* a fresh one: sendto has already bound the other */
    struct sockaddr_in any = { .sin_family = AF_INET };
    rc = s >= 0 ? bind(s, (struct sockaddr *)&any, sizeof any) : -1;
    report("bind(0.0.0.0:0)", rc == 0, errno);
    if (s >= 0) close(s);
    s = socket(AF_UNIX, SOCK_DGRAM, 0); /* syslogd's socket is a datagram socket */
    struct sockaddr_un syslog_addr = { .sun_family = AF_UNIX };
    strcpy(syslog_addr.sun_path, "/var/run/syslog");
    rc = s >= 0 ? connect(s, (struct sockaddr *)&syslog_addr, sizeof syslog_addr) : -1;
    report("connect(AF_UNIX /var/run/syslog)", rc == 0, errno);
    if (s >= 0) close(s);

    int fd = open(secret, O_RDONLY);
    report("open(user file)", fd >= 0, errno);
    if (fd >= 0) close(fd);
    fd = open("/etc/hosts", O_RDONLY);
    report("open(/etc/hosts)", fd >= 0, errno);
    if (fd >= 0) close(fd);
    char path[1024];
    snprintf(path, sizeof path, "%s/isolation-probe-write", dir);
    fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    report("open(dir, O_CREAT|O_WRONLY)", fd >= 0, errno);
    if (fd >= 0) { close(fd); unlink(path); }
    DIR *d = opendir("/Users");
    report("opendir(/Users)", d != NULL, errno);
    if (d) closedir(d);

    pid_t pid = fork();
    if (pid == 0) _exit(0);
    if (pid > 0) waitpid(pid, NULL, 0);
    report("fork()", pid >= 0, errno);
    char *av[] = { "/usr/bin/true", NULL };
    pid_t spawned;
    rc = posix_spawn(&spawned, "/usr/bin/true", NULL, NULL, av, environ);
    if (rc == 0) waitpid(spawned, NULL, 0);
    report("posix_spawn(/usr/bin/true)", rc == 0, rc);
    pid = fork();
    if (pid == 0) { execv("/usr/bin/true", av); _exit(127); }
    int status = 0;
    if (pid > 0) waitpid(pid, &status, 0);
    report("fork+execv(/usr/bin/true)", pid > 0 && WIFEXITED(status) && WEXITSTATUS(status) == 0, errno);

    report("getenv(SHINSOU_SECRET_TOKEN)", getenv("SHINSOU_SECRET_TOKEN") != NULL, 0);
    rc = kill(getppid(), 0);
    report("kill(parent, 0)", rc == 0, errno);
    report("inherited fd 3", fcntl(3, F_GETFD) != -1, errno);

    pthread_t t;
    int thread_failed = pthread_create(&t, NULL, noop, NULL);
    if (!thread_failed) pthread_join(t, NULL);
    void *m = mmap(NULL, 1 << 20, PROT_READ | PROT_WRITE, MAP_ANON | MAP_PRIVATE, -1, 0);
    printf("pthread_create %s\nmmap-anon %s\n", thread_failed ? "FAIL" : "OK", m == MAP_FAILED ? "FAIL" : "OK");
    int broken = thread_failed || m == MAP_FAILED;
    printf("PROBES %d\n", probes);
    printf("RESULT %s (%d forbidden operations allowed)\n", allowed_count || broken ? "FAIL" : "PASS", allowed_count);
    return allowed_count || broken ? 1 : 0;
}
#endif
