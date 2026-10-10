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

add_library(player_bridge SHARED player_bridge.cpp awt_view_resolver.cpp controls_overlay.cpp seek_thumbnails.cpp playback_inhibitor.cpp screensaver_idle.cpp mpris_session.cpp linux_gamepad.cpp window_chrome.cpp window_chrome_jni.cpp pip_geometry_jni.cpp)
# Optional X11 idle fallback. Wayland sessions never use an XWayland global idle estimate.
find_path(XSS_INCLUDE_DIR NAMES X11/extensions/scrnsaver.h)
if(XSS_INCLUDE_DIR)
    target_compile_definitions(player_bridge PRIVATE NUVIO_HAVE_XSS)
    target_include_directories(player_bridge SYSTEM PRIVATE "${XSS_INCLUDE_DIR}")
    target_link_libraries(player_bridge PRIVATE ${CMAKE_DL_LIBS})
endif()
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

# Built explicitly by the opt-in smoke suite; no test hooks in player_bridge.
add_executable(linux_seek_thumbnail_probe EXCLUDE_FROM_ALL
    tests/seek_thumbnail_probe.cpp seek_thumbnails.cpp)
target_compile_features(linux_seek_thumbnail_probe PRIVATE cxx_std_17)
target_compile_definitions(linux_seek_thumbnail_probe PRIVATE NUVIO_SEEK_THUMBNAIL_TESTING)
target_include_directories(linux_seek_thumbnail_probe PRIVATE "${MPV_INCLUDE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(linux_seek_thumbnail_probe PRIVATE Threads::Threads "${MPV_LIBRARY}" PkgConfig::GTK3)
target_link_options(linux_seek_thumbnail_probe PRIVATE "LINKER:--wrap=mpv_initialize")
set_target_properties(linux_seek_thumbnail_probe PROPERTIES BUILD_RPATH "${MPV_LIBRARY_DIR}")

# Deterministic chrome policy fixtures; no display, WM, mpv or JNI initialization.
add_executable(linux_pip_geometry_dispatch_test EXCLUDE_FROM_ALL tests/pip_geometry_dispatch_test.cpp)
target_compile_features(linux_pip_geometry_dispatch_test PRIVATE cxx_std_17)
target_compile_options(linux_pip_geometry_dispatch_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_pip_geometry_dispatch_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(linux_pip_geometry_dispatch_test PRIVATE Threads::Threads)

add_executable(linux_pip_hud_geometry_test EXCLUDE_FROM_ALL tests/pip_hud_geometry_test.cpp)
target_compile_features(linux_pip_hud_geometry_test PRIVATE cxx_std_17)
target_compile_options(linux_pip_hud_geometry_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_pip_hud_geometry_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

add_executable(linux_window_chrome_test EXCLUDE_FROM_ALL tests/window_chrome_test.cpp window_chrome.cpp)
target_compile_features(linux_window_chrome_test PRIVATE cxx_std_17)
target_compile_options(linux_window_chrome_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_window_chrome_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

# Deterministic controller fixtures; no real devices, display, mpv or JNI initialization.
add_executable(linux_gamepad_test EXCLUDE_FROM_ALL tests/linux_gamepad_test.cpp linux_gamepad.cpp)
target_compile_features(linux_gamepad_test PRIVATE cxx_std_17)
target_compile_definitions(linux_gamepad_test PRIVATE NUVIO_GAMEPAD_TEST)
target_compile_options(linux_gamepad_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_gamepad_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

# Read-only idle backend lifecycle probe; does not initialize mpv or GTK.
add_executable(linux_screensaver_idle_probe EXCLUDE_FROM_ALL
    tests/screensaver_idle_probe.cpp screensaver_idle.cpp)
target_compile_features(linux_screensaver_idle_probe PRIVATE cxx_std_17)
target_include_directories(linux_screensaver_idle_probe SYSTEM PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(linux_screensaver_idle_probe PRIVATE PkgConfig::GTK3)
if(XSS_INCLUDE_DIR)
    target_compile_definitions(linux_screensaver_idle_probe PRIVATE NUVIO_HAVE_XSS)
    target_include_directories(linux_screensaver_idle_probe SYSTEM PRIVATE "${XSS_INCLUDE_DIR}" "${X11_INCLUDE_DIR}" "${X11_PROTOCOL_INCLUDE_DIR}")
    target_link_libraries(linux_screensaver_idle_probe PRIVATE ${CMAKE_DL_LIBS} "${X11_LIBRARY}")
endif()

add_executable(linux_pip_hud_visibility_test EXCLUDE_FROM_ALL tests/pip_hud_visibility_test.cpp)
target_compile_features(linux_pip_hud_visibility_test PRIVATE cxx_std_17)
target_compile_options(linux_pip_hud_visibility_test PRIVATE -Wall -Wextra -Werror)
target_include_directories(linux_pip_hud_visibility_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
