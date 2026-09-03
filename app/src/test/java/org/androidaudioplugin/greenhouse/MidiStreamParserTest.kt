package org.androidaudioplugin.greenhouse

import android.content.Context
import android.media.midi.MidiDeviceInfo
import dev.atsushieno.ktmidi.Ump
import dev.atsushieno.ktmidi.UmpFactory
import dev.atsushieno.ktmidi.toPlatformNativeBytes
import org.androidaudioplugin.greenhouse.core.MidiControllerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field

class MidiStreamParserTest {

    private class MockMidiEventListener : MidiControllerManager.MidiEventListener {
        val noteOns = mutableListOf<Pair<Int, Float>>()
        val noteOffs = mutableListOf<Pair<Int, Float>>()
        val pitchBends = mutableListOf<Float>()
        val pressures = mutableListOf<Pair<Int, Float>>()
        val ccs = mutableListOf<Pair<Int, Float>>()

        override fun onMidiDevicesChanged(devices: List<MidiDeviceInfo>, activeDevice: MidiDeviceInfo?) {}
        override fun onMidiDeviceConnectionStateChanged(device: MidiDeviceInfo?, isConnected: Boolean, message: String) {}

        override fun onNoteOn(note: Int, velocity: Float) {
            noteOns.add(note to velocity)
        }

        override fun onNoteOff(note: Int, velocity: Float) {
            noteOffs.add(note to velocity)
        }

        override fun onPitchBend(value: Float) {
            pitchBends.add(value)
        }

        override fun onPressure(note: Int, value: Float) {
            pressures.add(note to value)
        }

        override fun onControlChange(controller: Int, value: Float) {
            ccs.add(controller to value)
        }

        override fun onRawUmp(bytes: ByteArray) {}
    }

    private fun createUninitializedManager(listener: MidiControllerManager.MidiEventListener): MidiControllerManager {
        // Allocate without calling Android Context constructor methods
        val sunReflection = Class.forName("sun.misc.Unsafe")
        val unsafeField = sunReflection.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val manager = unsafe.allocateInstance(MidiControllerManager::class.java) as MidiControllerManager

        val listenerField = MidiControllerManager::class.java.getDeclaredField("listener")
        listenerField.isAccessible = true
        listenerField.set(manager, listener)

        val activeNotesField = MidiControllerManager::class.java.getDeclaredField("activeNotes")
        activeNotesField.isAccessible = true
        activeNotesField.set(manager, BooleanArray(128) { false })

        return manager
    }

    @Test
    fun testNoteOnAndOffParsing() {
        val listener = MockMidiEventListener()
        val manager = createUninitializedManager(listener)

        // Note On: Channel 0, Note 60 (Middle C), Velocity 100
        val noteOnBytes = byteArrayOf(0x90.toByte(), 60.toByte(), 100.toByte())
        manager.parseMidiBytes(noteOnBytes, 0, noteOnBytes.size)

        assertEquals(1, listener.noteOns.size)
        assertEquals(60, listener.noteOns[0].first)
        assertEquals(100f / 127f, listener.noteOns[0].second, 0.001f)

        // Note Off: Channel 0, Note 60, Velocity 64
        val noteOffBytes = byteArrayOf(0x80.toByte(), 60.toByte(), 64.toByte())
        manager.parseMidiBytes(noteOffBytes, 0, noteOffBytes.size)

        assertEquals(1, listener.noteOffs.size)
        assertEquals(60, listener.noteOffs[0].first)
        assertEquals(64f / 127f, listener.noteOffs[0].second, 0.001f)
    }

    @Test
    fun testNoteOnWithZeroVelocityIsNoteOff() {
        val listener = MockMidiEventListener()
        val manager = createUninitializedManager(listener)

        // Note On with 0 velocity -> should trigger onNoteOff
        val zeroVelBytes = byteArrayOf(0x90.toByte(), 72.toByte(), 0.toByte())
        manager.parseMidiBytes(zeroVelBytes, 0, zeroVelBytes.size)

        assertEquals(0, listener.noteOns.size)
        assertEquals(1, listener.noteOffs.size)
        assertEquals(72, listener.noteOffs[0].first)
        assertEquals(0.0f, listener.noteOffs[0].second, 0.0001f)
    }

    @Test
    fun testRunningStatus() {
        val listener = MockMidiEventListener()
        val manager = createUninitializedManager(listener)

        // Status byte (Note On Ch 0) followed by 3 notes using running status
        val stream = byteArrayOf(
            0x90.toByte(), 60.toByte(), 100.toByte(), // Note On 60
            64.toByte(), 90.toByte(),                  // Note On 64 (running status)
            67.toByte(), 80.toByte()                   // Note On 67 (running status)
        )
        manager.parseMidiBytes(stream, 0, stream.size)

        assertEquals(3, listener.noteOns.size)
        assertEquals(60, listener.noteOns[0].first)
        assertEquals(64, listener.noteOns[1].first)
        assertEquals(67, listener.noteOns[2].first)
    }

    @Test
    fun testPitchBendCalculation() {
        val listener = MockMidiEventListener()
        val manager = createUninitializedManager(listener)

        // Center Pitch Bend (LSB=0, MSB=64 -> 8192 -> 0.0)
        val centerBend = byteArrayOf(0xE0.toByte(), 0x00.toByte(), 0x40.toByte())
        manager.parseMidiBytes(centerBend, 0, centerBend.size)

        assertEquals(1, listener.pitchBends.size)
        assertEquals(0.0f, listener.pitchBends[0], 0.001f)

        // Min Pitch Bend (LSB=0, MSB=0 -> 0 -> -1.0)
        val minBend = byteArrayOf(0xE0.toByte(), 0x00.toByte(), 0x00.toByte())
        manager.parseMidiBytes(minBend, 0, minBend.size)

        assertEquals(2, listener.pitchBends.size)
        assertEquals(-1.0f, listener.pitchBends[1], 0.001f)

        // Max Pitch Bend (LSB=127, MSB=127 -> 16383 -> ~1.0)
        val maxBend = byteArrayOf(0xE0.toByte(), 0x7F.toByte(), 0x7F.toByte())
        manager.parseMidiBytes(maxBend, 0, maxBend.size)

        assertEquals(3, listener.pitchBends.size)
        assertEquals(1.0f, listener.pitchBends[2], 0.001f)
    }

    @Test
    fun testControlChangeAndPressure() {
        val listener = MockMidiEventListener()
        val manager = createUninitializedManager(listener)

        // Control Change: Controller 74 (Filter Cutoff), Value 64
        val ccBytes = byteArrayOf(0xB0.toByte(), 74.toByte(), 64.toByte())
        manager.parseMidiBytes(ccBytes, 0, ccBytes.size)

        assertEquals(1, listener.ccs.size)
        assertEquals(74, listener.ccs[0].first)
        assertEquals(64f / 127f, listener.ccs[0].second, 0.001f)

        // Channel Pressure (Aftertouch): Value 96
        val pressureBytes = byteArrayOf(0xD0.toByte(), 96.toByte())
        manager.parseMidiBytes(pressureBytes, 0, pressureBytes.size)

        assertEquals(1, listener.pressures.size)
        assertEquals(-1, listener.pressures[0].first)
        assertEquals(96f / 127f, listener.pressures[0].second, 0.001f)
    }

    @Test
    fun testUmpPitchBendOutput() {
        val testValues = floatArrayOf(-1.0f, -0.5f, 0.0f, 0.5f, 1.0f)
        for (v in testValues) {
            // UmpFactory.midi2PitchBend expects signed 32-bit: v * 0x80000000L
            val signed32 = (v.coerceIn(-1.0f, 1.0f).toDouble() * 0x80000000L.toDouble()).toLong().coerceIn(-0x80000000L, 0x7FFFFFFFL)
            val ump64 = dev.atsushieno.ktmidi.UmpFactory.midi2PitchBend(0, 0, signed32)
            val umpObj = dev.atsushieno.ktmidi.Ump(ump64)
            
            val int1 = umpObj.int1
            val int2 = umpObj.int2
            val channel = (int1 ushr 16) and 0xF
            val messageType = (int1 ushr 28) and 0xF
            val status = (int1 ushr 20) and 0xF
            
            assertEquals(4, messageType) // Type 4 = MIDI 2.0 Channel Voice
            assertEquals(0xE, status)    // Status 0xE = Pitch Bend
            assertEquals(0, channel)     // Channel must remain 0!
        }
    }
}
