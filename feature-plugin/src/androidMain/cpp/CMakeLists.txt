cmake_minimum_required(VERSION 3.20)
project(shinsou_plugin_native C)

# sandbox-core: libshinsou_sandbox.so, the plugin sandbox (ADR-0002 S2), for every listed ABI. The
# isolated sandbox process loads it from the app's APK, where this library's native libraries are
# merged (R5-1: built here, next to SandboxNative and the sandbox's manifest entries).
set(REPOSITORY_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)
add_subdirectory(${REPOSITORY_ROOT}/sandbox-core sandbox-core)
