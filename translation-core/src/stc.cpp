#include "stc.h"

#if defined(__APPLE__)
#include <TargetConditionals.h>
#if TARGET_OS_IPHONE && !TARGET_OS_SIMULATOR
#include <os/proc.h>
#endif
#endif

#include <cstdlib>
#include <cstring>
#include <future>
#include <memory>
#include <string>
#include <vector>

#include "translator/parser.h"
#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/service.h"

using marian::bergamot::AsyncService;
using marian::bergamot::Response;
using marian::bergamot::ResponseOptions;
using marian::bergamot::TranslationModel;

struct stc_engine {
    // The service owns the workers and the workspace: memory only comes back when it goes.
    std::unique_ptr<AsyncService> service;
    std::shared_ptr<TranslationModel> model;
    std::string loaded;
};

namespace {

char *copy(const std::string &text) {
    char *out = static_cast<char *>(std::malloc(text.size() + 1));
    if (out) std::memcpy(out, text.c_str(), text.size() + 1);
    return out;
}

// A YAML double-quoted scalar: paths may hold spaces, colons and backslashes.
std::string quoted(const char *path) {
    std::string out = "\"";
    for (const char *c = path; *c; ++c) {
        if (*c == '\\' || *c == '"') out += '\\';
        out += *c;
    }
    return out + "\"";
}

std::string configFor(const stc_model &m) {
    return "models: [" + quoted(m.model) + "]\n"
           "vocabs: [" + quoted(m.src_vocab) + ", " + quoted(m.trg_vocab) + "]\n"
           "shortlist: [" + quoted(m.shortlist) + ", false]\n"
           "beam-size: 1\nnormalize: 1.0\nword-penalty: 0\nmax-length-break: 128\n"
           "mini-batch-words: 1024\nworkspace: 128\nmax-length-factor: 2.0\nskip-cost: true\n"
           "cpu-threads: 0\nquiet: true\nquiet-translation: true\n"
           "gemm-precision: int8shiftAlphaAll\nalignment: soft\n";
}

void unload(stc_engine *engine) {
    engine->model.reset();
    engine->service.reset();
    engine->loaded.clear();
}

}  // namespace

extern "C" {

stc_engine *stc_create(void) { return new (std::nothrow) stc_engine(); }

void stc_destroy(stc_engine *engine) {
    if (!engine) return;
    unload(engine);
    delete engine;
}

void stc_unload(stc_engine *engine) {
    if (engine) unload(engine);
}

int stc_translate(stc_engine *engine, const stc_model *model, const char *const *lines, size_t count,
                  char ***outputs, char **error) {
    if (outputs) *outputs = nullptr;
    if (error) *error = nullptr;
    if (!engine || !model || !model->model || !model->src_vocab || !model->trg_vocab || !model->shortlist ||
        (!lines && count) || !outputs) {
        if (error) *error = copy("invalid arguments");
        return 1;
    }
    try {
        std::string config = configFor(*model);
        if (engine->loaded != config) {
            unload(engine);
            AsyncService::Config serviceConfig;
            serviceConfig.numWorkers = 1;  // More workers multiply memory for little speed (ADR-0004 §3.4).
            engine->service = std::make_unique<AsyncService>(serviceConfig);
            engine->model = engine->service->createCompatibleModel(
                marian::bergamot::parseOptionsFromString(config, /*validate=*/true));
            engine->loaded = config;
        }
        // One request per line: the answer to a line can never spill into its neighbour.
        std::vector<std::future<Response>> pending;
        pending.reserve(count);
        for (size_t i = 0; i < count; ++i) {
            auto promise = std::make_shared<std::promise<Response>>();
            pending.push_back(promise->get_future());
            engine->service->translate(engine->model, std::string(lines[i] ? lines[i] : ""),
                                       [promise](Response &&response) { promise->set_value(std::move(response)); },
                                       ResponseOptions());
        }
        char **result = static_cast<char **>(std::calloc(count ? count : 1, sizeof(char *)));
        if (!result) throw std::bad_alloc();
        for (size_t i = 0; i < count; ++i) {
            result[i] = copy(pending[i].get().target.text);
            if (!result[i]) {
                stc_free_lines(result, count);
                throw std::bad_alloc();
            }
        }
        *outputs = result;
        return 0;
    } catch (const std::exception &e) {
        unload(engine);
        if (error) *error = copy(e.what());
        return 1;
    } catch (...) {
        unload(engine);
        if (error) *error = copy("translation engine failed");
        return 1;
    }
}

void stc_free_lines(char **lines, size_t count) {
    if (!lines) return;
    for (size_t i = 0; i < count; ++i) std::free(lines[i]);
    std::free(lines);
}

void stc_free(char *text) { std::free(text); }

size_t stc_available_memory(void) {
#if defined(__APPLE__) && TARGET_OS_IPHONE && !TARGET_OS_SIMULATOR
    return os_proc_available_memory();
#else
    return 0;
#endif
}

}  // extern "C"
