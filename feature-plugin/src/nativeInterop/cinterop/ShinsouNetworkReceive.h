#import <Network/Network.h>

typedef void (^shinsou_receive_completion_t)(dispatch_data_t _Nullable,
                                           bool,
                                           nw_error_t _Nullable);

// Apple's context may be a block-backed singleton. Kotlin/Native tries to convert it
// to Any before invoking a Kotlin lambda, even when that lambda ignores the argument.
// These byte-stream transports do not use contexts; discard it on the native side.
static inline nw_connection_receive_completion_t
shinsou_receive_adapter(shinsou_receive_completion_t completion) {
    return ^(dispatch_data_t data, nw_content_context_t context, bool complete, nw_error_t error) {
        completion(data, complete, error);
    };
}

static inline void shinsou_connection_receive(nw_connection_t connection,
                                              uint32_t minimum,
                                              uint32_t maximum,
                                              shinsou_receive_completion_t completion) {
    nw_connection_receive(connection, minimum, maximum, shinsou_receive_adapter(completion));
}

// Exercise the same adapter with Apple's actual singleton without opening a socket.
static inline void shinsou_receive_default_context_probe(shinsou_receive_completion_t completion) {
    shinsou_receive_adapter(completion)(NULL, NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT, true, NULL);
}
