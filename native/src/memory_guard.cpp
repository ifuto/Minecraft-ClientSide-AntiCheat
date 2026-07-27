#include "memory_guard.h"
#include "hashes.h"
#include "sha256.h"
#include <algorithm>
#include <cctype>
#include <iostream>

#ifdef _WIN32
#include <windows.h>
#include <psapi.h>
#pragma comment(lib, "psapi.lib")
#else
#include <fstream>
#include <unistd.h>
#endif

namespace anticheat {

MemoryGuard::MemoryGuard() {}

std::vector<std::string> MemoryGuard::scan() {
    std::vector<std::string> violations;
    auto modVios = scanModules();
    violations.insert(violations.end(), modVios.begin(), modVios.end());
    auto memVios = scanMemoryRegions();
    violations.insert(violations.end(), memVios.begin(), memVios.end());
    return violations;
}

static std::string toLower(const std::string& s) {
    std::string out = s;
    std::transform(out.begin(), out.end(), out.begin(), ::tolower);
    return out;
}

std::vector<std::string> MemoryGuard::scanModules() {
    std::vector<std::string> violations;
#ifdef _WIN32
    HMODULE hMods[1024];
    HANDLE hProcess = GetCurrentProcess();
    DWORD cbNeeded;

    if (EnumProcessModules(hProcess, hMods, sizeof(hMods), &cbNeeded)) {
        for (unsigned int i = 0; i < (cbNeeded / sizeof(HMODULE)); i++) {
            CHAR szModName[MAX_PATH];
            if (GetModuleBaseNameA(hProcess, hMods[i], szModName, sizeof(szModName))) {
                std::string modName = szModName;
                std::string lower = toLower(modName);
                uint64_t h = SHA256::hash64_trunc(lower);
                if (critical_module_hashes.find(h) != critical_module_hashes.end()) {
                    violations.push_back("Suspicious module loaded: " + modName + " hash=" + std::to_string(h));
                } else {
                    // Also substring check for module names containing blacklist keyword (similar to file monitor)
                    // For modules, we should check if lower contains any blacklisted substring hash via substring hashing
                    // To avoid storing plaintext substrings, we brute force substrings of lower and check against critical_module_hashes
                    // This catches renamed files like cheatengine -> still contains cheatengine substring? No if renamed. But we already hashed full name.
                    // For partial, we need bigger list: use critical_file_hashes as well? Some overlap.
                    // Quick check: if module name contains "cheat", "inject", etc would be heuristic
                    // We'll just do simple contains for demonstration (in production use hash comparison)
                    // For this demo we use direct lower containment of known bad words from module list originals (we have them in comment)
                    // Since we only have hashes, we do substring hash matching
                    size_t len = lower.length();
                    for (size_t a=0;a<len;a++) {
                        for (size_t b=a+3; b<=len && b-a<=30; ++b) {
                            std::string sub = lower.substr(a, b-a);
                            uint64_t hs = SHA256::hash64_trunc(sub);
                            if (critical_module_hashes.find(hs) != critical_module_hashes.end()) {
                                violations.push_back("Module contains blacklisted substring: " + modName + " sub=" + sub);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }
#else
    // Linux: parse /proc/self/maps and extract mapped .so names
    std::ifstream maps("/proc/self/maps");
    std::string line;
    if (maps.is_open()) {
        while (std::getline(maps, line)) {
            // Example: 7f...-... r-xp ... /path/to/lib.so
            // Check if line contains .so
            auto pos = line.find(".so");
            if (pos != std::string::npos) {
                // extract filename
                auto slash = line.find_last_of('/');
                std::string path = (slash != std::string::npos) ? line.substr(slash+1) : line;
                // trim
                path.erase(std::remove(path.begin(), path.end(), '\n'), path.end());
                std::string lower = toLower(path);
                uint64_t h = SHA256::hash64_trunc(lower);
                if (critical_module_hashes.find(h) != critical_module_hashes.end()) {
                    violations.push_back("Suspicious .so loaded: " + path);
                }
            }
        }
    }
#endif
    return violations;
}

std::vector<std::string> MemoryGuard::scanMemoryRegions() {
    std::vector<std::string> violations;
#ifdef _WIN32
    // Scan virtual memory for RWX regions that are not typical JIT
    SYSTEM_INFO sysInfo;
    GetSystemInfo(&sysInfo);
    uintptr_t addr = (uintptr_t)sysInfo.lpMinimumApplicationAddress;
    uintptr_t maxAddr = (uintptr_t)sysInfo.lpMaximumApplicationAddress;

    int rwxCount = 0;
    size_t rwxTotalSize = 0;
    MEMORY_BASIC_INFORMATION mbi;

    while (addr < maxAddr) {
        if (VirtualQuery((LPCVOID)addr, &mbi, sizeof(mbi)) == 0) break;
        // Check if committed and RWX
        if (mbi.State == MEM_COMMIT) {
            bool isRWX = (mbi.Protect & PAGE_EXECUTE_READWRITE) || (mbi.Protect & PAGE_EXECUTE_WRITECOPY);
            if (isRWX) {
                rwxCount++;
                rwxTotalSize += mbi.RegionSize;
                // RWX is sometimes used by JIT, but large RWX regions > 1MB could be suspicious (injected code)
                if (mbi.RegionSize > 1024*1024) {
                    violations.push_back("Large RWX region detected at " + std::to_string(addr) + " size=" + std::to_string(mbi.RegionSize));
                }
            }
        }
        addr += mbi.RegionSize;
    }

    // Heuristic: if many RWX regions (>20) or total size > 10MB, flag
    if (rwxCount > 30) {
        violations.push_back("Excessive RWX regions count=" + std::to_string(rwxCount));
    }
    if (rwxTotalSize > 20*1024*1024) {
        violations.push_back("Excessive RWX total size=" + std::to_string(rwxTotalSize));
    }
#else
    // Linux: check /proc/self/maps for rwxp
    std::ifstream maps("/proc/self/maps");
    std::string line;
    int rwxCount = 0;
    if (maps.is_open()) {
        while (std::getline(maps, line)) {
            if (line.find("rwxp") != std::string::npos) {
                rwxCount++;
            }
        }
        if (rwxCount > 30) {
            violations.push_back("Excessive rwxp regions on Linux count=" + std::to_string(rwxCount));
        }
    }
#endif
    return violations;
}

}
