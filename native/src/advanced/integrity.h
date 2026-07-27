#pragma once
#include <string>
#include <vector>

namespace anticheat {
namespace advanced {

class Integrity {
public:
    // Compute SHA-256 of file
    static std::string sha256File(const std::string& path);
    
    // Verify self DLL integrity vs expected hash (hardcoded at build time)
    bool verifySelf(const std::string& expectedHash);
    
    // Verify target jar
    bool verifyJar(const std::string& jarPath, const std::string& expectedHash, std::string& outActualHash);

private:
    static std::string getOwnDllPath();
};

}
}
