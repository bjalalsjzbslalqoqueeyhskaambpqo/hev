cmake_minimum_required(VERSION 3.22.1)
project(btproxy)

add_library(btproxy SHARED btproxy.c)

target_compile_options(btproxy PRIVATE
    -O3
    -march=native
    -ffast-math
    -fvisibility=hidden
    -Wall
)

target_link_libraries(btproxy
    android
    log
)
