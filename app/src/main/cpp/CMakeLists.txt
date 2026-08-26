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
)

target_link_libraries(aaphostnative
    PRIVATE
    androidaudioplugin::androidaudioplugin
    oboe::oboe
    binder_ndk
    log
    android
)
