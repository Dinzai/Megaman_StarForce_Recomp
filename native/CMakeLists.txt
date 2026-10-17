# SPDX-License-Identifier: GPL-3.0-or-later
# Star Force runtime core: melonDS (fetched at a pinned commit) + session/mod layer.
# Used by the Android Gradle build (externalNativeBuild) and the host test runner.
cmake_minimum_required(VERSION 3.22)
project(sfr_core C CXX)

# FetchContent_Populate(<name>) is deprecated from CMake 3.30; keep it working
# because we need EXCLUDE_FROM_ALL on CMake 3.22 (Android SDK's CMake).
if (POLICY CMP0169)
    cmake_policy(SET CMP0169 OLD)
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(SFR_BUILD_HOST "Build the headless host test runner" OFF)
option(SFR_ENABLE_JIT "Build the melonDS ARM JIT" ON)
set(MELONDS_SOURCE_DIR "" CACHE PATH "Use a local melonDS checkout instead of fetching")

# melonDS 1.1 (GPL-3.0-or-later). Pinned by commit so builds are reproducible.
set(MELONDS_GIT_URL "https://github.com/melonDS-emu/melonDS.git")
set(MELONDS_GIT_COMMIT "b86390e4428bf38ce4c1ce0e9ca446d6d25955e8")

set(BUILD_QT_SDL OFF CACHE BOOL "" FORCE)
set(ENABLE_OGLRENDERER OFF CACHE BOOL "" FORCE)
set(ENABLE_GDBSTUB OFF CACHE BOOL "" FORCE)
set(ENABLE_JIT ${SFR_ENABLE_JIT} CACHE BOOL "" FORCE)
set(ENABLE_LTO_RELEASE OFF CACHE BOOL "" FORCE)
set(USE_VCPKG OFF CACHE BOOL "" FORCE)

if (MELONDS_SOURCE_DIR)
    add_subdirectory(${MELONDS_SOURCE_DIR} melonds EXCLUDE_FROM_ALL)
else()
    include(FetchContent)
    FetchContent_Declare(melonds
        GIT_REPOSITORY ${MELONDS_GIT_URL}
        GIT_TAG ${MELONDS_GIT_COMMIT}
        GIT_SHALLOW FALSE)
    FetchContent_GetProperties(melonds)
    if (NOT melonds_POPULATED)
        FetchContent_Populate(melonds)
        add_subdirectory(${melonds_SOURCE_DIR} ${melonds_BINARY_DIR} EXCLUDE_FROM_ALL)
    endif()
endif()

add_library(sfr_session STATIC
    src/rompatch.cpp
    src/session.cpp)
target_include_directories(sfr_session PUBLIC src)
target_link_libraries(sfr_session PUBLIC core)

# The Platform layer satisfies symbols the static melonDS core needs, so it
# is compiled straight into every final binary (avoids static link ordering).
set(SFR_PLATFORM_SRC ${CMAKE_CURRENT_SOURCE_DIR}/src/platform.cpp)

if (ANDROID)
    add_library(sfrcore SHARED src/jni_bridge.cpp ${SFR_PLATFORM_SRC})
    target_link_libraries(sfrcore PRIVATE sfr_session android log dl)
    target_link_options(sfrcore PRIVATE "-Wl,-z,max-page-size=16384")
endif()

if (SFR_BUILD_HOST)
    add_executable(sfhost host/sfhost.cpp ${SFR_PLATFORM_SRC})
    target_link_libraries(sfhost PRIVATE sfr_session dl pthread)
    add_executable(sfr_native_tests host/native_tests.cpp ${SFR_PLATFORM_SRC})
    target_link_libraries(sfr_native_tests PRIVATE sfr_session dl pthread)
endif()
