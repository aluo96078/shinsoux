/*
 * The iOS sandbox host (ADR-0002 §4.5): runs the sandbox-core WASM guest in the WAMR fast
 * interpreter, one instance and one thread per package, and moves SandboxProtocol v1 bytes in and
 * out of it. The guest can reach nothing but these queues, the clocks and the time zone offset;
 * its memory is a bounded linear memory the interpreter checks on every access.
 */
#ifndef SSC_WASM_HOST_H
#define SSC_WASM_HOST_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ssc_wasm_session ssc_wasm_session;

/* Once per process (idempotent, thread-safe): starts WAMR and loads the embedded guest. 0 or -1. */
int ssc_wasm_engine_init(char *err, size_t err_len);

/* A new sandbox whose linear memory may not grow past `linear_max_bytes`. NULL with `err` on failure. */
ssc_wasm_session *ssc_wasm_session_open(uint32_t linear_max_bytes, char *err, size_t err_len);

/*
 * Frames for the sandbox. 0, or -1 once it has ended or its input was closed. A send that would
 * leave more than 48 MiB waiting for the guest fails the session itself: its input closes, what was
 * waiting is dropped, and the guest stops; every later send fails too.
 */
int ssc_wasm_session_send(ssc_wasm_session *s, const uint8_t *bytes, size_t len);

/* End of stream for the sandbox: it stops, even mid-hook, within the driver's poll interval. */
void ssc_wasm_session_close_input(ssc_wasm_session *s);

#define SSC_WASM_TIMEOUT (-2L)

/* Bytes from the sandbox: > 0, 0 once it has ended and everything was read, SSC_WASM_TIMEOUT. */
long ssc_wasm_session_receive(ssc_wasm_session *s, uint8_t *buf, size_t cap, uint32_t timeout_ms);

/* The guest's exit status once ended (SSC_DRIVER_*), -1 when it trapped or was terminated. */
int ssc_wasm_session_status(ssc_wasm_session *s);

/* Ends the sandbox (closing its input, then terminating it if it does not stop) and frees it. */
void ssc_wasm_session_free(ssc_wasm_session *s);

#ifdef __cplusplus
}
#endif

#endif
