#include "mock.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

uint64_t mock_now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

static const char DEFAULT_HTML[] =
    "\"<div class=\\\"item\\\"><a href=\\\"/m/1\\\">One</a></div><div class=\\\"item\\\"><a href=\\\"/m/2\\\">Two</a></div>\"";

void mock_init(mock_net *n, int delay_ms) {
    memset(n, 0, sizeof *n);
    pthread_mutex_init(&n->mu, NULL);
    pthread_cond_init(&n->cv, NULL);
    n->delay_ms = delay_ms;
    n->html_body = strdup(DEFAULT_HTML);
    n->html_len = strlen(DEFAULT_HTML);
}

void mock_reset(mock_net *n) {
    pthread_mutex_lock(&n->mu);
    while (n->threads_running > 0) {
        pthread_mutex_unlock(&n->mu);
        usleep(1000);
        pthread_mutex_lock(&n->mu);
    }
    for (int i = 0; i < n->queued; i++) free(n->queue[i].payload);
    char *html = n->html_body;
    size_t html_len = n->html_len;
    int delay = n->delay_ms;
    memset(n->queue, 0, sizeof n->queue);
    memset(n->cancelled_flag, 0, sizeof n->cancelled_flag);
    n->queued = n->started_count = n->inflight = n->max_inflight_seen = n->cancelled = 0;
    n->last_op = 0;
    n->sync_calls = 0;
    n->html_body = html;
    n->html_len = html_len;
    n->delay_ms = delay;
    pthread_mutex_unlock(&n->mu);
}

void mock_set_html(mock_net *n, const char *lit) {
    free(n->html_body);
    n->html_body = strdup(lit);
    n->html_len = strlen(lit);
}

int mock_bridge(void *u, const char *method, size_t mlen, const char *args, size_t alen, char **out, size_t *olen) {
    (void)args; (void)alen;
    mock_net *n = u;
    n->sync_calls++;
    const char *reply = "null";
    size_t len = 4;
    if (mlen == 7 && memcmp(method, "httpGet", 7) == 0) { reply = n->html_body; len = n->html_len; }
    *out = malloc(len + 1);
    memcpy(*out, reply, len);
    (*out)[len] = 0;
    *olen = len;
    return 0;
}

static void push_result(mock_net *n, uint32_t op) {
    pthread_mutex_lock(&n->mu);
    if (op < MOCK_MAX_OPS && !n->cancelled_flag[op] && n->queued < MOCK_MAX_OPS) {
        char body[96];
        int l = snprintf(body, sizeof body, "{\"status\":200,\"body\":\"op-%u\"}", op);
        mock_result *r = &n->queue[n->queued++];
        r->op = op; r->ok = 1; r->payload = strdup(body); r->len = (size_t)l;
        pthread_cond_broadcast(&n->cv);
    }
    pthread_mutex_unlock(&n->mu);
}

typedef struct { mock_net *n; uint32_t op; } delay_arg;

static void *delay_thread(void *p) {
    delay_arg a = *(delay_arg *)p;
    free(p);
    usleep((useconds_t)a.n->delay_ms * 1000);
    push_result(a.n, a.op);
    pthread_mutex_lock(&a.n->mu);
    a.n->threads_running--;
    pthread_mutex_unlock(&a.n->mu);
    return NULL;
}

int mock_async_start(void *u, uint32_t op, const char *req, size_t len) {
    (void)req; (void)len;
    mock_net *n = u;
    pthread_mutex_lock(&n->mu);
    n->started_count++;
    n->inflight++;
    if (n->inflight > n->max_inflight_seen) n->max_inflight_seen = n->inflight;
    n->last_op = op;
    int delay = n->delay_ms;
    if (delay > 0) n->threads_running++;
    pthread_mutex_unlock(&n->mu);
    if (delay <= 0) {
        push_result(n, op);
    } else {
        delay_arg *a = malloc(sizeof *a);
        a->n = n; a->op = op;
        pthread_t t;
        pthread_create(&t, NULL, delay_thread, a);
        pthread_detach(t);
    }
    return 0; /* returns immediately; the result is delivered later */
}

void mock_async_cancel(void *u, uint32_t op) {
    mock_net *n = u;
    pthread_mutex_lock(&n->mu);
    n->cancelled++;
    n->inflight--;
    if (op < MOCK_MAX_OPS) n->cancelled_flag[op] = 1;
    pthread_mutex_unlock(&n->mu);
}

int mock_async_wait(void *u, uint64_t deadline_ms, mock_result *res, int max) {
    mock_net *n = u;
    pthread_mutex_lock(&n->mu);
    while (n->queued == 0) {
        uint64_t now = mock_now_ms();
        if (now >= deadline_ms) break;
        uint64_t rel = deadline_ms - now;
        struct timespec ts = { (time_t)(rel / 1000), (long)(rel % 1000) * 1000000L };
        pthread_cond_timedwait_relative_np(&n->cv, &n->mu, &ts);
    }
    int count = 0;
    while (count < max && count < n->queued) { res[count] = n->queue[count]; count++; }
    memmove(n->queue, n->queue + count, (size_t)(n->queued - count) * sizeof(mock_result));
    n->queued -= count;
    n->inflight -= count;
    pthread_mutex_unlock(&n->mu);
    return count;
}
