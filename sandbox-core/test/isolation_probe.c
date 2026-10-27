/*
 * Isolation probe (ADR-0002 §13, S3; test build only): runs the helper's own startup lockdown
 * (ssc_os_lockdown), then attempts everything the sandbox must not do.
 * usage: isolation_probe [--control] <tcp-port> <secret-file> <writable-dir>
 * --control skips the lockdown, to show every probe can succeed. Prints "<name> BLOCKED|ALLOWED
 * <detail>" per operation; exits 0 only when everything forbidden was blocked and threads and
 * anonymous memory still work. Socket creation itself is not gated by Seatbelt (S0), so what is
 * judged is whether anything can leave: connect, sendto, bind and AF_UNIX connect.
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

#include "ssc_os_sandbox.h"

extern char **environ;
static int allowed_count;

static void report(const char *name, int allowed, int err) {
    printf("%s %s errno=%d\n", name, allowed ? "ALLOWED" : "BLOCKED", allowed ? 0 : err);
    if (allowed) allowed_count++;
}

static void *noop(void *a) { return a; }

int main(int argc, char **argv) {
    int control = argc > 1 && strcmp(argv[1], "--control") == 0;
    int ai = control ? 2 : 1;
    if (argc - ai < 3) return 2;
    int port = atoi(argv[ai]);
    const char *secret = argv[ai + 1], *dir = argv[ai + 2];
    if (!control) {
        char err[64];
        if (ssc_os_lockdown(err, sizeof err) != 0) { printf("lockdown failed: %s\n", err); return 3; }
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
    printf("RESULT %s (%d forbidden operations allowed)\n", allowed_count || broken ? "FAIL" : "PASS", allowed_count);
    return allowed_count || broken ? 1 : 0;
}
