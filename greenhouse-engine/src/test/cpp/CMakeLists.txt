# Desktop tests of the native MIDI sequencer (no Android, no Oboe):
#   cmake -S greenhouse-engine/src/test/cpp -B build/sequencer-test && cmake --build build/sequencer-test && build/sequencer-test/MidiSequencerTest
cmake_minimum_required(VERSION 3.22.1)

project("MidiSequencerTest" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ENGINE_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)

add_executable(MidiSequencerTest
    MidiSequencerTest.cpp
    ${ENGINE_SOURCES}/sequencer/Metronome.cpp
    ${ENGINE_SOURCES}/sequencer/MidiSequencer.cpp
    ${ENGINE_SOURCES}/sequencer/StandardMidiFile.cpp
)

# The stubs come first: they stand in for the Oboe engine and Android logging
target_include_directories(MidiSequencerTest PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/stub ${ENGINE_SOURCES})
target_compile_options(MidiSequencerTest PRIVATE -Wall -Wextra -Wno-unused-parameter -fsanitize=address,undefined)
target_link_options(MidiSequencerTest PRIVATE -fsanitize=address,undefined)
