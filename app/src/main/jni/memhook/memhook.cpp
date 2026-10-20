#include "memhook.h"

#include <iostream>
#include <fstream>
#include <sstream>
#include <iomanip>
#include <cstring>
#include <vector>
#include <algorithm>
#include <thread>
#include <chrono>

#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <sys/syscall.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/prctl.h>
#include <errno.h>

#ifndef __NR_process_vm_readv
#if defined(__arm__)
#define __NR_process_vm_readv 376
#define __NR_process_vm_writev 377
#elif defined(__aarch64__)
#define __NR_process_vm_readv 270
#define __NR_process_vm_writev 271
#endif
#endif

namespace MemHook {

    static ssize_t sys_process_vm_readv(pid_t pid,
                                        const struct iovec *local_iov,
                                        unsigned long liovcnt,
                                        const struct iovec *remote_iov,
                                        unsigned long riovcnt,
                                        unsigned long flags) {
#if defined(__NR_process_vm_readv)
        return syscall(__NR_process_vm_readv, pid, local_iov, liovcnt, remote_iov, riovcnt, flags);
#else
        errno = ENOSYS;
        return -1;
#endif
    }

    static ssize_t sys_process_vm_writev(pid_t pid,
                                         const struct iovec *local_iov,
                                         unsigned long liovcnt,
                                         const struct iovec *remote_iov,
                                         unsigned long riovcnt,
                                         unsigned long flags) {
#if defined(__NR_process_vm_writev)
        return syscall(__NR_process_vm_writev, pid, local_iov, liovcnt, remote_iov, riovcnt, flags);
#else
        errno = ENOSYS;
        return -1;
#endif
    }

    Engine::Engine() = default;

    Engine::~Engine() {
        Detach();
        StopIpcServer();
    }

    pid_t Engine::FindPidByName(const std::string &processName) {
        DIR *dir = opendir("/proc");
        if (!dir) return -1;

        pid_t foundPid = -1;
        struct dirent *entry;

        while ((entry = readdir(dir)) != nullptr) {
            if (entry->d_type != DT_DIR) continue;

            char *endptr = nullptr;
            long pid = strtol(entry->d_name, &endptr, 10);
            if (*endptr != '\0' || pid <= 0) continue;

            std::string cmdlinePath = std::string("/proc/") + entry->d_name + "/cmdline";
            std::ifstream cmdlineFile(cmdlinePath);
            if (cmdlineFile.is_open()) {
                std::string cmdline;
                std::getline(cmdlineFile, cmdline, '\0');
                if (cmdline.find(processName) != std::string::npos) {
                    foundPid = static_cast<pid_t>(pid);
                    break;
                }
            }
        }

        closedir(dir);
        return foundPid;
    }

    std::vector<ProcessInfo> Engine::ListProcesses() {
        std::vector<ProcessInfo> results;
        DIR *dir = opendir("/proc");
        if (!dir) return results;

        struct dirent *entry;
        while ((entry = readdir(dir)) != nullptr) {
            if (entry->d_type != DT_DIR) continue;

            char *endptr = nullptr;
            long pid = strtol(entry->d_name, &endptr, 10);
            if (*endptr != '\0' || pid <= 0) continue;

            std::string cmdlinePath = std::string("/proc/") + entry->d_name + "/cmdline";
            std::ifstream cmdlineFile(cmdlinePath);
            if (cmdlineFile.is_open()) {
                std::string cmdline;
                std::getline(cmdlineFile, cmdline, '\0');
                if (!cmdline.empty()) {
                    ProcessInfo p;
                    p.pid = static_cast<pid_t>(pid);
                    p.cmdline = cmdline;
                    p.name = cmdline;
                    results.push_back(p);
                }
            }
        }

        closedir(dir);
        return results;
    }

    bool Engine::Attach(pid_t pid, AccessMethod method) {
        if (pid <= 0) return false;
        Detach();

        m_targetPid = pid;
        m_method = method;

        if (m_method == AccessMethod::AUTO) {
            uint8_t probe = 0;
            struct iovec local_iov { &probe, sizeof(probe) };
            struct iovec remote_iov { reinterpret_cast<void *>(0x1000), sizeof(probe) };

            ssize_t ret = sys_process_vm_readv(m_targetPid, &local_iov, 1, &remote_iov, 1, 0);
            if (ret >= 0 || errno == EFAULT) {
                m_method = AccessMethod::PROCESS_VM;
            } else if (OpenProcMem()) {
                m_method = AccessMethod::PROCFS_MEM;
            } else {
                m_method = AccessMethod::PTRACE;
            }
        }

        if (m_method == AccessMethod::PROCFS_MEM) {
            return OpenProcMem();
        }

        if (m_method == AccessMethod::PTRACE) {
            return PtraceAttach();
        }

        return true;
    }

    bool Engine::AttachByName(const std::string &processName, AccessMethod method) {
        pid_t pid = FindPidByName(processName);
        if (pid <= 0) return false;
        return Attach(pid, method);
    }

    void Engine::Detach() {
        CloseProcMem();
        PtraceDetach();
        m_targetPid = -1;
    }

    bool Engine::IsAttached() const {
        return m_targetPid > 0;
    }

    bool Engine::OpenProcMem() {
        if (m_memFd >= 0) return true;
        if (m_targetPid <= 0) return false;

        std::string memPath = "/proc/" + std::to_string(m_targetPid) + "/mem";
        m_memFd = open(memPath.c_str(), O_RDWR | O_LARGEFILE);
        if (m_memFd < 0) {
            m_memFd = open(memPath.c_str(), O_RDONLY | O_LARGEFILE);
        }
        return m_memFd >= 0;
    }

    void Engine::CloseProcMem() {
        if (m_memFd >= 0) {
            close(m_memFd);
            m_memFd = -1;
        }
    }

    bool Engine::PtraceAttach() {
        if (m_targetPid <= 0) return false;
        if (ptrace(PTRACE_ATTACH, m_targetPid, nullptr, nullptr) == 0) {
            int status = 0;
            waitpid(m_targetPid, &status, WUNTRACED);
            m_isPtraceAttached = true;
            return true;
        }
        return false;
    }

    void Engine::PtraceDetach() {
        if (m_isPtraceAttached && m_targetPid > 0) {
            ptrace(PTRACE_DETACH, m_targetPid, nullptr, nullptr);
            m_isPtraceAttached = false;
        }
    }

    std::vector<ModuleInfo> Engine::GetMaps() {
        std::vector<ModuleInfo> maps;
        if (m_targetPid <= 0) return maps;

        std::string mapsPath = "/proc/" + std::to_string(m_targetPid) + "/maps";
        std::ifstream mapsFile(mapsPath);
        if (!mapsFile.is_open()) return maps;

        std::string line;
        while (std::getline(mapsFile, line)) {
            std::istringstream iss(line);
            std::string addrRange, perms, offsetStr, dev, inode;
            iss >> addrRange >> perms >> offsetStr >> dev >> inode;

            std::string path;
            std::getline(iss >> std::ws, path);

            auto dashPos = addrRange.find('-');
            if (dashPos == std::string::npos) continue;

            uintptr_t start = std::strtoull(addrRange.substr(0, dashPos).c_str(), nullptr, 16);
            uintptr_t end = std::strtoull(addrRange.substr(dashPos + 1).c_str(), nullptr, 16);
            uintptr_t offset = std::strtoull(offsetStr.c_str(), nullptr, 16);

            std::string moduleName;
            auto lastSlash = path.find_last_of('/');
            if (lastSlash != std::string::npos) {
                moduleName = path.substr(lastSlash + 1);
            } else {
                moduleName = path;
            }

            ModuleInfo info;
            info.name = moduleName;
            info.startAddress = start;
            info.endAddress = end;
            info.permissions = perms;
            info.offset = offset;
            info.path = path;

            maps.push_back(info);
        }

        return maps;
    }

    uintptr_t Engine::GetModuleBase(const std::string &moduleName) {
        auto maps = GetMaps();
        for (const auto &m : maps) {
            if (m.name.find(moduleName) != std::string::npos || m.path.find(moduleName) != std::string::npos) {
                if (m.permissions.find('r') != std::string::npos) {
                    return m.startAddress;
                }
            }
        }
        return 0;
    }

    bool Engine::GetModuleRange(const std::string &moduleName, uintptr_t &outStart, uintptr_t &outEnd) {
        auto maps = GetMaps();
        uintptr_t minAddr = UINTPTR_MAX;
        uintptr_t maxAddr = 0;
        bool found = false;

        for (const auto &m : maps) {
            if (m.name.find(moduleName) != std::string::npos || m.path.find(moduleName) != std::string::npos) {
                found = true;
                if (m.startAddress < minAddr) minAddr = m.startAddress;
                if (m.endAddress > maxAddr) maxAddr = m.endAddress;
            }
        }

        if (found) {
            outStart = minAddr;
            outEnd = maxAddr;
            return true;
        }
        return false;
    }

    bool Engine::Read(uintptr_t address, void *outBuffer, size_t size) {
        if (m_targetPid <= 0 || !outBuffer || size == 0) return false;

        if (m_method == AccessMethod::PROCESS_VM || m_method == AccessMethod::AUTO) {
            struct iovec local_iov { outBuffer, size };
            struct iovec remote_iov { reinterpret_cast<void *>(address), size };
            ssize_t nread = sys_process_vm_readv(m_targetPid, &local_iov, 1, &remote_iov, 1, 0);
            if (nread == static_cast<ssize_t>(size)) {
                return true;
            }
        }

        if (OpenProcMem()) {
            ssize_t nread = pread64(m_memFd, outBuffer, size, static_cast<off64_t>(address));
            if (nread == static_cast<ssize_t>(size)) {
                return true;
            }
        }

        return false;
    }

    bool Engine::Write(uintptr_t address, const void *inBuffer, size_t size) {
        if (m_targetPid <= 0 || !inBuffer || size == 0) return false;

        if (m_method == AccessMethod::PROCESS_VM || m_method == AccessMethod::AUTO) {
            struct iovec local_iov { const_cast<void *>(inBuffer), size };
            struct iovec remote_iov { reinterpret_cast<void *>(address), size };
            ssize_t nwritten = sys_process_vm_writev(m_targetPid, &local_iov, 1, &remote_iov, 1, 0);
            if (nwritten == static_cast<ssize_t>(size)) {
                return true;
            }
        }

        if (OpenProcMem()) {
            ssize_t nwritten = pwrite64(m_memFd, inBuffer, size, static_cast<off64_t>(address));
            if (nwritten == static_cast<ssize_t>(size)) {
                return true;
            }
        }

        if (m_isPtraceAttached || PtraceAttach()) {
            const uint8_t *src = static_cast<const uint8_t *>(inBuffer);
            size_t words = size / sizeof(long);
            size_t remain = size % sizeof(long);

            for (size_t i = 0; i < words; ++i) {
                long word;
                std::memcpy(&word, src + i * sizeof(long), sizeof(long));
                ptrace(PTRACE_POKETEXT, m_targetPid, address + i * sizeof(long), word);
            }

            if (remain > 0) {
                long word = ptrace(PTRACE_PEEKTEXT, m_targetPid, address + words * sizeof(long), nullptr);
                std::memcpy(&word, src + words * sizeof(long), remain);
                ptrace(PTRACE_POKETEXT, m_targetPid, address + words * sizeof(long), word);
            }
            return true;
        }

        return false;
    }

    static std::vector<std::pair<uint8_t, bool>> ParsePattern(const std::string &pattern) {
        std::vector<std::pair<uint8_t, bool>> result;
        std::istringstream iss(pattern);
        std::string byteStr;

        while (iss >> byteStr) {
            if (byteStr == "?" || byteStr == "??") {
                result.push_back({0, false});
            } else {
                uint8_t b = static_cast<uint8_t>(std::strtoul(byteStr.c_str(), nullptr, 16));
                result.push_back({b, true});
            }
        }
        return result;
    }

    std::vector<uintptr_t> Engine::ScanRange(uintptr_t start, uintptr_t end, const std::string &pattern) {
        std::vector<uintptr_t> matches;
        auto parsedPattern = ParsePattern(pattern);
        if (parsedPattern.empty() || start >= end) return matches;

        const size_t chunkSize = 64 * 1024;
        std::vector<uint8_t> buffer(chunkSize);
        size_t patternLen = parsedPattern.size();

        for (uintptr_t cur = start; cur < end; cur += (chunkSize - patternLen)) {
            size_t bytesToRead = std::min(chunkSize, static_cast<size_t>(end - cur));
            if (!Read(cur, buffer.data(), bytesToRead)) {
                continue;
            }

            for (size_t i = 0; i + patternLen <= bytesToRead; ++i) {
                bool found = true;
                for (size_t p = 0; p < patternLen; ++p) {
                    if (parsedPattern[p].second && buffer[i + p] != parsedPattern[p].first) {
                        found = false;
                        break;
                    }
                }
                if (found) {
                    matches.push_back(cur + i);
                }
            }
        }

        return matches;
    }

    std::vector<uintptr_t> Engine::ScanPattern(const std::string &moduleName, const std::string &pattern) {
        std::vector<uintptr_t> results;
        auto maps = GetMaps();

        for (const auto &m : maps) {
            if (m.name.find(moduleName) != std::string::npos || m.path.find(moduleName) != std::string::npos) {
                if (m.permissions.find('r') != std::string::npos) {
                    auto r = ScanRange(m.startAddress, m.endAddress, pattern);
                    results.insert(results.end(), r.begin(), r.end());
                }
            }
        }

        return results;
    }

    void Engine::MaskProcess(const std::string &fakeName) {
        prctl(PR_SET_NAME, fakeName.c_str(), 0, 0, 0);
    }

    void Engine::ProtectSelf() {
        prctl(PR_SET_DUMPABLE, 0, 0, 0, 0);
    }

    bool Engine::StartIpcServer(const std::string &socketPath) {
        unlink(socketPath.c_str());

        m_serverSockFd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (m_serverSockFd < 0) return false;

        struct sockaddr_un addr;
        std::memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        std::strncpy(addr.sun_path, socketPath.c_str(), sizeof(addr.sun_path) - 1);

        if (bind(m_serverSockFd, reinterpret_cast<struct sockaddr *>(&addr), sizeof(addr)) < 0) {
            close(m_serverSockFd);
            m_serverSockFd = -1;
            return false;
        }

        chmod(socketPath.c_str(), 0777);

        if (listen(m_serverSockFd, 5) < 0) {
            close(m_serverSockFd);
            m_serverSockFd = -1;
            return false;
        }

        m_serverRunning = true;
        std::cout << "[+] MemHook IPC server listening on " << socketPath << std::endl;

        std::thread([this]() {
            while (m_serverRunning) {
                int clientFd = accept(m_serverSockFd, nullptr, nullptr);
                if (clientFd < 0) {
                    if (!m_serverRunning) break;
                    continue;
                }

                char buf[1024];
                ssize_t n = read(clientFd, buf, sizeof(buf) - 1);
                if (n > 0) {
                    buf[n] = '\0';
                    std::string cmd(buf);
                    std::istringstream iss(cmd);
                    std::string action;
                    iss >> action;

                    std::string response = "ERR\n";

                    if (action == "PING") {
                        response = "PONG\n";
                    } else if (action == "ATTACH") {
                        std::string target;
                        iss >> target;
                        pid_t pid = strtol(target.c_str(), nullptr, 10);
                        bool ok = (pid > 0) ? Attach(pid) : AttachByName(target);
                        response = ok ? ("OK " + std::to_string(m_targetPid) + "\n") : "ERR_ATTACH\n";
                    } else if (action == "BASE") {
                        std::string mod;
                        iss >> mod;
                        uintptr_t base = GetModuleBase(mod);
                        std::stringstream ss;
                        ss << "OK 0x" << std::hex << base << "\n";
                        response = ss.str();
                    } else if (action == "READ") {
                        std::string addrStr;
                        size_t size = 0;
                        iss >> addrStr >> size;
                        uintptr_t addr = strtoull(addrStr.c_str(), nullptr, 16);
                        std::vector<uint8_t> mem(size);
                        if (Read(addr, mem.data(), size)) {
                            std::stringstream ss;
                            ss << "OK ";
                            for (size_t i = 0; i < size; ++i) {
                                ss << std::hex << std::setw(2) << std::setfill('0') << static_cast<int>(mem[i]);
                            }
                            ss << "\n";
                            response = ss.str();
                        } else {
                            response = "ERR_READ\n";
                        }
                    } else if (action == "WRITE") {
                        std::string addrStr, hexStr;
                        iss >> addrStr >> hexStr;
                        uintptr_t addr = strtoull(addrStr.c_str(), nullptr, 16);
                        std::vector<uint8_t> data;
                        for (size_t i = 0; i + 1 < hexStr.size(); i += 2) {
                            uint8_t b = static_cast<uint8_t>(strtoul(hexStr.substr(i, 2).c_str(), nullptr, 16));
                            data.push_back(b);
                        }
                        if (!data.empty() && Write(addr, data.data(), data.size())) {
                            response = "OK\n";
                        } else {
                            response = "ERR_WRITE\n";
                        }
                    } else if (action == "DETACH") {
                        Detach();
                        response = "OK\n";
                    }

                    write(clientFd, response.c_str(), response.size());
                }
                close(clientFd);
            }
        }).detach();

        return true;
    }

    void Engine::StopIpcServer() {
        m_serverRunning = false;
        if (m_serverSockFd >= 0) {
            close(m_serverSockFd);
            m_serverSockFd = -1;
        }
    }

} // namespace MemHook

int main(int argc, char **argv) {
    MemHook::Engine::ProtectSelf();
    MemHook::Engine::MaskProcess("kworker/u16:3");

    std::string targetPkg = "com.mobile.legends";
    pid_t targetPid = -1;
    bool daemonMode = false;
    std::string socketPath = "/data/local/tmp/memhook.sock";

    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--pkg" && i + 1 < argc) {
            targetPkg = argv[++i];
        } else if (arg == "--pid" && i + 1 < argc) {
            targetPid = std::strtol(argv[++i], nullptr, 10);
        } else if (arg == "--daemon" || arg == "-d") {
            daemonMode = true;
        } else if (arg == "--socket" && i + 1 < argc) {
            socketPath = argv[++i];
        } else if (arg == "--help" || arg == "-h") {
            std::cout << "MemHook - Android Native Memory Engine\n"
                      << "Usage:\n"
                      << "  memhook [--pkg <name>] [--pid <id>] [--daemon] [--socket <path>]\n"
                      << "Examples:\n"
                      << "  memhook --pkg com.mobile.legends --daemon\n";
            return 0;
        }
    }

    MemHook::Engine engine;
    std::cout << "[*] MemHook initializing...\n";

    if (daemonMode) {
        if (!engine.StartIpcServer(socketPath)) {
            std::cerr << "[-] Failed to start IPC server at " << socketPath << "\n";
            return 1;
        }

        std::cout << "[+] Daemon running. Waiting for IPC requests...\n";
        while (true) {
            std::this_thread::sleep_for(std::chrono::seconds(10));
        }
    } else {
        bool attached = (targetPid > 0) ? engine.Attach(targetPid) : engine.AttachByName(targetPkg);
        if (!attached) {
            std::cerr << "[-] Failed to attach to target: " << (targetPid > 0 ? std::to_string(targetPid) : targetPkg) << "\n";
            return 1;
        }

        std::cout << "[+] Attached to PID: " << engine.GetTargetPid() << "\n";

        uintptr_t il2cppBase = engine.GetModuleBase("libil2cpp.so");
        uintptr_t unityBase = engine.GetModuleBase("libunity.so");

        std::cout << "[+] libil2cpp.so base: 0x" << std::hex << il2cppBase << "\n";
        std::cout << "[+] libunity.so base:  0x" << std::hex << unityBase << std::dec << "\n";
    }

    return 0;
}
