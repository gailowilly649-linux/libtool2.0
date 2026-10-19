#pragma once
#include <cstdint>
#include <cstddef>
#include <string>

namespace MemoryGuard
{
    // Safely reads 'size' bytes from 'src' into 'dst' without crashing on invalid addresses.
    bool SafeRead(const void *src, void *dst, size_t size);

    // Safely writes 'size' bytes from 'src' to 'dst'.
    bool SafeWrite(void *dst, const void *src, size_t size);

    // Checks if pointer is readable for at least 'size' bytes.
    bool IsValidReadPtr(const void *ptr, size_t size = 1);

    // Template helper to safely read primitive types (int, float, pointers, etc.)
    template <typename T>
    bool SafeReadVal(const void *src, T &outVal)
    {
        return SafeRead(src, &outVal, sizeof(T));
    }

    template <typename T>
    bool SafeWriteVal(void *dst, const T &val)
    {
        return SafeWrite(dst, &val, sizeof(T));
    }

    // Safely read a null-terminated C-string up to maxLen
    std::string SafeReadString(const void *src, size_t maxLen = 256);
}
