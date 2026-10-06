cmake_minimum_required(VERSION 3.24)

project(nuvio_player_bridge_linux LANGUAGES CXX)

if(NOT CMAKE_SYSTEM_NAME STREQUAL "Linux")
    message(FATAL_ERROR "This player bridge is Linux-only")
endif()

# Only JNI is needed for this milestone; do not require AWT/X11 development packages.
find_package(JNI REQUIRED COMPONENTS JVM)

set(MPV_ROOT "$ENV{MPV_ROOT}" CACHE PATH "Root containing include/mpv and lib/libmpv.so")
if(MPV_ROOT)
    set(MPV_INCLUDE_DIR "${MPV_ROOT}/include")
    set(MPV_LIBRARY "${MPV_ROOT}/lib/libmpv.so")
else()
    # Avoid pkg-config: some installations have incomplete transitive .pc metadata.
    find_path(MPV_INCLUDE_DIR NAMES mpv/client.h REQUIRED)
    find_library(MPV_LIBRARY NAMES mpv REQUIRED)
endif()

if(NOT EXISTS "${MPV_INCLUDE_DIR}/mpv/client.h" OR
   NOT EXISTS "${MPV_INCLUDE_DIR}/mpv/render.h" OR
   NOT EXISTS "${MPV_LIBRARY}")
    message(FATAL_ERROR "libmpv headers/library not found; set MPV_ROOT to the mpv installation prefix")
endif()

add_library(player_bridge SHARED player_bridge.cpp)
target_compile_features(player_bridge PRIVATE cxx_std_17)
target_include_directories(player_bridge PRIVATE "${MPV_INCLUDE_DIR}")
target_link_libraries(player_bridge PRIVATE JNI::JNI "${MPV_LIBRARY}")
target_link_options(player_bridge PRIVATE "LINKER:-z,defs")

get_filename_component(MPV_LIBRARY_DIR "${MPV_LIBRARY}" DIRECTORY)
set_target_properties(player_bridge PROPERTIES
    CXX_EXTENSIONS OFF
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
    # Local development only: resolve libmpv without requiring LD_LIBRARY_PATH.
    BUILD_RPATH "$ORIGIN;${MPV_LIBRARY_DIR}"
)
