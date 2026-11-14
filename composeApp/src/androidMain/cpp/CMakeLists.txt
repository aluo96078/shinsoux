cmake_minimum_required(VERSION 3.20)
project(shinsou_android_native C CXX)

# The app's native libraries (one CMake project is all the Android Gradle plugin takes):
# - sandbox-core: libshinsou_sandbox.so, the plugin sandbox (ADR-0002 S2), for every listed ABI.
# - translation-core: libshinsou_translate.so, the offline translation engine (ADR-0004 §4.5).
#   The engine only supports 64-bit ARM here; on other ABIs the library is absent and the app
#   reports offline translation as unavailable.
set(REPOSITORY_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)
add_subdirectory(${REPOSITORY_ROOT}/sandbox-core sandbox-core)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_subdirectory(${REPOSITORY_ROOT}/translation-core translation-core)
endif()
