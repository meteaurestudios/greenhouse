#pragma once

#include <cstdio>

#define LOGI(...) ((void) 0)
#define LOGW(...) (std::fprintf(stderr, __VA_ARGS__), std::fprintf(stderr, "\n"))
#define LOGE(...) (std::fprintf(stderr, __VA_ARGS__), std::fprintf(stderr, "\n"))
