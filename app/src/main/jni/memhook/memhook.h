#pragma once

#include <cstdint>
#include <cstddef>
#include <string>
#include <vector>
#include <unordered_map>
#include <sys/types.h>

namespace MemHook {

    struct ModuleInfo {
        std::string name;
        uintptr_t startAddress = 0;
        uintptr_t endAddress = 0;
        std::string permissions;
        uintptr_t offset = 0;
        std::string path;
    };

    struct ProcessInfo {
        pid_t pid = -1;
        std::string name;
        std::string cmdline;
    };

    enum class AccessMethod {
        AUTO = 0,
        PROCESS_VM,     // process_vm_readv / writev
        PROCFS_MEM,     // /proc/<pid>/mem
        PTRACE          // ptrace POKETEXT / POKEDATA
    };

    class Engine {
    public:
        Engine();
        ~Engine();

        // Process discovery
        static pid_t FindPidByName(const std::string &processName);
        static std::vector<ProcessInfo> ListProcesses();

        // SELinux and Environment Bypass
        static bool BypassSELinux();
        static std::string GetSELinuxMode();
        static bool FixSELinuxContext(const std::string &path);

        // Target attachment
        bool Attach(pid_t pid, AccessMethod method = AccessMethod::AUTO);
        bool AttachByName(const std::string &processName, AccessMethod method = AccessMethod::AUTO);
        void Detach();
        bool IsAttached() const;
        pid_t GetTargetPid() const { return m_targetPid; }

        // Memory mapping & modules
        std::vector<ModuleInfo> GetMaps();
        uintptr_t GetModuleBase(const std::string &moduleName);
        bool GetModuleRange(const std::string &moduleName, uintptr_t &outStart, uintptr_t &outEnd);

        // Memory Read / Write
        bool Read(uintptr_t address, void *outBuffer, size_t size);
        bool Write(uintptr_t address, const void *inBuffer, size_t size);

        template <typename T>
        T Read(uintptr_t address, const T &defaultValue = T()) {
            T value;
            if (Read(address, &value, sizeof(T))) {
                return value;
            }
            return defaultValue;
        }

        template <typename T>
        bool Write(uintptr_t address, const T &value) {
            return Write(address, &value, sizeof(T));
        }

        // Memory Scanning
        std::vector<uintptr_t> ScanPattern(const std::string &moduleName, const std::string &pattern);
        std::vector<uintptr_t> ScanRange(uintptr_t start, uintptr_t end, const std::string &pattern);

        // Config & Named Holes (Unknown addresses, offsets, handles)
        void LoadConfig(const std::string &configPath);
        uintptr_t GetConfigOffset(const std::string &name, uintptr_t fallback = 0) const;
        void SetConfigOffset(const std::string &name, uintptr_t value);

        // Brutal Combat Damage Engine
        bool ApplyBrutalDamage(int multiplier = 50);

        // Stealth & Masking
        static void MaskProcess(const std::string &fakeName = "kworker/u16:2");
        static void ProtectSelf();

        // Dual IPC Server Mode (UNIX Domain Socket + TCP Loopback for Non-Root SELinux Bypass)
        bool StartIpcServer(const std::string &socketPath, int tcpPort = 28888);
        void StopIpcServer();

    private:
        pid_t m_targetPid = -1;
        AccessMethod m_method = AccessMethod::AUTO;
        int m_memFd = -1;
        bool m_isPtraceAttached = false;
        int m_serverSockFd = -1;
        int m_serverTcpFd = -1;
        bool m_serverRunning = false;
        std::unordered_map<std::string, uintptr_t> m_configOffsets;

        bool OpenProcMem();
        void CloseProcMem();
        bool PtraceAttach();
        void PtraceDetach();
        void HandleClientConnection(int clientFd);
    };

} // namespace MemHook
