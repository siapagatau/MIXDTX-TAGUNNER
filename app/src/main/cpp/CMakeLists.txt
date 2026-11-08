cmake_minimum_required(VERSION 3.22.1)
project("karaokeengine")

find_package(oboe REQUIRED CONFIG)

add_library(karaokeengine SHARED
    native-lib.cpp
    KaraokeEngine.cpp
    SysReverb.cpp
)

target_link_libraries(karaokeengine
    oboe::oboe
    log
    android
)

# 16 KB page size (syarat Google Play) - sama seperti APK DTX.
target_link_options(karaokeengine PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
