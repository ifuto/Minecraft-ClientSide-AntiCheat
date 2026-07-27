This folder should contain libanticheat-native.so built from native/.

Build:
cd native
mkdir build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
make
cp libanticheat-native.so ../../client-fabric/src/main/resources/natives/linux/

Client mod will extract to mods/ and load via System.load()
Fallback to Java WatchService if not present.
