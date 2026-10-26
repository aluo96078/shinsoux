/* Frame kinds carried by env.host_bridge (guest -> host). The reply's first byte for BRIDGE_CALL
 * and ASYNC_START is an ok flag (0 = ok). S0 uses these raw frames; production uses the CBOR
 * SandboxProtocol v1 of ADR-0002 §4.2 over the same import. */
#ifndef SW_WASM_FRAMES_H
#define SW_WASM_FRAMES_H

enum {
    SW_FRAME_BRIDGE_CALL = 1,   /* [u32 method_len][method][args_json]        -> [ok][json]        */
    SW_FRAME_ASYNC_START = 2,   /* [u32 op][request_json]                      -> [ok]              */
    SW_FRAME_ASYNC_CANCEL = 3,  /* [u32 op]                                    -> []                */
    SW_FRAME_ASYNC_WAIT = 4,    /* [u64 deadline_ms]  (blocks)                 -> N*[op][ok][len][payload] */
    SW_FRAME_LOG = 5,           /* [bytes]                                     -> []                */
    SW_FRAME_FETCH_REPLY = 6,   /* []  re-reads a reply larger than out_cap                          */
};

#endif
