#include <jni.h>
#include <cstdint>
#include <chromaprint.h>

namespace {
ChromaprintContext *ctx(jlong handle) {
    return reinterpret_cast<ChromaprintContext *>(handle);
}
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_reva_janeway_chromaprint_Chromaprint_nativeNew(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT));
}

JNIEXPORT jboolean JNICALL
Java_io_github_reva_janeway_chromaprint_Chromaprint_nativeStart(JNIEnv *, jclass, jlong handle, jint sampleRate,
                                                                jint channels) {
    return chromaprint_start(ctx(handle), sampleRate, channels) == 1;
}

// Feeds interleaved 16-bit PCM from a direct ByteBuffer, such as a MediaCodec output buffer.
JNIEXPORT jboolean JNICALL
Java_io_github_reva_janeway_chromaprint_Chromaprint_nativeFeed(JNIEnv *env, jclass, jlong handle, jobject buffer,
                                                               jint offsetBytes, jint sizeBytes) {
    auto *base = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    if (base == nullptr) return JNI_FALSE;
    auto *samples = reinterpret_cast<const int16_t *>(base + offsetBytes);
    return chromaprint_feed(ctx(handle), samples, sizeBytes / 2) == 1;
}

JNIEXPORT jstring JNICALL
Java_io_github_reva_janeway_chromaprint_Chromaprint_nativeFinish(JNIEnv *env, jclass, jlong handle) {
    if (chromaprint_finish(ctx(handle)) != 1) return nullptr;
    char *fingerprint = nullptr;
    if (chromaprint_get_fingerprint(ctx(handle), &fingerprint) != 1) return nullptr;
    jstring result = env->NewStringUTF(fingerprint);
    chromaprint_dealloc(fingerprint);
    return result;
}

JNIEXPORT void JNICALL
Java_io_github_reva_janeway_chromaprint_Chromaprint_nativeFree(JNIEnv *, jclass, jlong handle) {
    chromaprint_free(ctx(handle));
}

}
