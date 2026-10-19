#include "ConfigManager.h"
#include "Tool/ModFeatures.h"
#include "Tool/Stealth.h"
#include "Tool/LivePatcher.h"
#include "Tool/Tool.h"
#include "Includes/Logger.h"
#include "Includes/Utils.h"

#include <fstream>
#include <sstream>
#include <unistd.h>
#include <sys/stat.h>

namespace ConfigManager
{
    static std::string s_ConfigPath = "/sdcard/Download/libtool_config.txt";

    void Init()
    {
        // Try /sdcard/Download/ first
        if (access("/sdcard/Download", W_OK) == 0)
        {
            s_ConfigPath = "/sdcard/Download/libtool_config.txt";
        }
        else
        {
            s_ConfigPath = "/data/data/com.mobile.legends/files/libtool_config.txt";
        }
    }

    std::string GetConfigPath()
    {
        return s_ConfigPath;
    }

    void Save()
    {
        std::ofstream ofs(s_ConfigPath);
        if (!ofs.is_open())
        {
            LOGE("ConfigManager: Failed to save config to %s", s_ConfigPath.c_str());
            Tool::AddNotification("Save Failed", "Could not write to storage", false);
            return;
        }

        ofs << "# Android-LibTool Config\n";
        ofs << "drone_scale=" << ModFeatures::GetDroneViewScale() << "\n";
        ofs << "drone_enabled=" << (ModFeatures::IsDroneViewEnabled() ? "1" : "0") << "\n";
        ofs << "hud_enabled=" << (ModFeatures::IsOverlayHUDEnabled() ? "1" : "0") << "\n";
        ofs << "stealth_enabled=" << (Stealth::IsCloakEnabled() ? "1" : "0") << "\n";

        auto patches = LivePatcher::GetPatches();
        ofs << "# Patches: name|module|rva|hex|active\n";
        for (const auto &p : patches)
        {
            ofs << "patch=" << p.name << "|"
                << p.moduleName << "|"
                << std::hex << p.rva << "|"
                << p.patchHex << "|"
                << (p.active ? "1" : "0") << "\n";
        }

        ofs.close();
        LOGI("ConfigManager: Successfully saved config to %s", s_ConfigPath.c_str());
        Tool::AddNotification("Config Saved", s_ConfigPath, true);
    }

    void Load()
    {
        std::ifstream ifs(s_ConfigPath);
        if (!ifs.is_open())
        {
            LOGI("ConfigManager: No config file found at %s", s_ConfigPath.c_str());
            return;
        }

        std::string line;
        int restoredPatches = 0;

        while (std::getline(ifs, line))
        {
            if (line.empty() || line[0] == '#')
                continue;

            auto eq = line.find('=');
            if (eq == std::string::npos)
                continue;

            std::string key = line.substr(0, eq);
            std::string val = line.substr(eq + 1);

            if (key == "drone_scale")
            {
                try {
                    ModFeatures::SetDroneViewScale(std::stof(val));
                } catch (...) {}
            }
            else if (key == "drone_enabled")
            {
                ModFeatures::SetDroneViewEnabled(val == "1");
            }
            else if (key == "hud_enabled")
            {
                ModFeatures::SetOverlayHUDEnabled(val == "1");
            }
            else if (key == "stealth_enabled")
            {
                Stealth::SetCloakEnabled(val == "1");
            }
            else if (key == "patch")
            {
                // Format: name|module|rva|hex|active
                std::stringstream ss(val);
                std::string pName, pMod, pRvaStr, pHex, pActive;
                if (std::getline(ss, pName, '|') &&
                    std::getline(ss, pMod, '|') &&
                    std::getline(ss, pRvaStr, '|') &&
                    std::getline(ss, pHex, '|') &&
                    std::getline(ss, pActive))
                {
                    uintptr_t rva = 0;
                    try {
                        rva = (uintptr_t)std::stoull(pRvaStr, nullptr, 16);
                    } catch (...) {}

                    if (rva != 0 && pActive == "1")
                    {
                        uintptr_t modBase = getAbsoluteAddress(pMod.c_str(), 0);
                        if (modBase != 0)
                        {
                            uintptr_t targetAddr = modBase + rva;
                            if (LivePatcher::PatchHex(targetAddr, pHex, pName, pMod, rva))
                            {
                                restoredPatches++;
                            }
                        }
                    }
                }
            }
        }

        ifs.close();
        LOGI("ConfigManager: Loaded config. Restored %d patches.", restoredPatches);
        Tool::AddNotification("Config Loaded", "Restored " + std::to_string(restoredPatches) + " patches", true);
    }
} // namespace ConfigManager
