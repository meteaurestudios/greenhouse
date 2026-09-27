#pragma once

#include <cstdint>

#if defined(__x86_64__) || defined(__i386__)
#include <xmmintrin.h>
#include <pmmintrin.h>
#endif

namespace aaphost
{

class ScopedNoDenormals
{
public:
    ScopedNoDenormals()
    {
#if defined(__aarch64__)
        uint64_t fpcr = 0;
        asm volatile("mrs %0, fpcr" : "=r"(fpcr));
        mPreviousState = fpcr;
        fpcr |= (1ULL << 24);
        asm volatile("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__arm__)
        uint32_t fpscr = 0;
        asm volatile("vmrs %0, fpscr" : "=r"(fpscr));
        mPreviousState = fpscr;
        fpscr |= (1U << 24);
        asm volatile("vmsr fpscr, %0" : : "r"(fpscr));
#elif defined(__x86_64__) || defined(__i386__)
        mPreviousState = _mm_getcsr();
        _MM_SET_FLUSH_ZERO_MODE(_MM_FLUSH_ZERO_ON);
        _MM_SET_DENORMALS_ZERO_MODE(_MM_DENORMALS_ZERO_ON);
#endif
    }

    ~ScopedNoDenormals()
    {
#if defined(__aarch64__)
        uint64_t fpcr = mPreviousState;
        asm volatile("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__arm__)
        auto fpscr = static_cast<uint32_t>(mPreviousState);
        asm volatile("vmsr fpscr, %0" : : "r"(fpscr));
#elif defined(__x86_64__) || defined(__i386__)
        auto csr = static_cast<unsigned int>(mPreviousState);
        _mm_setcsr(csr);
#endif
    }

    ScopedNoDenormals(const ScopedNoDenormals&) = delete;
    ScopedNoDenormals& operator=(const ScopedNoDenormals&) = delete;

private:
#if defined(__aarch64__)
    uint64_t mPreviousState{0};
#else
    uint32_t mPreviousState{0};
#endif
};

} // namespace aaphost
