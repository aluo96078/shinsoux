/*
 * SandboxProtocol v1 frames in C (ADR-0002 §4.2, §17.1): the same bytes as the host's
 * composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/sandbox/SandboxProtocol.kt.
 *
 * Envelope: u32 big-endian body length (1..SSC_MAX_FRAME_BYTES) + body. Body: one CBOR array
 * [type, fields...] in a strict subset (unsigned ints, byte/text strings, arrays, false/true/null;
 * definite lengths; shortest encodings). Larger bodies travel as CHUNK envelopes [0, more, bytes]
 * where every chunk but the last carries exactly SSC_CHUNK_PAYLOAD_BYTES.
 *
 * Decoding is fail-closed: the first malformation makes the reader unusable.
 */
#ifndef SSC_FRAMES_H
#define SSC_FRAMES_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SSC_PROTOCOL_VERSION 1u
#define SSC_MAX_FRAME_BYTES (1u << 20)
#define SSC_CHUNK_PAYLOAD_BYTES (SSC_MAX_FRAME_BYTES - 16u)
#define SSC_LIMIT_FIELDS 10

typedef enum {
    SSC_F_CHUNK = 0,
    /* host -> sandbox */
    SSC_F_INIT = 1,
    SSC_F_INVOKE = 2,
    SSC_F_CANCEL = 3,
    SSC_F_BRIDGE_RESULT = 4,
    SSC_F_ASYNC_BRIDGE_RESULT = 5,
    SSC_F_ASYNC_CANCEL = 6,
    /* sandbox -> host */
    SSC_F_READY = 16,
    SSC_F_BRIDGE_CALL = 17,
    SSC_F_ASYNC_BRIDGE_CALL = 18,
    SSC_F_RESULT = 19,
    SSC_F_FAILURE = 20,
    SSC_F_LOG = 21,
    SSC_F_LIMIT_EXCEEDED = 22,
} ssc_frame_type;

/* Which side is reading: each side only accepts the other side's frame types. */
typedef enum { SSC_PEER_HOST = 0, SSC_PEER_SANDBOX = 1 } ssc_peer;

typedef struct {
    const uint8_t *ptr; /* points into the frame's owned body; valid until ssc_frame_free */
    size_t len;
} ssc_slice;

/*
 * One decoded frame. Field use by type:
 *   INIT              call_id, version, api_level, limits[], text = script
 *   INVOKE            call_id, text = method, text2 = args JSON
 *   CANCEL            call_id
 *   BRIDGE_RESULT     id = seq,   ok, ok ? (has_value ? text = JSON : null) : code
 *   ASYNC_BRIDGE_RES  id = op id, ok, ok ? (has_value ? text = JSON : null) : code
 *   ASYNC_CANCEL      id = op id
 *   READY             call_id, version
 *   BRIDGE_CALL       call_id, id = seq,   text = op, text2 = args JSON
 *   ASYNC_BRIDGE_CALL call_id, id = op id, text = op, text2 = args JSON
 *   RESULT            call_id, text = value JSON
 *   FAILURE           call_id, code
 *   LOG               text = bytes
 *   LIMIT_EXCEEDED    call_id, code = kind
 */
typedef struct {
    ssc_frame_type type;
    uint64_t call_id;
    uint64_t id;
    uint32_t version;
    uint32_t api_level;
    uint64_t limits[SSC_LIMIT_FIELDS];
    int ok;
    int has_value;
    uint32_t code;
    ssc_slice text;
    ssc_slice text2;
    uint8_t *owned; /* the body buffer; freed by ssc_frame_free */
} ssc_frame;

void ssc_frame_free(ssc_frame *f);

/* Per-field caps (bytes), mirroring SandboxFieldLimits. */
typedef struct {
    uint32_t max_script_bytes;
    uint32_t max_method_bytes;
    uint32_t max_json_bytes;
    uint32_t max_log_bytes;
} ssc_field_limits;

void ssc_field_limits_default(ssc_field_limits *l);

/* Status codes for this module (0 = OK). */
enum { SSC_FR_OK = 0, SSC_FR_MALFORMED = -1, SSC_FR_NOMEM = -2, SSC_FR_LIMIT = -3, SSC_FR_BROKEN = -4 };

/*
 * Decodes one complete body. On success *out owns a copy of the body. Returns SSC_FR_OK or
 * SSC_FR_MALFORMED (unknown type, wrong direction, bad encoding, trailing bytes, caps).
 */
int ssc_frame_decode(const uint8_t *body, size_t len, ssc_peer reader, const ssc_field_limits *limits, ssc_frame *out);

/* Growable output buffer for encoders. */
typedef struct {
    uint8_t *buf;
    size_t len;
    size_t cap;
    int failed; /* allocation failed; contents are unusable */
} ssc_buf;

void ssc_buf_free(ssc_buf *b);

/* Encodes a frame body (no envelope). Strings are copied as given; callers pass valid UTF-8. */
int ssc_encode_body(const ssc_frame *f, ssc_buf *out);

/*
 * Encodes a frame as envelopes (chunked when needed), appended to *out ready to write.
 */
int ssc_encode_envelopes(const ssc_frame *f, ssc_buf *out);

/* Incremental envelope reader with chunk reassembly. */
typedef struct ssc_reader ssc_reader;

ssc_reader *ssc_reader_new(ssc_peer peer, size_t max_message_bytes, const ssc_field_limits *limits);
void ssc_reader_free(ssc_reader *r);

/*
 * Feeds bytes; for every complete frame calls on_frame (which takes ownership of the frame; it
 * must call ssc_frame_free). Returns SSC_FR_OK, or a negative status after which the reader is
 * broken and every later feed returns SSC_FR_BROKEN.
 */
typedef void (*ssc_on_frame)(void *opaque, ssc_frame *frame);
int ssc_reader_feed(ssc_reader *r, const uint8_t *data, size_t len, ssc_on_frame on_frame, void *opaque);

/* True when no partial envelope or chunk sequence is buffered. */
int ssc_reader_idle(const ssc_reader *r);

/* Strict UTF-8 check (no overlongs, surrogates or values above U+10FFFF). */
int ssc_utf8_valid(const uint8_t *p, size_t len);

#ifdef __cplusplus
}
#endif

#endif
