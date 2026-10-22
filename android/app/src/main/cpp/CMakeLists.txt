cmake_minimum_required(VERSION 3.22)
project(earshot C CXX)

find_package(oboe REQUIRED CONFIG)

# Prebuilt roc-toolkit 0.4.0 (native/build-roc-android.sh puts it in jniLibs).
add_library(roc SHARED IMPORTED)
set_target_properties(roc PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libroc.so
    INTERFACE_INCLUDE_DIRECTORIES ${CMAKE_CURRENT_SOURCE_DIR}/roc/include)

add_library(earshot SHARED
    engine.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../native/es_link.c)
target_include_directories(earshot PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../native)
target_compile_options(earshot PRIVATE -Wall -Wextra -O2 -ffast-math)
target_link_options(earshot PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(earshot oboe::oboe roc log android)
