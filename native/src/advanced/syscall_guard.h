#pragma once
#include <vector>
#include <string>

namespace anticheat {
namespace advanced {

class SyscallGuard {
public:
    // Detect manual mapped DLLs by scanning memory for PE headers not in module list
    std::vector<std::string> scanForManualMappedDlls();
    
    // Detect hooked syscalls / ntdll hooks (first byte = 0xE9 JMP)
    std::vector<std::string> scanForHookedFunctions();
    
    // Detect hidden threads (NtQueryInformationThread)
    std::vector<std::string> scanForHiddenThreads();

private:
    bool isPeHeader(void* addr);
    bool isHooked(void* funcAddr);
};

}
}
