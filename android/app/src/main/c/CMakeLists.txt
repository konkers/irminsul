cmake_minimum_required(VERSION 3.18.1)

project(irminsul_capture)

add_library(capture SHARED
        capture_vpn.c
        jni_bridge.c
        zdtun/zdtun.c
        zdtun/utils.c
)

target_include_directories(capture PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/zdtun
        ${CMAKE_CURRENT_SOURCE_DIR}/zdtun/third_party
)

target_compile_definitions(capture PRIVATE
        ANDROID
)

target_link_libraries(capture
        android
        log
)
