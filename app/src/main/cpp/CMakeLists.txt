cmake_minimum_required(VERSION 3.22.1)
project(ramenwine_native LANGUAGES CXX)

add_library(
    ramenwine_native
    SHARED
    ramenwine_native.cpp
)

target_compile_features(ramenwine_native PRIVATE cxx_std_17)

find_library(android_log_lib log)

target_link_libraries(
    ramenwine_native
    PRIVATE
    ${android_log_lib}
)
