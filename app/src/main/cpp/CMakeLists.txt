cmake_minimum_required(VERSION 3.22.1)
project("blacktunnel")

add_library(hev-jni SHARED hev_jni.cpp)
add_library(btproxy SHARED btproxy.c)

target_link_libraries(hev-jni android)
target_link_libraries(btproxy android)
