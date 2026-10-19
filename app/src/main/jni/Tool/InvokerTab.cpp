
#include "InvokerTab.h"
#include "Il2cpp/Il2cpp.h"
#include "Il2cpp/il2cpp-class.h"
#include "Tool/Tool.h"
#include "Includes/Logger.h"
#include "imgui.h"

#include <vector>
#include <string>
#include <sstream>

namespace InvokerTab
{
    static char s_ClassFilter[128] = "";
    static Il2CppClass *s_SelectedClass = nullptr;
    static std::vector<Il2CppObject *> s_Instances;
    static int s_SelectedInstanceIdx = -1;
    static std::string s_InvokeLog;

    void Init()
    {
    }

    void Draw()
    {
        ImGui::TextColored(ImVec4(0.4f, 0.9f, 1.0f, 1.0f), "Runtime Method Invoker & Object Inspector");
        ImGui::Separator();

        // Class Search Area
        ImGui::InputTextWithHint("Search Class", "e.g. LogicHero, BattleCamera, Player...", s_ClassFilter, sizeof(s_ClassFilter));

        if (ImGui::Button("Find Class") && s_ClassFilter[0] != '\0')
        {
            s_SelectedClass = Il2cpp::FindClass(s_ClassFilter);
            s_Instances.clear();
            s_SelectedInstanceIdx = -1;
            if (s_SelectedClass)
            {
                Tool::AddNotification("Class Found", s_SelectedClass->getName(), true);
            }
            else
            {
                Tool::AddNotification("Not Found", "No class matching filter", false);
            }
        }

        if (!s_SelectedClass)
        {
            ImGui::TextDisabled("Search and select a class above to inspect instances and invoke methods.");
            return;
        }

        ImGui::Spacing();
        ImGui::TextColored(ImVec4(0.2f, 1.0f, 0.4f, 1.0f), "Class: %s (Namespace: %s)",
                           s_SelectedClass->getName(),
                           s_SelectedClass->getNamespace() ? s_SelectedClass->getNamespace() : "[global]");

        // Heap Instances Section
        if (ImGui::CollapsingHeader("Live Heap Instances (GC Scan)", ImGuiTreeNodeFlags_DefaultOpen))
        {
            if (ImGui::Button("Scan Heap for Active Instances"))
            {
                s_Instances = Il2cpp::GC::FindObjects(s_SelectedClass);
                s_SelectedInstanceIdx = s_Instances.empty() ? -1 : 0;
                Tool::AddNotification("Scan Complete", "Found " + std::to_string(s_Instances.size()) + " instances", true);
            }

            if (!s_Instances.empty())
            {
                ImGui::Text("Active Instances (%zu):", s_Instances.size());
                for (int i = 0; i < (int)s_Instances.size() && i < 15; ++i)
                {
                    char label[64];
                    snprintf(label, sizeof(label), "Instance #%d (%p)", i, (void *)s_Instances[i]);
                    bool isSelected = (s_SelectedInstanceIdx == i);
                    if (ImGui::RadioButton(label, isSelected))
                    {
                        s_SelectedInstanceIdx = i;
                    }
                }
            }
            else
            {
                ImGui::TextDisabled("No active instances scanned in heap.");
            }
        }

        ImGui::Spacing();
        ImGui::Separator();

        // Methods Invocation Section
        if (ImGui::CollapsingHeader("Methods & Runtime Invoke", ImGuiTreeNodeFlags_DefaultOpen))
        {
            auto methods = s_SelectedClass->getMethods();
            ImGui::Text("Methods count: %zu", methods.size());

            if (ImGui::BeginTable("MethodsInvokerTable", 4, ImGuiTableFlags_Borders | ImGuiTableFlags_RowBg | ImGuiTableFlags_ScrollY, ImVec2(0, 260)))
            {
                ImGui::TableSetupColumn("Return Type", ImGuiTableColumnFlags_WidthFixed, 100.0f);
                ImGui::TableSetupColumn("Method Name", ImGuiTableColumnFlags_WidthStretch);
                ImGui::TableSetupColumn("Params", ImGuiTableColumnFlags_WidthFixed, 60.0f);
                ImGui::TableSetupColumn("Action", ImGuiTableColumnFlags_WidthFixed, 90.0f);
                ImGui::TableHeadersRow();

                for (auto m : methods)
                {
                    if (!m)
                        continue;

                    ImGui::TableNextRow();
                    ImGui::PushID((void *)m);

                    // Col 0: Return Type
                    ImGui::TableSetColumnIndex(0);
                    Il2CppType *retType = m->getReturnType();
                    const char *retName = retType ? retType->getName() : "void";
                    ImGui::TextUnformatted(retName);

                    // Col 1: Name
                    ImGui::TableSetColumnIndex(1);
                    ImGui::TextUnformatted(m->getName());

                    // Col 2: Params Count
                    ImGui::TableSetColumnIndex(2);
                    auto paramsInfo = m->getParamsInfo();
                    ImGui::Text("%zu", paramsInfo.size());

                    // Col 3: Invoke Button
                    ImGui::TableSetColumnIndex(3);
                    bool canInvoke = paramsInfo.empty();
                    ImGui::BeginDisabled(!canInvoke);
                    if (ImGui::Button("Invoke"))
                    {
                        void *targetObj = nullptr;
                        if (s_SelectedInstanceIdx >= 0 && s_SelectedInstanceIdx < (int)s_Instances.size())
                        {
                            targetObj = s_Instances[s_SelectedInstanceIdx];
                        }

                        Il2CppException *exc = nullptr;
                        void *params[1] = {nullptr};
                        Il2CppObject *res = Il2cpp::RuntimeInvoke(m, targetObj, params, &exc);

                        std::stringstream ss;
                        ss << "Invoked " << s_SelectedClass->getName() << "::" << m->getName() << "() -> ";
                        if (exc)
                        {
                            ss << "[EXCEPTION TRIGGERED]";
                            Tool::AddNotification("Invoke Exception", "Method threw an exception", false);
                        }
                        else if (!res)
                        {
                            ss << "[void / null result]";
                            Tool::AddNotification("Invoked OK", "Returned void or null", true);
                        }
                        else
                        {
                            ss << "Result Pointer: " << (void *)res;
                            Tool::AddNotification("Invoked OK", "Success!", true);
                        }
                        s_InvokeLog = ss.str();
                    }
                    ImGui::EndDisabled();

                    ImGui::PopID();
                }
                ImGui::EndTable();
            }

            if (!s_InvokeLog.empty())
            {
                ImGui::Spacing();
                ImGui::TextColored(ImVec4(1, 1, 0, 1), "Last Invoke Output:");
                ImGui::TextUnformatted(s_InvokeLog.c_str());
            }
        }
    }
} // namespace InvokerTab
