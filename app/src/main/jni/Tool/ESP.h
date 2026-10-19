#pragma once
#include <cstdint>
#include <string>
#include "imgui.h"

namespace ESP
{
    struct Vector2
    {
        float x, y;
        Vector2() : x(0.0f), y(0.0f) {}
        Vector2(float _x, float _y) : x(_x), y(_y) {}
        Vector2(const ImVec2 &v) : x(v.x), y(v.y) {}
        operator ImVec2() const { return ImVec2(x, y); }
    };

    struct Vector3
    {
        float x, y, z;
        Vector3() : x(0.0f), y(0.0f), z(0.0f) {}
        Vector3(float _x, float _y, float _z) : x(_x), y(_y), z(_z) {}
    };

    struct TargetEntity
    {
        Vector3 worldPos;
        Vector2 screenPos;
        float hp;
        float maxHp;
        std::string name;
        float distance;
        bool isEnemy;
        bool visible;
    };

    void Init();
    void Update();
    void DrawOverlay();

    // Core ESP Feature Toggles
    bool IsESPEnabled();
    void SetESPEnabled(bool enabled);

    bool IsBoxEnabled();
    void SetBoxEnabled(bool enabled);

    bool IsCornerBoxEnabled();
    void SetCornerBoxEnabled(bool enabled);

    bool IsLineEnabled();
    void SetLineEnabled(bool enabled);

    bool IsHealthBarEnabled();
    void SetHealthBarEnabled(bool enabled);

    bool IsHeroNameEnabled();
    void SetHeroNameEnabled(bool enabled);

    bool IsDistanceEnabled();
    void SetDistanceEnabled(bool enabled);

    bool IsFOVCircleEnabled();
    void SetFOVCircleEnabled(bool enabled);
    float GetFOVCircleRadius();
    void SetFOVCircleRadius(float radius);

    bool IsCrosshairEnabled();
    void SetCrosshairEnabled(bool enabled);

    bool IsDemoTargetsEnabled();
    void SetDemoTargetsEnabled(bool enabled);

    // Snapline Origin: 0 = Bottom Center, 1 = Screen Center, 2 = Top Center
    int GetSnaplineOrigin();
    void SetSnaplineOrigin(int origin);

    float GetBoxThickness();
    void SetBoxThickness(float thickness);

    // World To Screen projection via Unity Camera
    bool WorldToScreen(const Vector3 &worldPos, Vector2 &screenPos);

    // UI Controls
    void DrawSettings();
}
