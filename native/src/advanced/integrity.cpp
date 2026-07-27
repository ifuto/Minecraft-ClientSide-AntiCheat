#include "integrity.h"
#include "../sha256.h"
#include <fstream>
#include <sstream>
#include <iomanip>

#ifdef _WIN32
#include <windows.h>
#endif

namespace anticheat {
namespace advanced {

std::string Integrity::sha256File(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) return "FILE_NOT_FOUND";
    std::vector<uint8_t> data((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    auto hash = SHA256::hash(data);
    std::ostringstream oss;
    for (auto b : hash) oss << std::hex << std::setw(2) << std::setfill('0') << (int)b;
    return oss.str();
}

std::string Integrity::getOwnDllPath() {
#ifdef _WIN32
    char path[MAX_PATH];
    HMODULE hm = NULL;
    if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT, (LPCSTR)&sha256File, &hm)) {
        GetModuleFileNameA(hm, path, sizeof(path));
        return std::string(path);
    }
#endif
    return "";
}

bool Integrity::verifySelf(const std::string& expectedHash) {
    if (expectedHash.empty() || expectedHash=="CHECKSUM_NOT_SET") return true; // skip in demo
    std::string selfPath = getOwnDllPath();
    if (selfPath.empty()) return false;
    std::string actual = sha256File(selfPath);
    return actual == expectedHash;
}

bool Integrity::verifyJar(const std::string& jarPath, const std::string& expectedHash, std::string& outActualHash) {
    outActualHash = sha256File(jarPath);
    if (expectedHash.empty() || expectedHash=="CHECKSUM_NOT_SET") return true;
    return outActualHash == expectedHash;
}

}
}
