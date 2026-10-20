#include "Stealth.h"
#include "Includes/Logger.h"
#include "Tool/Tool.h"
#include "Dobby/include/dobby.h"
#include "imgui.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <signal.h>
#include <dlfcn.h>
#include <sys/system_properties.h>
#include <mutex>
#include <string>
#include <vector>

namespace Stealth
{
    static bool s_Initialized = false;
    static bool s_CloakEnabled = true;
    static bool s_AntiDebugBypassEnabled = true;

    static std::vector<std::string> s_HiddenKeywords = {
        "libTool.so",
        "libfrida",
        "dobby",
        "gum-js-loop"
    };
    static std::mutex s_Mutex;

    // Original function pointers
    static FILE *(*orig_fopen)(const char *path, const char *mode) = nullptr;
    static FILE *(*orig_fopen64)(const char *path, const char *mode) = nullptr;
    static int (*orig_open)(const char *path, int flags, ...) = nullptr;
    static int (*orig_openat)(int dirfd, const char *path, int flags, ...) = nullptr;
    static int (*orig_system_property_get)(const char *name, char *value) = nullptr;

    static int my_system_property_get(const char *name, char *value)
    {
        if (s_CloakEnabled && name && value)
        {
            if (strcmp(name, "ro.product.model") == 0 || strcmp(name, "ro.product.name") == 0 ||
                strcmp(name, "ro.product.device") == 0 || strcmp(name, "ro.build.product") == 0)
            {
                strcpy(value, "ASUS_AI2401_A");
                return (int)strlen(value);
            }
            if (strcmp(name, "ro.product.manufacturer") == 0 || strcmp(name, "ro.product.brand") == 0)
            {
                strcpy(value, "asus");
                return (int)strlen(value);
            }
            if (strcmp(name, "ro.board.platform") == 0)
            {
                strcpy(value, "pineapple");
                return (int)strlen(value);
            }
            if (strcmp(name, "ro.hardware") == 0)
            {
                strcpy(value, "qcom");
                return (int)strlen(value);
            }
            if (strcmp(name, "ro.soc.model") == 0)
            {
                strcpy(value, "SM8650");
                return (int)strlen(value);
            }
        }
        if (orig_system_property_get)
            return orig_system_property_get(name, value);
        return 0;
    }

    static bool IsMapsPath(const char *path)
    {
        if (!path)
            return false;
        return (strstr(path, "/maps") != nullptr);
    }

    static bool ShouldHideLine(const char *line)
    {
        std::lock_guard<std::mutex> lock(s_Mutex);
        for (const auto &kw : s_HiddenKeywords)
        {
            if (strstr(line, kw.c_str()) != nullptr)
                return true;
        }
        return false;
    }

    static std::string GetSanitizedMaps()
    {
        if (!orig_fopen)
            return "";

        FILE *fp = orig_fopen("/proc/self/maps", "r");
        if (!fp)
            return "";

        std::string result;
        result.reserve(64 * 1024); // 64KB initial

        char line[1024];
        while (fgets(line, sizeof(line), fp))
        {
            if (!ShouldHideLine(line))
            {
                result.append(line);
            }
        }
        fclose(fp);
        return result;
    }

    static FILE *my_fopen(const char *path, const char *mode)
    {
        if (s_CloakEnabled && IsMapsPath(path))
        {
            std::string sanitized = GetSanitizedMaps();
            int p[2];
            if (pipe(p) == 0)
            {
                write(p[1], sanitized.data(), sanitized.size());
                close(p[1]);
                return fdopen(p[0], mode ? mode : "r");
            }
        }

        if (orig_fopen)
            return orig_fopen(path, mode);
        return fopen(path, mode);
    }

    static FILE *my_fopen64(const char *path, const char *mode)
    {
        if (s_CloakEnabled && IsMapsPath(path))
        {
            std::string sanitized = GetSanitizedMaps();
            int p[2];
            if (pipe(p) == 0)
            {
                write(p[1], sanitized.data(), sanitized.size());
                close(p[1]);
                return fdopen(p[0], mode ? mode : "r");
            }
        }

        if (orig_fopen64)
            return orig_fopen64(path, mode);
        return fopen(path, mode);
    }

    static int my_open(const char *path, int flags, ...)
    {
        mode_t mode = 0;
        if (flags & O_CREAT)
        {
            va_list args;
            va_start(args, flags);
            mode = (mode_t)va_arg(args, int);
            va_end(args);
        }

        if (s_CloakEnabled && IsMapsPath(path))
        {
            std::string sanitized = GetSanitizedMaps();
            int p[2];
            if (pipe(p) == 0)
            {
                write(p[1], sanitized.data(), sanitized.size());
                close(p[1]);
                return p[0];
            }
        }

        if (orig_open)
            return orig_open(path, flags, mode);
        return open(path, flags, mode);
    }

    static int my_openat(int dirfd, const char *path, int flags, ...)
    {
        mode_t mode = 0;
        if (flags & O_CREAT)
        {
            va_list args;
            va_start(args, flags);
            mode = (mode_t)va_arg(args, int);
            va_end(args);
        }

        if (s_CloakEnabled && IsMapsPath(path))
        {
            std::string sanitized = GetSanitizedMaps();
            int p[2];
            if (pipe(p) == 0)
            {
                write(p[1], sanitized.data(), sanitized.size());
                close(p[1]);
                return p[0];
            }
        }

        if (orig_openat)
            return orig_openat(dirfd, path, flags, mode);
        return openat(dirfd, path, flags, mode);
    }

    static void SigTrapHandler(int signum)
    {
        LOGI("Stealth: Blocked anticheat SIGTRAP signal (%d)", signum);
    }

    void Init()
    {
        if (s_Initialized)
            return;
        s_Initialized = true;

        LOGI("Stealth: Initializing anti-detection cloaking...");

        // Hook fopen
        void *fopen_addr = dlsym(RTLD_DEFAULT, "fopen");
        if (fopen_addr)
        {
            DobbyHook(fopen_addr, (void *)my_fopen, (void **)&orig_fopen);
        }

        // Hook fopen64 if available
        void *fopen64_addr = dlsym(RTLD_DEFAULT, "fopen64");
        if (fopen64_addr && fopen64_addr != fopen_addr)
        {
            DobbyHook(fopen64_addr, (void *)my_fopen64, (void **)&orig_fopen64);
        }

        // Hook open
        void *open_addr = dlsym(RTLD_DEFAULT, "open");
        if (open_addr)
        {
            DobbyHook(open_addr, (void *)my_open, (void **)&orig_open);
        }

        // Hook openat
        void *openat_addr = dlsym(RTLD_DEFAULT, "openat");
        if (openat_addr)
        {
            DobbyHook(openat_addr, (void *)my_openat, (void **)&orig_openat);
        }

        // Hook __system_property_get for Full Device Spoofing
        void *prop_get_addr = dlsym(RTLD_DEFAULT, "__system_property_get");
        if (prop_get_addr)
        {
            DobbyHook(prop_get_addr, (void *)my_system_property_get, (void **)&orig_system_property_get);
        }

        // Catch SIGTRAP
        signal(SIGTRAP, SigTrapHandler);

        LOGI("Stealth: Cloaking & Device Spoofing hooks installed successfully.");
    }

    void SetCloakEnabled(bool enabled)
    {
        s_CloakEnabled = enabled;
    }

    bool IsCloakEnabled()
    {
        return s_CloakEnabled;
    }

    void SetAntiDebugBypassEnabled(bool enabled)
    {
        s_AntiDebugBypassEnabled = enabled;
    }

    bool IsAntiDebugBypassEnabled()
    {
        return s_AntiDebugBypassEnabled;
    }

    void AddHiddenKeyword(const std::string &keyword)
    {
        if (keyword.empty())
            return;
        std::lock_guard<std::mutex> lock(s_Mutex);
        s_HiddenKeywords.push_back(keyword);
    }

    const std::vector<std::string>& GetHiddenKeywords()
    {
        return s_HiddenKeywords;
    }

    void DrawSettings()
    {
        ImGui::TextColored(ImVec4(0.3f, 0.8f, 1.0f, 1.0f), "Stealth & Anti-Detection Engine");
        ImGui::Separator();

        bool cloak = s_CloakEnabled;
        if (ImGui::Checkbox("Cloak /proc/self/maps (/proc/<pid>/maps)", &cloak))
        {
            SetCloakEnabled(cloak);
            Tool::AddNotification("Stealth", cloak ? "Maps cloaking enabled" : "Maps cloaking disabled", cloak);
        }
        ImGui::SameLine();
        ImGui::TextDisabled("(Hides libTool.so & mod memory from AC scanners)");

        bool antiDebug = s_AntiDebugBypassEnabled;
        if (ImGui::Checkbox("SIGTRAP Anti-Debug Trap Handler", &antiDebug))
        {
            SetAntiDebugBypassEnabled(antiDebug);
        }

        ImGui::Spacing();
        ImGui::Text("Hidden Signatures / Keywords in Maps:");
        std::lock_guard<std::mutex> lock(s_Mutex);
        for (size_t i = 0; i < s_HiddenKeywords.size(); ++i)
        {
            ImGui::BulletText("%s", s_HiddenKeywords[i].c_str());
        }

        static char newKw[64] = "";
        ImGui::InputTextWithHint("##NewKeyword", "Add keyword to hide...", newKw, sizeof(newKw));
        ImGui::SameLine();
        if (ImGui::Button("Add Keyword") && newKw[0] != '\0')
        {
            s_HiddenKeywords.push_back(newKw);
            newKw[0] = '\0';
        }
    }
} // namespace Stealth
