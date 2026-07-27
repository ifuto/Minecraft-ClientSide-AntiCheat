#pragma once
#include <string>
#include <vector>

namespace anticheat {

class MemoryGuard {
public:
    MemoryGuard();
    std::vector<std::string> scan(); // returns list of violation descriptions

private:
    std::vector<std::string> scanModules();
    std::vector<std::string> scanMemoryRegions();

#ifdef _WIN32
    std::string getModuleBaseName(void* module);
#endif
};

}
