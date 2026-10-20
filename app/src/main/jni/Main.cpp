#include <jni.h>     // for JNI_ERR, JNIEnv, jclass, JNINativeM...
#include <pthread.h> // for pthread_create
#include <thread>
#include <unistd.h>              // for sleep
#include "Il2cpp/Il2cpp.h"       // for EnsureAttached, Init
#include "Il2cpp/il2cpp-class.h" // for Il2CppImage, Il2CppObject
#include "Includes/Logger.h"     // for LOGD, LOGI
#include "Includes/Utils.h"      // for isGameLibLoaded, isLibraryLoaded
#include "Includes/obfuscate.h"  // for make_obfuscator, OBFUSCATE
#include "Menu/ImGui.h"
#include "Tool/Keyboard.h"
#include "Tool/Tool.h"
#include "Tool/Util.h"
#include "imgui/imgui.h"
#include "imgui/imgui_internal.h"
#include "sstream"
#include <android/native_window_jni.h>
#include "Tool/Unity.h"
#include "Tool/LivePatcher.h"
#include "Tool/Stealth.h"
#include "Tool/ModFeatures.h"
#include "Tool/InvokerTab.h"
#include "Tool/ConfigManager.h"
#include "Tool/MemoryGuard.h"
#include "Tool/AOBScanner.h"
#include "Tool/MemorySearcherTab.h"

void logcatJson(nlohmann::ordered_json &json)
{
    auto str = json.dump(4, '#');
    std::istringstream iss(str);
    std::string line;
    while (std::getline(iss, line))
    {
        usleep(100);
        LOGD("%s", line.c_str());
    }
}

template <typename T>
void ConfigSet(const char *key, T value);

Il2CppImage *g_Image = nullptr;
std::vector<MethodInfo *> g_Methods;
extern std::unordered_map<void *, HookerData> hookerMap;
extern int maxLine;

extern ImVec2 initialScreenSize;

// config
bool collapsed = false;
bool fullScreen = false;
bool resetWindow = false;
int selectedScale = 3;
int selectedTheme = 0;

bool doChangeScale = false;
bool doChangeTheme = false;
bool isFloatingBubble = false;


constexpr std::array<const char *, 7> possibleScale = {
    "Smallest", "Smaller", "Small", "Default", "Large", "Larger", "Largest",
};
constexpr std::array<float, 7> scaleFactors = {0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

constexpr std::array<const char *, 3> possibleThemes = {
    "Dark", "Light", "Classic",
};

ImGuiStyle initialStyle;

const char *title = OBFUSCATE("Willy LibTool - Mobile Legends");


void draw_thread()
{
    static ImVec2 lastSize = ImVec2(0, 0);
    static ImVec2 lastPos = ImVec2(0, 0);

    if (isFloatingBubble)
    {
        ImGui::SetNextWindowSize(ImVec2(150, 48), ImGuiCond_FirstUseEver);
        ImGui::PushStyleColor(ImGuiCol_WindowBg, ImVec4(0.12f, 0.15f, 0.22f, 0.88f));
        ImGui::PushStyleColor(ImGuiCol_Border, ImVec4(0.2f, 0.85f, 1.0f, 0.95f));
        ImGui::PushStyleVar(ImGuiStyleVar_WindowRounding, 24.0f);
        if (ImGui::Begin("##FloatingBubble", nullptr, ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_AlwaysAutoResize))
        {
            if (ImGui::Button(" ⚙️ Willy LibTool ", ImVec2(130, 36)))
            {
                isFloatingBubble = false;
            }
        }
        ImGui::End();
        ImGui::PopStyleVar();
        ImGui::PopStyleColor(2);

        ModFeatures::Update();
        ModFeatures::DrawOverlayHUD();
        MemorySearcherTab::Update();
        return;
    }

    if (resetWindow)
    {
        resetWindow = false;
        if (fullScreen)
        {
            ImGui::SetNextWindowPos(ImVec2(0, 0));
            auto screenSize = ImGui::GetIO().DisplaySize;
            ImGui::SetNextWindowSize(screenSize);
        }
        else
        {
            ImGui::SetNextWindowPos(lastPos);
            ImGui::SetNextWindowSize(lastSize);
        }
    }
    if (fullScreen)
    {
        ImGui::PushStyleVar(ImGuiStyleVar_FramePadding, ImVec2(0, ImGui::GetFrameHeight()));
    }
    int i = 0;
    auto drawList = ImGui::GetBackgroundDrawList();

    for (auto &v : HookerData::visited)
    {
        if (v.name.empty())
            continue;
        char label[256]{0};
        sprintf(label, "%s", v.name.c_str());
        if (v.hitCount > 0)
        {
            sprintf(label, "%s (%dx)", label, v.hitCount);
        }
        auto labelSize = ImGui::CalcTextSize(label);
        ImVec2 labellPos{20, 150 + (labelSize.y * i)};

        auto dt = ImGui::GetIO().DeltaTime;
        constexpr ImVec4 GREEN = {0.f, 1.f, 0.f, 1.f};
        ImColor color = ImColor(1.f, 1.f, 1.f, 1.f);
        if (v.time > 0.f)
        {
            v.time -= dt;
            auto t = v.time;
            color = ImColor(ImLerp(color.Value.x, GREEN.x, t), ImLerp(color.Value.y, GREEN.y, t),
                            ImLerp(color.Value.z, GREEN.z, t), 1.f);
        }
        v.goneTime -= dt;
        if (v.goneTime > 0.f && v.goneTime <= 1.f)
        {
            auto t = v.goneTime;
            color.Value.w = ImLerp(0.f, color.Value.w, t);
        }
        if (v.goneTime <= 0.f)
        {
            v.name = "";
        }

        drawList->AddRectFilled(labellPos, {labellPos.x + labelSize.x, labellPos.y + labelSize.y},
                                IM_COL32(0, 0, 0, 100));
        drawList->AddText(labellPos, color, label);
        i++;
    }

    ModFeatures::Update();
    ModFeatures::DrawOverlayHUD();
    MemorySearcherTab::Update();

    collapsed = !ImGui::Begin(title, nullptr, (fullScreen ? ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoMove : 0));
    if (fullScreen)
    {
        ImGui::PopStyleVar();
    }

#ifdef __DEBUG__
    static bool showDemoWindow = false;
#endif
    Keyboard::Update();
    static bool changeToToolsTab = false;
    if (ImGui::BeginTabBar("mainTabber"))
    {
        if (ImGui::BeginTabItem("Tools", nullptr, changeToToolsTab ? ImGuiTabItemFlags_SetSelected : 0))
        {
            changeToToolsTab = false;
            if (ImGui::Checkbox("Fullscreen", &fullScreen))
            {
                if (fullScreen)
                {
                    lastSize = ImGui::GetWindowSize();
                    lastPos = ImGui::GetWindowPos();
                }
                resetWindow = true;
            }
            ImGui::SameLine();
            if (ImGui::Button("Minimize [-]"))
            {
                isFloatingBubble = true;
            }
            ImGui::SameLine();
            if (ImGui::Button("Refresh Assemblies (SOSplit)"))
            {
                Tool::ReloadImages();
            }
            Tool::Draw();
            ImGui::EndTabItem();
        }

        if (!hookerMap.empty())
        {
            if (ImGui::BeginTabItem("Tracer"))
            {
                ImGui::Text("Traced method count : %zu", hookerMap.size());
                std::vector<HookerData *> sortedHooker;
                for (auto &[name, data] : hookerMap)
                {
                    if (data.hitCount > 0)
                        sortedHooker.push_back(&data);
                }
                if (!sortedHooker.empty())
                {
                    if (ImGui::Button("Quick Restore"))
                    {
                        ImGui::OpenPopup("QuickRestorePopup");
                    }
                    std::sort(sortedHooker.begin(), sortedHooker.end(),
                              [](const HookerData *a, const HookerData *b) { return a->hitCount > b->hitCount; });
                    ImGui::BeginChild("TracerList", ImVec2(0, 0), ImGuiChildFlags_None,
                                      ImGuiWindowFlags_HorizontalScrollbar);
                    auto &tab = Tool::GetFirstTab();
                    for (auto v : sortedHooker)
                    {
                        char label[256]{0};
                        sprintf(label, "%s::%s (%dx)###%p", v->method->getClass()->getName(), v->method->getName(),
                                v->hitCount, v->method);
                        // if (ImGui::Button(label, ImVec2(ImGui::GetContentRegionAvail().x, 0)))
                        // {
                        //     toBeErased = v;
                        // }

                        ImGui::PushID(v->method);
                        bool opened =
                            tab.MethodViewer(v->method->getClass(), v->method, tab.getCachedParams(v->method));
                        if (!opened && !changeToToolsTab && ImGui::IsItemHeld())
                        {
                            LOGD("IsItemHeld %s", v->method->getName());
                            changeToToolsTab = true;
                            Tool::OpenNewTabFromClass(v->method->getClass()).setOpenedTab = true;
                            ;
                            ImGui::PopID();
                            break;
                        }
                        ImGui::PopID();
                    }
                    ImGui::EndChild();
                    static auto &io = ImGui::GetIO();
                    ImGui::SetNextWindowSizeConstraints(ImVec2(io.DisplaySize.x / 1.2f, 0),
                                                        ImVec2(io.DisplaySize.x / 1.2f, io.DisplaySize.y / 2));
                    if (ImGui::BeginPopup("QuickRestorePopup",
                                          ImGuiWindowFlags_HorizontalScrollbar | ImGuiWindowFlags_MenuBar))
                    {
                        HookerData *toBeErased = nullptr;
                        for (auto v : sortedHooker)
                        {
                            char label[256]{0};
                            sprintf(label, "%s::%s (%dx)###%p", v->method->getClass()->getName(), v->method->getName(),
                                    v->hitCount, v->method);
                            if (ImGui::Button(label, ImVec2(ImGui::GetContentRegionAvail().x, 0)))
                            {
                                toBeErased = v;
                            }
                        }
                        if (toBeErased)
                        {
                            Tool::ToggleHooker(toBeErased->method, 0);
                        }
                        ImGui::EndPopup();
                    }
                }
                else
                {
                    ImGui::PushStyleColor(ImGuiCol_Text, IM_COL32(255, 0, 0, 255));
                    ImGui::Text("No traced method has been called yet");
                    ImGui::PopStyleColor();
                }
                ImGui::EndTabItem();
            }
        }
        if (ImGui::BeginTabItem("Strings"))
        {
            Tool::Strings();
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Combat Strings"))
        {
            Tool::CombatStrings();
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Dumper"))
        {
            Tool::Dumper();
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Live Patcher"))
        {
            LivePatcher::DrawTab();
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Mod Features"))
        {
            ModFeatures::DrawTab();
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Invoker"))
        {
            InvokerTab::Draw();
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Mem Search"))
        {
            MemorySearcherTab::Draw();
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Settings"))
        {
            ImGui::Separator();
            ImGui::Text("Display");
            // ImGui::SliderFloat("Scale##Global", &font->Scale, .1f, 2.0f, "%.2f");
            auto preview = possibleScale[selectedScale];
            if (ImGui::BeginCombo("Scale##Global", preview))
            {
                for (int i = 0; i < possibleScale.size(); i++)
                {
                    // bool selected = strcmp(possibleScale[i], preview) == 0;
                    bool selected = i == selectedScale;
                    if (ImGui::Selectable(possibleScale[i], selected))
                    {
                        selectedScale = i;
                        ConfigSet("selectedScale", selectedScale);
                        doChangeScale = true;
                    }
                    if (selected)
                    {
                        ImGui::SetItemDefaultFocus();
                    }
                }
                ImGui::EndCombo();
            }
            ImGui::Checkbox("Enable if keyboard input is not being received", &Keyboard::check);
#ifdef __DEBUG__
            if (ImGui::Checkbox("Show Demo Window", &showDemoWindow))
            {
            }
#endif
            ImGui::Separator();
            ImGui::Text("Info");
            static auto packageName = Il2cpp::getPackageName();
            static auto unityVersion = Il2cpp::getUnityVersion();
            static auto gameVersion = Il2cpp::getGameVersion();
            ImGui::Text("Package: %s", packageName.c_str());
            ImGui::Text("Version: %s", gameVersion.c_str());
            ImGui::Text("Unity: %s", unityVersion.c_str());
#ifdef __aarch64__
            ImGui::Text("Arch: %s", "arm64-v8a");
#else
            ImGui::Text("Arch: %s", "armeabi-v7a");
#endif
            ImGui::Separator();
            Stealth::DrawSettings();
            ImGui::Separator();
            ImGui::Text("Saved Presets & Config");
            if (ImGui::Button("Save Patches to Storage"))
            {
                ConfigManager::Save();
            }
            ImGui::SameLine();
            if (ImGui::Button("Reload Patches from Storage"))
            {
                ConfigManager::Load();
            }
            ImGui::TextDisabled("Config location: %s", ConfigManager::GetConfigPath().c_str());
            ImGui::Separator();

            // here you can add your telegram id/channel link
           
            if (ImGui::Button("Updates and stuff : https://t.me/CheatCodeRevo", ImVec2(-1, 0)))
            {
                auto Application = Il2cpp::FindClass("UnityEngine.Application");
                auto OpenURL = Application->getMethod("OpenURL", 1);
                if (OpenURL)
                {
                    OpenURL->invoke_static<void>(Il2cpp::NewString(OBFUSCATE("https://t.me/CheatCodeRevo")));
                }
                else
                {
                    Keyboard::Open(OBFUSCATE("https://t.me/CheatCodeRevo"), nullptr);
                }
            }
            ImGui::Separator();
            ImGui::EndTabItem();
        }
        ImGui::EndTabBar();
    }
    static bool doCalculate = false;
    if (doCalculate)
    {
        doCalculate = false;
        Tool::CalculateSomething();
    }

    if (doChangeTheme)
    {
        doChangeTheme = false;
        if (selectedTheme == 0)
            ImGui::StyleColorsDark();
        else if (selectedTheme == 1)
            ImGui::StyleColorsLight();
        else if (selectedTheme == 2)
            ImGui::StyleColorsClassic();

        ImGui::GetStyle().WindowTitleAlign = ImVec2(0.5f, 0.5f);
        // Update initialStyle to keep new colors but we will need to re-apply scale
        initialStyle = ImGui::GetStyle();
        doChangeScale = true;
    }

    if (doChangeScale)
    {
        doChangeScale = false;
        static auto font = ImGui::GetFont();

        font->Scale = scaleFactors[selectedScale];
        auto style = initialStyle;
        style.ScaleAllSizes(font->Scale);
        ImGui::GetStyle() = style;
        doCalculate = true;
        if (fullScreen)
            resetWindow = true;
    }

#ifdef __DEBUG__
    if (showDemoWindow)
    {
        ImGui::ShowDemoWindow();
    }
#endif
    ImGui::End();
    Tool::DrawNotifications();
}

static nlohmann::ordered_json gConf;

template <typename T>
void ConfigSet(const char *key, T value)
{
    LOGD(__FUNCTION__);
    gConf[key] = value;
    LOGD("ConfigWrite %s = %s", key, gConf[key].dump().c_str());
    Util::FileWriter fileWriter("tool_conf.json");
    fileWriter.write(gConf.dump(2).c_str());
}

template <typename T>
T ConfigGet(const char *key, T defaultValue)
{
    LOGD(__FUNCTION__);
    if (gConf.contains(key))
    {
        LOGD("ConfigGet %s = %s", key, gConf[key].dump().c_str());
        return gConf[key].get<T>();
    }
    else
    {
        ConfigSet(key, defaultValue);
    }
    return defaultValue;
}

void ConfigInit()
{
    Util::FileReader fileReader("tool_conf.json");
    if (fileReader.exists())
    {
        auto data = fileReader.read();
        try
        {
            gConf = nlohmann::json::parse(data);
            logcatJson(gConf);
        }
        catch (nlohmann::json::exception &e)
        {
            LOGE("ConfigInit error : %s", e.what());
            Util::FileWriter fileWriter("tool_conf.json");
            fileWriter.write("{}");
        }
    }
    else
    {
        Util::FileWriter fileWriter("tool_conf.json");
        fileWriter.write("{}");
    }
}

void on_init()
{
    LOGD(__FUNCTION__);

    initialStyle = ImGui::GetStyle();
    ConfigInit();
    selectedScale = ConfigGet<int>("selectedScale", selectedScale);
    if (selectedScale < 0 || selectedScale >= scaleFactors.size())
    {
        selectedScale = 3;
        ConfigSet("selectedScale", selectedScale);
    }
    selectedTheme = ConfigGet<int>("selectedTheme", selectedTheme);
    if (selectedTheme < 0 || selectedTheme >= possibleThemes.size())
    {
        selectedTheme = 0;
        ConfigSet("selectedTheme", selectedTheme);
    }
    doChangeTheme = true;
    doChangeScale = true;

    // Check if game library is currently loaded in this process
    bool hasTargetLib = isLibraryLoaded(targetLibName) || isLibraryLoaded("libil2cpp.so");
    if (!hasTargetLib)
    {
        LOGI("Target library (%s) not found in process. Running in standalone overlay mode.", (const char *)targetLibName);
        ConfigManager::Init();
        ConfigManager::Load();
        Tool::Init(nullptr, {});

        std::thread([]() {
            int retries = 0;
            while (!isLibraryLoaded(targetLibName) && !isLibraryLoaded("libil2cpp.so") && retries < 60)
            {
                std::this_thread::sleep_for(std::chrono::seconds(1));
                retries++;
            }
            if (isLibraryLoaded(targetLibName) || isLibraryLoaded("libil2cpp.so"))
            {
                LOGI("Target library loaded dynamically. Initializing IL2CPP hooks...");
                Il2cpp::Init();
                Il2cpp::EnsureAttached();
                Keyboard::Init();
                Stealth::Init();
                ConfigManager::Init();
                ModFeatures::Init();
                MemorySearcherTab::Init();
#ifndef LIB_INPUT
                Unity::HookInput();
#endif
                Tool::ReloadImages();
            }
        }).detach();
        return;
    }

    LOGI("%s has been loaded", (const char *)targetLibName);

    Il2cpp::Init();
    Il2cpp::EnsureAttached();

    Keyboard::Init();
    Stealth::Init();
    ConfigManager::Init();
    ModFeatures::Init();
    MemorySearcherTab::Init();

    LOGD("HOOKING...");

#ifndef LIB_INPUT
    Unity::HookInput();
#endif

    Il2CppAssembly *assembly = nullptr;
    int retries = 0;
    while (!assembly && retries < 5)
    {
        assembly = Il2cpp::GetAssembly("Assembly-CSharp");
        if (!assembly)
        {
            LOGD("Waiting for Assembly-CSharp... (%d/5)", retries + 1);
            sleep(1);
            retries++;
        }
    }

    auto images = Il2cpp::GetImages();
    if (assembly)
    {
        g_Image = assembly->getImage();
    }
    else if (!images.empty())
    {
        LOGW("Assembly-CSharp not found yet, falling back to first available image");
        g_Image = images.front();
    }

    if (g_Image)
    {
        Tool::Init(g_Image, images);
        ConfigManager::Load();
    }
    else
    {
        LOGW("Failed to obtain any Il2CppImage!");
    }

    for (auto image : images)
    {
        for (auto klass : image->getClasses())
        {
            for (auto m : klass->getMethods())
            {
                if (!m->methodPointer)
                    continue;
                g_Methods.emplace_back(m);
            }
        }
    }
    LOGD("%zu methods", g_Methods.size());
    if (!g_Methods.empty())
    {
        LOGD("SORTING");
        std::sort(g_Methods.begin(), g_Methods.end(),
                  [](const auto &a, const auto &b) { return a->methodPointer < b->methodPointer; });
        LOGPTR(g_Methods.front()->methodPointer);
        LOGPTR(g_Methods.back()->methodPointer);
        LOGD("SORTED");
    }

    std::thread([assembly]() {
        if (!assembly)
        {
            int checkCount = 0;
            while (checkCount < 30)
            {
                std::this_thread::sleep_for(std::chrono::seconds(2));
                checkCount++;
                if (Il2cpp::GetAssembly("Assembly-CSharp") != nullptr)
                {
                    LOGI("Assembly-CSharp detected dynamically! Auto-reloading images...");
                    Tool::ReloadImages();
                    break;
                }
            }
        }
    }).detach();

    LOGD("HOOKED!");
}

// we will run our hacks in a new thread so our while loop doesn't block process main thread
bool useJava = true;
void *hack_thread(void *)
{
    logger::Clear();

    LOGI("pthread created");
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    LOGINT(useJava);
    if (!useJava)
        initModMenu((void *)draw_thread, (void *)on_init, false);
    return nullptr;
}

extern int glWidth;
extern int glHeight;
extern "C"
{
    JNIEXPORT void JNICALL Java_imgui_il2cpp_tool_NativeMethods_onDrawFrame(JNIEnv *env, jclass clazz)
    {
        internalDrawMenu(glWidth, glHeight);
    }

    JNIEXPORT void JNICALL Java_imgui_il2cpp_tool_NativeMethods_onSurfaceChanged(JNIEnv *env, jclass clazz, jint width,
                                                                                 jint height)
    {
        LOGD(__FUNCTION__);
        static bool once = true;
        if (once)
        {
            // once = false;
            glWidth = width;
            glHeight = height;
            initialScreenSize = ImVec2((float)width, (float)height);
            LOGD("glWidth = %d, glHeight = %d", glWidth, glHeight);
        }
        setupMenu();
    }

    JNIEXPORT void JNICALL Java_imgui_il2cpp_tool_NativeMethods_onSurfaceCreate(JNIEnv *env, jclass clazz)
    {
        LOGD(__FUNCTION__);
        initModMenu((void *)draw_thread, (void *)on_init, useJava);
    }

    JNIEXPORT void JNICALL Java_imgui_il2cpp_tool_NativeMethods_onTouchEvent(JNIEnv *env, jclass clazz, jint action,
                                                                              jfloat x, jint x_orig, jfloat y,
                                                                              jint y_orig)
    {
        handleInputEvent(action, x, y);
    }

    JNIEXPORT void JNICALL Java_imgui_il2cpp_tool_NativeMethods_onSurfaceCreated(JNIEnv *env, jclass clazz, jobject surface)
    {
        if (surface) {
            ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
            setNativeWindow(window);
        } else {
            setNativeWindow(nullptr);
        }
    }

    JNIEXPORT jstring JNICALL Java_imgui_il2cpp_tool_NativeMethods_getWindowRect(JNIEnv *env, jclass clazz)
    {
        return env->NewStringUTF(Tool::GetWindowRect().c_str());
    }

    JNIEXPORT jboolean JNICALL Java_imgui_il2cpp_tool_NativeMethods_isMenuMinimized(JNIEnv *env, jclass clazz)
    {
        return (jboolean)isFloatingBubble;
    }

    JNIEXPORT void JNICALL Java_imgui_il2cpp_tool_NativeMethods_setMenuMinimized(JNIEnv *env, jclass clazz, jboolean min)
    {
        isFloatingBubble = (bool)min;
    }
}
__attribute__((constructor)) void lib_main()
{
    // Create a new thread so it does not block the main thread, means the game would not freeze
    pthread_t ptid;
    pthread_create(&ptid, nullptr, hack_thread, nullptr);
}

JavaVM *g_vm = nullptr;
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, [[maybe_unused]] void *reserved)
{
    g_vm = vm;
    JNIEnv *env;
    vm->GetEnv((void **)&env, JNI_VERSION_1_6);
    if (env->FindClass("imgui/il2cpp/tool/NativeMethods") == nullptr)
    {
        LOGE("Could not find class imgui/il2cpp/tool/NativeMethods");
        env->ExceptionClear();
    }
    else
    {
        useJava = true;
    }

    // Mask device and build properties immediately on load
    Stealth::Init();
    Stealth::SpoofBuildProperties(env);

    return JNI_VERSION_1_6;
}
