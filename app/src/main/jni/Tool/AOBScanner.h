#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace AOBScanner
{
    struct PatternByte
    {
        uint8_t byte;
        bool isWildcard;
    };

    // Parse an IDA-style hex pattern, e.g. "E0 03 1F 2A ?? ?? ?? 94"
    std::vector<PatternByte> ParsePattern(const std::string &pattern);

    // Scans memory of a specific module (e.g. "libil2cpp.so", "libunity.so") for an AOB pattern.
    // Returns all matching absolute addresses.
    std::vector<uintptr_t> ScanModule(const std::string &moduleName, const std::string &pattern, bool executableOnly = true);

    // Scans a specific memory range [start, end)
    std::vector<uintptr_t> ScanRange(uintptr_t start, uintptr_t end, const std::string &pattern);

    // Convenience function to find the first match in a module. Returns 0 if not found.
    uintptr_t FindFirst(const std::string &moduleName, const std::string &pattern, bool executableOnly = true);

    // Finds the first match and returns its RVA relative to the module base.
    uintptr_t FindFirstRVA(const std::string &moduleName, const std::string &pattern, bool executableOnly = true);
}
