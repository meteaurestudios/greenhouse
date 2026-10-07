# Desktop tests of the native engine (no Android, no Oboe, no AAP):
#   cmake -S greenhouse-engine/src/test/cpp -B build/engine-test && cmake --build build/engine-test && build/engine-test/MidiSequencerTest && build/engine-test/RackEngineTest
cmake_minimum_required(VERSION 3.22.1)

project("GreenhouseEngineTest" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ENGINE_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)

set(SEQUENCER_SOURCES
    ${ENGINE_SOURCES}/sequencer/Metronome.cpp
    ${ENGINE_SOURCES}/sequencer/MidiSequencer.cpp
    ${ENGINE_SOURCES}/sequencer/StandardMidiFile.cpp
)

# A quoted include is looked up next to the including file first: from the cpp root, RackEngine.h
# would find the real engine/OboeEngine.h before the stub. Compile copies of the root sources
# instead (copied again whenever they change), where only the stubs can be found.
set(ROOT_SOURCES_COPY ${CMAKE_CURRENT_BINARY_DIR}/root)

foreach(ROOT_SOURCE RackEngine.h RackEngine.cpp)
    configure_file(${ENGINE_SOURCES}/${ROOT_SOURCE} ${ROOT_SOURCES_COPY}/${ROOT_SOURCE} COPYONLY)
endforeach()

add_executable(MidiSequencerTest MidiSequencerTest.cpp ${SEQUENCER_SOURCES})
add_executable(RackEngineTest RackEngineTest.cpp ${ROOT_SOURCES_COPY}/RackEngine.cpp ${SEQUENCER_SOURCES})

foreach(TEST_TARGET MidiSequencerTest RackEngineTest)
    # The stubs come first: they stand in for the Oboe engine and Android logging
    target_include_directories(${TEST_TARGET} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/stub ${ROOT_SOURCES_COPY} ${ENGINE_SOURCES})
    target_compile_options(${TEST_TARGET} PRIVATE -Wall -Wextra -Wno-unused-parameter -fsanitize=address,undefined)
    target_link_options(${TEST_TARGET} PRIVATE -fsanitize=address,undefined)
endforeach()
