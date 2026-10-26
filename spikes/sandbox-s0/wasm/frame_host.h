/* Transport-independent handling of the guest's host_bridge frames (shared by the WAMR and
 * wasm2c embedders). */
#ifndef SW_FRAME_HOST_H
#define SW_FRAME_HOST_H

#include <stdatomic.h>

#include "wamr_host.h" /* sw_callbacks, sw_async_result */

typedef struct {
    sw_callbacks cb;
    uint8_t *pending;      /* reply larger than the guest buffer, parked until FETCH_REPLY */
    size_t pending_len;
    atomic_int *dead;      /* set by terminate; lets blocked waits return early */
} sw_frame_host;

/* Returns reply length (> out_cap means "parked, fetch it"), or -1. */
int32_t sw_frame_dispatch(sw_frame_host *s, const uint8_t *frame, uint32_t frame_len, uint8_t *out, uint32_t out_cap);

#endif
