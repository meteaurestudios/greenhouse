#include <jni.h>
#include "NativeAudioEngine.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_androidaudioplugin_host_core_AapAudioPlayer_nativeCreate(
        JNIEnv *env, jclass clazz, jint sampleRate, jint framesPerCallback, jint channelCount, jint numSlots)
{
    auto engine = new aaphost::NativeAudioEngine(sampleRate, framesPerCallback, channelCount, numSlots);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_host_core_AapAudioPlayer_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong engineHandle)
{
    auto engine = reinterpret_cast<aaphost::NativeAudioEngine*>(engineHandle);

    if (engine != nullptr) {
        delete engine;
    }
}

JNIEXPORT jboolean JNICALL
Java_org_androidaudioplugin_host_core_AapAudioPlayer_nativeStart(
        JNIEnv *env, jclass clazz, jlong engineHandle)
{
    auto engine = reinterpret_cast<aaphost::NativeAudioEngine*>(engineHandle);

    if (engine != nullptr) {
        return static_cast<jboolean>(engine->start());
    }

    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_host_core_AapAudioPlayer_nativePause(
        JNIEnv *env, jclass clazz, jlong engineHandle)
{
    auto engine = reinterpret_cast<aaphost::NativeAudioEngine*>(engineHandle);

    if (engine != nullptr) {
        engine->pause();
    }
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_host_core_AapAudioPlayer_nativeSetFramesPerCallback(
        JNIEnv *env, jclass clazz, jlong engineHandle, jint framesPerCallback)
{
    auto engine = reinterpret_cast<aaphost::NativeAudioEngine*>(engineHandle);

    if (engine != nullptr) {
        engine->setFramesPerCallback(framesPerCallback);
    }
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_host_core_AapAudioPlayer_nativeSetSlotPlugin(
        JNIEnv *env, jclass clazz, jlong engineHandle, jint slotIndex, jlong nativeClient, jint instanceId)
{
    auto engine = reinterpret_cast<aaphost::NativeAudioEngine*>(engineHandle);

    if (engine != nullptr) {
        auto client = reinterpret_cast<aap::PluginClient*>(nativeClient);
        engine->setSlotPlugin(slotIndex, client, instanceId);
    }
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_host_core_AapAudioPlayer_nativeSetSlotBypassed(
        JNIEnv *env, jclass clazz, jlong engineHandle, jint slotIndex, jboolean bypassed)
{
    auto engine = reinterpret_cast<aaphost::NativeAudioEngine*>(engineHandle);

    if (engine != nullptr) {
        engine->setSlotBypassed(slotIndex, bypassed);
    }
}

JNIEXPORT void JNICALL
Java_org_androidaudioplugin_host_core_AapAudioPlayer_nativeSendUmp(
        JNIEnv *env, jclass clazz, jlong engineHandle, jint slotIndex, jbyteArray data, jint length)
{
    auto engine = reinterpret_cast<aaphost::NativeAudioEngine*>(engineHandle);

    if (engine != nullptr && data != nullptr && length > 0) {
        auto elements = env->GetByteArrayElements(data, nullptr);

        if (elements != nullptr) {
            engine->sendUmpToSlot(slotIndex, reinterpret_cast<const uint8_t*>(elements), static_cast<size_t>(length));
            env->ReleaseByteArrayElements(data, elements, JNI_ABORT);
        }
    }
}

JNIEXPORT jfloat JNICALL
Java_org_androidaudioplugin_host_core_AapAudioPlayer_nativeGetCpuLoad(
        JNIEnv *env, jclass clazz, jlong engineHandle)
{
    auto engine = reinterpret_cast<aaphost::NativeAudioEngine*>(engineHandle);

    if (engine != nullptr) {
        return engine->getTotalCpuLoad();
    }

    return 0.0f;
}

JNIEXPORT jfloat JNICALL
Java_org_androidaudioplugin_host_core_AapAudioPlayer_nativeGetSlotCpuLoad(
        JNIEnv *env, jclass clazz, jlong engineHandle, jint slotIndex)
{
    auto engine = reinterpret_cast<aaphost::NativeAudioEngine*>(engineHandle);

    if (engine != nullptr) {
        return engine->getSlotCpuLoad(slotIndex);
    }

    return 0.0f;
}

} // extern "C"
