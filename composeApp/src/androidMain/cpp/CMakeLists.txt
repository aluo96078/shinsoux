cmake_minimum_required(VERSION 3.20)
project(shinsou_android_native C CXX)

# The app's native library (one CMake project is all the Android Gradle plugin takes):
# - translation-core: libshinsou_translate.so, the offline translation engine (ADR-0004 §4.5).
#   The engine only supports 64-bit ARM here; on other ABIs the library is absent and the app
#   reports offline translation as unavailable.
# sandbox-core's libshinsou_sandbox.so is built by :feature-plugin (R5-1) and merged into the APK.
set(REPOSITORY_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_subdirectory(${REPOSITORY_ROOT}/translation-core translation-core)
endif()
