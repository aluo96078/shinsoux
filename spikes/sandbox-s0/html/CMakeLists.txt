# S0 sandbox HTML parser evaluation (ADR-0002 section 5.2).
# Standalone: builds the used lexbor modules from third_party/lexbor plus sandbox_dom.c.
# Native:  cmake -S html -B build/html && cmake --build build/html
# wasm32:  see html/scripts/build-wasm.sh (compile-only check)
cmake_minimum_required(VERSION 3.20)
project(shinsou_sandbox_html C)
set(CMAKE_C_STANDARD 11)

set(LXB ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/lexbor/source)
set(LXB_MODULES core css dom encoding html ns selectors tag)   # no style/url/unicode/utils/engine
set(LXB_SRC "")
foreach(m ${LXB_MODULES})
    file(GLOB_RECURSE s CONFIGURE_DEPENDS ${LXB}/lexbor/${m}/*.c)
    list(APPEND LXB_SRC ${s})
endforeach()
list(APPEND LXB_SRC ${LXB}/lexbor/ports/posix/lexbor/core/memory.c)

option(SBX_SANITIZE "ASan+UBSan" OFF)
option(SBX_LIBFUZZER "libFuzzer build (clang with the fuzzer runtime; implies SBX_SANITIZE)" OFF)
set(SBX_COPTS -O2 -fvisibility=hidden)
if(SBX_SANITIZE OR SBX_LIBFUZZER)
    set(SBX_SANITIZE ON)
    set(SBX_COPTS -O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined)
endif()
if(SBX_LIBFUZZER)
    list(APPEND SBX_COPTS -fsanitize=fuzzer-no-link)
endif()

add_library(lexbor_min STATIC ${LXB_SRC})
target_include_directories(lexbor_min PUBLIC ${LXB})
target_compile_options(lexbor_min PRIVATE ${SBX_COPTS} -w)
target_compile_definitions(lexbor_min PRIVATE LEXBOR_STATIC)
if(SBX_SANITIZE)
    target_link_options(lexbor_min PUBLIC -fsanitize=address,undefined)
endif()

add_library(sandbox_dom STATIC src/sandbox_dom.c)
target_include_directories(sandbox_dom PUBLIC src)
target_link_libraries(sandbox_dom PUBLIC lexbor_min)
target_compile_options(sandbox_dom PRIVATE ${SBX_COPTS} -Wall -Wextra)

if(NOT CMAKE_SYSTEM_NAME STREQUAL "WASI")
    add_executable(dom_test test/dom_test.c)
    target_link_libraries(dom_test sandbox_dom m)
    target_compile_options(dom_test PRIVATE ${SBX_COPTS})
    add_executable(golden_run test/golden_run.c)
    target_link_libraries(golden_run sandbox_dom m)
    target_compile_options(golden_run PRIVATE ${SBX_COPTS})
    # Stand-alone mutation fuzzer (works with any compiler); the libFuzzer build is
    # scripts/run-fuzz.sh (needs a clang that ships the fuzzer runtime, e.g. Homebrew LLVM).
    add_executable(fuzz_dom test/fuzz_dom.c)
    target_compile_definitions(fuzz_dom PRIVATE SBX_STANDALONE_FUZZ)
    target_link_libraries(fuzz_dom sandbox_dom m)
    target_compile_options(fuzz_dom PRIVATE ${SBX_COPTS})
    if(SBX_LIBFUZZER)
        add_executable(fuzz_libfuzzer test/fuzz_dom.c)
        target_link_libraries(fuzz_libfuzzer sandbox_dom m)
        target_compile_options(fuzz_libfuzzer PRIVATE ${SBX_COPTS})
        target_link_options(fuzz_libfuzzer PRIVATE -fsanitize=fuzzer,address,undefined)
    endif()
    if(NOT SBX_SANITIZE)   # timings are only meaningful for the optimised build
        add_executable(bench_dom test/bench_dom.c)
        target_link_libraries(bench_dom sandbox_dom m)
        target_compile_options(bench_dom PRIVATE ${SBX_COPTS})

        # Baseline for the benchmark: the S0 core with the iOS pure-JS DOM bootstrap.  Built from the
        # sources in place (core/ and third_party/quickjs are only read).
        set(QJS ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/quickjs)
        set(CORE ${CMAKE_CURRENT_SOURCE_DIR}/../core/src)
        find_package(Threads REQUIRED)
        add_library(quickjs_bench STATIC ${QJS}/dtoa.c ${QJS}/libregexp.c ${QJS}/libunicode.c ${QJS}/quickjs.c ${CORE}/shinsou_sandbox_core.c)
        target_include_directories(quickjs_bench PUBLIC ${QJS} ${CORE})
        target_compile_definitions(quickjs_bench PRIVATE _GNU_SOURCE)
        target_compile_options(quickjs_bench PRIVATE -w ${SBX_COPTS})
        add_executable(bench_js test/bench_js.c)
        target_link_libraries(bench_js quickjs_bench Threads::Threads m)
        target_compile_options(bench_js PRIVATE ${SBX_COPTS})
        add_executable(parity_js test/parity_js.c)
        target_link_libraries(parity_js quickjs_bench Threads::Threads m)
        target_compile_options(parity_js PRIVATE ${SBX_COPTS})
        add_custom_command(
            OUTPUT ${CMAKE_BINARY_DIR}/bootstrap.js
            COMMAND python3 -I ${CMAKE_CURRENT_SOURCE_DIR}/../scripts/extract-bootstrap.py ${CMAKE_BINARY_DIR}/bootstrap.js
            DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/../scripts/extract-bootstrap.py
            ${CMAKE_CURRENT_SOURCE_DIR}/../../../composeApp/src/iosMain/kotlin/dev/shinsou/kmp/plugin/IosJavaScriptBootstrap.kt)
        add_custom_target(html_bootstrap ALL DEPENDS ${CMAKE_BINARY_DIR}/bootstrap.js)

    endif()

    enable_testing()
    add_test(NAME dom_test COMMAND dom_test)
endif()
