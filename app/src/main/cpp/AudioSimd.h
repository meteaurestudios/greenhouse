#pragma once

#include <cstdint>
#include <cstddef>
#include <cmath>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace aaphost
{

namespace simd
{

inline void interleaveStereo(const float* inL, const float* inR, float* outInterleaved, int32_t numFrames)
{
#if defined(__ARM_NEON) || defined(__aarch64__)
    int32_t i = 0;

    for (; i <= numFrames - 4; i += 4) {
        float32x4x2_t v;
        v.val[0] = vld1q_f32(inL + i);
        v.val[1] = vld1q_f32(inR + i);
        vst2q_f32(outInterleaved + (i * 2), v);
    }

    for (; i < numFrames; i++) {
        outInterleaved[i * 2 + 0] = inL[i];
        outInterleaved[i * 2 + 1] = inR[i];
    }
#else
    for (int32_t i = 0; i < numFrames; i++) {
        outInterleaved[i * 2 + 0] = inL[i];
        outInterleaved[i * 2 + 1] = inR[i];
    }
#endif
}

inline void deinterleaveStereo(const float* inInterleaved, float* outL, float* outR, int32_t numFrames)
{
#if defined(__ARM_NEON) || defined(__aarch64__)
    int32_t i = 0;

    for (; i <= numFrames - 4; i += 4) {
        float32x4x2_t v = vld2q_f32(inInterleaved + (i * 2));
        vst1q_f32(outL + i, v.val[0]);
        vst1q_f32(outR + i, v.val[1]);
    }

    for (; i < numFrames; i++) {
        outL[i] = inInterleaved[i * 2 + 0];
        outR[i] = inInterleaved[i * 2 + 1];
    }
#else
    for (int32_t i = 0; i < numFrames; i++) {
        outL[i] = inInterleaved[i * 2 + 0];
        outR[i] = inInterleaved[i * 2 + 1];
    }
#endif
}

inline void interleaveMonoToStereo(const float* inMono, float* outInterleaved, int32_t numFrames)
{
#if defined(__ARM_NEON) || defined(__aarch64__)
    int32_t i = 0;

    for (; i <= numFrames - 4; i += 4) {
        float32x4_t m = vld1q_f32(inMono + i);
        float32x4x2_t v;
        v.val[0] = m;
        v.val[1] = m;
        vst2q_f32(outInterleaved + (i * 2), v);
    }

    for (; i < numFrames; i++) {
        outInterleaved[i * 2 + 0] = inMono[i];
        outInterleaved[i * 2 + 1] = inMono[i];
    }
#else
    for (int32_t i = 0; i < numFrames; i++) {
        outInterleaved[i * 2 + 0] = inMono[i];
        outInterleaved[i * 2 + 1] = inMono[i];
    }
#endif
}

inline void deinterleaveStereoToMono(const float* inInterleaved, float* outMono, int32_t numFrames)
{
#if defined(__ARM_NEON) || defined(__aarch64__)
    float32x4_t half = vdupq_n_f32(0.5f);
    int32_t i = 0;

    for (; i <= numFrames - 4; i += 4) {
        float32x4x2_t v = vld2q_f32(inInterleaved + (i * 2));
        float32x4_t sum = vaddq_f32(v.val[0], v.val[1]);
        vst1q_f32(outMono + i, vmulq_f32(sum, half));
    }

    for (; i < numFrames; i++) {
        outMono[i] = 0.5f * (inInterleaved[i * 2 + 0] + inInterleaved[i * 2 + 1]);
    }
#else
    for (int32_t i = 0; i < numFrames; i++) {
        outMono[i] = 0.5f * (inInterleaved[i * 2 + 0] + inInterleaved[i * 2 + 1]);
    }
#endif
}

inline void measureStereoPeak(const float* interleavedStereo, int32_t numFrames, float& outPeakL, float& outPeakR)
{
    float maxL = 0.0f;
    float maxR = 0.0f;

#if defined(__ARM_NEON) || defined(__aarch64__)
    float32x4_t maxVecL = vdupq_n_f32(0.0f);
    float32x4_t maxVecR = vdupq_n_f32(0.0f);
    int32_t i = 0;

    for (; i <= numFrames - 4; i += 4) {
        float32x4x2_t v = vld2q_f32(interleavedStereo + (i * 2));
        maxVecL = vmaxq_f32(maxVecL, vabsq_f32(v.val[0]));
        maxVecR = vmaxq_f32(maxVecR, vabsq_f32(v.val[1]));
    }

#if defined(__aarch64__)
    maxL = vmaxvq_f32(maxVecL);
    maxR = vmaxvq_f32(maxVecR);
#elif defined(__ARM_NEON)
    float32x2_t max2L = vpmax_f32(vget_low_f32(maxVecL), vget_high_f32(maxVecL));
    max2L = vpmax_f32(max2L, max2L);
    maxL = vget_lane_f32(max2L, 0);

    float32x2_t max2R = vpmax_f32(vget_low_f32(maxVecR), vget_high_f32(maxVecR));
    max2R = vpmax_f32(max2R, max2R);
    maxR = vget_lane_f32(max2R, 0);
#endif

    for (; i < numFrames; i++) {
        float absL = std::fabs(interleavedStereo[i * 2 + 0]);
        float absR = std::fabs(interleavedStereo[i * 2 + 1]);

        if (absL > maxL) {
            maxL = absL;
        }

        if (absR > maxR) {
            maxR = absR;
        }
    }
#else
    for (int32_t i = 0; i < numFrames; i++) {
        float absL = std::fabs(interleavedStereo[i * 2 + 0]);
        float absR = std::fabs(interleavedStereo[i * 2 + 1]);

        if (absL > maxL) {
            maxL = absL;
        }

        if (absR > maxR) {
            maxR = absR;
        }
    }
#endif

    outPeakL = maxL;
    outPeakR = maxR;
}

} // namespace simd

} // namespace aaphost
