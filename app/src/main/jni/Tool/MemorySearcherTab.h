#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace MemorySearcherTab
{
    enum class DataType
    {
        Type_Float = 0,
        Type_Int32 = 1,
        Type_Int64 = 2
    };

    enum class RefineType
    {
        Exact = 0,
        Increased = 1,
        Decreased = 2,
        Changed = 3
    };

    struct SearchResult
    {
        uintptr_t address;
        std::string prevValue;
        bool frozen;
        std::string freezeValue;
    };

    void Init();
    void Update();
    void Draw();
} // namespace MemorySearcherTab
