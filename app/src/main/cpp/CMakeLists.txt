cmake_minimum_required(VERSION 3.22.1)
project("blacktunnel")

add_library(hev-socks5-tunnel SHARED IMPORTED)
set_target_properties(hev-socks5-tunnel PROPERTIES
    IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libhev-socks5-tunnel.so)

add_library(hev-jni SHARED hev_jni.cpp)
target_link_libraries(hev-jni android log hev-socks5-tunnel)
