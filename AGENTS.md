# AAP Test Host Development Guide (`AGENTS.md`)

This repository is **`aap-test-host`**, a modern Jetpack Compose Android host application for testing and playing **Android Audio Plugins (AAP)**.

---

## 1. Project Architecture & Structure

- **`app/`**: Android application module (`org.androidaudioplugin.host`).
  - **`host/core/AapHostEngine.kt`**: Connects to AAP services and instantiates `NativeRemotePluginInstance`.
  - **`host/core/AapAudioPlayer.kt`**: Low-latency Oboe audio render engine, sample player, and MIDI UMP parameter / note dispatching.
  - **`host/data/PluginRepository.kt`**: Discovers system & local AAP services via `AudioPluginHostHelper`.
  - **`host/ui/HostViewModel.kt`**: ViewModel managing multi-slot rack state (Instrument slot 0, Effect slots 1 & 2), active parameter state maps, and plugin lifecycle.
  - **`host/ui/screens/StudioRackScreen.kt`**: Studio rack UI (Signal chain, slot cards, parameter controls, native plugin surfaces).
  - **`host/ui/screens/PluginBrowserScreen.kt`**: Plugin catalog browser with category filters and search.
  - **`host/ui/screens/EngineSettingsScreen.kt`**: Audio hardware specs and diagnostic monitor.
- **`external/aap-core/`**: Submodule containing core AAP runtime (`:androidaudioplugin`) and Compose UI interop (`:androidaudioplugin-ui-compose`).

> **CRITICAL RULE: Submodule Immutability**  
> Any repository or file located under `external/` (including `external/aap-core`) **MUST NOT BE ALTERED OR EDITED**. All application code, workarounds, UI fixes, and engine wrappers must be implemented strictly inside the main project files (e.g. `app/`).

---

## 2. Build & Execution Commands

Always specify `JAVA_HOME` using Android Studio's bundled JDK when invoking Gradle:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
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




