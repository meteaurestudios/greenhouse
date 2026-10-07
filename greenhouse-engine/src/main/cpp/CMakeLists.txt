cmake_minimum_required(VERSION 3.22.1)

project("greenhouse-engine" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)
find_package(androidaudioplugin REQUIRED CONFIG)

add_library(greenhouse-engine
    SHARED
    engine/OboeEngine.cpp
    sequencer/Metronome.cpp
    sequencer/MidiSequencer.cpp
    sequencer/StandardMidiFile.cpp
    slot/AapSlotProcessor.cpp
    RackEngine.cpp
    EngineApi.cpp
    RackEngineJni.cpp
    MidiSequencerJni.cpp
)

target_include_directories(greenhouse-engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(greenhouse-engine
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

target_link_options(greenhouse-engine
    PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    $<$<NOT:$<CONFIG:Debug>>:-flto=thin>
)

target_link_libraries(greenhouse-engine
    PRIVATE
    androidaudioplugin::androidaudioplugin
    oboe::oboe
    log
)
