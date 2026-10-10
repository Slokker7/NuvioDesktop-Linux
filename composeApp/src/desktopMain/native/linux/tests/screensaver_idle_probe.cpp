#include <jni.h>
#include <iostream>
#include <cstring>
#include "../dbus_connection.h"

extern "C" jlong Java_com_nuvio_app_features_screensaver_LinuxIdleNative_open(JNIEnv*, jobject);
extern "C" jlong Java_com_nuvio_app_features_screensaver_LinuxIdleNative_query(JNIEnv*, jobject, jlong);
extern "C" void Java_com_nuvio_app_features_screensaver_LinuxIdleNative_close(JNIEnv*, jobject, jlong);

int main(int argc, char** argv) {
    if (argc > 1 && std::strcmp(argv[1], "--connection") == 0) {
        // The same connection helper used by playback inhibition, without acquiring
        // an inhibitor or starting a JVM/player/display.
        GError* error = nullptr;
        auto* bus = nuvio::dbus::connect(G_BUS_TYPE_SESSION, 1500, &error);
        std::cout << (bus ? "connected" : "unavailable") << std::endl;
        if (bus) {
            if (!g_dbus_connection_close_sync(bus, nullptr, nullptr) || !g_dbus_connection_is_closed(bus)) return 1;
            g_object_unref(bus);
        }
        g_clear_error(&error);
        return 0;
    }
    for (int round = 0; round < 2; ++round) {
        auto handle = Java_com_nuvio_app_features_screensaver_LinuxIdleNative_open(nullptr, nullptr);
        for (int query = 0; query < 2; ++query) {
            std::cout << Java_com_nuvio_app_features_screensaver_LinuxIdleNative_query(nullptr, nullptr, handle) << std::endl;
            if (argc > 1 && query == 0) { std::string line; std::getline(std::cin, line); }
        }
        Java_com_nuvio_app_features_screensaver_LinuxIdleNative_close(nullptr, nullptr, handle);
    }
}
