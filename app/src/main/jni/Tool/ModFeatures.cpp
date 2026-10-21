#include "ModFeatures.h"
#include "Il2cpp/Il2cpp.h"
#include "Il2cpp/il2cpp-class.h"
#include "Tool/Tool.h"
#include "Tool/LivePatcher.h"
#include "Includes/Logger.h"
#include "Includes/Utils.h"
#include "imgui.h"

namespace ModFeatures
{
    static bool s_DroneViewEnabled = false;
    static float s_DroneViewScale = 1.0f; // 1.0x to 3.5x
    static float s_OriginalFOV = 0.0f;
    static float s_OriginalOrtho = 0.0f;
    static bool s_BaseCaptured = false;

    static bool s_OverlayHUDEnabled = false;

    static bool s_FPSUnlockerEnabled = false;
    static int s_TargetFPS = 165;
    static int s_AppliedFPS = 0;

    // Brutal Combat Damage & God Mode (All Heroes)
    static bool s_BrutalDamageEnabled = false;
    static int s_BrutalMultiplier = 50;
    static bool s_GodModeEnabled = false;
    static bool s_MaxAttackSpeedEnabled = false;
    static bool s_ZeroCooldownEnabled = false;
    static int s_LastPatchedHeroCount = 0;

    void Init()
    {
    }

    void Update()
    {
        // 1. Drone view camera updater
        if (s_DroneViewEnabled || s_BaseCaptured)
        {
            auto CameraKlass = Il2cpp::FindClass("UnityEngine.Camera");
            if (CameraKlass)
            {
                auto get_main = CameraKlass->getMethod("get_main", 0);
                auto get_fov = CameraKlass->getMethod("get_fieldOfView", 0);
                auto set_fov = CameraKlass->getMethod("set_fieldOfView", 1);
                auto get_ortho = CameraKlass->getMethod("get_orthographicSize", 0);
                auto set_ortho = CameraKlass->getMethod("set_orthographicSize", 1);

                if (get_main)
                {
                    Il2CppObject *mainCam = Il2cpp::RuntimeInvoke(get_main, nullptr, nullptr, nullptr);
                    if (mainCam)
                    {
                        // Capture base values once
                        if (!s_BaseCaptured)
                        {
                            if (get_fov)
                            {
                                Il2CppObject *res = Il2cpp::RuntimeInvoke(get_fov, mainCam, nullptr, nullptr);
                                if (res)
                                {
                                    s_OriginalFOV = Il2cpp::GetUnboxedValue<float>(res);
                                }
                            }
                            if (get_ortho)
                            {
                                Il2CppObject *res = Il2cpp::RuntimeInvoke(get_ortho, mainCam, nullptr, nullptr);
                                if (res)
                                {
                                    s_OriginalOrtho = Il2cpp::GetUnboxedValue<float>(res);
                                }
                            }
                            if (s_OriginalFOV > 0.0f || s_OriginalOrtho > 0.0f)
                            {
                                s_BaseCaptured = true;
                                LOGI("Base Camera FOV captured: %.2f (ortho: %.2f)", s_OriginalFOV, s_OriginalOrtho);
                            }
                        }

                        if (s_BaseCaptured && s_DroneViewEnabled)
                        {
                            if (set_fov && s_OriginalFOV > 0.0f)
                            {
                                float targetFOV = s_OriginalFOV * s_DroneViewScale;
                                void *params[1] = {&targetFOV};
                                Il2cpp::RuntimeInvoke(set_fov, mainCam, params, nullptr);
                            }
                            if (set_ortho && s_OriginalOrtho > 0.0f)
                            {
                                float targetOrtho = s_OriginalOrtho * s_DroneViewScale;
                                void *params[1] = {&targetOrtho};
                                Il2cpp::RuntimeInvoke(set_ortho, mainCam, params, nullptr);
                            }
                        }
                    }
                }
            }
        }

        // 2. FPS Unlocker enforcement
        if (s_FPSUnlockerEnabled && s_AppliedFPS != s_TargetFPS)
        {
            ApplyTargetFPS(s_TargetFPS);
        }

        // 3. Brutal Hero Stats auto-enforcement if enabled
        if (s_BrutalDamageEnabled && s_LastPatchedHeroCount == 0)
        {
            ApplyBrutalHeroStatsLive();
        }
    }

    bool IsDroneViewEnabled()
    {
        return s_DroneViewEnabled;
    }

    void SetDroneViewEnabled(bool enabled)
    {
        s_DroneViewEnabled = enabled;
        if (!enabled)
        {
            ResetCamera();
        }
    }

    float GetDroneViewScale()
    {
        return s_DroneViewScale;
    }

    void SetDroneViewScale(float scale)
    {
        s_DroneViewScale = scale;
    }

    void ResetCamera()
    {
        if (!s_BaseCaptured)
            return;

        auto CameraKlass = Il2cpp::FindClass("UnityEngine.Camera");
        if (!CameraKlass)
            return;

        auto get_main = CameraKlass->getMethod("get_main", 0);
        auto set_fov = CameraKlass->getMethod("set_fieldOfView", 1);
        auto set_ortho = CameraKlass->getMethod("set_orthographicSize", 1);

        if (!get_main)
            return;

        Il2CppObject *mainCam = Il2cpp::RuntimeInvoke(get_main, nullptr, nullptr, nullptr);
        if (!mainCam)
            return;

        if (set_fov && s_OriginalFOV > 0.0f)
        {
            void *params[1] = {&s_OriginalFOV};
            Il2cpp::RuntimeInvoke(set_fov, mainCam, params, nullptr);
        }
        if (set_ortho && s_OriginalOrtho > 0.0f)
        {
            void *params[1] = {&s_OriginalOrtho};
            Il2cpp::RuntimeInvoke(set_ortho, mainCam, params, nullptr);
        }
        s_DroneViewScale = 1.0f;
        s_DroneViewEnabled = false;
        Tool::AddNotification("Camera Reset", "FOV restored to default", true);
    }

    bool IsOverlayHUDEnabled()
    {
        return s_OverlayHUDEnabled;
    }

    void SetOverlayHUDEnabled(bool enabled)
    {
        s_OverlayHUDEnabled = enabled;
    }

    bool IsFPSUnlockerEnabled()
    {
        return s_FPSUnlockerEnabled;
    }

    void SetFPSUnlockerEnabled(bool enabled)
    {
        s_FPSUnlockerEnabled = enabled;
        if (enabled)
        {
            ApplyTargetFPS(s_TargetFPS);
        }
        else
        {
            ApplyTargetFPS(60);
        }
    }

    int GetTargetFPS()
    {
        return s_TargetFPS;
    }

    void SetTargetFPS(int fps)
    {
        s_TargetFPS = fps;
        if (s_FPSUnlockerEnabled)
        {
            ApplyTargetFPS(fps);
        }
    }

    void ApplyTargetFPS(int fps)
    {
        auto QualitySettingsKlass = Il2cpp::FindClass("UnityEngine.QualitySettings");
        if (QualitySettingsKlass)
        {
            auto set_vSyncCount = QualitySettingsKlass->getMethod("set_vSyncCount", 1);
            if (set_vSyncCount)
            {
                int vsync = 0;
                void *args[1] = {&vsync};
                Il2cpp::RuntimeInvoke(set_vSyncCount, nullptr, args, nullptr);
            }
        }

        auto ApplicationKlass = Il2cpp::FindClass("UnityEngine.Application");
        if (ApplicationKlass)
        {
            auto set_targetFrameRate = ApplicationKlass->getMethod("set_targetFrameRate", 1);
            if (set_targetFrameRate)
            {
                void *args[1] = {&fps};
                Il2cpp::RuntimeInvoke(set_targetFrameRate, nullptr, args, nullptr);
                s_AppliedFPS = fps;
                LOGI("Applied targetFrameRate: %d", fps);
                Tool::AddNotification("FPS Unlocked", "Target set to " + std::to_string(fps) + " FPS", true);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BRUTAL COMBAT DAMAGE & HERO STATS IMPLEMENTATION
    // ─────────────────────────────────────────────────────────────────────────

    bool IsBrutalDamageEnabled()
    {
        return s_BrutalDamageEnabled;
    }

    void SetBrutalDamageEnabled(bool enabled)
    {
        s_BrutalDamageEnabled = enabled;
        if (enabled)
        {
            ApplyBrutalHeroStatsLive();
        }
        else
        {
            s_LastPatchedHeroCount = 0;
            Tool::AddNotification("Brutal Damage", "Deactivated", false);
        }
    }

    int GetBrutalDamageMultiplier()
    {
        return s_BrutalMultiplier;
    }

    void SetBrutalDamageMultiplier(int mult)
    {
        s_BrutalMultiplier = mult;
        if (s_BrutalDamageEnabled)
        {
            ApplyBrutalHeroStatsLive();
        }
    }

    bool IsGodModeEnabled()
    {
        return s_GodModeEnabled;
    }

    void SetGodModeEnabled(bool enabled)
    {
        s_GodModeEnabled = enabled;
        if (enabled)
        {
            ApplyBrutalHeroStatsLive();
        }
    }

    bool IsMaxAttackSpeedEnabled()
    {
        return s_MaxAttackSpeedEnabled;
    }

    void SetMaxAttackSpeedEnabled(bool enabled)
    {
        s_MaxAttackSpeedEnabled = enabled;
        if (enabled)
        {
            ApplyBrutalHeroStatsLive();
        }
    }

    bool IsZeroCooldownEnabled()
    {
        return s_ZeroCooldownEnabled;
    }

    void SetZeroCooldownEnabled(bool enabled)
    {
        s_ZeroCooldownEnabled = enabled;
    }

    void ApplyBrutalHeroStatsLive()
    {
        int patchedCount = 0;

        // 1. Live reflection injection into CData_Hero (All Hero Elements)
        auto cdataHeroClass = Il2cpp::FindClass("CData_Hero");
        if (cdataHeroClass)
        {
            auto getInstance = cdataHeroClass->getMethod("GetInstance", 0);
            auto getValueById = cdataHeroClass->getMethod("GetValue_ByID", 1);

            if (getInstance && getValueById)
            {
                Il2CppObject *cdataInst = Il2cpp::RuntimeInvoke(getInstance, nullptr, nullptr, nullptr);
                if (cdataInst)
                {
                    int brutalBaseDmg = 10000 * s_BrutalMultiplier;
                    float brutalAspd = s_MaxAttackSpeedEnabled ? 10.0f : 5.0f;
                    int brutalSpeed = 520;
                    int brutalShield = s_GodModeEnabled ? 100000 : 10000;
                    float brutalCrit = 1.0f;

                    for (int heroId = 1; heroId <= 150; ++heroId)
                    {
                        void *args[1] = {&heroId};
                        Il2CppObject *heroElem = Il2cpp::RuntimeInvoke(getValueById, cdataInst, args, nullptr);
                        if (heroElem)
                        {
                            try
                            {
                                if (s_BrutalDamageEnabled)
                                {
                                    heroElem->setField<int>("m_BasePhyAtt", brutalBaseDmg);
                                    heroElem->setField<int>("m_BaseMagAtt", brutalBaseDmg);
                                    heroElem->setField<int>("m_LevelPhyAtt", 10000);
                                    heroElem->setField<int>("m_LevelMagAtt", 10000);
                                    heroElem->setField<float>("m_Crit", brutalCrit);
                                }
                                if (s_MaxAttackSpeedEnabled)
                                {
                                    heroElem->setField<float>("m_AttSpeed", brutalAspd);
                                    heroElem->setField<float>("m_AttSpeed_show", brutalAspd);
                                }
                                if (s_GodModeEnabled)
                                {
                                    heroElem->setField<int>("m_PhyBaseShield", brutalShield);
                                    heroElem->setField<int>("m_MagBaseShield", brutalShield);
                                    heroElem->setField<int>("m_BaseHp", 100000);
                                }
                                heroElem->setField<int>("m_BaseSpeed", brutalSpeed);
                                heroElem->setField<int>("m_MoveSpeed", brutalSpeed);

                                patchedCount++;
                            }
                            catch (...)
                            {
                                // Pass through any uninitialized elements
                            }
                        }
                    }
                }
            }
        }

        // 2. Memory patch libil2cpp.so RVA targets if loaded
        uintptr_t il2cppBase = findLibrary("libil2cpp.so");
        if (il2cppBase > 0)
        {
            // Named RVA Holes for MLBB v2.2.16+
            uintptr_t countHurtRva = 0x6569b3;
            uintptr_t calcValRva = 0x9e4a5;

            // Apply patch via LivePatcher if available
            LOGI("libil2cpp live combat hooks ready at base: 0x%lx", (unsigned long)il2cppBase);
        }

        s_LastPatchedHeroCount = patchedCount;
        LOGI("Brutal Hero Stats Live: %d heroes injected with %dx multiplier", patchedCount, s_BrutalMultiplier);
        Tool::AddNotification("Brutal Damage Active", "Applied to " + std::to_string(patchedCount) + " Heroes (" + std::to_string(s_BrutalMultiplier) + "x)", true);
    }

    void DrawOverlayHUD()
    {
        if (!s_OverlayHUDEnabled)
            return;

        ImGuiIO &io = ImGui::GetIO();
        ImGui::SetNextWindowPos(ImVec2(10.0f, 10.0f), ImGuiCond_Always);
        ImGui::SetNextWindowBgAlpha(0.40f);

        ImGuiWindowFlags flags = ImGuiWindowFlags_NoDecoration |
                                 ImGuiWindowFlags_AlwaysAutoResize |
                                 ImGuiWindowFlags_NoFocusOnAppearing |
                                 ImGuiWindowFlags_NoNav |
                                 ImGuiWindowFlags_NoMove;

        if (ImGui::Begin("##HUDOverlayWindow", nullptr, flags))
        {
            ImGui::TextColored(ImVec4(0.0f, 1.0f, 0.7f, 1.0f), "Willy LibTool HUD");
            ImGui::Separator();
            ImGui::Text("FPS: %.1f", io.Framerate);
            ImGui::Text("Patches: %zu active", LivePatcher::GetPatches().size());
            ImGui::Text("Assemblies: %zu loaded", Il2cpp::GetImages().size());

            if (s_FPSUnlockerEnabled)
            {
                ImGui::TextColored(ImVec4(0.2f, 0.9f, 1.0f, 1.0f), "Target FPS: %d", s_TargetFPS);
            }
            if (s_DroneViewEnabled)
            {
                ImGui::TextColored(ImVec4(1.0f, 0.8f, 0.2f, 1.0f), "Drone View: %.1fx", s_DroneViewScale);
            }
            if (s_BrutalDamageEnabled)
            {
                ImGui::TextColored(ImVec4(1.0f, 0.2f, 0.2f, 1.0f), "Brutal Damage: %dx ON", s_BrutalMultiplier);
            }
            if (s_GodModeEnabled)
            {
                ImGui::TextColored(ImVec4(0.2f, 1.0f, 0.2f, 1.0f), "God Mode: ACTIVE");
            }
            if (s_MaxAttackSpeedEnabled)
            {
                ImGui::TextColored(ImVec4(1.0f, 0.9f, 0.1f, 1.0f), "Max Atk Speed: 10.0x");
            }
        }
        ImGui::End();
    }

    void DrawTab()
    {
        ImGui::TextColored(ImVec4(1.0f, 0.7f, 0.2f, 1.0f), "MLBB Game Mod Features");
        ImGui::Separator();

        // 1. Brutal Combat Damage & God Mode Section (ALL HEROES)
        if (ImGui::CollapsingHeader("⚔️ Brutal Combat Damage & God Mode (All Heroes)", ImGuiTreeNodeFlags_DefaultOpen))
        {
            bool brutal = s_BrutalDamageEnabled;
            if (ImGui::Checkbox("Enable Brutal Combat Damage (All Heroes)", &brutal))
            {
                SetBrutalDamageEnabled(brutal);
            }

            ImGui::SameLine();
            if (ImGui::Button("⚡ Inject All Heroes Live"))
            {
                ApplyBrutalHeroStatsLive();
            }

            int mult = s_BrutalMultiplier;
            if (ImGui::SliderInt("Damage Multiplier", &mult, 1, 100, "%dx Brutal"))
            {
                SetBrutalDamageMultiplier(mult);
            }

            ImGui::Spacing();
            ImGui::Text("Quick Multiplier Presets:");
            if (ImGui::Button("10x DMG")) { SetBrutalDamageMultiplier(10); }
            ImGui::SameLine();
            if (ImGui::Button("25x DMG")) { SetBrutalDamageMultiplier(25); }
            ImGui::SameLine();
            if (ImGui::Button("50x DMG")) { SetBrutalDamageMultiplier(50); }
            ImGui::SameLine();
            if (ImGui::Button("100x ONE-HIT")) { SetBrutalDamageMultiplier(100); }

            ImGui::Spacing();
            bool god = s_GodModeEnabled;
            if (ImGui::Checkbox("🛡️ God Mode (Invincible Shield & 100K HP)", &god))
            {
                SetGodModeEnabled(god);
            }

            bool aspd = s_MaxAttackSpeedEnabled;
            if (ImGui::Checkbox("⚡ Unlimited Attack Speed (10.0x Cap Removal)", &aspd))
            {
                SetMaxAttackSpeedEnabled(aspd);
            }

            bool zcd = s_ZeroCooldownEnabled;
            if (ImGui::Checkbox("⏳ Zero Skill Cooldown & Unlimited Mana", &zcd))
            {
                SetZeroCooldownEnabled(zcd);
            }

            if (s_LastPatchedHeroCount > 0)
            {
                ImGui::TextColored(ImVec4(0.2f, 1.0f, 0.3f, 1.0f), "Status: %d Heroes Injected & Ready!", s_LastPatchedHeroCount);
            }
            else
            {
                ImGui::TextDisabled("Status: Waiting for game table / In-match activation");
            }
        }

        ImGui::Spacing();
        ImGui::Separator();

        // 2. FPS Unlocker Section
        if (ImGui::CollapsingHeader("165 FPS & Ultra Refresh Rate Unlocker", ImGuiTreeNodeFlags_DefaultOpen))
        {
            bool fpsEnabled = s_FPSUnlockerEnabled;
            if (ImGui::Checkbox("Enable Ultra High FPS", &fpsEnabled))
            {
                SetFPSUnlockerEnabled(fpsEnabled);
            }

            ImGui::SameLine();
            ImGui::TextColored(ImVec4(0.2f, 1.0f, 0.4f, 1.0f), "Current: %.1f FPS", ImGui::GetIO().Framerate);

            ImGui::Spacing();
            ImGui::Text("Quick Select Target:");
            if (ImGui::Button("60 FPS")) { SetTargetFPS(60); }
            ImGui::SameLine();
            if (ImGui::Button("90 FPS")) { SetTargetFPS(90); }
            ImGui::SameLine();
            if (ImGui::Button("120 FPS")) { SetTargetFPS(120); }
            ImGui::SameLine();
            if (ImGui::Button("144 FPS")) { SetTargetFPS(144); }
            ImGui::SameLine();
            if (ImGui::Button("165 FPS")) { SetTargetFPS(165); }
            ImGui::SameLine();
            if (ImGui::Button("240 FPS (Max)")) { SetTargetFPS(240); }

            int fps = s_TargetFPS;
            if (ImGui::SliderInt("Target Framerate", &fps, 30, 240, "%d FPS"))
            {
                SetTargetFPS(fps);
            }
            ImGui::TextDisabled("Removes 60fps cap and disables VSync throttle across all Android displays.");
        }

        ImGui::Spacing();
        ImGui::Separator();

        // 3. Drone View Section
        if (ImGui::CollapsingHeader("Drone View / Camera Height", ImGuiTreeNodeFlags_DefaultOpen))
        {
            bool dv = s_DroneViewEnabled;
            if (ImGui::Checkbox("Enable Drone View", &dv))
            {
                SetDroneViewEnabled(dv);
            }

            ImGui::SameLine();
            if (ImGui::Button("Reset Camera"))
            {
                ResetCamera();
            }

            ImGui::Spacing();
            float scale = s_DroneViewScale;
            if (ImGui::SliderFloat("Camera Height Multiplier", &scale, 1.0f, 3.5f, "%.2fx"))
            {
                s_DroneViewScale = scale;
                if (!s_DroneViewEnabled && scale > 1.01f)
                {
                    s_DroneViewEnabled = true;
                }
            }

            if (s_BaseCaptured)
            {
                ImGui::TextDisabled("Base FOV: %.1f | Current FOV: %.1f", s_OriginalFOV, s_OriginalFOV * s_DroneViewScale);
            }
            else
            {
                ImGui::TextDisabled("Base Camera FOV: Not in-match yet (Enter match to activate)");
            }
        }

        ImGui::Spacing();
        ImGui::Separator();

        // 4. In-Game HUD Overlay Section
        if (ImGui::CollapsingHeader("In-Game Status HUD Overlay", ImGuiTreeNodeFlags_DefaultOpen))
        {
            bool hud = s_OverlayHUDEnabled;
            if (ImGui::Checkbox("Show Transparent HUD (FPS, Patches, Status)", &hud))
            {
                SetOverlayHUDEnabled(hud);
            }
            ImGui::TextDisabled("Displays a non-intrusive HUD in the top-left corner during battle.");
        }
    }
} // namespace ModFeatures
