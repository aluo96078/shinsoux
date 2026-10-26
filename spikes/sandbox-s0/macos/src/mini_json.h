/* Minimal bounded JSON object scanner for SandboxProtocol frames (S0). Values stay as raw spans. */
#ifndef MINI_JSON_H
#define MINI_JSON_H
#include <stddef.h>
#include <stdint.h>

typedef struct { const char *p; size_t n; } span;

/* Validates that [s, s+n) is exactly one JSON value (bounded depth). Returns 1 if valid. */
int mj_validate(const char *s, size_t n);
/* Finds a top-level key of a JSON object; returns 1 and sets *v to the raw value span. */
int mj_get(span obj, const char *key, span *v);
/* Decodes a JSON string span (with quotes) to a NUL-terminated malloc()ed UTF-8 buffer. */
char *mj_string(span v, size_t *len);
int mj_u64(span v, uint64_t *out);
#endif
