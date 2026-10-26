cmake_minimum_required(VERSION 3.22)
project(rover_receiver LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
include(FetchContent)
FetchContent_Declare(vstreamer GIT_REPOSITORY https://github.com/FlyingComputer2/vstreamer.git
    GIT_TAG f4044615fe6857dc1a7dd3fdb8704b492d618caf)
FetchContent_Declare(bfc GIT_REPOSITORY https://github.com/therooftopprinz/BFC.git
    GIT_TAG 6c02a495d1a2cf4a8a13dfa6b25b342780527c89)
FetchContent_Declare(isal GIT_REPOSITORY https://github.com/intel/isa-l.git GIT_TAG v2.31.1 GIT_SHALLOW TRUE)
# Populate only: the full desktop application is intentionally not configured.
foreach(dep vstreamer bfc isal)
    FetchContent_GetProperties(${dep})
    if(NOT ${dep}_POPULATED)
        FetchContent_Populate(${dep})
    endif()
endforeach()
add_library(receiver_core STATIC receiver.cpp
    ${vstreamer_SOURCE_DIR}/src/core/rtp_h264.cpp
    ${vstreamer_SOURCE_DIR}/src/core/rs_block_erasure.cpp
    ${vstreamer_SOURCE_DIR}/src/core/rs_block_erasure_rx.cpp
    ${vstreamer_SOURCE_DIR}/src/core/rs_encode_scalar.cpp
    ${isal_SOURCE_DIR}/erasure_code/ec_base.c)
target_include_directories(receiver_core PUBLIC . ${vstreamer_SOURCE_DIR}/src ${bfc_SOURCE_DIR}/src
    PRIVATE ${isal_SOURCE_DIR}/include ${isal_SOURCE_DIR}/erasure_code)
# Portable ISA-L primitives; no architecture-specific ABI or assembly dependency.
target_compile_definitions(receiver_core PRIVATE NDEBUG)
if(ROVER_NATIVE_TESTS)
    enable_testing()
    add_executable(receiver_test ../../../../native-tests/receiver_test.cpp)
    target_link_libraries(receiver_test receiver_core)
    add_test(NAME receiver_test COMMAND receiver_test)
else()
    add_library(rover_receiver SHARED jni.cpp)
    target_link_libraries(rover_receiver receiver_core log)
endif()
