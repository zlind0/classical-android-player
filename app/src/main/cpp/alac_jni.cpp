// JNI bridge for Apple's reference ALAC decoder (Apache-2.0, vendored under ./alac).
// One decoder instance per AirPlay session; no JNI callbacks, no allocation on the
// caller's audio thread other than the caller-provided output array.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "alac/ALACDecoder.h"
#include "alac/ALACBitUtilities.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_aurora_music_data_cast_receiver_airplay_AlacBridge_nativeCreate(
    JNIEnv *, jobject,
    jint frameLength, jint compatibleVersion, jint bitDepth, jint pb, jint mb, jint kb,
    jint numChannels, jint maxRun, jint maxFrameBytes, jint avgBitRate, jint sampleRate) {
    // ALACSpecificConfig is a 24-byte big-endian magic cookie.
    uint8_t cookie[24];
    memset(cookie, 0, sizeof(cookie));
    cookie[0] = (uint8_t) (frameLength >> 24);
    cookie[1] = (uint8_t) (frameLength >> 16);
    cookie[2] = (uint8_t) (frameLength >> 8);
    cookie[3] = (uint8_t) frameLength;
    cookie[4] = (uint8_t) compatibleVersion;
    cookie[5] = (uint8_t) bitDepth;
    cookie[6] = (uint8_t) pb;
    cookie[7] = (uint8_t) mb;
    cookie[8] = (uint8_t) kb;
    cookie[9] = (uint8_t) numChannels;
    cookie[10] = (uint8_t) (maxRun >> 8);
    cookie[11] = (uint8_t) maxRun;
    cookie[12] = (uint8_t) (maxFrameBytes >> 24);
    cookie[13] = (uint8_t) (maxFrameBytes >> 16);
    cookie[14] = (uint8_t) (maxFrameBytes >> 8);
    cookie[15] = (uint8_t) maxFrameBytes;
    cookie[16] = (uint8_t) (avgBitRate >> 24);
    cookie[17] = (uint8_t) (avgBitRate >> 16);
    cookie[18] = (uint8_t) (avgBitRate >> 8);
    cookie[19] = (uint8_t) avgBitRate;
    cookie[20] = (uint8_t) (sampleRate >> 24);
    cookie[21] = (uint8_t) (sampleRate >> 16);
    cookie[22] = (uint8_t) (sampleRate >> 8);
    cookie[23] = (uint8_t) sampleRate;

    ALACDecoder *decoder = new ALACDecoder();
    if (decoder->Init(cookie, sizeof(cookie)) != ALAC_noErr) {
        delete decoder;
        return 0;
    }
    return (jlong) (intptr_t) decoder;
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_data_cast_receiver_airplay_AlacBridge_nativeDecode(
    JNIEnv *env, jobject, jlong handle, jbyteArray packet, jint packetLen,
    jshortArray out, jint maxSamples) {
    ALACDecoder *decoder = (ALACDecoder *) (intptr_t) handle;
    if (!decoder || !packet || !out) return 0;
    jsize inLen = env->GetArrayLength(packet);
    if (packetLen <= 0 || packetLen > inLen) packetLen = inLen;

    jbyte *in = env->GetByteArrayElements(packet, nullptr);
    jshort *outPtr = env->GetShortArrayElements(out, nullptr);
    if (!in || !outPtr) {
        if (in) env->ReleaseByteArrayElements(packet, in, JNI_ABORT);
        if (outPtr) env->ReleaseShortArrayElements(out, outPtr, JNI_ABORT);
        return 0;
    }

    BitBuffer bits;
    BitBufferInit(&bits, (uint8_t *) in, (uint32_t) packetLen);
    uint32_t numSamples = 0;
    int32_t status = decoder->Decode(
        &bits, (uint8_t *) outPtr, (uint32_t) maxSamples, 2, &numSamples);

    env->ReleaseByteArrayElements(packet, in, JNI_ABORT);
    env->ReleaseShortArrayElements(out, outPtr, 0);
    return status == ALAC_noErr ? (jint) numSamples : 0;
}

JNIEXPORT void JNICALL
Java_com_aurora_music_data_cast_receiver_airplay_AlacBridge_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    ALACDecoder *decoder = (ALACDecoder *) (intptr_t) handle;
    delete decoder;
}

} // extern "C"
