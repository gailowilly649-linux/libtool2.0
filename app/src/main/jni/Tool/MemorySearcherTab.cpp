#include "MemorySearcherTab.h"
#include "MemoryGuard.h"
#include "Tool/Tool.h"
#include "Includes/Logger.h"
#include "imgui.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <string>
#include <mutex>
#include <cmath>

namespace MemorySearcherTab
{
    static int s_SelectedDataType = 0; // 0 = Float, 1 = Int32, 2 = Int64
    static char s_SearchInput[64] = "100";
    static char s_WriteInput[64] = "999";
    static std::vector<SearchResult> s_Results;
    static std::mutex s_Mutex;
    static bool s_IsSearching = false;
    static std::string s_StatusMsg = "Ready. Enter value and tap 'First Search'.";

    void Init()
    {
    }

    void Update()
    {
        std::lock_guard<std::mutex> lock(s_Mutex);
        for (auto &item : s_Results)
        {
            if (!item.frozen)
                continue;

            if (s_SelectedDataType == 0) // Float
            {
                float val = std::strtof(item.freezeValue.c_str(), nullptr);
                MemoryGuard::SafeWriteVal(reinterpret_cast<void *>(item.address), val);
            }
            else if (s_SelectedDataType == 1) // Int32
            {
                int32_t val = static_cast<int32_t>(std::strtol(item.freezeValue.c_str(), nullptr, 10));
                MemoryGuard::SafeWriteVal(reinterpret_cast<void *>(item.address), val);
            }
            else if (s_SelectedDataType == 2) // Int64
            {
                int64_t val = std::strtoll(item.freezeValue.c_str(), nullptr, 10);
                MemoryGuard::SafeWriteVal(reinterpret_cast<void *>(item.address), val);
            }
        }
    }

    static void DoFirstSearch()
    {
        std::lock_guard<std::mutex> lock(s_Mutex);
        s_Results.clear();

        FILE *fp = fopen("/proc/self/maps", "r");
        if (!fp)
        {
            s_StatusMsg = "Failed to open /proc/self/maps";
            return;
        }

        float searchFloat = std::strtof(s_SearchInput, nullptr);
        int32_t searchInt32 = static_cast<int32_t>(std::strtol(s_SearchInput, nullptr, 10));
        int64_t searchInt64 = std::strtoll(s_SearchInput, nullptr, 10);

        char line[512];
        const size_t MAX_RESULTS = 5000;

        while (fgets(line, sizeof(line), fp) && s_Results.size() < MAX_RESULTS)
        {
            uintptr_t start = 0, end = 0;
            char perms[5] = {0};
            if (sscanf(line, "%lx-%lx %4s", &start, &end, perms) < 3)
                continue;

            // Target writable and readable memory
            if (perms[0] != 'r' || perms[1] != 'w')
                continue;

            // Skip read-only fonts/framework files if mapped
            if (strstr(line, "/system/fonts") || strstr(line, "/dev/ashmem"))
                continue;

            // Scan range in 4-byte or 8-byte steps
            if (s_SelectedDataType == 0) // Float
            {
                for (uintptr_t addr = start; addr + sizeof(float) <= end; addr += sizeof(float))
                {
                    float val = 0.0f;
                    if (MemoryGuard::SafeReadVal(reinterpret_cast<void *>(addr), val))
                    {
                        if (std::fabs(val - searchFloat) < 0.0001f)
                        {
                            SearchResult r;
                            r.address = addr;
                            r.prevValue = std::to_string(val);
                            r.frozen = false;
                            s_Results.push_back(r);
                            if (s_Results.size() >= MAX_RESULTS)
                                break;
                        }
                    }
                }
            }
            else if (s_SelectedDataType == 1) // Int32
            {
                for (uintptr_t addr = start; addr + sizeof(int32_t) <= end; addr += sizeof(int32_t))
                {
                    int32_t val = 0;
                    if (MemoryGuard::SafeReadVal(reinterpret_cast<void *>(addr), val))
                    {
                        if (val == searchInt32)
                        {
                            SearchResult r;
                            r.address = addr;
                            r.prevValue = std::to_string(val);
                            r.frozen = false;
                            s_Results.push_back(r);
                            if (s_Results.size() >= MAX_RESULTS)
                                break;
                        }
                    }
                }
            }
            else if (s_SelectedDataType == 2) // Int64
            {
                for (uintptr_t addr = start; addr + sizeof(int64_t) <= end; addr += sizeof(int64_t))
                {
                    int64_t val = 0;
                    if (MemoryGuard::SafeReadVal(reinterpret_cast<void *>(addr), val))
                    {
                        if (val == searchInt64)
                        {
                            SearchResult r;
                            r.address = addr;
                            r.prevValue = std::to_string(val);
                            r.frozen = false;
                            s_Results.push_back(r);
                            if (s_Results.size() >= MAX_RESULTS)
                                break;
                        }
                    }
                }
            }
        }
        fclose(fp);

        s_StatusMsg = "Found " + std::to_string(s_Results.size()) + " matches.";
        Tool::AddNotification("Search Complete", s_StatusMsg, true);
    }

    static void DoRefineSearch(RefineType rType)
    {
        std::lock_guard<std::mutex> lock(s_Mutex);
        if (s_Results.empty())
        {
            s_StatusMsg = "No results to refine. Do a First Search first.";
            return;
        }

        float searchFloat = std::strtof(s_SearchInput, nullptr);
        int32_t searchInt32 = static_cast<int32_t>(std::strtol(s_SearchInput, nullptr, 10));
        int64_t searchInt64 = std::strtoll(s_SearchInput, nullptr, 10);

        std::vector<SearchResult> refined;

        for (auto &item : s_Results)
        {
            if (s_SelectedDataType == 0) // Float
            {
                float cur = 0.0f;
                if (!MemoryGuard::SafeReadVal(reinterpret_cast<void *>(item.address), cur))
                    continue;

                float old = std::strtof(item.prevValue.c_str(), nullptr);
                bool match = false;
                if (rType == RefineType::Exact) match = (std::fabs(cur - searchFloat) < 0.0001f);
                else if (rType == RefineType::Increased) match = (cur > old);
                else if (rType == RefineType::Decreased) match = (cur < old);
                else if (rType == RefineType::Changed) match = (std::fabs(cur - old) > 0.0001f);

                if (match)
                {
                    item.prevValue = std::to_string(cur);
                    refined.push_back(item);
                }
            }
            else if (s_SelectedDataType == 1) // Int32
            {
                int32_t cur = 0;
                if (!MemoryGuard::SafeReadVal(reinterpret_cast<void *>(item.address), cur))
                    continue;

                int32_t old = static_cast<int32_t>(std::strtol(item.prevValue.c_str(), nullptr, 10));
                bool match = false;
                if (rType == RefineType::Exact) match = (cur == searchInt32);
                else if (rType == RefineType::Increased) match = (cur > old);
                else if (rType == RefineType::Decreased) match = (cur < old);
                else if (rType == RefineType::Changed) match = (cur != old);

                if (match)
                {
                    item.prevValue = std::to_string(cur);
                    refined.push_back(item);
                }
            }
            else if (s_SelectedDataType == 2) // Int64
            {
                int64_t cur = 0;
                if (!MemoryGuard::SafeReadVal(reinterpret_cast<void *>(item.address), cur))
                    continue;

                int64_t old = std::strtoll(item.prevValue.c_str(), nullptr, 10);
                bool match = false;
                if (rType == RefineType::Exact) match = (cur == searchInt64);
                else if (rType == RefineType::Increased) match = (cur > old);
                else if (rType == RefineType::Decreased) match = (cur < old);
                else if (rType == RefineType::Changed) match = (cur != old);

                if (match)
                {
                    item.prevValue = std::to_string(cur);
                    refined.push_back(item);
                }
            }
        }

        s_Results = std::move(refined);
        s_StatusMsg = "Refined to " + std::to_string(s_Results.size()) + " matches.";
        Tool::AddNotification("Refine Complete", s_StatusMsg, true);
    }

    void Draw()
    {
        ImGui::TextColored(ImVec4(0.3f, 0.8f, 1.0f, 1.0f), "In-Game Live Memory Value Searcher & Freezer");
        ImGui::Separator();

        // Data Type Selection
        ImGui::Text("Data Type:");
        ImGui::SameLine();
        ImGui::RadioButton("Float", &s_SelectedDataType, 0);
        ImGui::SameLine();
        ImGui::RadioButton("Int32 (DWORD)", &s_SelectedDataType, 1);
        ImGui::SameLine();
        ImGui::RadioButton("Int64 (QWORD)", &s_SelectedDataType, 2);

        // Search Value Input
        ImGui::InputTextWithHint("Value", "Enter value to search...", s_SearchInput, sizeof(s_SearchInput));

        // Search Action Buttons
        if (ImGui::Button("First Search"))
        {
            DoFirstSearch();
        }
        ImGui::SameLine();
        if (ImGui::Button("Refine (Exact)"))
        {
            DoRefineSearch(RefineType::Exact);
        }
        ImGui::SameLine();
        if (ImGui::Button("Increased"))
        {
            DoRefineSearch(RefineType::Increased);
        }
        ImGui::SameLine();
        if (ImGui::Button("Decreased"))
        {
            DoRefineSearch(RefineType::Decreased);
        }
        ImGui::SameLine();
        if (ImGui::Button("Changed"))
        {
            DoRefineSearch(RefineType::Changed);
        }
        ImGui::SameLine();
        if (ImGui::Button("Clear"))
        {
            std::lock_guard<std::mutex> lock(s_Mutex);
            s_Results.clear();
            s_StatusMsg = "Results cleared.";
        }

        ImGui::Spacing();
        ImGui::TextColored(ImVec4(1.0f, 1.0f, 0.2f, 1.0f), "Status: %s", s_StatusMsg.c_str());
        ImGui::Separator();

        // Write Value Input
        ImGui::InputTextWithHint("New Value", "Value to write/freeze...", s_WriteInput, sizeof(s_WriteInput));

        // Results Table
        if (ImGui::BeginTable("MemSearchResultsTable", 5, ImGuiTableFlags_Borders | ImGuiTableFlags_RowBg | ImGuiTableFlags_ScrollY, ImVec2(0, 300)))
        {
            ImGui::TableSetupColumn("Address", ImGuiTableColumnFlags_WidthFixed, 140.0f);
            ImGui::TableSetupColumn("Current Value", ImGuiTableColumnFlags_WidthStretch);
            ImGui::TableSetupColumn("Write", ImGuiTableColumnFlags_WidthFixed, 70.0f);
            ImGui::TableSetupColumn("Freeze", ImGuiTableColumnFlags_WidthFixed, 70.0f);
            ImGui::TableSetupColumn("Copy", ImGuiTableColumnFlags_WidthFixed, 60.0f);
            ImGui::TableHeadersRow();

            std::lock_guard<std::mutex> lock(s_Mutex);
            size_t count = std::min(s_Results.size(), (size_t)200);

            for (size_t i = 0; i < count; ++i)
            {
                auto &item = s_Results[i];
                ImGui::TableNextRow();
                ImGui::PushID(static_cast<int>(i));

                // Col 0: Address
                ImGui::TableSetColumnIndex(0);
                char addrBuf[32];
                snprintf(addrBuf, sizeof(addrBuf), "0x%lx", item.address);
                ImGui::TextUnformatted(addrBuf);

                // Col 1: Current Live Value
                ImGui::TableSetColumnIndex(1);
                std::string liveStr = "???";
                if (s_SelectedDataType == 0)
                {
                    float val = 0.0f;
                    if (MemoryGuard::SafeReadVal(reinterpret_cast<void *>(item.address), val))
                        liveStr = std::to_string(val);
                }
                else if (s_SelectedDataType == 1)
                {
                    int32_t val = 0;
                    if (MemoryGuard::SafeReadVal(reinterpret_cast<void *>(item.address), val))
                        liveStr = std::to_string(val);
                }
                else if (s_SelectedDataType == 2)
                {
                    int64_t val = 0;
                    if (MemoryGuard::SafeReadVal(reinterpret_cast<void *>(item.address), val))
                        liveStr = std::to_string(val);
                }
                ImGui::TextUnformatted(liveStr.c_str());

                // Col 2: Write Button
                ImGui::TableSetColumnIndex(2);
                if (ImGui::Button("Write"))
                {
                    if (s_SelectedDataType == 0)
                    {
                        float f = std::strtof(s_WriteInput, nullptr);
                        MemoryGuard::SafeWriteVal(reinterpret_cast<void *>(item.address), f);
                    }
                    else if (s_SelectedDataType == 1)
                    {
                        int32_t v = static_cast<int32_t>(std::strtol(s_WriteInput, nullptr, 10));
                        MemoryGuard::SafeWriteVal(reinterpret_cast<void *>(item.address), v);
                    }
                    else if (s_SelectedDataType == 2)
                    {
                        int64_t v = std::strtoll(s_WriteInput, nullptr, 10);
                        MemoryGuard::SafeWriteVal(reinterpret_cast<void *>(item.address), v);
                    }
                    Tool::AddNotification("Value Written", addrBuf, true);
                }

                // Col 3: Freeze Toggle
                ImGui::TableSetColumnIndex(3);
                bool frozen = item.frozen;
                if (ImGui::Checkbox("##Freeze", &frozen))
                {
                    item.frozen = frozen;
                    item.freezeValue = s_WriteInput;
                }

                // Col 4: Copy Address
                ImGui::TableSetColumnIndex(4);
                if (ImGui::Button("Copy"))
                {
                    ImGui::SetClipboardText(addrBuf);
                    Tool::AddNotification("Copied", addrBuf, true);
                }

                ImGui::PopID();
            }
            ImGui::EndTable();
        }
        if (s_Results.size() > 200)
        {
            ImGui::TextDisabled("Displaying first 200 of %zu results to maintain high FPS.", s_Results.size());
        }
    }
} // namespace MemorySearcherTab
