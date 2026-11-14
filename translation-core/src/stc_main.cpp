// shinsou-translate: the desktop helper around the translation core (ADR-0004 §4.5). A separate
// process, so a failed model load (the engine aborts) or a crash cannot take the app down, and
// ending it returns every byte of model memory.
//
// Protocol, UTF-8 lines on stdin/stdout:
//   TRANSLATE <count>\n <model>\n <source vocab>\n <target vocab>\n <shortlist>\n <count lines>
//     -> OK <count>\n <count lines>      or      ERROR <message>\n
//   UNLOAD -> OK 0\n
// End of input ends the process.
#include <clocale>
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

#include "stc.h"

#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

namespace {
const size_t kMaxLines = 4096;
const size_t kMaxLineBytes = 64 * 1024;

bool readLine(std::string &line) {
    if (!std::getline(std::cin, line)) return false;
    if (!line.empty() && line.back() == '\r') line.pop_back();
    return true;
}

void fail(const std::string &message) {
    std::string flat = message;
    for (char &c : flat) if (c == '\n' || c == '\r') c = ' ';
    std::cout << "ERROR " << flat << "\n" << std::flush;
}
}  // namespace

int main() {
#ifdef _WIN32
    // Paths arrive as UTF-8; with the UCRT this makes the C runtime's file functions take them as
    // such, so a profile directory with non-ASCII characters works. Binary mode keeps "\n" as it is.
    std::setlocale(LC_ALL, ".UTF-8");
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
#endif
    std::ios::sync_with_stdio(false);
    stc_engine *engine = stc_create();
    if (!engine) return 1;
    std::string command;
    while (readLine(command)) {
        if (command == "UNLOAD") {
            stc_unload(engine);
            std::cout << "OK 0\n" << std::flush;
            continue;
        }
        if (command.rfind("TRANSLATE ", 0) != 0) {
            fail("unknown command");
            break;  // The stream is out of step: nothing after this can be trusted.
        }
        char *end = nullptr;
        unsigned long count = std::strtoul(command.c_str() + 10, &end, 10);
        if (!end || *end != '\0' || count > kMaxLines) {
            fail("invalid line count");
            break;
        }
        std::string paths[4];
        bool complete = true;
        for (auto &path : paths) complete = complete && readLine(path);
        std::vector<std::string> lines(count);
        bool oversized = false;
        for (auto &line : lines) {
            complete = complete && readLine(line);
            oversized = oversized || line.size() > kMaxLineBytes;
        }
        if (!complete) break;
        if (oversized) {
            fail("line too long");
            continue;
        }
        std::vector<const char *> inputs;
        inputs.reserve(count);
        for (const auto &line : lines) inputs.push_back(line.c_str());
        stc_model model = {paths[0].c_str(), paths[1].c_str(), paths[2].c_str(), paths[3].c_str()};
        char **outputs = nullptr;
        char *error = nullptr;
        if (stc_translate(engine, &model, inputs.data(), count, &outputs, &error) != 0) {
            fail(error ? error : "translation failed");
            stc_free(error);
            continue;
        }
        std::cout << "OK " << count << "\n";
        for (size_t i = 0; i < count; ++i) {
            std::string text = outputs[i];
            for (char &c : text) if (c == '\n' || c == '\r') c = ' ';
            std::cout << text << "\n";
        }
        std::cout << std::flush;
        stc_free_lines(outputs, count);
    }
    stc_destroy(engine);
    return 0;
}
