#pragma once
// public domain SHA256 implementation
#include <cstdint>
#include <string>
#include <vector>

namespace anticheat {
    struct SHA256 {
        static std::vector<uint8_t> hash(const std::string& data);
        static std::vector<uint8_t> hash(const std::vector<uint8_t>& data);
        static uint64_t hash64_trunc(const std::string& data); // first 8 bytes big endian
    };
}
