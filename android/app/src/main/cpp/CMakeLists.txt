cmake_minimum_required(VERSION 3.22.1)
project(aftercheck_stockfish_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# CI/local build helper checks out the exact Stockfish tag into this directory.
set(STOCKFISH_DIR "${CMAKE_CURRENT_LIST_DIR}/stockfish")
if(NOT EXISTS "${STOCKFISH_DIR}/uci.cpp" OR NOT EXISTS "${STOCKFISH_DIR}/evaluate.h")
    message(FATAL_ERROR "Stockfish source is missing. Run scripts/build-stockfish-android.sh or the documented CI setup first.")
endif()

set(STOCKFISH_SOURCES
    ${STOCKFISH_DIR}/attacks.cpp
    ${STOCKFISH_DIR}/benchmark.cpp
    ${STOCKFISH_DIR}/bitboard.cpp
    ${STOCKFISH_DIR}/engine.cpp
    ${STOCKFISH_DIR}/evaluate.cpp
    ${STOCKFISH_DIR}/memory.cpp
    ${STOCKFISH_DIR}/misc.cpp
    ${STOCKFISH_DIR}/movegen.cpp
    ${STOCKFISH_DIR}/movepick.cpp
    ${STOCKFISH_DIR}/position.cpp
    ${STOCKFISH_DIR}/score.cpp
    ${STOCKFISH_DIR}/search.cpp
    ${STOCKFISH_DIR}/thread.cpp
    ${STOCKFISH_DIR}/timeman.cpp
    ${STOCKFISH_DIR}/tt.cpp
    ${STOCKFISH_DIR}/tune.cpp
    ${STOCKFISH_DIR}/uci.cpp
    ${STOCKFISH_DIR}/ucioption.cpp
    ${STOCKFISH_DIR}/syzygy/tbprobe.cpp
    ${STOCKFISH_DIR}/nnue/nnue_accumulator.cpp
    ${STOCKFISH_DIR}/nnue/nnue_misc.cpp
    ${STOCKFISH_DIR}/nnue/network.cpp
    ${STOCKFISH_DIR}/nnue/features/half_ka_v2_hm.cpp
    ${STOCKFISH_DIR}/nnue/features/full_threats.cpp
    ${STOCKFISH_DIR}/nnue/features/pp_3wide.cpp
    stockfish_jni.cpp
)

add_library(stockfish-jni SHARED ${STOCKFISH_SOURCES})
target_include_directories(stockfish-jni PRIVATE
    ${STOCKFISH_DIR}
    ${STOCKFISH_DIR}/syzygy
    ${STOCKFISH_DIR}/nnue
    ${STOCKFISH_DIR}/nnue/features
)
target_compile_definitions(stockfish-jni PRIVATE
    NDEBUG
    NNUE_EMBEDDING_OFF
    USE_PTHREADS
)
target_compile_options(stockfish-jni PRIVATE
    -O3
    -fno-exceptions
    -Wall
    -Wcast-qual
)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(stockfish-jni PRIVATE ARCH=armv8 IS_64BIT USE_POPCNT USE_NEON=8)
    target_compile_options(stockfish-jni PRIVATE -march=armv8-a)
elseif(ANDROID_ABI STREQUAL "x86_64")
    target_compile_definitions(stockfish-jni PRIVATE ARCH=x86-64 IS_64BIT USE_SSE2)
    target_compile_options(stockfish-jni PRIVATE -msse2)
else()
    message(FATAL_ERROR "Unsupported AfterCheck native ABI: ${ANDROID_ABI}")
endif()

# Supports Android devices using 16 KB memory pages.
target_link_options(stockfish-jni PRIVATE "-Wl,-z,max-page-size=16384")
