#include <jni.h>
#include <iostream>

extern "C" jlong Java_com_nuvio_app_features_screensaver_LinuxIdleNative_open(JNIEnv*, jobject);
extern "C" jlong Java_com_nuvio_app_features_screensaver_LinuxIdleNative_query(JNIEnv*, jobject, jlong);
extern "C" void Java_com_nuvio_app_features_screensaver_LinuxIdleNative_close(JNIEnv*, jobject, jlong);

int main(int argc, char**) {
    for (int round = 0; round < 2; ++round) {
        auto handle = Java_com_nuvio_app_features_screensaver_LinuxIdleNative_open(nullptr, nullptr);
        for (int query = 0; query < 2; ++query) {
            std::cout << Java_com_nuvio_app_features_screensaver_LinuxIdleNative_query(nullptr, nullptr, handle) << std::endl;
            if (argc > 1 && query == 0) { std::string line; std::getline(std::cin, line); }
        }
        Java_com_nuvio_app_features_screensaver_LinuxIdleNative_close(nullptr, nullptr, handle);
    }
}
