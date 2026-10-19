#include "AOBScanner.h"
#include "MemoryGuard.h"
#include "Includes/Logger.h"
#include "KittyMemory/KittyMemory.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cctype>
#include <sstream>
#include <algorithm>

namespace AOBScanner
{
    std::vector<PatternByte> ParsePattern(const std::string &pattern)
    {
        std::vector<PatternByte> result;
        std::istringstream iss(pattern);
        std::string token;

        while (iss >> token)
        {
            if (token == "?" || token == "??")
            {
                result.push_back({0, true});
            }
            else
            {
                uint8_t byteVal = static_cast<uint8_t>(std::strtoul(token.c_str(), nullptr, 16));
                result.push_back({byteVal, false});
            }
        }
        return result;
    }

    std::vector<uintptr_t> ScanRange(uintptr_t start, uintptr_t end, const std::string &pattern)
    {
        std::vector<uintptr_t> matches;
        auto parsed = ParsePattern(pattern);
        if (parsed.empty() || start >= end)
            return matches;

        size_t patternLen = parsed.size();
        if (end - start < patternLen)
            return matches;

        const uint8_t *curr = reinterpret_cast<const uint8_t *>(start);
        const uint8_t *maxScan = reinterpret_cast<const uint8_t *>(end - patternLen);

        for (; curr <= maxScan; ++curr)
        {
            bool found = true;
            for (size_t i = 0; i < patternLen; ++i)
            {
                if (parsed[i].isWildcard)
                    continue;

                uint8_t b = 0;
                if (!MemoryGuard::SafeReadVal(curr + i, b) || b != parsed[i].byte)
                {
                    found = false;
                    break;
                }
            }

            if (found)
            {
                matches.push_back(reinterpret_cast<uintptr_t>(curr));
            }
        }

        return matches;
    }

    std::vector<uintptr_t> ScanModule(const std::string &moduleName, const std::string &pattern, bool executableOnly)
    {
        std::vector<uintptr_t> matches;
        if (moduleName.empty() || pattern.empty())
            return matches;

        FILE *fp = fopen("/proc/self/maps", "r");
        if (!fp)
            return matches;

        char line[512];
        while (fgets(line, sizeof(line), fp))
        {
            if (strstr(line, moduleName.c_str()) == nullptr)
                continue;

            uintptr_t start = 0, end = 0;
            char perms[5] = {0};
            if (sscanf(line, "%lx-%lx %4s", &start, &end, perms) < 3)
                continue;

            // Must be readable
            if (perms[0] != 'r')
                continue;

            if (executableOnly && perms[2] != 'x')
                continue;

            auto rangeMatches = ScanRange(start, end, pattern);
            matches.insert(matches.end(), rangeMatches.begin(), rangeMatches.end());
        }
        fclose(fp);

        return matches;
    }

    uintptr_t FindFirst(const std::string &moduleName, const std::string &pattern, bool executableOnly)
    {
        auto results = ScanModule(moduleName, pattern, executableOnly);
        return results.empty() ? 0 : results.front();
    }

    uintptr_t FindFirstRVA(const std::string &moduleName, const std::string &pattern, bool executableOnly)
    {
        uintptr_t addr = FindFirst(moduleName, pattern, executableOnly);
        if (!addr)
            return 0;

        auto map = KittyMemory::getLibraryMap(moduleName.c_str());
        if (!map.isValid())
            return 0;

        uintptr_t base = reinterpret_cast<uintptr_t>(map.startAddr);
        return (addr >= base) ? (addr - base) : 0;
    }
}
