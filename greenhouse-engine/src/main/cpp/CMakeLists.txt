cmake_minimum_required(VERSION 3.22.1)

project("aaphostnative" LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)
find_package(androidaudioplugin REQUIRED CONFIG)

add_library(aaphostnative
    SHARED
    NativeAudioEngine.cpp
    NativeAudioEngineJni.cpp
)

target_compile_options(aaphostnative
    PRIVATE
    -std=c++17
    -Wall
    -Wextra
    -Wno-unused-parameter
    -O3
    -ffast-math
    -funroll-loops
    -fomit-frame-pointer
    -fvectorize
    -fslp-vectorize
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    -flto=thin
)

target_link_options(aaphostnative
    PRIVATE
    -flto=thin
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)

target_link_libraries(aaphostnative
    PRIVATE
    androidaudioplugin::androidaudioplugin
    oboe::oboe
    binder_ndk
    log
    android
)
