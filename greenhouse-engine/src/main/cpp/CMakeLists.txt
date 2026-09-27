cmake_minimum_required(VERSION 3.22.1)

project("aaphostnative" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)
find_package(androidaudioplugin REQUIRED CONFIG)

add_library(aaphostnative
    SHARED
    engine/OboeEngine.cpp
    RackEngine.cpp
    RackEngineJni.cpp
)

target_include_directories(aaphostnative PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(aaphostnative
    PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    # Keep NaN / Inf semantics: this code handles output from untrusted plugins, and NaN checks must survive
    -ffast-math
    -fno-finite-math-only
    $<$<NOT:$<CONFIG:Debug>>:-O3 -funroll-loops -fomit-frame-pointer -flto=thin>
)

target_link_options(aaphostnative
    PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    $<$<NOT:$<CONFIG:Debug>>:-flto=thin>
)

target_link_libraries(aaphostnative
    PRIVATE
    androidaudioplugin::androidaudioplugin
    oboe::oboe
    log
)
