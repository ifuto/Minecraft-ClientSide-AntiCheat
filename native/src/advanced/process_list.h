#pragma once
#include <vector>
#include <string>
namespace anticheat { namespace advanced {
class ProcessList {
public:
    std::vector<std::string> getRunningApps();
    std::vector<std::string> getRunningAppsDetailed();
};
}}
