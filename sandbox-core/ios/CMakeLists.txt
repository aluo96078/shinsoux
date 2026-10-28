cmake_minimum_required(VERSION 3.20)
project(shinsou_wasm_host C ASM)
# The iOS sandbox host library (ADR-0002 §4.5), built per Apple SDK/architecture:
#   cmake -S ios -B <dir> -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=iphoneos|iphonesimulator \
#         -DCMAKE_OSX_ARCHITECTURES=arm64 -DSSC_WASM_MODULE=<guest .wasm>
set(CMAKE_C_STANDARD 11)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()
set(SSC_CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/..)
find_package(Python3 REQUIRED COMPONENTS Interpreter)
include(wamr.cmake)
