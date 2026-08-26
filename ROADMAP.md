# AAP Host Roadmap: Remaining Tasks & Enhancement Opportunities

This document outlines future tasks, technical enhancements, and UX improvements for the **Android Audio Plugin (AAP) Test Host** application.

---

## 1. 🎛️ Audio Engine & DSP Improvements

| Feature / Task | Priority | Description |
| :--- | :--- | :--- |
| **Real-Time DSP CPU Load Meter UI** | `High` | Add a real-time CPU meter (%) in the workstation status banner displaying total and per-slot DSP callback load. |
| **Master Output Gain & Limiter** | `High` | Add a master output gain slider and soft-clipper/limiter on the workstation banner to prevent digital clipping when testing high-gain plugins or polyphonic synths. |
| **Latency Benchmark Display** | `Low` | Display real-time round-trip latency (in milliseconds) based on native Oboe callback performance metrics in the status banner. |

> [!NOTE]
> **Sample Rate & Buffer Configuration**: The native audio engine automatically configures hardware sample rate and buffer burst size for minimal latency.

---

## 2. 🎹 MIDI & Performance Controls

| Feature / Task | Priority | Description |
| :--- | :--- | :--- |
| **Hardware MIDI Input Event Handling** | `High` | Connect external USB / Bluetooth MIDI controllers via Android MIDI Service / `ktmidi` to process hardware note-on/off and CC events in real-time into AAP. *(Top Priority)* |
| **Pitch & Mod Wheels** | `High` | Add vertical pitch bend and modulation wheel controls alongside the on-screen MIDI keyboard. |
| **MIDI Hot-Plugging & Device Status Indicator** | `Medium` | Display connected hardware MIDI devices in the header status bar and handle device connection/disconnection events. |

---

## 3. 🖥️ UI & Parameter UX Refinements

| Feature / Task | Priority | Description |
| :--- | :--- | :--- |
| **Adaptive Native UI Scaling** | `High` | Dynamically scale and adapt embedded native plugin GUI surfaces to fit available device height and width without clipping. |
| **AAP User Preset Saving & Export** | `High` | Enable saving current parameter states as custom user presets, exporting/importing them as JSON or native AAP state blobs. |
| **Native GUI Fallback & Error Handling** | `Medium` | Display a smooth fallback message if a native plugin surface crashes or fails to instantiate via IPC. |
| **Compact Mode / Landscape Optimization** | `Low` | Further optimize layout spacing for tablet and landscape tablet displays. |

---

## 4. 🛠️ Stability & Quality Assurance

| Feature / Task | Priority | Description |
| :--- | :--- | :--- |
| **IPC Crash Monitor & Recovery** | `High` | Automatically detect if a remote AAP plugin process crashes, showing a "Re-instantiate" button instead of hanging the host UI. |
| **Production Release Signing Setup** | `Medium` | Configure official release signing configuration and keystore credentials in `app/build.gradle.kts` for production builds. |
| **Automated UI & Integration Tests** | `Medium` | Add Compose UI tests validating catalog loading, parameter state updates, and note triggering. |

---

## Summary of Completed Baseline Features

- ✅ **Native C++ Audio Engine (`NativeAudioEngine`)**: High-performance C++ Oboe playback engine executing dynamic multi-slot signal chains (Slot 0 instrument $\rightarrow$ Slot $1 \dots N-1$ effects) with lock-free CPU load tracking.
- ✅ **Matte Titanium Luxury DAW UI**: Apple Pro / Teenage Engineering inspired dark obsidian theme.
- ✅ **Dynamic Multi-Slot Workstation Rack**: Configurable $N$-slot signal chains with active slot focus, parameter modulation, and bypass toggles.
- ✅ **Live Interactive MIDI Keyboard**: Full octave shifting, note latch/hold mode, and polyphonic MIDI 2.0 UMP event dispatching.
- ✅ **Comprehensive Plugin Browser**: Real-time developer filtering, category badges, text search, and slot routing.
- ✅ **Diagnostic & Engine Settings Screen**: Real-time audio hardware inspection, buffer sizing, and log monitor.
