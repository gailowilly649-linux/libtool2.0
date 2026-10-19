#include "ESP.h"
#include "Il2cpp/Il2cpp.h"
#include "Il2cpp/il2cpp-class.h"
#include "Includes/Logger.h"
#include "imgui.h"

#include <vector>
#include <string>

namespace ESP
{
    static bool s_ESPEnabled = false;
    static bool s_BoxEnabled = true;
    static bool s_LineEnabled = true;
    static bool s_HealthBarEnabled = true;

    static ImVec4 s_BoxColor = ImVec4(1.0f, 0.2f, 0.2f, 1.0f);
    static ImVec4 s_LineColor = ImVec4(0.2f, 0.8f, 1.0f, 0.8f);

    void Init()
    {
    }

    void Update()
    {
    }

    bool IsESPEnabled() { return s_ESPEnabled; }
    void SetESPEnabled(bool enabled) { s_ESPEnabled = enabled; }

    bool IsBoxEnabled() { return s_BoxEnabled; }
    void SetBoxEnabled(bool enabled) { s_BoxEnabled = enabled; }

    bool IsLineEnabled() { return s_LineEnabled; }
    void SetLineEnabled(bool enabled) { s_LineEnabled = enabled; }

    bool IsHealthBarEnabled() { return s_HealthBarEnabled; }
    void SetHealthBarEnabled(bool enabled) { s_HealthBarEnabled = enabled; }

    bool WorldToScreen(const Vector3 &worldPos, Vector2 &screenPos)
    {
        auto CameraKlass = Il2cpp::FindClass("UnityEngine.Camera");
        if (!CameraKlass)
            return false;

        auto get_main = CameraKlass->getMethod("get_main", 0);
        auto worldToScreen = CameraKlass->getMethod("WorldToScreenPoint", 1);
        if (!get_main || !worldToScreen)
            return false;

        Il2CppObject *cam = Il2cpp::RuntimeInvoke(get_main, nullptr, nullptr, nullptr);
        if (!cam)
            return false;

        void *args[1] = {const_cast<Vector3 *>(&worldPos)};
        Il2CppObject *resObj = Il2cpp::RuntimeInvoke(worldToScreen, cam, args, nullptr);
        if (!resObj)
            return false;

        Vector3 res = Il2cpp::GetUnboxedValue<Vector3>(resObj);
        if (res.z <= 0.0f)
            return false; // Behind camera

        ImGuiIO &io = ImGui::GetIO();
        screenPos.x = res.x;
        screenPos.y = io.DisplaySize.y - res.y; // Invert Unity Y to ImGui screen Y
        return true;
    }

    void DrawOverlay()
    {
        if (!s_ESPEnabled)
            return;

        ImDrawList *drawList = ImGui::GetBackgroundDrawList();
        if (!drawList)
            return;

        ImGuiIO &io = ImGui::GetIO();
        ImVec2 screenBottom(io.DisplaySize.x * 0.5f, io.DisplaySize.y);

        // Watermark indicator when ESP is active
        drawList->AddText(ImVec2(10.0f, io.DisplaySize.y - 30.0f),
                          IM_COL32(0, 255, 180, 200),
                          "[MLBB ESP Active]");
    }

    void DrawSettings()
    {
        ImGui::TextColored(ImVec4(0.2f, 0.9f, 1.0f, 1.0f), "Hero ESP & WorldToScreen Visuals");
        ImGui::Separator();

        bool esp = s_ESPEnabled;
        if (ImGui::Checkbox("Enable Hero ESP Overlay", &esp))
        {
            SetESPEnabled(esp);
        }

        if (s_ESPEnabled)
        {
            ImGui::Indent();
            ImGui::Checkbox("Show 2D Player Box", &s_BoxEnabled);
            ImGui::Checkbox("Show Snaplines", &s_LineEnabled);
            ImGui::Checkbox("Show Health Bar", &s_HealthBarEnabled);

            ImGui::ColorEdit4("Box Color", (float *)&s_BoxColor, ImGuiColorEditFlags_NoInputs);
            ImGui::ColorEdit4("Line Color", (float *)&s_LineColor, ImGuiColorEditFlags_NoInputs);
            ImGui::Unindent();
        }
    }
}
