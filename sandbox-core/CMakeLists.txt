cmake_minimum_required(VERSION 3.20)
project(shinsou_sandbox_core C)

# ADR-0002 §17: the production sandbox core (increment c1). Third-party sources are fetched by
# scripts/fetch-deps.sh at pinned tags and are not committed.

set(CMAKE_C_STANDARD 11)
if(ANDROID)
    # Hidden by default so --gc-sections can drop what the JNI entry points never reach.
    add_compile_options(-fvisibility=hidden -ffunction-sections -fdata-sections)
endif()
set(QJS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/quickjs)
set(BOOTSTRAP_KT ${CMAKE_CURRENT_SOURCE_DIR}/../composeApp/src/iosMain/kotlin/dev/shinsou/kmp/plugin/IosJavaScriptBootstrap.kt)

option(SSC_SANITIZE "Build with AddressSanitizer and UBSan" OFF)
option(SSC_FUZZ "Build the libFuzzer targets (clang with libFuzzer; implies ASan+UBSan)" OFF)
if(SSC_FUZZ)
    add_compile_options(-fsanitize=fuzzer-no-link,address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
    add_link_options(-fsanitize=address,undefined)
elseif(SSC_SANITIZE)
    add_compile_options(-fsanitize=address,undefined -fno-omit-frame-pointer)
    add_link_options(-fsanitize=address,undefined)
endif()

# QuickJS without quickjs-libc: no file, process, socket or environment access inside the sandbox.
add_library(quickjs STATIC
    ${QJS_DIR}/dtoa.c ${QJS_DIR}/libregexp.c ${QJS_DIR}/libunicode.c ${QJS_DIR}/quickjs.c)
target_include_directories(quickjs PUBLIC ${QJS_DIR})
target_compile_definitions(quickjs PRIVATE _GNU_SOURCE)
target_compile_options(quickjs PRIVATE -w)
# libm after quickjs on the link line (GNU ld resolves static archives in order).
target_link_libraries(quickjs PUBLIC m)

# lexbor: only the modules the DOM uses (no style/url/unicode/utils/engine).
set(LXB ${CMAKE_CURRENT_SOURCE_DIR}/third_party/lexbor/source)
set(LXB_SRC "")
foreach(m core css dom encoding html ns selectors tag)
    file(GLOB_RECURSE s CONFIGURE_DEPENDS ${LXB}/lexbor/${m}/*.c)
    list(APPEND LXB_SRC ${s})
endforeach()
if(WIN32)
    list(APPEND LXB_SRC ${LXB}/lexbor/ports/windows_nt/lexbor/core/memory.c)
else()
    list(APPEND LXB_SRC ${LXB}/lexbor/ports/posix/lexbor/core/memory.c)
endif()
add_library(lexbor_min STATIC ${LXB_SRC})
target_include_directories(lexbor_min PUBLIC ${LXB})
target_compile_options(lexbor_min PRIVATE -w)
target_compile_definitions(lexbor_min PUBLIC LEXBOR_STATIC)

add_library(ssc_frames STATIC src/ssc_frames.c)
target_include_directories(ssc_frames PUBLIC src)
target_compile_options(ssc_frames PRIVATE -Wall -Wextra -Werror)

add_library(ssc_core STATIC src/ssc_core.c src/ssc_dom_js.c src/sbx_dom.c)
target_include_directories(ssc_core PUBLIC src)
target_link_libraries(ssc_core PUBLIC quickjs lexbor_min)
target_compile_options(ssc_core PRIVATE -Wall -Wextra)

find_package(Python3 REQUIRED COMPONENTS Interpreter)
add_custom_command(
    OUTPUT ${CMAKE_BINARY_DIR}/ssc_bootstrap_js.c
    COMMAND Python3::Interpreter -I ${CMAKE_CURRENT_SOURCE_DIR}/scripts/embed-bootstrap.py ${BOOTSTRAP_KT} ${CMAKE_BINARY_DIR}/ssc_bootstrap_js.c
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/scripts/embed-bootstrap.py ${BOOTSTRAP_KT})

find_package(Threads REQUIRED)
add_library(ssc_driver STATIC src/ssc_driver.c ${CMAKE_BINARY_DIR}/ssc_bootstrap_js.c)
target_include_directories(ssc_driver PUBLIC src)
target_link_libraries(ssc_driver PUBLIC ssc_core ssc_frames Threads::Threads)
target_compile_options(ssc_driver PRIVATE -Wall -Wextra)

if(ANDROID)
    # S2: the isolated process loads this from the app (composeApp's externalNativeBuild). Only the
    # JNI entry points are exported; tests and tools are host-only.
    add_library(shinsou_sandbox SHARED src/ssc_android_jni.c)
    target_link_libraries(shinsou_sandbox PRIVATE ssc_driver)
    target_compile_options(shinsou_sandbox PRIVATE -Wall -Wextra -Werror)
    target_link_options(shinsou_sandbox PRIVATE
        -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
    return()
endif()

set(SSC_OS_SANDBOX_SRC src/ssc_os_sandbox.c src/ssc_os_windows.c)
add_executable(shinsou-sandbox src/ssc_main.c ${SSC_OS_SANDBOX_SRC})
target_link_libraries(shinsou-sandbox ssc_driver)
if(WIN32)
    # S3 Windows: one self-contained executable, since the AppContainer may read only its own
    # directory. userenv is loaded at run time, by the launcher only.
    target_link_libraries(shinsou-sandbox advapi32)
    target_link_options(shinsou-sandbox PRIVATE -static)
endif()


enable_testing()
# S3 isolation probe (test only, never shipped): the helper's lockdown, then every forbidden operation.
add_executable(isolation_probe test/isolation_probe.c ${SSC_OS_SANDBOX_SRC})
target_include_directories(isolation_probe PRIVATE src)
target_link_libraries(isolation_probe Threads::Threads)
if(WIN32)
    target_link_libraries(isolation_probe advapi32 ws2_32)
    target_link_options(isolation_probe PRIVATE -static)
endif()
if(APPLE OR WIN32)
    add_test(NAME isolation_probe COMMAND Python3::Interpreter -I ${CMAKE_CURRENT_SOURCE_DIR}/test/run_isolation_probe.py $<TARGET_FILE:isolation_probe>)
endif()
add_executable(frames_test test/frames_test.c)
target_link_libraries(frames_test ssc_frames)
add_test(NAME frames_test COMMAND frames_test)

add_custom_command(
    OUTPUT ${CMAKE_BINARY_DIR}/bootstrap.js
    COMMAND Python3::Interpreter -I ${CMAKE_CURRENT_SOURCE_DIR}/scripts/extract-bootstrap.py ${BOOTSTRAP_KT} ${CMAKE_BINARY_DIR}/bootstrap.js
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/scripts/extract-bootstrap.py ${BOOTSTRAP_KT})
add_custom_target(bootstrap_js ALL DEPENDS ${CMAKE_BINARY_DIR}/bootstrap.js)
add_executable(core_test test/core_test.c)
target_link_libraries(core_test ssc_core Threads::Threads)
add_test(NAME core_test COMMAND core_test ${CMAKE_BINARY_DIR}/bootstrap.js)

if(NOT WIN32) # socketpair harness; on Windows the Kotlin interop tests drive the real helper
    add_executable(driver_test test/driver_test.c)
    target_link_libraries(driver_test ssc_driver)
    add_test(NAME driver_test COMMAND driver_test)
endif()

add_executable(dom_js_test test/dom_js_test.c)
target_link_libraries(dom_js_test ssc_core)
add_test(NAME dom_js_test COMMAND dom_js_test ${CMAKE_BINARY_DIR}/bootstrap.js)

# jsoup parity through the script-visible API (needs spikes/sandbox-s0/html/golden fixtures).
add_executable(golden_js test/golden_js.c)
target_link_libraries(golden_js ssc_core)

add_executable(dom_fault_test test/dom_fault_test.c)
target_link_libraries(dom_fault_test ssc_core)
add_test(NAME dom_fault_test COMMAND dom_fault_test)

# ADR-0002 §13 / S1: long-running fuzz (see .github/workflows/sandbox-fuzz.yml).
if(SSC_FUZZ)
    add_executable(frames_fuzz fuzz/frames_fuzz.c)
    target_link_libraries(frames_fuzz ssc_frames)
    target_link_options(frames_fuzz PRIVATE -fsanitize=fuzzer)
    add_executable(chunks_fuzz fuzz/frames_fuzz.c)
    target_compile_definitions(chunks_fuzz PRIVATE SSC_FUZZ_CHUNKS)
    target_link_libraries(chunks_fuzz ssc_frames)
    target_link_options(chunks_fuzz PRIVATE -fsanitize=fuzzer)
    add_executable(dom_fuzz fuzz/dom_fuzz.c)
    target_link_libraries(dom_fuzz ssc_core)
    target_link_options(dom_fuzz PRIVATE -fsanitize=fuzzer)
    add_executable(frames_seeds fuzz/frames_seeds.c)
    target_link_libraries(frames_seeds ssc_frames)
endif()
