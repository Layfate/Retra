#include <jni.h>

#include <string>

#include "slang_compiler.h"

namespace {

std::string fromJava(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

/** NewStringUTF aborts on invalid modified UTF-8; GLSL sources and logs only need ASCII. */
std::string ascii(const std::string &value) {
    std::string result(value);
    for (char &c : result) {
        const auto code = static_cast<unsigned char>(c);
        if (code > 0x7e || (code < 0x20 && c != '\n' && c != '\t')) c = ' ';
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_retra_emulator_SlangNative_nativeConvert(JNIEnv *env, jclass, jstring vertex, jstring fragment) {
    const retra::SlangConversion result = retra::convertSlang(fromJava(env, vertex), fromJava(env, fragment));
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(4, stringClass, nullptr);
    const std::string values[4] = {result.ok ? "1" : "0", result.vertex, result.fragment, result.error};
    for (int i = 0; i < 4; i++) {
        jstring item = env->NewStringUTF(ascii(values[i]).c_str());
        env->SetObjectArrayElement(array, i, item);
        env->DeleteLocalRef(item);
    }
    return array;
}
