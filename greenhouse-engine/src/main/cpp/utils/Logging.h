#pragma once

#include <android/log.h>

#define AAPHOST_LOG_TAG "AAPHostEngineNative"
#define LOGI(...) ((void) __android_log_print(ANDROID_LOG_INFO, AAPHOST_LOG_TAG, __VA_ARGS__))
#define LOGW(...) ((void) __android_log_print(ANDROID_LOG_WARN, AAPHOST_LOG_TAG, __VA_ARGS__))
#define LOGE(...) ((void) __android_log_print(ANDROID_LOG_ERROR, AAPHOST_LOG_TAG, __VA_ARGS__))
