#pragma once

#include <string>
#include <functional>

namespace DumpExporter
{
    struct ExportProgress
    {
        float progress = 0.0f;
        std::string currentItem;
        int currentClass = 0;
        int totalClasses = 0;
    };

    typedef std::function<void(const ExportProgress &)> ProgressCallback;

    bool ExportDumpCs(const std::string &outPath, ProgressCallback cb = nullptr);
    bool ExportIdaScript(const std::string &outPath, ProgressCallback cb = nullptr);
    bool ExportGhidraScript(const std::string &outPath, ProgressCallback cb = nullptr);
    bool ExportGameGuardianScript(const std::string &outPath, ProgressCallback cb = nullptr);
} // namespace DumpExporter
