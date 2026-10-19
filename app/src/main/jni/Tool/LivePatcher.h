#pragma once

#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>
#include "KittyMemory/MemoryPatch.h"
#include "Il2cpp/il2cpp-class.h"

struct PatchItem
{
    uintptr_t address = 0;
    std::string name;
    std::string moduleName;
    uintptr_t rva = 0;
    std::string patchHex;
    std::string origHex;
    MemoryPatch patch;
    bool active = false;
};

namespace LivePatcher
{
    void Init();
    bool PatchNop(uintptr_t address, const std::string &name = "", const std::string &module = "", uintptr_t rva = 0);
    bool PatchRetVoid(uintptr_t address, const std::string &name = "", const std::string &module = "", uintptr_t rva = 0);
    bool PatchRetTrue(uintptr_t address, const std::string &name = "", const std::string &module = "", uintptr_t rva = 0);
    bool PatchRetFalse(uintptr_t address, const std::string &name = "", const std::string &module = "", uintptr_t rva = 0);
    bool PatchHex(uintptr_t address, const std::string &hex, const std::string &name = "", const std::string &module = "", uintptr_t rva = 0);
    bool Restore(uintptr_t address);
    void RestoreAll();
    bool IsPatched(uintptr_t address);
    std::vector<PatchItem> GetPatches();
    void DrawTab();
} // namespace LivePatcher
