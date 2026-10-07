#pragma once

#include <android/log.h>

#define GREENHOUSE_LOG_TAG "GreenhouseEngine"
#define LOGI(...) ((void) __android_log_print(ANDROID_LOG_INFO, GREENHOUSE_LOG_TAG, __VA_ARGS__))
#define LOGW(...) ((void) __android_log_print(ANDROID_LOG_WARN, GREENHOUSE_LOG_TAG, __VA_ARGS__))
#define LOGE(...) ((void) __android_log_print(ANDROID_LOG_ERROR, GREENHOUSE_LOG_TAG, __VA_ARGS__))
