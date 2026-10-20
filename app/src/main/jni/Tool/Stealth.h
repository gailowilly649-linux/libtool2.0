#pragma once

#include <string>
#include <vector>

namespace Stealth
{
    void Init();
    void SetCloakEnabled(bool enabled);
    bool IsCloakEnabled();
    void SetAntiDebugBypassEnabled(bool enabled);
    bool IsAntiDebugBypassEnabled();
    void AddHiddenKeyword(const std::string &keyword);
    const std::vector<std::string>& GetHiddenKeywords();
    void DrawSettings();
    void SpoofBuildProperties(void *env);
} // namespace Stealth
