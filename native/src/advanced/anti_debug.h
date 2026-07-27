#pragma once
#include <vector>
#include <string>

namespace anticheat {
namespace advanced {

class AntiDebug {
public:
    bool isDebuggerPresent();
    bool isRemoteDebuggerPresent();
    bool checkNtQueryInformationProcess();
    bool checkTiming(); // RDTSC timing check
    std::vector<std::string> scanAll();
};

}
}
