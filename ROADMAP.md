# Greenhouse Roadmap: Milestones & Enhancement Opportunities

This document outlines the architectural milestones, remaining tasks, and future feature enhancements for **Greenhouse**.

---

## 🌟 Summary of Completed Milestones

- ✅ **Native C++ Audio Engine (`NativeAudioEngine`)**: Ultra low-latency C++ Oboe audio engine executing dynamic multi-slot signal chains (Slot 0 instrument $\rightarrow$ Slot $1 \dots N-1$ effects) with lock-free atomics and zero-allocation realtime callbacks.
- ✅ **Decoupled FIFO Render Architecture & MMAP Burst Detection**: Lock-free circular ring buffers decoupling the high-priority Oboe audio callback from the AAP plugin graph execution, coupled with native Android MMAP burst size detection and configurable buffer multiplier settings.
- ✅ **Real-Time Per-Slot VU & Peak Level Meters**: Lock-free SIMD-accelerated peak & RMS stereo audio meter extraction in C++ (`AudioSimd.h`) paired with phosphor green/amber/red LED level meter UI on each rack slot card.
- ✅ **Real-Time DSP CPU Load Monitors**: Live DSP callback load meter in the master workstation banner, per-slot load badges on active rack cards, and comprehensive hardware diagnostics in Engine Settings.
- ✅ **Adaptive Native Plugin UI Display**: Smart proportional auto-fit, calibrated 15% zoom stepping aligned to 5% multiples, seamless 2D translation (`MOVE` mode), frictionless single-touch consecutive knob tweaking (`TWEAK` mode), stationary glass toolbar with far-left mode toggle, and full-screen immersive mode with playable live MIDI keyboard.
- ✅ **Dynamic Multi-Slot Workstation Rack**: Configurable $N$-slot signal chains with active slot focus, parameter modulation, bypass toggles, and safe concurrent teardown/instantiation guards.
- ✅ **Live Interactive MIDI Keyboard**: Octave shifting, note latch/hold mode, and polyphonic MIDI 2.0 UMP event dispatching.
- ✅ **Comprehensive Plugin Browser**: Instant developer filtering, category badges, text search, and direct slot routing.
- ✅ **Diagnostic & Engine Settings Screen**: Real-time audio hardware inspection, buffer sizing, burst metrics, and log monitor.
- ✅ **Production Release Signing & CI/CD**: Keystore signing configuration for secure local/CI builds and automated GitHub Actions workflows for testing, building, and release publishing.

---

## 1. 🎛️ Audio Engine & DSP Improvements

| Feature / Task | Priority | Description |
| :--- | :--- | :--- |
| **Hardware MIDI Input Event Handling** | `High` | Connect external USB / Bluetooth MIDI controllers via Android MIDI Service / `ktmidi` to process hardware note-on/off and CC events in real-time into AAP. *(Top Priority)* |
| **Pitch & Mod Wheels** | `High` | Add vertical pitch bend and modulation wheel controls alongside the on-screen MIDI keyboard. |
| **Master Output Gain & Soft Limiter** | `Medium` | Add a master output gain slider and soft-clipper/limiter on the workstation banner to prevent digital clipping when testing high-gain plugins or polyphonic synths. |

---

## 2. 💾 Preset Management & State Persistence

| Feature / Task | Priority | Description |
| :--- | :--- | :--- |
| **Session State Persistence** | `High` | Automatically save and restore the active rack configuration (loaded plugins, slot states, parameter values) across app restarts. |

---

## 3. 🖥️ UI & Workflow Refinements

| Feature / Task | Priority | Description |
| :--- | :--- | :--- |
| **MIDI Hot-Plugging & Device Status Indicator** | `Medium` | Display connected hardware MIDI devices in the header status bar and handle hot-plug connection/disconnection events. |
| **Parameter Search & Grouping** | `Medium` | Search bar and collapsible group sections in the parameter list for complex plugins with dozens/hundreds of parameters. |
| **Native GUI Fallback & Error Handling** | `Medium` | Display a smooth fallback message and retry option if a remote native plugin UI surface crashes or fails IPC binding. |
| **Tablet & Landscape Optimization** | `Low` | Expanded dual-pane workstation view for tablet and landscape screen orientations. |
| **Drag-and-Drop Slot Reordering** | `Low` | Allow reordering effect slots via intuitive drag-and-drop handles on the rack signal chain. |

---

## 4. 🛠️ Stability, Quality Assurance & Distribution

| Feature / Task | Priority | Description |
| :--- | :--- | :--- |
| **IPC Crash Monitor & Recovery** | `High` | Automatically detect if a remote AAP plugin service process crashes, displaying an alert with a "Re-instantiate" action instead of hanging the host UI. |
| **Automated UI & Integration Tests** | `Medium` | Compose UI tests and native audio pipeline integration tests validating catalog loading, parameter state changes, and audio rendering. |
| **App Logo & Vector Icon Assets** | `Medium` | Design custom vector icon, adaptive app icon launcher assets, and splash screen graphics for Greenhouse. |
