cmake_minimum_required(VERSION 3.24)

project(nuvio_player_bridge_linux LANGUAGES CXX)

if(NOT CMAKE_SYSTEM_NAME STREQUAL "Linux")
    message(FATAL_ERROR "This player bridge is Linux-only")
endif()

find_package(JNI REQUIRED COMPONENTS JVM AWT)
find_package(Threads REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(GTK3 REQUIRED IMPORTED_TARGET gtk+-3.0)
pkg_check_modules(WEBKITGTK REQUIRED IMPORTED_TARGET webkit2gtk-4.1)
# The controls overlay uses Xlib; JAWT also needs the protocol headers.
find_path(X11_INCLUDE_DIR NAMES X11/Xlib.h REQUIRED)
find_path(X11_PROTOCOL_INCLUDE_DIR NAMES X11/X.h REQUIRED)
find_library(X11_LIBRARY NAMES X11 REQUIRED)

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

add_library(player_bridge SHARED player_bridge.cpp awt_view_resolver.cpp controls_overlay.cpp)
target_compile_features(player_bridge PRIVATE cxx_std_17)
target_include_directories(player_bridge PRIVATE "${MPV_INCLUDE_DIR}")
target_include_directories(player_bridge SYSTEM PRIVATE
    ${JNI_INCLUDE_DIRS} "${X11_INCLUDE_DIR}" "${X11_PROTOCOL_INCLUDE_DIR}")
target_link_libraries(player_bridge PRIVATE JNI::JNI JNI::AWT Threads::Threads "${MPV_LIBRARY}"
    PkgConfig::GTK3 PkgConfig::WEBKITGTK "${X11_LIBRARY}")
target_link_options(player_bridge PRIVATE "LINKER:-z,defs")

get_filename_component(MPV_LIBRARY_DIR "${MPV_LIBRARY}" DIRECTORY)
set_target_properties(player_bridge PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED YES
    CXX_EXTENSIONS OFF
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
    # Local development only: resolve libmpv without requiring LD_LIBRARY_PATH.
    BUILD_RPATH "$ORIGIN;${MPV_LIBRARY_DIR}"
)
