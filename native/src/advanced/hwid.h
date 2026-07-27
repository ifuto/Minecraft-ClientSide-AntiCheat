#pragma once
#include <string>

namespace anticheat {
namespace advanced {

class HWID {
public:
    static std::string generate();
    static std::string getOSInfo();
    static std::string getHashedMac();
};

}
}
