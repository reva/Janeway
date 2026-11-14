cmake_minimum_required(VERSION 3.22)
project(janeway_chromaprint LANGUAGES C CXX)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(FFT_LIB kissfft CACHE STRING "" FORCE)

set(CHROMAPRINT_DIR ${CMAKE_BINARY_DIR}/chromaprint-src)
# Chromaprint searches its bundled kissfft relative to the top-level project; point it there directly.
set(KISSFFT_SOURCE_DIR ${CHROMAPRINT_DIR}/src/3rdparty/kissfft CACHE PATH "" FORCE)

FetchContent_Declare(
    chromaprint
    URL https://github.com/acoustid/chromaprint/archive/refs/tags/v1.6.2.tar.gz
    URL_HASH SHA256=7f92ea1141429b43f695a1ba56d5058132f89fae5f137ce705afc57734f6b5fa
    SOURCE_DIR ${CHROMAPRINT_DIR}
)
FetchContent_MakeAvailable(chromaprint)

set_target_properties(chromaprint chromaprint_objs PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(janeway_chromaprint SHARED chromaprint_jni.cpp)
target_include_directories(janeway_chromaprint PRIVATE ${chromaprint_SOURCE_DIR}/src)
target_compile_definitions(janeway_chromaprint PRIVATE CHROMAPRINT_NODLL)
target_link_libraries(janeway_chromaprint PRIVATE chromaprint)
