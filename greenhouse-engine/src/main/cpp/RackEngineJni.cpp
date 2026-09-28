#include <jni.h>
#include <algorithm>
#include "RackEngine.h"

namespace
{

// One engine per process. It is intentionally never destroyed: tearing it down during static
// destruction could touch plugin instances aap-core has already freed. The stream and plugin
// references are released explicitly through nativeShutdown().
aaphost::RackEngine& getEngine()
{
    static auto engine = new aaphost::RackEngine();
    return *engine;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeConfigure(
        JNIEnv* env, jclass clazz, jint framesPerCallback, jint numSlots)
{
    getEngine().configure(framesPerCallback, numSlots);
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeShutdown(
        JNIEnv* env, jclass clazz)
{
    getEngine().shutdown();
}

JNIEXPORT jboolean JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeStart(
        JNIEnv* env, jclass clazz)
{
    return getEngine().startStreaming() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativePause(
        JNIEnv* env, jclass clazz)
{
    getEngine().stopStreaming();
}

JNIEXPORT jboolean JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeOpenStream(
        JNIEnv* env, jclass clazz)
{
    return getEngine().openStream() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeCloseStream(
        JNIEnv* env, jclass clazz)
{
    getEngine().closeStream();
}

JNIEXPORT jboolean JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeIsStreaming(
        JNIEnv* env, jclass clazz)
{
    return getEngine().isStreaming() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeSetFramesPerCallback(
        JNIEnv* env, jclass clazz, jint framesPerCallback)
{
    getEngine().setFramesPerCallback(framesPerCallback);
}

JNIEXPORT jint JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeGetSampleRate(
        JNIEnv* env, jclass clazz)
{
    return getEngine().getSampleRate();
}

JNIEXPORT jint JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeGetBurstFrames(
        JNIEnv* env, jclass clazz)
{
    return getEngine().getFramesPerBurst();
}

JNIEXPORT jboolean JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeIsLowLatency(
        JNIEnv* env, jclass clazz)
{
    return getEngine().isLowLatency() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeIsExclusive(
        JNIEnv* env, jclass clazz)
{
    return getEngine().isExclusive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeIsMMapUsed(
        JNIEnv* env, jclass clazz)
{
    return getEngine().isMMapUsed() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeSetSlotPlugin(
        JNIEnv* env, jclass clazz, jint slotIndex, jlong nativeClient, jint instanceId, jint sampleRate)
{
    getEngine().setSlotPlugin(slotIndex, reinterpret_cast<aap::PluginClient*>(nativeClient), instanceId, sampleRate);
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeSetSlotBypassed(
        JNIEnv* env, jclass clazz, jint slotIndex, jboolean bypassed)
{
    getEngine().setSlotBypassed(slotIndex, bypassed == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeSetSlotGain(
        JNIEnv* env, jclass clazz, jint slotIndex, jfloat gain)
{
    getEngine().setSlotGain(slotIndex, gain);
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeSetSlotMix(
        JNIEnv* env, jclass clazz, jint slotIndex, jfloat mix)
{
    getEngine().setSlotMix(slotIndex, mix);
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeSendUmp(
        JNIEnv* env, jclass clazz, jint slotIndex, jbyteArray data, jint length)
{
    if (data == nullptr) {
        return;
    }

    auto size = std::min(length, env->GetArrayLength(data));

    if (size <= 0) {
        return;
    }

    auto elements = env->GetByteArrayElements(data, nullptr);

    if (elements == nullptr) {
        return;
    }

    getEngine().sendUmpToSlot(slotIndex, reinterpret_cast<const uint8_t*>(elements), size);
    env->ReleaseByteArrayElements(data, elements, JNI_ABORT);
}

JNIEXPORT jfloat JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeGetCpuLoad(
        JNIEnv* env, jclass clazz)
{
    return getEngine().getTotalCpuLoad();
}

JNIEXPORT jfloat JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeGetSlotCpuLoad(
        JNIEnv* env, jclass clazz, jint slotIndex)
{
    return getEngine().getSlotCpuLoad(slotIndex);
}

JNIEXPORT jint JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeGetSlotInvalidBlocks(
        JNIEnv* env, jclass clazz, jint slotIndex)
{
    return getEngine().getSlotInvalidBlocks(slotIndex);
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_greenhouse_core_RackEngine_nativeGetAllSlotLevels(
        JNIEnv* env, jclass clazz, jfloatArray outLevels)
{
    if (outLevels == nullptr) {
        return;
    }

    auto slotCount = env->GetArrayLength(outLevels) / aaphost::STEREO_CHANNEL_COUNT;

    if (slotCount <= 0) {
        return;
    }

    // Lock-free atomic reads only, so a critical section is fine here
    auto levels = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(outLevels, nullptr));

    if (levels == nullptr) {
        return;
    }

    getEngine().getAllSlotLevels(levels, slotCount);
    env->ReleasePrimitiveArrayCritical(outLevels, levels, 0);
}

} // extern "C"
