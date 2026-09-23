# Greenhouse Roadmap: Milestones & Remaining Enhancements

This document outlines the architectural milestones, remaining tasks, and future feature enhancements for **Greenhouse**, the modern Android Audio Plugin (AAP) host and workstation.

---

## 🌟 Summary of Completed Milestones

- ✅ **Native C++ Audio Engine (`NativeAudioEngine`)**: Ultra low-latency C++ Oboe audio engine executing dynamic multi-slot signal chains (Slot 0 instrument $\rightarrow$ Slot $1 \dots N-1$ effects) with lock-free atomics and zero-allocation realtime callbacks.
- ✅ **Decoupled FIFO Render Architecture & MMAP Burst Detection**: Lock-free circular ring buffers decoupling the high-priority Oboe audio callback from the AAP plugin graph execution, coupled with native Android MMAP burst size detection and configurable buffer multiplier settings.
- ✅ **Real-Time Per-Slot VU & Peak Level Meters**: Lock-free SIMD-accelerated peak & RMS stereo audio meter extraction in C++ (`AudioSimd.h`) paired with phosphor green/amber/red LED level meter UI on each rack slot card and smooth, responsive peak needles.
- ✅ **Lifecycle-Aware Background Audio Management**: Automatically pauses audio stream and DSP processing when the application enters the background, and seamlessly resumes when returning to foreground.
- ✅ **Real-Time DSP CPU Load Monitors**: Live DSP callback load meter in the master workstation banner, per-slot load badges on active rack cards, and comprehensive hardware diagnostics in Engine Settings.
- ✅ **Hardware MIDI Controller Input & Stream Parser (`MidiControllerManager`)**:
  - Real-time Android `MidiManager` USB, Bluetooth LE, and Virtual MIDI controller support with dynamic hot-plugging and auto-reconnection.
  - Low-latency byte stream parser for MIDI 1.0 (Note On/Off, zero-velocity Note Off, Running Status, 14-bit continuous Pitch Bend, Channel & Polyphonic Pressure, and Control Changes) converted to MIDI 2.0 UMP.
- ✅ **Adaptive Native Plugin UI Display**: Smart proportional auto-fit, calibrated 15% zoom stepping aligned to 5% multiples, seamless 2D translation (`MOVE` mode), frictionless single-touch consecutive knob tweaking (`TWEAK` mode), stationary glass toolbar with far-left mode toggle, and full-screen immersive mode with playable live MIDI keyboard.
- ✅ **Native GUI Lifecycle & Out-of-Process Crash Recovery**: Resilient IPC surface host (`GreenhouseSurfaceControlHost`) catching remote service crashes, robust client disconnect/teardown synchronization, preventing host main-thread termination, and presenting a studio-themed fallback card with one-click slot reload and parameter failover.
- ✅ **Bidirectional Parameter Synchronization**: Real-time two-way sync between host UI controls and native plugin surfaces with cooldown gating and startup reconciliation to prevent feedback loops.
- ✅ **Automated Preset Catalog**: Automatic discovery, browsing, and switching of factory presets exposed by AAP plugins via AAPXS extensions.
- ✅ **Full Session Persistence & Modular Rack Presets (`.ghrack`)**:
  - Complete plugin binary state chunk serialization via `getState()` / `setState()` encoded as Base64, combined with explicit parameter state maps, bypass states, and extensible custom metadata.
  - Automatic background autosave and seamless cold-start session restoration.
  - Dedicated studio session management menu (`RackSessionDialog`) with custom preset naming, 1-tap loading, deletion, SAF document import, and Android share sheet integration.
- ✅ **Dynamic Multi-Slot Workstation Rack**: Configurable $N$-slot signal chains with active slot focus, parameter modulation, bypass toggles, and safe concurrent teardown/instantiation guards.
- ✅ **Live Interactive MIDI Keyboard**: Octave shifting, note latch/hold mode, and polyphonic MIDI 2.0 UMP event dispatching.
- ✅ **Comprehensive Plugin Browser**: Instant developer filtering, category badges, text search, and direct slot routing.
- ✅ **Diagnostic & Engine Settings Screen**: Real-time audio hardware inspection, buffer sizing, burst metrics, and log monitor.
- ✅ **Production Release Signing & CI/CD**: Keystore signing configuration for secure local/CI builds and automated GitHub Actions workflows for testing, building, and release publishing.

---

## 🚀 Active Feature Roadmap

| Feature / Task | Priority | Category | Description |
| :--- | :--- | :--- | :--- |
| **MIDI Learn & Hardware CC Mapping** | `High` | MIDI & Control | Intuitive MIDI Learn mode to map physical hardware knobs, sliders, and faders directly to any AAP plugin parameter with customizable range scaling and inversion. |
| **MIDI File (.MID) Player & Loop Tester** | `High` | MIDI & Auditioning | Integrated standard MIDI file player with loop points, playback controls, and tempo synchronization for hands-free, repeatable patch testing and sound design. |
| **Built-in Studio Effects Suite (Zero-IPC / Real-Time DSP)** | `High` | Native DSP & Effects | Ship a collection of high-performance, zero-latency native C++ effects running in-process on the host's `SCHED_FIFO` audio thread for maximum performance and instant out-of-the-box playback. |
| **MIDI-FX Plugin Hosting & Routing Matrix** | `Medium` | MIDI & Routing | Support discovering, loading, and chaining pure AAP MIDI plugins (Sequencers, Arpeggiators, Chord Engines, CC Modulators) upstream of instruments, with routing to internal synths and external USB/BLE MIDI hardware (inspired by AUM). |
| **Per-Slot Dry/Wet Mix** | `Medium` | Signal Chain & DSP | Independent Dry/Wet blend slider for each effect slot, enabling parallel processing, subtle modulation, and non-destructive FX blending. |
| **Arpeggiator** | `Medium` | MIDI & Performance | Built-in playable real-time arpeggiator (Up, Down, Up/Down, Random, Euclidean) with tempo sync, gate length, swing, and octave range for the on-screen keyboard. |
| **Tablet, Foldable & Desktop (DeX) Multi-Pane Layout** | `Low` | UI & Workflow | Dedicated dual-pane split workstation and detachable floating windows tailored for tablets, foldables, and Samsung DeX desktop mode. |
