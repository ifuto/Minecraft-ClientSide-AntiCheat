#pragma once
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <mutex>
#include <functional>

namespace anticheat {

class FileMonitor {
public:
    FileMonitor(const std::string& modsPath);
    ~FileMonitor();

    // Start background monitoring thread
    void start();
    void stop();

    // Get and clear pending violations (file names)
    std::vector<std::string> getViolationsAndClear();

    // Callback for violation
    using ViolationCallback = std::function<void(const std::string&)>;

    void setCallback(ViolationCallback cb);

private:
    std::string modsPath;
    std::atomic<bool> running{false};
    std::thread worker;
    std::mutex violMutex;
    std::vector<std::string> violations;
    ViolationCallback callback;

    void workerLoop();
    void workerLoopWindows();
    void workerLoopLinux();
    bool checkFileName(const std::string& fileName);

    static uint64_t hash64(const std::string& s);
    static bool containsBlacklistedSubstring(const std::string& fileName);
};

}
