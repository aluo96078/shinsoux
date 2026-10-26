# macOS helper for ADR-0002 S0. Standalone: cmake -S spikes/sandbox-s0/macos -B spikes/sandbox-s0/build/macos
cmake_minimum_required(VERSION 3.20)
project(shinsou_sandbox_macos C)
set(CMAKE_C_STANDARD 11)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()
set(S0 ${CMAKE_CURRENT_SOURCE_DIR}/..)
set(QJS_DIR ${S0}/third_party/quickjs)

add_library(quickjs STATIC ${QJS_DIR}/dtoa.c ${QJS_DIR}/libregexp.c ${QJS_DIR}/libunicode.c ${QJS_DIR}/quickjs.c)
target_include_directories(quickjs PUBLIC ${QJS_DIR})
target_compile_definitions(quickjs PRIVATE _GNU_SOURCE)
target_compile_options(quickjs PRIVATE -w)

add_library(ssc STATIC ${S0}/core/src/shinsou_sandbox_core.c)
target_include_directories(ssc PUBLIC ${S0}/core/src)
target_link_libraries(ssc PUBLIC quickjs)

find_package(Threads REQUIRED)
add_library(sbx STATIC src/sandbox_apply.c)
add_executable(shinsou-sandbox src/main.c src/mini_json.c)
target_link_libraries(shinsou-sandbox ssc sbx Threads::Threads m)
target_compile_options(shinsou-sandbox PRIVATE -Wall -Wextra)

# Isolation probe: applies the same confinement as the helper, then tries forbidden operations.
add_executable(isolation-probe src/probe.c)
target_link_libraries(isolation-probe sbx Threads::Threads)

# Bootstrap (same extraction as the root build).
set(BOOTSTRAP ${CMAKE_BINARY_DIR}/bootstrap.js)
add_custom_command(OUTPUT ${BOOTSTRAP}
  COMMAND python3 -I ${S0}/scripts/extract-bootstrap.py ${BOOTSTRAP}
  DEPENDS ${S0}/scripts/extract-bootstrap.py ${S0}/../../composeApp/src/iosMain/kotlin/dev/shinsou/kmp/plugin/IosJavaScriptBootstrap.kt)
add_custom_target(bootstrap ALL DEPENDS ${BOOTSTRAP})

# Ad-hoc signed copies: App Sandbox entitlement variants (no network.client). The plain binary is unsigned-entitlement.
set(ENT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/entitlements)
# App Sandbox only initialises for a binary that has an embedded Info.plist with CFBundleIdentifier (otherwise
# _libsecinit_appsandbox traps at launch). Embedding it is harmless for the Seatbelt-only variant.
foreach(T shinsou-sandbox isolation-probe)
  target_link_options(${T} PRIVATE -Wl,-sectcreate,__TEXT,__info_plist,${ENT_DIR}/Info.plist)
  add_custom_command(TARGET ${T} POST_BUILD
    COMMAND codesign --force --sign - $<TARGET_FILE:${T}>
    COMMAND cp $<TARGET_FILE:${T}> ${CMAKE_BINARY_DIR}/${T}-appsandbox
    COMMAND codesign --force --sign - --identifier dev.shinsou.${T}-appsandbox --entitlements ${ENT_DIR}/helper.entitlements ${CMAKE_BINARY_DIR}/${T}-appsandbox
    VERBATIM)
endforeach()
