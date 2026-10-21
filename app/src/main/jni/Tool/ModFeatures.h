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

    // FPS Unlocker (90 / 120 / 144 / 165 / 240 FPS)
    bool IsFPSUnlockerEnabled();
    void SetFPSUnlockerEnabled(bool enabled);
    int GetTargetFPS();
    void SetTargetFPS(int fps);
    void ApplyTargetFPS(int fps);

    // Brutal Combat Damage & God Mode (All Heroes)
    bool IsBrutalDamageEnabled();
    void SetBrutalDamageEnabled(bool enabled);
    int GetBrutalDamageMultiplier();
    void SetBrutalDamageMultiplier(int mult);
    bool IsGodModeEnabled();
    void SetGodModeEnabled(bool enabled);
    bool IsMaxAttackSpeedEnabled();
    void SetMaxAttackSpeedEnabled(bool enabled);
    bool IsZeroCooldownEnabled();
    void SetZeroCooldownEnabled(bool enabled);
    void ApplyBrutalHeroStatsLive();
} // namespace ModFeatures
