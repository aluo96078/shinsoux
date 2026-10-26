cmake_minimum_required(VERSION 3.20)
project(shinsou_sandbox_s0 C)

set(CMAKE_C_STANDARD 11)
set(QJS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/quickjs)

# QuickJS without quickjs-libc: no file, process, socket or environment access inside the sandbox.
add_library(quickjs STATIC
    ${QJS_DIR}/dtoa.c ${QJS_DIR}/libregexp.c ${QJS_DIR}/libunicode.c ${QJS_DIR}/quickjs.c)
target_include_directories(quickjs PUBLIC ${QJS_DIR})
target_compile_definitions(quickjs PRIVATE _GNU_SOURCE)
target_compile_options(quickjs PRIVATE -w)

add_library(shinsou_sandbox_core STATIC core/src/shinsou_sandbox_core.c)
target_include_directories(shinsou_sandbox_core PUBLIC core/src)
target_link_libraries(shinsou_sandbox_core PUBLIC quickjs)
target_compile_options(shinsou_sandbox_core PRIVATE -Wall -Wextra)

if(NOT CMAKE_SYSTEM_NAME STREQUAL "WASI" AND NOT ANDROID)
    find_package(Threads REQUIRED)
    add_executable(core_test core/test/core_test.c)
    target_link_libraries(core_test shinsou_sandbox_core Threads::Threads m)

    # The S0 compatibility bootstrap is the existing pure-JS iOS DOM layer (ADR-0002 §5.2).
    add_custom_command(
        OUTPUT ${CMAKE_BINARY_DIR}/bootstrap.js
        COMMAND python3 -I ${CMAKE_CURRENT_SOURCE_DIR}/scripts/extract-bootstrap.py ${CMAKE_BINARY_DIR}/bootstrap.js
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/scripts/extract-bootstrap.py
        ${CMAKE_CURRENT_SOURCE_DIR}/../../composeApp/src/iosMain/kotlin/dev/shinsou/kmp/plugin/IosJavaScriptBootstrap.kt)
    add_custom_target(bootstrap ALL DEPENDS ${CMAKE_BINARY_DIR}/bootstrap.js)
    enable_testing()
    add_test(NAME core_test COMMAND core_test ${CMAKE_BINARY_DIR}/bootstrap.js)
endif()
