#include "syscall_guard.h"
#include <iostream>

#ifdef _WIN32
#include <windows.h>
#include <psapi.h>
#endif

namespace anticheat {
namespace advanced {

bool SyscallGuard::isPeHeader(void* addr) {
#ifdef _WIN32
    __try {
        auto dos = (IMAGE_DOS_HEADER*)addr;
        if (dos->e_magic != IMAGE_DOS_SIGNATURE) return false;
        auto nt = (IMAGE_NT_HEADERS*)((BYTE*)addr + dos->e_lfanew);
        if (nt->Signature != IMAGE_NT_SIGNATURE) return false;
        return true;
    } __except(EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
#else
    return false;
#endif
}

bool SyscallGuard::isHooked(void* funcAddr) {
#ifdef _WIN32
    __try {
        BYTE* b = (BYTE*)funcAddr;
        // JMP rel (0xE9), JMP [rip+...] (0xFF 0x25), or RET (0xC3) as first byte for some hooks
        if (b[0] == 0xE9 || b[0] == 0xEB || (b[0]==0xFF && b[1]==0x25) ) {
            return true;
        }
        return false;
    } __except(EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
#else
    return false;
#endif
}

std::vector<std::string> SyscallGuard::scanForManualMappedDlls() {
    std::vector<std::string> violations;
#ifdef _WIN32
    SYSTEM_INFO sysInfo;
    GetSystemInfo(&sysInfo);
    uintptr_t addr = (uintptr_t)sysInfo.lpMinimumApplicationAddress;
    uintptr_t maxAddr = (uintptr_t)sysInfo.lpMaximumApplicationAddress;
    MEMORY_BASIC_INFORMATION mbi;

    // Get module list for comparison
    HMODULE hMods[1024];
    HANDLE hProcess = GetCurrentProcess();
    DWORD cbNeeded;
    std::vector<void*> moduleBases;
    if (EnumProcessModules(hProcess, hMods, sizeof(hMods), &cbNeeded)) {
        for (unsigned int i=0;i<cbNeeded/sizeof(HMODULE);i++) {
            MODULEINFO modInfo;
            if (GetModuleInformation(hProcess, hMods[i], &modInfo, sizeof(modInfo))) {
                moduleBases.push_back(modInfo.lpBaseOfDll);
            }
        }
    }

    while (addr < maxAddr) {
        if (VirtualQuery((LPCVOID)addr, &mbi, sizeof(mbi))==0) break;
        if (mbi.State == MEM_COMMIT && (mbi.Protect & (PAGE_EXECUTE_READ | PAGE_EXECUTE_READWRITE | PAGE_EXECUTE_WRITECOPY))) {
            // Check if this region starts with PE header and is not in module list
            if (isPeHeader(mbi.BaseAddress)) {
                bool inModuleList = false;
                for (void* base : moduleBases) {
                    if (base == mbi.BaseAddress) { inModuleList = true; break; }
                }
                if (!inModuleList) {
                    violations.push_back("Manual mapped DLL detected at " + std::to_string((uintptr_t)mbi.BaseAddress) + " size=" + std::to_string(mbi.RegionSize));
                }
            }
        }
        addr += mbi.RegionSize;
    }
#endif
    return violations;
}

std::vector<std::string> SyscallGuard::scanForHookedFunctions() {
    std::vector<std::string> violations;
#ifdef _WIN32
    HMODULE ntdll = GetModuleHandleA("ntdll.dll");
    if (!ntdll) return violations;
    // List of critical NT functions often hooked by cheats/hacks
    const char* funcs[] = {"NtOpenProcess", "NtReadVirtualMemory", "NtWriteVirtualMemory", "NtQuerySystemInformation", "NtSetInformationThread"};
    for (const char* fname : funcs) {
        FARPROC addr = GetProcAddress(ntdll, fname);
        if (addr && isHooked((void*)addr)) {
            violations.push_back(std::string("Hooked function detected: ") + fname);
        }
    }
    // Check OpenGL32 wglSwapBuffers hook (common for ESP)
    HMODULE opengl = GetModuleHandleA("opengl32.dll");
    if (opengl) {
        FARPROC swap = GetProcAddress(opengl, "wglSwapBuffers");
        if (swap && isHooked((void*)swap)) {
            violations.push_back("Hooked wglSwapBuffers (possible ESP overlay)");
        }
    }
#endif
    return violations;
}

std::vector<std::string> SyscallGuard::scanForHiddenThreads() {
    std::vector<std::string> violations;
    // Complex: enumerate threads via NtQuerySystemInformation(SystemProcessInformation) and compare with Toolhelp32Snapshot
    // For brevity, stub: if thread hiding via NtSetInformationThread(ThreadHideFromDebugger) detected, check...
    // In real implementation, would call NtQueryInformationThread with ThreadHideFromDebugger flag
    // Skipped for demo
    return violations;
}

}
}
