#pragma once

#include <string>

namespace ModFeatures
{
    void Init();
    void Update();
    void DrawTab();
    void DrawOverlayHUD();

    // Drone view settings
    bool IsDroneViewEnabled();
    void SetDroneViewEnabled(bool enabled);
    float GetDroneViewScale();
    void SetDroneViewScale(float scale);
    void ResetCamera();

    // HUD overlay settings
    bool IsOverlayHUDEnabled();
    void SetOverlayHUDEnabled(bool enabled);

    // FPS Unlocker (90 / 120 / 144 / 240 FPS)
    bool IsFPSUnlockerEnabled();
    void SetFPSUnlockerEnabled(bool enabled);
    int GetTargetFPS();
    void SetTargetFPS(int fps);
    void ApplyTargetFPS(int fps);
} // namespace ModFeatures
