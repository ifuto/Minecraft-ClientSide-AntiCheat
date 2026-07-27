This folder should contain anticheat-native.dll built from native/.

Build instructions:
cd native
mkdir build && cd build
cmake .. -G "Visual Studio 17 2022" -A x64
cmake --build . --config Release
copy Release/anticheat-native.dll ../../client-fabric/src/main/resources/natives/win64/

The client mod will extract this DLL to mods/ folder at runtime (same hierarchy as jar per requirement) and load it.

If this file is missing, the mod will fallback to Java-only WatchService monitoring, but native features (ReadDirectoryChangesW, EnumProcessModules, low-level input hooks) will be disabled.
