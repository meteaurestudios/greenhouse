#include <jni.h>
#include <algorithm>
#include <limits>
#include "RackEngineJni.h"

namespace
{

// Layouts shared with MidiSequencer.kt
constexpr int32_t SETTINGS_BPM = 0;
constexpr int32_t SETTINGS_LENGTH_BARS = 1;
constexpr int32_t SETTINGS_IS_QUANTIZING = 2;
constexpr int32_t SETTINGS_QUANTIZE_TICKS = 3;
constexpr int32_t SETTINGS_METRONOME_LEVEL_DB = 4;
constexpr int32_t SETTINGS_SIZE = 5;

constexpr int32_t STATUS_STATE = 0;
constexpr int32_t STATUS_IS_RECORDING = 1;
constexpr int32_t STATUS_LENGTH_TICKS = 2;
constexpr int32_t STATUS_AUTO_LENGTH_BARS = 3;
constexpr int32_t STATUS_EVENT_COUNT = 4;
constexpr int32_t STATUS_REVISION = 5;
constexpr int32_t STATUS_SIZE = 6;

// Each event: tick, slot, take, word count, then MAX_PACKET_WORDS words
constexpr int32_t EVENT_TICK = 0;
constexpr int32_t EVENT_SLOT = 1;
constexpr int32_t EVENT_TAKE = 2;
constexpr int32_t EVENT_WORD_COUNT = 3;
constexpr int32_t EVENT_WORDS = 4;
constexpr int32_t EVENT_STRIDE = EVENT_WORDS + aaphost::ump::MAX_PACKET_WORDS;

aaphost::MidiSequencer& getSequencer()
{
    return aaphost::getEngine().getSequencer();
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_MidiSequencer_nativeSetSettings(
        JNIEnv* env, jobject thiz, jdoubleArray values)
{
    if (values == nullptr || env->GetArrayLength(values) < SETTINGS_SIZE) {
        return;
    }

    jdouble v[SETTINGS_SIZE];
    env->GetDoubleArrayRegion(values, 0, SETTINGS_SIZE, v);

    aaphost::SequencerSettings settings;
    settings.mBpm = v[SETTINGS_BPM];
    settings.mLengthBars = static_cast<int32_t>(v[SETTINGS_LENGTH_BARS]);
    settings.mIsQuantizing = v[SETTINGS_IS_QUANTIZING] != 0.0;
    settings.mQuantizeTicks = static_cast<int32_t>(v[SETTINGS_QUANTIZE_TICKS]);
    settings.mMetronomeLevelDb = static_cast<float>(v[SETTINGS_METRONOME_LEVEL_DB]);
    getSequencer().setSettings(settings);
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_MidiSequencer_nativeGetSettings(
        JNIEnv* env, jobject thiz, jdoubleArray out)
{
    if (out == nullptr || env->GetArrayLength(out) < SETTINGS_SIZE) {
        return;
    }

    auto settings = getSequencer().getSettings();
    jdouble v[SETTINGS_SIZE];
    v[SETTINGS_BPM] = settings.mBpm;
    v[SETTINGS_LENGTH_BARS] = settings.mLengthBars;
    v[SETTINGS_IS_QUANTIZING] = settings.mIsQuantizing ? 1.0 : 0.0;
    v[SETTINGS_QUANTIZE_TICKS] = settings.mQuantizeTicks;
    v[SETTINGS_METRONOME_LEVEL_DB] = settings.mMetronomeLevelDb;
    env->SetDoubleArrayRegion(out, 0, SETTINGS_SIZE, v);
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_MidiSequencer_nativeGetStatus(
        JNIEnv* env, jobject thiz, jlongArray out)
{
    if (out == nullptr || env->GetArrayLength(out) < STATUS_SIZE) {
        return;
    }

    auto status = getSequencer().getStatus();
    jlong v[STATUS_SIZE];
    v[STATUS_STATE] = static_cast<jlong>(status.mState);
    v[STATUS_IS_RECORDING] = status.mIsRecording ? 1 : 0;
    v[STATUS_LENGTH_TICKS] = status.mLengthTicks;
    v[STATUS_AUTO_LENGTH_BARS] = status.mAutoLengthBars;
    v[STATUS_EVENT_COUNT] = status.mEventCount;
    v[STATUS_REVISION] = status.mRevision;
    env->SetLongArrayRegion(out, 0, STATUS_SIZE, v);
}

JNIEXPORT jlong JNICALL
Java_org_androidaudioplugin_greenhouse_core_MidiSequencer_getPositionTick(
        JNIEnv* env, jobject thiz)
{
    return getSequencer().getPositionTick();
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_MidiSequencer_startPlayback(
        JNIEnv* env, jobject thiz)
{
    getSequencer().startPlayback();
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_MidiSequencer_startRecording(
        JNIEnv* env, jobject thiz)
{
    getSequencer().startRecording();
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_MidiSequencer_stopRecording(
        JNIEnv* env, jobject thiz)
{
    getSequencer().stopRecording();
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_MidiSequencer_stop(
        JNIEnv* env, jobject thiz)
{
    getSequencer().stop();
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_MidiSequencer_undoLastTake(
        JNIEnv* env, jobject thiz)
{
    getSequencer().undoLastTake();
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_MidiSequencer_clear(
        JNIEnv* env, jobject thiz)
{
    getSequencer().clear();
}

JNIEXPORT jintArray JNICALL
Java_org_androidaudioplugin_greenhouse_core_MidiSequencer_getEvents(
        JNIEnv* env, jobject thiz)
{
    auto events = getSequencer().getEvents();
    std::vector<jint> values(events.size() * EVENT_STRIDE);

    for (size_t i = 0; i < events.size(); i++) {
        const auto& event = events[i];
        auto base = i * EVENT_STRIDE;
        values[base + EVENT_TICK] = static_cast<jint>(std::min<int64_t>(event.mTick, std::numeric_limits<jint>::max()));
        values[base + EVENT_SLOT] = event.mSlot;
        values[base + EVENT_TAKE] = event.mTake;
        values[base + EVENT_WORD_COUNT] = event.mPacket.mWordCount;
        std::copy(event.mPacket.mWords.begin(), event.mPacket.mWords.end(), values.begin() + base + EVENT_WORDS);
    }

    auto result = env->NewIntArray(static_cast<jsize>(values.size()));

    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }

    return result;
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_MidiSequencer_setEvents(
        JNIEnv* env, jobject thiz, jintArray packed, jint autoLengthBars)
{
    auto length = packed != nullptr ? env->GetArrayLength(packed) : 0;
    std::vector<jint> values(static_cast<size_t>(length));
    std::vector<aaphost::SequenceEvent> events;

    if (length > 0) {
        env->GetIntArrayRegion(packed, 0, length, values.data());
    }

    // Validated by the sequencer
    for (jsize base = 0; base + EVENT_STRIDE <= length; base += EVENT_STRIDE) {
        aaphost::SequenceEvent event;
        event.mTick = values[base + EVENT_TICK];
        event.mSlot = values[base + EVENT_SLOT];
        event.mTake = values[base + EVENT_TAKE];
        event.mPacket.mWordCount = values[base + EVENT_WORD_COUNT];
        std::copy_n(values.begin() + base + EVENT_WORDS, aaphost::ump::MAX_PACKET_WORDS, event.mPacket.mWords.begin());
        events.push_back(event);
    }

    getSequencer().setEvents(std::move(events), autoLengthBars);
}

JNIEXPORT jbyteArray JNICALL
Java_org_androidaudioplugin_greenhouse_core_MidiSequencer_exportStandardMidiFile(
        JNIEnv* env, jobject thiz)
{
    auto bytes = getSequencer().exportStandardMidiFile();
    auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));

    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    }

    return result;
}

JNIEXPORT jboolean JNICALL
Java_org_androidaudioplugin_greenhouse_core_MidiSequencer_importStandardMidiFile(
        JNIEnv* env, jobject thiz, jbyteArray data)
{
    if (data == nullptr) {
        return JNI_FALSE;
    }

    auto length = env->GetArrayLength(data);
    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    return getSequencer().importStandardMidiFile(bytes.data(), length) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
