#include "ESP.h"
#include "Il2cpp/Il2cpp.h"
#include "Il2cpp/il2cpp-class.h"
#include "Includes/Logger.h"
#include "imgui.h"

#include <vector>
#include <string>
#include <cmath>
#include <algorithm>

namespace ESP
{
    // Feature Toggles
    static bool s_ESPEnabled = false;
    static bool s_BoxEnabled = true;
    static bool s_CornerBoxEnabled = true;
    static bool s_LineEnabled = true;
    static bool s_HealthBarEnabled = true;
    static bool s_HeroNameEnabled = true;
    static bool s_DistanceEnabled = true;
    static bool s_FOVCircleEnabled = false;
    static float s_FOVCircleRadius = 150.0f;
    static bool s_CrosshairEnabled = false;
    static bool s_DemoTargetsEnabled = true;

    // Appearance
    static int s_SnaplineOrigin = 0; // 0 = Bottom, 1 = Center, 2 = Top
    static float s_BoxThickness = 1.8f;
    static ImVec4 s_EnemyBoxColor = ImVec4(1.0f, 0.25f, 0.25f, 0.95f);
    static ImVec4 s_FriendlyBoxColor = ImVec4(0.2f, 0.85f, 1.0f, 0.95f);
    static ImVec4 s_LineColor = ImVec4(0.2f, 0.95f, 1.0f, 0.75f);
    static ImVec4 s_FOVCircleColor = ImVec4(0.0f, 1.0f, 0.6f, 0.6f);

    // Demo simulation state
    static float s_SimTime = 0.0f;

    void Init()
    {
    }

    void Update()
    {
        if (s_ESPEnabled && s_DemoTargetsEnabled)
        {
            s_SimTime += ImGui::GetIO().DeltaTime;
        }
    }

    bool IsESPEnabled() { return s_ESPEnabled; }
    void SetESPEnabled(bool enabled) { s_ESPEnabled = enabled; }

    bool IsBoxEnabled() { return s_BoxEnabled; }
    void SetBoxEnabled(bool enabled) { s_BoxEnabled = enabled; }

    bool IsCornerBoxEnabled() { return s_CornerBoxEnabled; }
    void SetCornerBoxEnabled(bool enabled) { s_CornerBoxEnabled = enabled; }

    bool IsLineEnabled() { return s_LineEnabled; }
    void SetLineEnabled(bool enabled) { s_LineEnabled = enabled; }

    bool IsHealthBarEnabled() { return s_HealthBarEnabled; }
    void SetHealthBarEnabled(bool enabled) { s_HealthBarEnabled = enabled; }

    bool IsHeroNameEnabled() { return s_HeroNameEnabled; }
    void SetHeroNameEnabled(bool enabled) { s_HeroNameEnabled = enabled; }

    bool IsDistanceEnabled() { return s_DistanceEnabled; }
    void SetDistanceEnabled(bool enabled) { s_DistanceEnabled = enabled; }

    bool IsFOVCircleEnabled() { return s_FOVCircleEnabled; }
    void SetFOVCircleEnabled(bool enabled) { s_FOVCircleEnabled = enabled; }
    float GetFOVCircleRadius() { return s_FOVCircleRadius; }
    void SetFOVCircleRadius(float radius) { s_FOVCircleRadius = radius; }

    bool IsCrosshairEnabled() { return s_CrosshairEnabled; }
    void SetCrosshairEnabled(bool enabled) { s_CrosshairEnabled = enabled; }

    bool IsDemoTargetsEnabled() { return s_DemoTargetsEnabled; }
    void SetDemoTargetsEnabled(bool enabled) { s_DemoTargetsEnabled = enabled; }

    int GetSnaplineOrigin() { return s_SnaplineOrigin; }
    void SetSnaplineOrigin(int origin) { s_SnaplineOrigin = origin; }

    float GetBoxThickness() { return s_BoxThickness; }
    void SetBoxThickness(float thickness) { s_BoxThickness = thickness; }

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

    // ─────────────────────────────────────────────────────────────────────────
    // ESP DRAWING PRIMITIVES
    // ─────────────────────────────────────────────────────────────────────────

    static void DrawCornerBox(ImDrawList *drawList, ImVec2 min, ImVec2 max, ImU32 color, float thickness)
    {
        float w = max.x - min.x;
        float h = max.y - min.y;
        float cornerW = std::clamp(w * 0.25f, 6.0f, 25.0f);
        float cornerH = std::clamp(h * 0.25f, 6.0f, 25.0f);

        // Black outer shadow for high contrast on bright battlefields
        ImU32 shadow = IM_COL32(0, 0, 0, 160);
        float st = thickness + 1.5f;

        // Top-Left
        drawList->AddLine(ImVec2(min.x, min.y), ImVec2(min.x + cornerW, min.y), shadow, st);
        drawList->AddLine(ImVec2(min.x, min.y), ImVec2(min.x, min.y + cornerH), shadow, st);
        drawList->AddLine(ImVec2(min.x, min.y), ImVec2(min.x + cornerW, min.y), color, thickness);
        drawList->AddLine(ImVec2(min.x, min.y), ImVec2(min.x, min.y + cornerH), color, thickness);

        // Top-Right
        drawList->AddLine(ImVec2(max.x, min.y), ImVec2(max.x - cornerW, min.y), shadow, st);
        drawList->AddLine(ImVec2(max.x, min.y), ImVec2(max.x, min.y + cornerH), shadow, st);
        drawList->AddLine(ImVec2(max.x, min.y), ImVec2(max.x - cornerW, min.y), color, thickness);
        drawList->AddLine(ImVec2(max.x, min.y), ImVec2(max.x, min.y + cornerH), color, thickness);

        // Bottom-Left
        drawList->AddLine(ImVec2(min.x, max.y), ImVec2(min.x + cornerW, max.y), shadow, st);
        drawList->AddLine(ImVec2(min.x, max.y), ImVec2(min.x, max.y - cornerH), shadow, st);
        drawList->AddLine(ImVec2(min.x, max.y), ImVec2(min.x + cornerW, max.y), color, thickness);
        drawList->AddLine(ImVec2(min.x, max.y), ImVec2(min.x, max.y - cornerH), color, thickness);

        // Bottom-Right
        drawList->AddLine(ImVec2(max.x, max.y), ImVec2(max.x - cornerW, max.y), shadow, st);
        drawList->AddLine(ImVec2(max.x, max.y), ImVec2(max.x, max.y - cornerH), shadow, st);
        drawList->AddLine(ImVec2(max.x, max.y), ImVec2(max.x - cornerW, max.y), color, thickness);
        drawList->AddLine(ImVec2(max.x, max.y), ImVec2(max.x, max.y - cornerH), color, thickness);
    }

    static void DrawFullBox(ImDrawList *drawList, ImVec2 min, ImVec2 max, ImU32 color, float thickness)
    {
        // Shadow outline
        drawList->AddRect(ImVec2(min.x - 1, min.y - 1), ImVec2(max.x + 1, max.y + 1), IM_COL32(0, 0, 0, 160), 0.0f, 0, thickness + 1.2f);
        // Main outline
        drawList->AddRect(min, max, color, 0.0f, 0, thickness);
    }

    static void DrawHealthBar(ImDrawList *drawList, ImVec2 min, ImVec2 max, float hpPercent)
    {
        float barWidth = 4.0f;
        float gap = 4.0f;
        float h = max.y - min.y;

        ImVec2 barMin = ImVec2(min.x - gap - barWidth, min.y);
        ImVec2 barMax = ImVec2(min.x - gap, max.y);

        // Dark background with black border
        drawList->AddRectFilled(barMin, barMax, IM_COL32(20, 20, 20, 180));
        drawList->AddRect(ImVec2(barMin.x - 1, barMin.y - 1), ImVec2(barMax.x + 1, barMax.y + 1), IM_COL32(0, 0, 0, 220), 0.0f, 0, 1.0f);

        // Health fill (drawn from bottom up)
        float currentH = h * std::clamp(hpPercent, 0.0f, 1.0f);
        ImVec2 fillMin = ImVec2(barMin.x, max.y - currentH);
        ImVec2 fillMax = barMax;

        // Dynamic HP color gradient (Green -> Yellow -> Red)
        ImU32 hpColor;
        if (hpPercent > 0.5f)
        {
            hpColor = IM_COL32(50, 230, 80, 240);
        }
        else if (hpPercent > 0.25f)
        {
            hpColor = IM_COL32(240, 200, 40, 240);
        }
        else
        {
            hpColor = IM_COL32(255, 45, 45, 240);
        }

        drawList->AddRectFilled(fillMin, fillMax, hpColor);
    }

    static void DrawHeroInfo(ImDrawList *drawList, ImVec2 pos, const char *name, float distance, ImU32 color)
    {
        char buf[64]{0};
        if (distance > 0.0f)
        {
            snprintf(buf, sizeof(buf), "%s [%.0fm]", name, distance);
        }
        else
        {
            snprintf(buf, sizeof(buf), "%s", name);
        }

        ImVec2 txtSize = ImGui::CalcTextSize(buf);
        ImVec2 drawPos = ImVec2(pos.x - txtSize.x * 0.5f, pos.y - txtSize.y - 2.0f);

        // Dark background pill for readable text
        ImVec2 bgMin = ImVec2(drawPos.x - 4.0f, drawPos.y - 2.0f);
        ImVec2 bgMax = ImVec2(drawPos.x + txtSize.x + 4.0f, drawPos.y + txtSize.y + 2.0f);
        drawList->AddRectFilled(bgMin, bgMax, IM_COL32(10, 15, 20, 180), 3.0f);

        // Black drop-shadow
        drawList->AddText(ImVec2(drawPos.x + 1, drawPos.y + 1), IM_COL32(0, 0, 0, 255), buf);
        // Colored text
        drawList->AddText(drawPos, color, buf);
    }

    void DrawOverlay()
    {
        if (!s_ESPEnabled)
            return;

        ImDrawList *drawList = ImGui::GetBackgroundDrawList();
        if (!drawList)
            return;

        ImGuiIO &io = ImGui::GetIO();
        ImVec2 screenCenter = ImVec2(io.DisplaySize.x * 0.5f, io.DisplaySize.y * 0.5f);

        // 1. Crosshair & FOV Circle
        if (s_FOVCircleEnabled)
        {
            ImU32 fovCol = ImGui::ColorConvertFloat4ToU32(s_FOVCircleColor);
            drawList->AddCircle(screenCenter, s_FOVCircleRadius, fovCol, 64, 1.5f);
        }

        if (s_CrosshairEnabled)
        {
            float chSize = 12.0f;
            ImU32 chCol = IM_COL32(0, 255, 180, 220);
            drawList->AddLine(ImVec2(screenCenter.x - chSize, screenCenter.y), ImVec2(screenCenter.x + chSize, screenCenter.y), chCol, 1.5f);
            drawList->AddLine(ImVec2(screenCenter.x, screenCenter.y - chSize), ImVec2(screenCenter.x, screenCenter.y + chSize), chCol, 1.5f);
        }

        // 2. Active Targets (Demo Mode or Live Match)
        std::vector<TargetEntity> targets;

        if (s_DemoTargetsEnabled)
        {
            // Simulate 3 animated enemy heroes in realistic battlefield positions
            float t = s_SimTime;
            float cx = io.DisplaySize.x * 0.5f;
            float cy = io.DisplaySize.y * 0.5f;

            // Target 1: Enemy Fanny (Swift moving)
            TargetEntity t1;
            t1.screenPos = Vector2(cx - 280.0f + sinf(t * 1.5f) * 120.0f, cy - 140.0f + cosf(t * 1.2f) * 60.0f);
            t1.name = "Fanny";
            t1.distance = 11.5f + sinf(t * 0.5f) * 3.0f;
            t1.hp = 0.72f;
            t1.maxHp = 1.0f;
            t1.isEnemy = true;
            t1.visible = true;
            targets.push_back(t1);

            // Target 2: Enemy Ling (Collector Seraphic)
            TargetEntity t2;
            t2.screenPos = Vector2(cx + 260.0f + cosf(t * 1.1f) * 100.0f, cy - 100.0f + sinf(t * 1.3f) * 50.0f);
            t2.name = "Ling";
            t2.distance = 15.2f + cosf(t * 0.7f) * 2.5f;
            t2.hp = 0.38f;
            t2.maxHp = 1.0f;
            t2.isEnemy = true;
            t2.visible = true;
            targets.push_back(t2);

            // Target 3: Friendly Gusion (Support / ally)
            TargetEntity t3;
            t3.screenPos = Vector2(cx - 90.0f + sinf(t * 0.8f) * 60.0f, cy + 180.0f + cosf(t * 0.9f) * 30.0f);
            t3.name = "Gusion [Ally]";
            t3.distance = 6.4f;
            t3.hp = 0.95f;
            t3.maxHp = 1.0f;
            t3.isEnemy = false;
            t3.visible = true;
            targets.push_back(t3);
        }

        // Snapline origin point
        ImVec2 lineOrigin;
        if (s_SnaplineOrigin == 0)
        {
            lineOrigin = ImVec2(io.DisplaySize.x * 0.5f, io.DisplaySize.y); // Bottom Center
        }
        else if (s_SnaplineOrigin == 1)
        {
            lineOrigin = screenCenter; // Screen Center
        }
        else
        {
            lineOrigin = ImVec2(io.DisplaySize.x * 0.5f, 0.0f); // Top Center
        }

        ImU32 enemyCol = ImGui::ColorConvertFloat4ToU32(s_EnemyBoxColor);
        ImU32 friendCol = ImGui::ColorConvertFloat4ToU32(s_FriendlyBoxColor);
        ImU32 lineCol = ImGui::ColorConvertFloat4ToU32(s_LineColor);

        for (const auto &tgt : targets)
        {
            if (!tgt.visible)
                continue;

            // Box dimensions scaled by distance
            float boxH = std::clamp(65.0f * (15.0f / tgt.distance), 40.0f, 130.0f);
            float boxW = boxH * 0.58f;

            ImVec2 boxMin = ImVec2(tgt.screenPos.x - boxW * 0.5f, tgt.screenPos.y - boxH * 0.5f);
            ImVec2 boxMax = ImVec2(tgt.screenPos.x + boxW * 0.5f, tgt.screenPos.y + boxH * 0.5f);

            ImU32 targetColor = tgt.isEnemy ? enemyCol : friendCol;

            // 1. Snapline
            if (s_LineEnabled)
            {
                drawList->AddLine(lineOrigin, ImVec2(tgt.screenPos.x, boxMax.y), lineCol, 1.2f);
            }

            // 2. 2D Box (Corner or Full)
            if (s_BoxEnabled)
            {
                if (s_CornerBoxEnabled)
                {
                    DrawCornerBox(drawList, boxMin, boxMax, targetColor, s_BoxThickness);
                }
                else
                {
                    DrawFullBox(drawList, boxMin, boxMax, targetColor, s_BoxThickness);
                }
            }

            // 3. Health Bar
            if (s_HealthBarEnabled)
            {
                DrawHealthBar(drawList, boxMin, boxMax, tgt.hp / tgt.maxHp);
            }

            // 4. Hero Name & Distance Tag
            if (s_HeroNameEnabled || s_DistanceEnabled)
            {
                DrawHeroInfo(drawList, boxMin, tgt.name.c_str(), s_DistanceEnabled ? tgt.distance : 0.0f, targetColor);
            }
        }

        // ESP Active Watermark indicator in top center
        const char *watermark = s_DemoTargetsEnabled ? "● MLBB ESP ACTIVE [PREVIEW MODE]" : "● MLBB ESP ACTIVE [MATCH READY]";
        ImVec2 wmSize = ImGui::CalcTextSize(watermark);
        ImVec2 wmPos = ImVec2((io.DisplaySize.x - wmSize.x) * 0.5f, 25.0f);
        drawList->AddRectFilled(ImVec2(wmPos.x - 8, wmPos.y - 3), ImVec2(wmPos.x + wmSize.x + 8, wmPos.y + wmSize.y + 3), IM_COL32(10, 20, 25, 170), 4.0f);
        drawList->AddText(wmPos, IM_COL32(0, 255, 180, 240), watermark);
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
            ImGui::Spacing();
            ImGui::Indent();

            // Demo targets toggle
            ImGui::Checkbox("★ Preview / Demo Targets (Test On-Screen Now)", &s_DemoTargetsEnabled);
            ImGui::TextDisabled("Displays simulated enemy heroes so you can preview boxes and lines immediately.");

            ImGui::Spacing();
            ImGui::TextColored(ImVec4(1.0f, 0.8f, 0.3f, 1.0f), "Visual Elements:");
            ImGui::Checkbox("2D Hero Box", &s_BoxEnabled);
            if (s_BoxEnabled)
            {
                ImGui::SameLine();
                ImGui::Checkbox("Corner Box Style", &s_CornerBoxEnabled);
                ImGui::SliderFloat("Box Thickness", &s_BoxThickness, 1.0f, 4.0f, "%.1f px");
            }

            ImGui::Checkbox("Snaplines", &s_LineEnabled);
            if (s_LineEnabled)
            {
                const char *origins[] = {"Bottom Center", "Screen Center", "Top Center"};
                ImGui::Combo("Line Origin", &s_SnaplineOrigin, origins, IM_ARRAYSIZE(origins));
            }

            ImGui::Checkbox("Health Bar", &s_HealthBarEnabled);
            ImGui::Checkbox("Hero Names", &s_HeroNameEnabled);
            ImGui::Checkbox("Distance Tags", &s_DistanceEnabled);

            ImGui::Spacing();
            ImGui::TextColored(ImVec4(1.0f, 0.8f, 0.3f, 1.0f), "Crosshair & Aim FOV:");
            ImGui::Checkbox("Center Crosshair", &s_CrosshairEnabled);
            ImGui::Checkbox("Aim FOV Circle", &s_FOVCircleEnabled);
            if (s_FOVCircleEnabled)
            {
                ImGui::SliderFloat("FOV Radius", &s_FOVCircleRadius, 50.0f, 450.0f, "%.0f px");
            }

            ImGui::Spacing();
            ImGui::TextColored(ImVec4(1.0f, 0.8f, 0.3f, 1.0f), "Palette Colors:");
            ImGui::ColorEdit4("Enemy Color", (float *)&s_EnemyBoxColor, ImGuiColorEditFlags_NoInputs);
            ImGui::ColorEdit4("Line Color", (float *)&s_LineColor, ImGuiColorEditFlags_NoInputs);
            ImGui::ColorEdit4("FOV Circle Color", (float *)&s_FOVCircleColor, ImGuiColorEditFlags_NoInputs);

            ImGui::Unindent();
        }
    }
}
