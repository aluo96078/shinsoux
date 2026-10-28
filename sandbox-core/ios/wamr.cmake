# WAMR as ADR-0002 §4.5 / S0 require it: the fast interpreter only (no JIT or AOT, which iOS
# forbids anyway), the thread manager so a running instance can be terminated, no WASI or libc
# builtins (the guest's only imports are the four host functions), software bounds checks (iOS
# refuses the hardware check's address-space reservation and stack guard pages).
set(WAMR_ROOT_DIR ${SSC_CORE_DIR}/third_party/wamr)
set(WAMR_BUILD_PLATFORM "darwin")
if(CMAKE_OSX_ARCHITECTURES MATCHES "x86_64" OR (NOT CMAKE_OSX_ARCHITECTURES AND CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64"))
    set(WAMR_BUILD_TARGET "X86_64")
else()
    set(WAMR_BUILD_TARGET "AARCH64")
endif()
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
set(WAMR_DISABLE_HW_BOUND_CHECK 1)
set(WAMR_DISABLE_STACK_HW_BOUND_CHECK 1)
include(${WAMR_ROOT_DIR}/build-scripts/runtime_lib.cmake)

# The host library: WAMR + the session shim + the embedded guest (SSC_WASM_MODULE).
add_custom_command(
    OUTPUT ${CMAKE_BINARY_DIR}/ssc_wasm_module.c
    COMMAND Python3::Interpreter -I ${SSC_CORE_DIR}/wasm/embed-module.py ${SSC_WASM_MODULE} ${CMAKE_BINARY_DIR}/ssc_wasm_module.c
    DEPENDS ${SSC_CORE_DIR}/wasm/embed-module.py ${SSC_WASM_MODULE})
add_library(shinsou_wasm_host STATIC
    ${WAMR_RUNTIME_LIB_SOURCE} ${SSC_CORE_DIR}/ios/ssc_wasm_host.c ${CMAKE_BINARY_DIR}/ssc_wasm_module.c)
target_include_directories(shinsou_wasm_host PUBLIC ${SSC_CORE_DIR}/ios PRIVATE ${WAMR_ROOT_DIR}/core/iwasm/include)
target_compile_options(shinsou_wasm_host PRIVATE -w)
set_source_files_properties(${SSC_CORE_DIR}/ios/ssc_wasm_host.c PROPERTIES COMPILE_OPTIONS "-Wall;-Wextra")
