/*
 * Isolation probe (ADR-0002 §13 "隔離探測"): same startup hardening as shinsou-sandbox, then tries forbidden operations.
 * usage: isolation-probe [--sandbox=seatbelt|none] <tcp-port> <secret-file> <dir>
 * Each line: "<name> BLOCKED|ALLOWED <detail>". Exit 0 only if every forbidden operation was blocked and the
 * required ones (stdout, malloc, thread) still work.
 */
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

#include "sandbox_apply.h"

extern char **environ;
static int bad;
static char *empty_env[] = {NULL};

static void report(const char *name, int allowed, const char *detail) {
    printf("%s %s %s\n", name, allowed ? "ALLOWED" : "BLOCKED", detail);
    if (allowed) bad++;
}
static void *noop(void *a) { return a; }

int main(int argc, char **argv) {
    int seatbelt = 1, ai = 1;
    if (ai < argc && !strncmp(argv[ai], "--sandbox=", 10)) { seatbelt = !strcmp(argv[ai] + 10, "seatbelt"); ai++; }
    if (argc - ai < 3) return 2;
    int port = atoi(argv[ai]);
    const char *secret = argv[ai + 1], *dir = argv[ai + 2];

    const char *env_before = getenv("SHINSOU_SECRET_TOKEN");
    printf("env-before-clear %s\n", env_before ? "present" : "absent");
    environ = empty_env;
    for (int fd = 3; fd < 256; fd++) close(fd);
    if (seatbelt) {
        char err[256];
        if (apply_seatbelt_sandbox(err, sizeof err)) { printf("sandbox_init failed: %s\n", err); return 3; }
    }
    char d[96];

    /* 1. network. Creating an unconnected socket is not gated by Seatbelt, so it is informational only; what
     * matters is that no traffic can leave: connect, UDP sendto, bind/listen and AF_UNIX connect are all probed. */
    int s = socket(AF_INET, SOCK_STREAM, 0);
    printf("socket(AF_INET) INFO %s (creation alone is harmless)\n", s >= 0 ? "created" : "denied");
    struct sockaddr_in a = {.sin_family = AF_INET, .sin_port = htons((uint16_t)port)};
    inet_pton(AF_INET, "127.0.0.1", &a.sin_addr);
    if (s >= 0) {
        int rc = connect(s, (struct sockaddr *)&a, sizeof a);
        snprintf(d, sizeof d, "errno=%d", errno);
        report("connect(127.0.0.1:tcp)", rc == 0, rc == 0 ? "connected" : d);
        close(s);
    } else {
        report("connect(127.0.0.1:tcp)", 0, "skipped (socket denied)");
    }
    int u = socket(AF_INET, SOCK_DGRAM, 0);
    if (u >= 0) {
        ssize_t n = sendto(u, "x", 1, 0, (struct sockaddr *)&a, sizeof a);
        snprintf(d, sizeof d, "errno=%d", errno);
        report("sendto(127.0.0.1:udp)", n == 1, n == 1 ? "sent" : d);
        struct sockaddr_in l = {.sin_family = AF_INET};
        int rc = bind(u, (struct sockaddr *)&l, sizeof l);
        snprintf(d, sizeof d, "errno=%d", errno);
        report("bind(0.0.0.0:0)", rc == 0, rc == 0 ? "bound" : d);
        close(u);
    } else {
        report("sendto(127.0.0.1:udp)", 0, "skipped (socket denied)");
        report("bind(0.0.0.0:0)", 0, "skipped (socket denied)");
    }
    int ux = socket(AF_UNIX, SOCK_STREAM, 0);
    if (ux >= 0) {
        struct sockaddr_un ua = {.sun_family = AF_UNIX};
        strcpy(ua.sun_path, "/var/run/syslog");
        int rc = connect(ux, (struct sockaddr *)&ua, sizeof ua);
        snprintf(d, sizeof d, "errno=%d", errno);
        report("connect(AF_UNIX /var/run/syslog)", rc == 0, rc == 0 ? "connected" : d);
        close(ux);
    } else {
        report("connect(AF_UNIX /var/run/syslog)", 0, "skipped (socket denied)");
    }

    /* 2. files */
    int fd = open(secret, O_RDONLY);
    snprintf(d, sizeof d, "errno=%d", errno);
    report("open(user file, O_RDONLY)", fd >= 0, fd >= 0 ? "opened" : d);
    fd = open("/etc/hosts", O_RDONLY);
    snprintf(d, sizeof d, "errno=%d", errno);
    report("open(/etc/hosts)", fd >= 0, fd >= 0 ? "opened" : d);
    char path[512];
    snprintf(path, sizeof path, "%s/probe-write.txt", dir);
    fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    snprintf(d, sizeof d, "errno=%d", errno);
    report("open(tmp, O_CREAT|O_WRONLY)", fd >= 0, fd >= 0 ? "created" : d);
    if (fd >= 0) unlink(path);
    DIR *dp = opendir("/Users");
    snprintf(d, sizeof d, "errno=%d", errno);
    report("opendir(/Users)", dp != NULL, dp ? "opened" : d);

    /* 3. processes */
    pid_t pid = fork();
    snprintf(d, sizeof d, "errno=%d", errno);
    if (pid == 0) _exit(0);
    if (pid > 0) waitpid(pid, NULL, 0);
    report("fork()", pid >= 0, pid >= 0 ? "forked" : d);
    pid_t sp;
    char *av[] = {"/usr/bin/true", NULL};
    int rc = posix_spawn(&sp, "/usr/bin/true", NULL, NULL, av, environ);
    snprintf(d, sizeof d, "rc=%d", rc);
    if (rc == 0) waitpid(sp, NULL, 0);
    report("posix_spawn(/usr/bin/true)", rc == 0, rc == 0 ? "spawned" : d);
    pid = vfork();
    if (pid == 0) { execv("/usr/bin/true", av); _exit(127); }
    int status = 0;
    if (pid > 0) waitpid(pid, &status, 0);
    int exec_ok = pid > 0 && WIFEXITED(status) && WEXITSTATUS(status) == 0;
    report("vfork+execv(/usr/bin/true)", exec_ok, exec_ok ? "ran" : "failed");

    /* 4. environment and other processes */
    const char *e = getenv("SHINSOU_SECRET_TOKEN");
    report("getenv(SHINSOU_SECRET_TOKEN)", e != NULL, e ? "readable" : "unset (cleared at startup)");
    rc = kill(getppid(), 0);
    snprintf(d, sizeof d, "errno=%d", errno);
    report("kill(parent, 0)", rc == 0, rc == 0 ? "signal sent" : d);

    /* 5. things that must still work */
    pthread_t t;
    int th = pthread_create(&t, NULL, noop, NULL);
    if (!th) pthread_join(t, NULL);
    printf("pthread_create %s\n", th ? "FAIL" : "OK");
    void *m = mmap(NULL, 1 << 20, PROT_READ | PROT_WRITE, MAP_ANON | MAP_PRIVATE, -1, 0);
    printf("mmap-anon %s\n", m == MAP_FAILED ? "FAIL" : "OK");
    if (th || m == MAP_FAILED) bad++;
    printf("%s (%d forbidden operations allowed)\n", bad ? "RESULT FAIL" : "RESULT PASS", bad);
    return bad ? 1 : 0;
}
