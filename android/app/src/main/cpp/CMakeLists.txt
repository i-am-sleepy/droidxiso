cmake_minimum_required(VERSION 3.18)
project(xisonative C)

set(CMAKE_C_STANDARD 11)

# The upstream project (extract-xiso.c) lives at the repository root, five levels up.
# It is compiled UNMODIFIED: xiso_core.c #includes it.
get_filename_component(XISO_UPSTREAM_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)
if(NOT EXISTS "${XISO_UPSTREAM_DIR}/extract-xiso.c")
    message(FATAL_ERROR "extract-xiso.c not found in ${XISO_UPSTREAM_DIR}")
endif()

add_library(xisonative SHARED xiso_core.c xiso_jni.c)
target_include_directories(xisonative PRIVATE "${XISO_UPSTREAM_DIR}" "${CMAKE_CURRENT_LIST_DIR}")

# Upstream is old-style C: keep newer compilers from turning its warnings into errors.
target_compile_options(xisonative PRIVATE
    -O2 -w
    -Wno-error
    -Wno-error=implicit-function-declaration
    -Wno-error=int-conversion
    -Wno-error=incompatible-pointer-types
    -Wno-error=incompatible-function-pointer-types)
