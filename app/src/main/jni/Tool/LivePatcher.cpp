#include "LivePatcher.h"
#include "Tool/Tool.h"
#include "Includes/Logger.h"
#include "imgui.h"
#include <mutex>
#include <algorithm>
#include <sstream>
#include <iomanip>

namespace LivePatcher
{
    static std::unordered_map<uintptr_t, PatchItem> s_Patches;
    static std::mutex s_Mutex;

    void Init()
    {
        // Initialization if needed
    }

    bool PatchHex(uintptr_t address, const std::string &hex, const std::string &name, const std::string &module, uintptr_t rva)
    {
        if (!address || hex.empty())
            return false;

        std::lock_guard<std::mutex> lock(s_Mutex);

        auto it = s_Patches.find(address);
        if (it != s_Patches.end())
        {
            // Already tracked, restore first if active
            if (it->second.active)
                it->second.patch.Restore();
        }

        MemoryPatch mp = MemoryPatch::createWithHex(address, hex);
        if (!mp.isValid())
        {
            LOGE("LivePatcher: Invalid patch hex '%s' for address %p", hex.c_str(), (void *)address);
            Tool::AddNotification("Patch Failed", "Invalid hex or memory protection error", false);
            return false;
        }

        std::string origHex = mp.get_CurrBytes();
        if (!mp.Modify())
        {
            LOGE("LivePatcher: Modify failed for address %p", (void *)address);
            Tool::AddNotification("Patch Failed", "Failed to write memory at address", false);
            return false;
        }

        PatchItem item;
        item.address = address;
        item.name = name.empty() ? "Manual Patch" : name;
        item.moduleName = module.empty() ? "Unknown" : module;
        item.rva = rva;
        item.patchHex = hex;
        item.origHex = origHex;
        item.patch = mp;
        item.active = true;

        s_Patches[address] = std::move(item);

        LOGI("LivePatcher: Successfully patched %p (%s) with %s", (void *)address, item.name.c_str(), hex.c_str());
        Tool::AddNotification("Patch Applied", item.name + " (" + hex + ")", true);
        return true;
    }

    bool PatchNop(uintptr_t address, const std::string &name, const std::string &module, uintptr_t rva)
    {
#if defined(__aarch64__)
        return PatchHex(address, "1F 20 03 D5", name.empty() ? "NOP" : (name + " [NOP]"), module, rva);
#else
        return PatchHex(address, "00 F0 20 E3", name.empty() ? "NOP" : (name + " [NOP]"), module, rva);
#endif
    }

    bool PatchRetVoid(uintptr_t address, const std::string &name, const std::string &module, uintptr_t rva)
    {
#if defined(__aarch64__)
        return PatchHex(address, "C0 03 5F D6", name.empty() ? "RET Void" : (name + " [RET Void]"), module, rva);
#else
        return PatchHex(address, "1E FF 2F E1", name.empty() ? "RET Void" : (name + " [RET Void]"), module, rva);
#endif
    }

    bool PatchRetTrue(uintptr_t address, const std::string &name, const std::string &module, uintptr_t rva)
    {
#if defined(__aarch64__)
        return PatchHex(address, "20 00 80 52 C0 03 5F D6", name.empty() ? "RET True" : (name + " [RET True]"), module, rva);
#else
        return PatchHex(address, "01 00 A0 E3 1E FF 2F E1", name.empty() ? "RET True" : (name + " [RET True]"), module, rva);
#endif
    }

    bool PatchRetFalse(uintptr_t address, const std::string &name, const std::string &module, uintptr_t rva)
    {
#if defined(__aarch64__)
        return PatchHex(address, "00 00 80 52 C0 03 5F D6", name.empty() ? "RET False" : (name + " [RET False]"), module, rva);
#else
        return PatchHex(address, "00 00 A0 E3 1E FF 2F E1", name.empty() ? "RET False" : (name + " [RET False]"), module, rva);
#endif
    }

    bool Restore(uintptr_t address)
    {
        std::lock_guard<std::mutex> lock(s_Mutex);
        auto it = s_Patches.find(address);
        if (it != s_Patches.end())
        {
            if (it->second.active)
            {
                it->second.patch.Restore();
                it->second.active = false;
                Tool::AddNotification("Patch Restored", it->second.name, true);
                return true;
            }
        }
        return false;
    }

    void RestoreAll()
    {
        std::lock_guard<std::mutex> lock(s_Mutex);
        int count = 0;
        for (auto &pair : s_Patches)
        {
            if (pair.second.active)
            {
                pair.second.patch.Restore();
                pair.second.active = false;
                count++;
            }
        }
        Tool::AddNotification("Restored All", "Restored " + std::to_string(count) + " patches", true);
    }

    bool IsPatched(uintptr_t address)
    {
        std::lock_guard<std::mutex> lock(s_Mutex);
        auto it = s_Patches.find(address);
        return (it != s_Patches.end() && it->second.active);
    }

    std::vector<PatchItem> GetPatches()
    {
        std::lock_guard<std::mutex> lock(s_Mutex);
        std::vector<PatchItem> res;
        res.reserve(s_Patches.size());
        for (const auto &p : s_Patches)
        {
            res.push_back(p.second);
        }
        return res;
    }

    void DrawTab()
    {
        static char manualAddrBuf[32] = "";
        static char manualHexBuf[128] = "";
        static char searchFilter[64] = "";

        ImGui::TextColored(ImVec4(0.2f, 0.9f, 0.4f, 1.0f), "Live In-Memory Patcher (ARM64)");
        ImGui::SameLine();
        ImGui::TextDisabled("| Target: MLBB SOSplit Runtime");

        ImGui::Separator();

        // Manual Patching Section
        if (ImGui::CollapsingHeader("Manual Memory Patch", ImGuiTreeNodeFlags_DefaultOpen))
        {
            ImGui::InputTextWithHint("Address (Hex)", "e.g. 0x7b12345678", manualAddrBuf, sizeof(manualAddrBuf));
            ImGui::InputTextWithHint("Opcodes (Hex)", "e.g. 1F 20 03 D5 (NOP)", manualHexBuf, sizeof(manualHexBuf));

            uintptr_t targetAddr = 0;
            if (manualAddrBuf[0] != '\0')
            {
                try {
                    targetAddr = (uintptr_t)std::stoull(manualAddrBuf, nullptr, 16);
                } catch (...) {}
            }

            ImGui::BeginDisabled(targetAddr == 0);
            if (ImGui::Button("Patch Custom Hex"))
            {
                if (manualHexBuf[0] != '\0')
                    PatchHex(targetAddr, manualHexBuf, "Manual Hex", "Custom", 0);
            }
            ImGui::SameLine();
            if (ImGui::Button("NOP"))
            {
                PatchNop(targetAddr, "Manual NOP");
            }
            ImGui::SameLine();
            if (ImGui::Button("RET True"))
            {
                PatchRetTrue(targetAddr, "Manual RET True");
            }
            ImGui::SameLine();
            if (ImGui::Button("RET False"))
            {
                PatchRetFalse(targetAddr, "Manual RET False");
            }
            ImGui::SameLine();
            if (ImGui::Button("Restore"))
            {
                Restore(targetAddr);
            }
            ImGui::EndDisabled();
        }

        ImGui::Spacing();
        ImGui::Separator();

        // Active Patches Section
        std::vector<PatchItem> patchList = GetPatches();
        ImGui::Text("Active Patches: %zu", patchList.size());
        ImGui::SameLine();
        if (ImGui::Button("Restore All"))
        {
            RestoreAll();
        }

        ImGui::InputTextWithHint("##SearchPatches", "Filter patches...", searchFilter, sizeof(searchFilter));

        if (ImGui::BeginTable("PatchesTable", 6, ImGuiTableFlags_Borders | ImGuiTableFlags_RowBg | ImGuiTableFlags_Resizable | ImGuiTableFlags_ScrollY, ImVec2(0, 300)))
        {
            ImGui::TableSetupColumn("Active", ImGuiTableColumnFlags_WidthFixed, 60.0f);
            ImGui::TableSetupColumn("Name", ImGuiTableColumnFlags_WidthStretch);
            ImGui::TableSetupColumn("Module:RVA", ImGuiTableColumnFlags_WidthStretch);
            ImGui::TableSetupColumn("Address", ImGuiTableColumnFlags_WidthFixed, 140.0f);
            ImGui::TableSetupColumn("Patched Hex", ImGuiTableColumnFlags_WidthStretch);
            ImGui::TableSetupColumn("Actions", ImGuiTableColumnFlags_WidthFixed, 100.0f);
            ImGui::TableHeadersRow();

            for (auto &item : patchList)
            {
                if (searchFilter[0] != '\0')
                {
                    if (item.name.find(searchFilter) == std::string::npos &&
                        item.moduleName.find(searchFilter) == std::string::npos)
                    {
                        continue;
                    }
                }

                ImGui::TableNextRow();
                ImGui::PushID((void *)item.address);

                // Col 0: Active Toggle
                ImGui::TableSetColumnIndex(0);
                bool isActive = item.active;
                if (ImGui::Checkbox("##ActiveToggle", &isActive))
                {
                    if (isActive)
                    {
                        PatchHex(item.address, item.patchHex, item.name, item.moduleName, item.rva);
                    }
                    else
                    {
                        Restore(item.address);
                    }
                }

                // Col 1: Name
                ImGui::TableSetColumnIndex(1);
                ImGui::TextUnformatted(item.name.c_str());

                // Col 2: Module + RVA
                ImGui::TableSetColumnIndex(2);
                if (item.rva != 0)
                    ImGui::Text("%s+0x%llX", item.moduleName.c_str(), (unsigned long long)item.rva);
                else
                    ImGui::TextUnformatted(item.moduleName.c_str());

                // Col 3: Address
                ImGui::TableSetColumnIndex(3);
                ImGui::Text("0x%llX", (unsigned long long)item.address);

                // Col 4: Hex
                ImGui::TableSetColumnIndex(4);
                ImGui::TextUnformatted(item.patchHex.c_str());

                // Col 5: Actions
                ImGui::TableSetColumnIndex(5);
                if (ImGui::Button("Restore"))
                {
                    Restore(item.address);
                }

                ImGui::PopID();
            }

            ImGui::EndTable();
        }
    }
} // namespace LivePatcher
