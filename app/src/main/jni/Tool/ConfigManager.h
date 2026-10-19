#pragma once

#include <string>

namespace ConfigManager
{
    void Init();
    void Save();
    void Load();
    std::string GetConfigPath();
} // namespace ConfigManager
