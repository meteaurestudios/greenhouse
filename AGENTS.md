# Greenhouse Development Guide (`AGENTS.md`)

This repository is **`greenhouse`**, a modern Jetpack Compose Android host application for testing and playing **Audio Plugins for Android (AAP)**.

---

## 1. Project Architecture & Structure

The project is split into library modules so that downstream apps can build on the same base. Dependencies flow one way: `:app` → `:greenhouse-ui` → `:greenhouse-host` → `:greenhouse-engine` → aap-core. Kotlin packages are unchanged by the split.

- **`greenhouse-engine/`** (`:greenhouse-engine`): Audio engine, no UI.
  - **`src/main/cpp/`**: Native audio engine. Includes are relative to this folder (e.g. `"engine/OboeEngine.h"`).
    - `engine/OboeEngine`: reusable base that owns the AAudio output stream (open at the device's native rate, start / stop, reopen after errors, latency tuning, ADPF, denormals). Subclasses implement `prepareToPlay` / `streamStarting` / `process` / `flushState`.
    - `RackEngine`: the rack built on `OboeEngine` (instrument slot 0, then effect slots, rendered in fixed-size blocks). Each slot runs a `SlotProcessor` and the rack keeps the rest: chain, bypass, NaN guard, meters, CPU load, sequencer. Each slot also has a host level (gain) and dry / wet mix, ramped per block; the host resets them when a new plugin is added and saves them with the session.
    - `slot/SlotProcessor.h`: what runs in a slot (prepare, activate / deactivate, queued live UMP, `process()` over the previous slot's interleaved output). The rack owns it and destroys it once the audio thread has let go of it. `slot/AapSlotProcessor` wraps an AAP plugin instance (not owned; slots prepared at another sample rate stay silent until reloaded); DSP running inside the engine implements the same interface.
    - `sequencer/MidiSequencer`: MIDI sequencer owned by `RackEngine`. Records the notes played on the instrument slot (`MAX_SEQUENCER_SLOTS`; events still carry a slot index) and plays them back in a loop (PPQ 960 ticks, 4/4 bars), handing each slot processor sample-accurate JR-timestamped UMP for its block (`AapSlotProcessor` writes it into the plugin's MIDI2 input port, where aap-core merges the queued live input). The audio thread reads an immutable `PlaybackBuffer` swapped through an atomic pointer (old buffers freed once `OboeEngine::hasAudioThreadPassed()`), and takes transport commands from a lock-free queue. Also the metronome (`sequencer/Metronome`) and `.mid` import / export (`sequencer/StandardMidiFile`).
    - `RackEngineJni.cpp` / `MidiSequencerJni.cpp`: JNI bindings to a single process-wide `RackEngine` (`getEngine()`, defined in `EngineApi.cpp`; no native handles cross JNI).
    - `EngineApi.h`: the native API other modules' C++ code can call (`setSlotProcessor()` on the app's rack, e.g. to run their own DSP in a slot). It is published with `slot/SlotProcessor.h`, `slot/SlotEvents.h` (reading the events a processor receives: JR timestamps, AAP parameter changes) and `sequencer/Ump.h` as the module's prefab package (`find_package(greenhouse-engine REQUIRED CONFIG)`, target `greenhouse-engine::greenhouse-engine`). The library hides every other symbol: mark new entry points `GREENHOUSE_API` and keep the published headers free of Oboe, AAP and engine internals.
    - `utils/AudioSimd.h`: NEON helpers. `utils/Logging.h`: log macros.
  - **`greenhouse/core/AapHostEngine.kt`**: Connects to AAP services and instantiates `NativeRemotePluginInstance`.
  - **`greenhouse/core/RackEngine.kt`**: Kotlin side of the native `RackEngine`: transport, AAP slot plugins (`clearSlot()` empties a slot whatever it holds), meters, and MIDI UMP parameter / note dispatching. The sequencer records only the notes sent to the slots.
  - **`greenhouse/core/MidiSequencer.kt`**: Kotlin side of the native sequencer (`RackEngine.sequencer`): settings, transport, status, packed events.
  - **`greenhouse/core/MidiControllerManager.kt`**: Hardware MIDI input and MIDI 1.0 → UMP stream parser.
  - Manifest declares audio permissions and the AAP service `<queries>`; `consumer-rules.pro` carries the JNI / AAP R8 keep rules.
- **`greenhouse-host/`** (`:greenhouse-host`): Host state and logic, no screens.
  - **`greenhouse/device/`**: What fills a slot. `SlotDevice` (parameters, presets, state, attaching its processor to the native rack) and `SlotDeviceSource` (lists `DeviceInfo`s and creates devices; its `id` is saved as each slot's `source` in `.ghrack`).
    - `aap/`: the AAP implementation. `AapDeviceSource` (installed plugins, one `AapHostEngine` slot host each), `AapSlotDevice` (a `NativeRemotePluginInstance`, process death detection, parameter list kept current through aap-core's `addParameterMetadataChangedListener`), `PluginSlotLoader` (blocking instance queries: instantiate, dynamic parameter / port discovery, value and preset-name reads; IO thread only, except `queryUnlessDied`).
  - **`greenhouse/data/`**: `PluginRepository` (discovers AAP services via `AudioPluginHostHelper`), `.ghrack` session models, serializer, and storage. `SlotPlacement` restores saved slots by role (`SlotType`) and order rather than index, so sessions survive rack layout changes.
  - **`greenhouse/ui/HostViewModel.kt`**: Thin composition root. Wires the controllers below, runs the engine monitor loop, and handles app lifecycle. Screens access controllers directly (`viewModel.rack`, `viewModel.audio`, …).
  - **`greenhouse/ui/HostConfig.kt`**: How an app configures the host (its device sources, AAP only by default), given through `HostViewModel.factory(config)`.
  - **`greenhouse/ui/RackModels.kt`**: Rack UI models (`RackSlotData`, `SlotUiState`, `StudioRackViewMode`, …).
  - **`greenhouse/ui/host/`**: Feature controllers owned by `HostViewModel`:
    - `RackController`: multi-slot rack (Instrument slot 0, Effect slots 1 & 2), device load / unload / restore through the sources, bypass, parameters, presets, device-side parameter sync and parameter list changes (`SlotDevice.onParametersChanged`), crashed devices. A slot restored with a saved state gets that state alone (it holds the preset and parameter values, and the saved ones may be stale: aap-core does not always learn of changes made inside a plugin); without one, the saved preset is selected and the saved parameter values are pushed.
    - `AudioEngineController`: `RackEngine` transport, buffer sizing, background / foreground pause and resume.
    - `RackMeters`: per-slot levels and CPU load.
    - `PluginBrowserController`: device catalog from every source, slot-target filtering, developer filter, search, saved device lookup.
    - `VirtualKeyboardController` / `MidiDeviceController`: on-screen keyboard state and hardware MIDI input.
    - `RackSessionController`: saved sessions, autosave, session restore (including the sequence).
    - `SequencerController`: sequencer transport, settings, note lane, tap tempo, `.mid` import / export, sequence capture / restore.
  - Manifest declares the session-sharing `FileProvider` (`${applicationId}.fileprovider`).
- **`greenhouse-ui/`** (`:greenhouse-ui`): Compose UI.
  - **`greenhouse/ui/MainHostApp.kt`**: Root composable and navigation graph.
  - **`greenhouse/ui/screens/StudioRackScreen.kt`**: Studio rack UI (Signal chain, slot cards, parameter controls, native plugin surfaces).
  - **`greenhouse/ui/screens/rack/RackSequencer.kt`**: Sequencer strip in the keyboard card (record, play / stop, read-only lane, tempo / length chip) and its settings sheet.
  - **`greenhouse/ui/screens/PluginBrowserScreen.kt`**: Plugin catalog browser with category filters and search.
  - **`greenhouse/ui/screens/EngineSettingsScreen.kt`**: Audio hardware specs and diagnostic monitor.
  - **`greenhouse/ui/theme/`**, **`greenhouse/ui/components/`**: Theme and reusable controls.
- **`app/`** (`:app`): Thin application shell (`org.androidaudioplugin.greenhouse`): `MainActivity`, launcher icons, app name, signing, and release config. Keep app logic out of this module.
- **`external/aap-core/`**: Submodule containing core AAP runtime (`:androidaudioplugin`) and Compose UI interop (`:androidaudioplugin-ui-compose`).

> **CRITICAL RULE: Submodule Immutability**  
> Any repository or file located under `external/` (including `external/aap-core`) **MUST NOT BE ALTERED OR EDITED**. All application code, workarounds, UI fixes, and engine wrappers must be implemented strictly inside the main project files (e.g. `app/`).

---

## 2. Build & Execution Commands

Always specify `JAVA_HOME` using Android Studio's bundled JDK when invoking Gradle:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

The native MIDI sequencer and rack have desktop tests (no Android needed), with stubs for the Oboe engine and logging and fake slot processors:

```bash
cmake -S greenhouse-engine/src/test/cpp -B build/engine-test && cmake --build build/engine-test && build/engine-test/MidiSequencerTest && build/engine-test/RackEngineTest
```

> **Note**: Gradle requires network loopback socket permissions for its daemon process. When running Gradle in sandbox environments, run with unsandboxed execution (`BypassSandbox: true`).

---

## 3. Crucial AAP Architecture Rules

### Parameter & Port Discovery
1. **Static Manifest (`aap_metadata.xml`)**:
   - `AudioPluginHostHelper` populates `PluginInformation.parameters` **only** if the plugin statically declared `<parameter>` XML elements in `aap_metadata.xml`.
2. **Dynamic AAPXS Extension**:
   - Most AAP plugins expose parameters dynamically at runtime over the `parameters` AAPXS extension.
   - When instantiating a plugin (`NativeRemotePluginInstance`), if `plugin.parameters.isEmpty()`, query `instance.getParameterCount()` and `instance.getParameter(i)` to dynamically discover parameters. Do the same for `plugin.ports` via `instance.getPortCount()` and `instance.getPort(i)`.

### Realtime Audio Thread Safety
1. **Lock-Free Audio Callbacks**:
   - The Oboe / AAudio audio render callback (`onAudioReady`) runs at highest realtime priority (`SCHED_FIFO`).
   - **NEVER** use mutexes (`std::mutex`, `std::unique_lock`, `try_lock`), priority-inversion hazards, system calls, or dynamic heap allocations (`malloc`, `free`, `new`, `delete`) inside the audio callback or realtime render path.
   - All state accessed in the audio thread MUST use lock-free atomics (e.g. `std::atomic<aap::PluginInstance*>`, atomic port indices, and atomic render epoch counters for control-thread quiescent-state synchronization).

### Jetpack Compose UI Patterns
1. **Grid Item Keying**:
   - In `LazyVerticalGrid` / `LazyColumn` for parameter controls, always scope item keys to include the slot index and plugin ID:
     `key = { param -> "${slotIndex}_${pluginId}_${param.id}" }`
   - Never use raw `param.id` alone as a key, because numeric IDs collide across different plugins when switching slots.
2. **Touch Targets & Sizing**:
   - Standard Material 3 `IconButton` enforces a minimum 48dp interactive component size layout. When precise custom dimensions are needed without forced padding, use `Box(modifier = Modifier.size(...).clip(CircleShape).clickable { ... })`.
3. **Window Insets**:
   - Account for system navigation bar insets using `WindowInsets.navigationBars` or `WindowInsets.systemBars`.
4. **Panels & Controls**:
   - Use the shared style in `greenhouse-ui/.../ui/components/ControlSurface.kt`: `Modifier.panelSurface()` for a rack panel (bordered card), `Modifier.controlSurface()` / `ControlButton` for the controls inside it (`CONTROL_HEIGHT`, `CONTROL_SHAPE`).
   - Controls inside a panel are filled, without an outline; while on, they take a tint of their accent (`isActive`). Do not nest outlined boxes inside outlined panels.
   - Labels and buttons use sentence case ("Play", "Hold", "Load"), never all caps with letter spacing. The monospace, uppercase style is kept for the slot badges and diagnostic text.
5. **Icon + Text Buttons**:
   - In any button or control combining an icon and text, the icon and text form one group, centered inside the container (`Box(contentAlignment = Alignment.Center)` around the group, or `horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally)`). Never push them apart with `Arrangement.SpaceBetween` or a weighted spacer, and never leave the group start-aligned in a wider container.

---

## 4. Code Formatting & Style Rules

1. **Mandatory Braces and Newlines**:
   - All `if`, `else if`, `else`, `for`, and `while` control flow statements (in both Kotlin and C/C++) **MUST ALWAYS** have curly brackets `{}` with their body on a new line. Single-line statement bodies without braces are strictly prohibited.
2. **Blank Lines Around Control Flow Blocks**:
   - All `if`, `else if`, `else`, `for`, and `while` blocks **MUST ALWAYS** have a blank / new line immediately before and after them (to clearly separate control flow blocks from preceding and subsequent statements).
3. **`auto` with `static_cast` (C++)**:
   - Always use `auto` when declaring and assigning a variable initialized with a `static_cast` (e.g. `auto out = static_cast<float*>(...);` rather than `float* out = static_cast<float*>(...);`).
4. **C++ Function Opening Braces on New Line**:
   - All C++ functions (constructors, destructors, member methods, and JNI functions) **MUST ALWAYS** place their opening curly bracket `{` on a new line.
5. **C++ Member Variable Naming (`m` Prefix & CamelCase)**:
   - All C++ member variables (in classes and structs) **MUST ALWAYS** start with a lowercase `m` followed by CamelCase (e.g. `mSampleRate`, `mFramesPerCallback`, `mIsProcessing`, `mSlots`, `mAudioInPorts`, `mCpuLoad`).
6. **No Magic / Hardcoded Numbers**:
   - Never use raw numeric literals or magic values (e.g. `256`, `512`, `16384`, `4096`, `128`, `44100`, etc.) directly in business logic, fallbacks, or buffer calculations.
   - Always define named, self-documenting constants (`constexpr`, `const val`, or `companion object` constants) such as `DEFAULT_FRAMES_PER_CALLBACK`, `DEFAULT_BURST_MULTIPLIER`, `FIFO_CAPACITY_FRAMES`, `MAX_DSP_BLOCK_FRAMES`, `DEFAULT_SAMPLE_RATE`, etc., with clear semantic meaning.




