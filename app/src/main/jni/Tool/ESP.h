#pragma once
#include <cstdint>
#include <string>
#include "imgui.h"

namespace ESP
{
    struct Vector2
    {
        float x, y;
    };

    struct Vector3
    {
        float x, y, z;
    };

    void Init();
    void Update();
    void DrawOverlay();

    // ESP Feature Toggles
    bool IsESPEnabled();
    void SetESPEnabled(bool enabled);
    bool IsBoxEnabled();
    void SetBoxEnabled(bool enabled);
    bool IsLineEnabled();
    void SetLineEnabled(bool enabled);
    bool IsHealthBarEnabled();
    void SetHealthBarEnabled(bool enabled);

    // World To Screen projection via Unity Camera
    bool WorldToScreen(const Vector3 &worldPos, Vector2 &screenPos);

    // UI Controls
    void DrawSettings();
}
