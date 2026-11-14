cmake_minimum_required(VERSION 3.20)
project(shinsou_translation_core CXX C)

# ADR-0004 §4.5: the offline translation engine behind a small C interface. The engine's sources
# are fetched by scripts/fetch-deps.sh at a pinned commit, patched, and are not committed.

set(ENGINE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/translations/inference)
if(NOT EXISTS ${ENGINE_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "Run translation-core/scripts/fetch-deps.sh first")
endif()

# The engine's own build files predate current CMake.
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
set(GIT_SUBMODULE OFF CACHE BOOL "" FORCE)             # fetch-deps.sh did it, pinned
set(COMPILE_CUDA OFF CACHE BOOL "" FORCE)
set(COMPILE_CPU ON CACHE BOOL "" FORCE)
set(SSPLIT_USE_INTERNAL_PCRE2 ON CACHE BOOL "" FORCE)  # the verified copy from fetch-deps.sh

if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(arm64|aarch64)$" OR CMAKE_OSX_ARCHITECTURES MATCHES "arm64" OR ANDROID_ABI STREQUAL "arm64-v8a")
    # The engine only passes these to part of its tree; every file that includes its SIMD
    # headers needs them.
    add_compile_definitions(ARM FMA SSE)
    if(CMAKE_CROSSCOMPILING)
        set(BUILD_ARCH armv8-a CACHE STRING "" FORCE)
    endif()
endif()

if(WIN32)
    # The regex library is linked statically; without this its header declares DLL imports.
    add_compile_definitions(PCRE2_STATIC)
    # Marian's path matching needs shlwapi. As a standard library it lands at the end of the link
    # line, after the static libraries that refer to it.
    set(CMAKE_CXX_STANDARD_LIBRARIES "${CMAKE_CXX_STANDARD_LIBRARIES} -lshlwapi")
    if(NOT BUILD_ARCH)
        # A baseline every 64-bit Windows 10 machine has. The engine's default, "native", would
        # tie the helper to the build machine's CPU.
        set(BUILD_ARCH x86-64-v2 CACHE STRING "" FORCE)
    endif()
endif()

# The engine's documentation target needs a Doxyfile.in this checkout does not have; a machine
# with Doxygen installed (GitHub's windows-2025 image) would otherwise fail to configure.
set(USE_DOXYGEN OFF CACHE BOOL "" FORCE)
add_subdirectory(${ENGINE_DIR} engine EXCLUDE_FROM_ALL)

add_library(shinsou_translation_core STATIC src/stc.cpp)
target_include_directories(shinsou_translation_core PUBLIC src)
target_link_libraries(shinsou_translation_core PRIVATE bergamot-translator-source)
set_target_properties(shinsou_translation_core PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)

if(ANDROID)
    # Loaded in the app process; only the JNI entry points are exported.
    add_library(shinsou_translate SHARED src/stc_jni.cpp)
    target_link_libraries(shinsou_translate PRIVATE shinsou_translation_core)
    target_compile_options(shinsou_translate PRIVATE -fvisibility=hidden)
    target_link_options(shinsou_translate PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL
        -Wl,-z,max-page-size=16384)  # devices with 16 KB pages refuse 4 KB-aligned libraries
    set_target_properties(shinsou_translate PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
endif()

if(NOT ANDROID AND NOT CMAKE_SYSTEM_NAME STREQUAL "iOS")
    add_executable(shinsou-translate src/stc_main.cpp)
    target_link_libraries(shinsou-translate PRIVATE shinsou_translation_core)
    if(WIN32)
        if(MINGW)
            # Self-contained: no libstdc++ or winpthread DLLs to ship beside it.
            target_link_options(shinsou-translate PRIVATE -static -s)
        endif()
    endif()
    set_target_properties(shinsou-translate PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
endif()
