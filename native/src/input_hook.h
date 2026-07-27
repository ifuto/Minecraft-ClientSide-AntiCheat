#pragma once
#include <atomic>
#include <cstdint>

namespace anticheat {

class InputHook {
public:
    InputHook();
    ~InputHook();

    bool install();
    void uninstall();

    static InputHook& getInstance();

    // Accessors
    int64_t getLastMouseClickTime() const { return lastMouseClick.load(); }
    int64_t getLastKeyPressTime() const { return lastKeyPress.load(); }
    int64_t getLastMouseMoveTime() const { return lastMouseMove.load(); }
    bool wasLastInjected() const { return lastInjected.load(); }

    // Called from hook callbacks
    void onMouseClick(bool injected);
    void onKeyPress(bool injected);
    void onMouseMove(bool injected);

private:
    std::atomic<int64_t> lastMouseClick{0};
    std::atomic<int64_t> lastKeyPress{0};
    std::atomic<int64_t> lastMouseMove{0};
    std::atomic<bool> lastInjected{false};

#ifdef _WIN32
    void* mouseHook = nullptr;
    void* keyboardHook = nullptr;
    static InputHook* instance;
    static int64_t currentTimeMs();
#endif
};

}
