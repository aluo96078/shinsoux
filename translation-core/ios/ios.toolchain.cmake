# Cross-compiles translation-core for an Apple SDK: -DSTC_IOS_SDK=iphoneos or iphonesimulator.
# The engine's build files know nothing about iOS; these are the settings it needs spelled out.
# The nested build of the regex library gets this toolchain file but not the -D option, so the
# SDK may also come from the environment (the Gradle task exports it).
if(NOT STC_IOS_SDK)
    if(DEFINED ENV{STC_IOS_SDK})
        set(STC_IOS_SDK $ENV{STC_IOS_SDK})
    else()
        set(STC_IOS_SDK iphoneos)
    endif()
endif()
# Try-compile projects only see variables listed here.
list(APPEND CMAKE_TRY_COMPILE_PLATFORM_VARIABLES STC_IOS_SDK)
set(CMAKE_SYSTEM_NAME iOS)
set(CMAKE_SYSTEM_PROCESSOR arm64)
set(CMAKE_OSX_SYSROOT ${STC_IOS_SDK})
set(CMAKE_OSX_ARCHITECTURES "")   # the engine's architecture detection refuses an explicit arm64
set(CMAKE_OSX_DEPLOYMENT_TARGET 16.0)
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
set(CMAKE_MACOSX_BUNDLE OFF)
set(IOS_ARCH arm64)               # cpuinfo picks its ARM sources from this
# FindThreads cannot run its checks when cross-compiling for iOS.
set(CMAKE_THREAD_LIBS_INIT -pthread)
set(CMAKE_HAVE_THREADS_LIBRARY 1)
set(CMAKE_USE_PTHREADS_INIT 1)
set(THREADS_PREFER_PTHREAD_FLAG ON)
