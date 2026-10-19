#pragma once
#include <string>
#include <vector>

struct CombatEntry {
    const char *identifier;
    const char *exactName;
    const char *category;
    const char *offset;
    const char *source;
};

extern const std::vector<CombatEntry> g_CombatEntries;
extern const std::vector<const char*> g_CombatCategories;
