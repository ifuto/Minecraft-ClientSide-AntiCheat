cmake_minimum_required(VERSION 3.15)
project(anticheat-native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find JNI
find_package(JNI REQUIRED)

include_directories(${JNI_INCLUDE_DIRS})
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/src)

# Source files - evolution includes advanced guards
set(SOURCES
    src/main.cpp
    src/sha256.cpp
    src/file_monitor.cpp
    src/memory_guard.cpp
    src/input_hook.cpp
    src/jni_bridge.cpp
    src/advanced/syscall_guard.cpp
    src/advanced/anti_debug.cpp
    src/advanced/hwid.cpp
    src/advanced/integrity.cpp
    src/advanced/advanced_jni.cpp
)

# Build shared library (DLL / SO)
add_library(anticheat-native SHARED ${SOURCES})

# On Windows, need psapi
if(WIN32)
    target_link_libraries(anticheat-native psapi)
endif()

# Set output name
set_target_properties(anticheat-native PROPERTIES
    OUTPUT_NAME "anticheat-native"
    PREFIX ""
    SUFFIX ".dll"
)

if(UNIX AND NOT WIN32)
    set_target_properties(anticheat-native PROPERTIES
        PREFIX "lib"
        SUFFIX ".so"
    )
endif()

# Compiler flags for security (control flow guard, ASLR etc)
if(MSVC)
    target_compile_options(anticheat-native PRIVATE /W4 /guard:cf)
    target_link_options(anticheat-native PRIVATE /DYNAMICBASE /NXCOMPAT /GUARD:CF)
else()
    target_compile_options(anticheat-native PRIVATE -Wall -Wextra -fstack-protector-strong -fPIC)
endif()

# Installation
install(TARGETS anticheat-native DESTINATION lib)

message(STATUS "JNI include dirs: ${JNI_INCLUDE_DIRS}")
message(STATUS "Build type: ${CMAKE_BUILD_TYPE}")
