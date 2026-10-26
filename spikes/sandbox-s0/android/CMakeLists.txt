# Builds libshinsou_sandbox_core.so (QuickJS + core + JNI). Used for the NDK ABIs and, with
# -DSSC_HOST_JNI=ON, for a macOS/Linux JVM test build. The repo-level CMakeLists is untouched.
cmake_minimum_required(VERSION 3.20)
project(shinsou_sandbox_android C)

set(CMAKE_C_STANDARD 11)
set(S0 ${CMAKE_CURRENT_SOURCE_DIR}/..)
set(QJS_DIR ${S0}/third_party/quickjs)

add_library(quickjs_a STATIC
    ${QJS_DIR}/dtoa.c ${QJS_DIR}/libregexp.c ${QJS_DIR}/libunicode.c ${QJS_DIR}/quickjs.c)
target_include_directories(quickjs_a PUBLIC ${QJS_DIR})
target_compile_definitions(quickjs_a PRIVATE _GNU_SOURCE)
target_compile_options(quickjs_a PRIVATE -w -fvisibility=hidden -fPIC)

add_library(shinsou_sandbox_core SHARED ${S0}/core/src/shinsou_sandbox_core.c jni/sandbox_jni.c)
target_include_directories(shinsou_sandbox_core PRIVATE ${S0}/core/src)
target_link_libraries(shinsou_sandbox_core PRIVATE quickjs_a m)
target_compile_options(shinsou_sandbox_core PRIVATE -Wall -Wextra -fvisibility=hidden)

if(SSC_HOST_JNI)
    find_package(JNI REQUIRED)
    target_include_directories(shinsou_sandbox_core PRIVATE ${JNI_INCLUDE_DIRS})
    # JNI_OnLoad-free; System.loadLibrary on macOS wants .dylib
    set_target_properties(shinsou_sandbox_core PROPERTIES SUFFIX ".dylib")
else()
    # Android: no libc beyond bionic basics; 16 KiB page alignment for newer devices.
    target_link_options(shinsou_sandbox_core PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL)
endif()
