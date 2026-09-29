#pragma once

#include <cstdio>

#define LOGW(...) (std::fprintf(stderr, __VA_ARGS__), std::fprintf(stderr, "\n"))
