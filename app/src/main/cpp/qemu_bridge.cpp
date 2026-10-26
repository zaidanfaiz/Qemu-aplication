#include <jni.h>
#include <dlfcn.h>
#include <atomic>
#include <string>
#include <vector>
#include <android/log.h>
#include <cstdlib>
#include <cstring>

namespace {
std::atomic_flag running = ATOMIC_FLAG_INIT;
using init_type = void (*)(int, char**);
using loop_type = void (*)();
using cleanup_type = void (*)();
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_zaidan_qemu_NativeEngine_nativeRun(JNIEnv* env, jclass,
                                           jstring path, jobjectArray javaArgs) {
    if (running.test_and_set()) return env->NewStringUTF("A QEMU engine is already running");
    std::string error;
    void* handle = nullptr;
    std::vector<std::string> args;
    std::vector<char*> argv;
    const char* pathChars = nullptr;
    do {
        if (!path || !javaArgs) { error = "Invalid native parameters"; break; }
        pathChars = env->GetStringUTFChars(path, nullptr);
        if (!pathChars) { error = "Unable to read native library path"; break; }
        jsize count = env->GetArrayLength(javaArgs);
        for (jsize index = 0; index < count; ++index) {
            auto str = static_cast<jstring>(env->GetObjectArrayElement(javaArgs, index));
            if (!str) { error = "Null QEMU argument"; break; }
            const char* raw = env->GetStringUTFChars(str, nullptr);
            if (!raw) { error = "Failed to decode QEMU argument"; env->DeleteLocalRef(str); break; }
            args.emplace_back(raw);
            env->ReleaseStringUTFChars(str, raw);
            env->DeleteLocalRef(str);
        }
        if (!error.empty()) break;
        argv.reserve(args.size() + 1);
        for (auto& item : args) argv.push_back(item.data());
        argv.push_back(nullptr);

        // A separately compiled, Android/Bionic-compatible QEMU shared object must
        // export qemu_init, qemu_main_loop and qemu_cleanup. A stock Linux executable
        // or unmodified system library is not an interchangeable substitute.
        handle = dlopen(pathChars, RTLD_NOW | RTLD_LOCAL);
        if (!handle) { const char* detail = dlerror(); error = detail ? detail : "dlopen failed"; break; }
        auto init = reinterpret_cast<init_type>(dlsym(handle, "qemu_init"));
        auto loop = reinterpret_cast<loop_type>(dlsym(handle, "qemu_main_loop"));
        auto cleanup = reinterpret_cast<cleanup_type>(dlsym(handle, "qemu_cleanup"));
        if (!init || !loop || !cleanup) {
            error = "QEMU library lacks required native entry points";
            break;
        }
        init(static_cast<int>(args.size()), argv.data());
        loop();
        cleanup();
    } while (false);
    if (handle) dlclose(handle);
    if (pathChars) env->ReleaseStringUTFChars(path, pathChars);
    running.clear();
    return env->NewStringUTF(error.c_str());
}
