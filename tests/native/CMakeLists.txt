cmake_minimum_required(VERSION 3.22)
project(liveprog_lifecycle C)
set(CMAKE_C_STANDARD 11)
set(core "${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp")
# Use the same core source set as the Android build, without JNI or Android logging.
file(GLOB_RECURSE sources CONFIGURE_DEPENDS "${core}/*.c")
add_executable(liveprog_lifecycle liveprog_lifecycle_test.c ${sources})
target_include_directories(liveprog_lifecycle PRIVATE "${core}")
target_compile_options(liveprog_lifecycle PRIVATE -O1 -g -ffunction-sections -fdata-sections
    -fsanitize=address,undefined -fno-sanitize-recover=all
    -Wno-incompatible-pointer-types -Wno-implicit-int -Wno-discarded-qualifiers)
target_link_options(liveprog_lifecycle PRIVATE -fsanitize=address,undefined -Wl,--gc-sections)
target_link_libraries(liveprog_lifecycle PRIVATE m pthread)
option(LIVEPROG_ALIGNMENT_RECOVERY "Report inherited alignment UB but continue behavioral characterization" OFF)
if(LIVEPROG_ALIGNMENT_RECOVERY)
    target_compile_options(liveprog_lifecycle PRIVATE -fsanitize-recover=alignment)
endif()
