cmake_minimum_required(VERSION 3.22.1)
project(native_base LANGUAGES C)
add_library(fataar_math SHARED native.c)
target_include_directories(fataar_math PUBLIC include)
target_link_options(fataar_math PRIVATE "-Wl,-z,max-page-size=16384")
add_library(fataar_static STATIC native_static.c)
target_include_directories(fataar_static PUBLIC static-include)
# AGP includes every Prefab module name in its native target validation, even headerOnly.
# This build target satisfies that check; the Prefab module exports headers only.
add_library(fataar_headers STATIC header_stub.c)
