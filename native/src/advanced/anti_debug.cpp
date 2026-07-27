#include "anti_debug.h"
#include <iostream>

#ifdef _WIN32
#include <windows.h>
#include <intrin.h>
#pragma intrinsic(__rdtsc)
#else
#include <sys/ptrace.h>
#endif

namespace anticheat {
namespace advanced {

bool AntiDebug::isDebuggerPresent() {
#ifdef _WIN32
    return IsDebuggerPresent() != FALSE;
#else
    // Linux: check if ptrace already attached (being debugged)
    if (ptrace(PTRACE_TRACEME, 0, 1, 0) == -1) {
        return true; // already being traced
    }
    ptrace(PTRACE_DETACH, 0, 0, 0);
    return false;
#endif
}

bool AntiDebug::isRemoteDebuggerPresent() {
#ifdef _WIN32
    BOOL isRemote = FALSE;
    CheckRemoteDebuggerPresent(GetCurrentProcess(), &isRemote);
    return isRemote != FALSE;
#else
    return false;
#endif
}

bool AntiDebug::checkNtQueryInformationProcess() {
#ifdef _WIN32
    // Use NtQueryInformationProcess to check ProcessDebugPort (7) and ProcessDebugFlags (31)
    HMODULE ntdll = GetModuleHandleA("ntdll.dll");
    if (!ntdll) return false;
    typedef NTSTATUS (WINAPI *pNtQuery)(HANDLE, ULONG, PVOID, ULONG, PULONG);
    auto NtQuery = (pNtQuery)GetProcAddress(ntdll, "NtQueryInformationProcess");
    if (!NtQuery) return false;

    DWORD debugPort = 0;
    NTSTATUS status = NtQuery(GetCurrentProcess(), 7, &debugPort, sizeof(debugPort), nullptr); // ProcessDebugPort
    if (NT_SUCCESS(status) && debugPort != 0) return true;

    DWORD debugFlags = 0;
    status = NtQuery(GetCurrentProcess(), 31, &debugFlags, sizeof(debugFlags), nullptr); // ProcessDebugFlags
    if (NT_SUCCESS(status) && debugFlags == 0) return true; // 0 means degenerate, being debugged

    return false;
#else
    return false;
#endif
}

bool AntiDebug::checkTiming() {
#ifdef _WIN32
    // RDTSC timing: measure time between two RDTSC, if large delta => debugger breakpoint / single step
    __try {
        unsigned __int64 t1 = __rdtsc();
        // Some work
        volatile int x = 0;
        for (int i=0;i<1000;i++) x+=i;
        unsigned __int64 t2 = __rdtsc();
        unsigned __int64 delta = t2 - t1;
        // Normally delta < 100k cycles. If > 1M, possible debugger / VM
        if (delta > 1000000) return true;
        return false;
    } __except(EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
#else
    return false;
#endif
}

std::vector<std::string> AntiDebug::scanAll() {
    std::vector<std::string> vios;
    if (isDebuggerPresent()) vios.push_back("IsDebuggerPresent true");
    if (isRemoteDebuggerPresent()) vios.push_back("Remote debugger present");
    if (checkNtQueryInformationProcess()) vios.push_back("NtQueryInformationProcess debug port/non-zero");
    if (checkTiming()) vios.push_back("RDTSC timing anomaly (possible debugger)");
    return vios;
}

}
}
