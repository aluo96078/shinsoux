/* Shinsou translation core (ADR-0004 §4.5): a C interface over the offline translation engine. */
#ifndef SHINSOU_TRANSLATION_CORE_H
#define SHINSOU_TRANSLATION_CORE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct stc_engine stc_engine;

/* The four files of one translation direction. `trg_vocab` may equal `src_vocab`. */
typedef struct {
    const char *model;
    const char *src_vocab;
    const char *trg_vocab;
    const char *shortlist;
} stc_model;

stc_engine *stc_create(void);
void stc_destroy(stc_engine *engine);

/*
 * Translates `count` UTF-8 lines, one output per input, in order. The engine keeps one model
 * loaded: asking for another releases the previous one first (a model is about 350 MB of
 * memory). On success returns 0 and sets `*outputs` to `count` strings; on failure returns
 * non-zero and sets `*error`. Release either with stc_free_lines / stc_free.
 */
int stc_translate(stc_engine *engine, const stc_model *model, const char *const *lines, size_t count,
                  char ***outputs, char **error);

/* Releases the loaded model and the memory it holds; the engine stays usable. */
void stc_unload(stc_engine *engine);

/*
 * How many bytes this process may still take before the system ends it, where the system says
 * (iOS); 0 where it does not. Asked before a model is loaded.
 */
size_t stc_available_memory(void);

void stc_free_lines(char **lines, size_t count);
void stc_free(char *text);

#ifdef __cplusplus
}
#endif
#endif
