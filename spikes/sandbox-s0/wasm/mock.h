/* Mock Host used by the WASM/native comparison tests: sync bridge replies plus a simulated
 * network whose completions are posted from other threads (so results really arrive "later"). */
#ifndef SW_MOCK_H
#define SW_MOCK_H

#include <pthread.h>
#include <stddef.h>
#include <stdint.h>

#define MOCK_MAX_OPS 2048

typedef struct {
    uint32_t op;
    int ok;
    char *payload; /* malloc()ed, owned by the receiver */
    size_t len;
} mock_result;

typedef struct mock_net {
    pthread_mutex_t mu;
    pthread_cond_t cv;
    mock_result queue[MOCK_MAX_OPS];
    int queued;
    int delay_ms;               /* simulated network latency; 0 = completes at the next wait */
    int started_count;
    int inflight;
    int max_inflight_seen;
    int cancelled;
    uint32_t last_op;
    int cancelled_flag[MOCK_MAX_OPS];
    int sync_calls;
    int threads_running;
    char *html_body;            /* reply of bridge.httpGet */
    size_t html_len;
} mock_net;

uint64_t mock_now_ms(void);
void mock_init(mock_net *n, int delay_ms);
void mock_reset(mock_net *n);               /* waits for in-flight completion threads */
void mock_set_html(mock_net *n, const char *json_string_literal);
int mock_bridge(void *u, const char *method, size_t mlen, const char *args, size_t alen, char **out, size_t *olen);
int mock_async_start(void *u, uint32_t op, const char *req, size_t len);
void mock_async_cancel(void *u, uint32_t op);
/* Blocks until at least one result is queued or the monotonic deadline passes. */
int mock_async_wait(void *u, uint64_t deadline_ms, mock_result *res, int max);

#endif
