cmake_minimum_required(VERSION 3.20)
project(s0bench_ios C ASM)
# iOS arm64 static library: WAMR fast interpreter (THREAD_MGR=1, no JIT/AOT) + the two S0 WASM
# benchmarks from ../wasm (QuickJS core in WASM, and lexbor in WASM), unchanged except `main`.
# Built by build.sh with the iOS SDK (CMAKE_SYSTEM_NAME=iOS); the Xcode app links the result.
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_FLAGS_RELEASE "-O2 -DNDEBUG")
set(S0 ${CMAKE_CURRENT_SOURCE_DIR}/..)
set(WAMR_ROOT_DIR ${S0}/third_party/wamr)
set(WAMR_BUILD_PLATFORM "darwin")
set(WAMR_BUILD_TARGET "AARCH64")
set(WAMR_BUILD_INTERP 1)
set(WAMR_BUILD_FAST_INTERP 1)
set(WAMR_BUILD_AOT 0)
set(WAMR_BUILD_JIT 0)
set(WAMR_BUILD_FAST_JIT 0)
set(WAMR_BUILD_LIBC_BUILTIN 0)
set(WAMR_BUILD_LIBC_WASI 0)
set(WAMR_BUILD_MULTI_MODULE 0)
set(WAMR_BUILD_LIB_PTHREAD 0)
set(WAMR_BUILD_SHARED_MEMORY 0)
set(WAMR_BUILD_THREAD_MGR 1)
set(WAMR_BUILD_BULK_MEMORY 1)
set(WAMR_BUILD_REF_TYPES 1)
set(WAMR_BUILD_SIMD 0)
set(WAMR_BUILD_MINI_LOADER 0)
set(WAMR_BUILD_EXCE_HANDLING 0)
set(WAMR_BUILD_GC 0)
set(WAMR_BUILD_DEBUG_INTERP 0)
# iOS refuses the hardware bound check's large reservation and mprotect()ed stack guard pages
# ("Failed to init stack guard pages"); use WAMR's software bounds checks instead.
set(WAMR_DISABLE_HW_BOUND_CHECK 1)
set(WAMR_DISABLE_STACK_HW_BOUND_CHECK 1)
include(${WAMR_ROOT_DIR}/build-scripts/runtime_lib.cmake)

set(W ${S0}/wasm)
add_library(s0bench STATIC
    ${WAMR_RUNTIME_LIB_SOURCE}
    ${W}/backend_wamr.c ${W}/wamr_host.c ${W}/frame_host.c ${W}/mock.c ${W}/bench.c ${W}/dombench/db_wamr.c)
target_include_directories(s0bench PRIVATE ${W} ${S0}/core/src ${WAMR_ROOT_DIR}/core/iwasm/include)
target_compile_definitions(s0bench PRIVATE SW_WAMR_FLAVOUR="wamr-fast-ios")
target_compile_options(s0bench PRIVATE -w)
set_source_files_properties(${W}/bench.c PROPERTIES COMPILE_DEFINITIONS "main=s0_bench_main")
set_source_files_properties(${W}/dombench/db_wamr.c PROPERTIES COMPILE_DEFINITIONS "main=s0_dombench_main")
