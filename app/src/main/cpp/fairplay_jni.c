// JNI bridge for the FairPlay handshake used by AirPlay screen mirroring.
// The handshake wrapper and playfair decryption are vendored from shairplay
// (src/lib/fairplay_playfair.c and src/lib/playfair, GPLv3 -- see playfair/LICENSE.md).
//
// Protocol:
//   POST /fp-setup, 16-byte body  -> 142-byte reply (canned, indexed by request[14])
//   POST /fp-setup, 164-byte body -> 32-byte reply (header + echo of request[144..164))
//   the 164-byte request is then kept; the 72-byte "ekey" from SETUP is decrypted
//   into the 16-byte AES key used by the mirroring audio/video streams.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "playfair/fairplay.h"

JNIEXPORT jlong JNICALL
Java_com_aurora_music_data_cast_receiver_airplay_FairPlayBridge_nativeCreate(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return (jlong) (intptr_t) calloc(1, sizeof(fairplay_t));
}

JNIEXPORT void JNICALL
Java_com_aurora_music_data_cast_receiver_airplay_FairPlayBridge_nativeDestroy(JNIEnv *env, jobject thiz, jlong h) {
    (void) env; (void) thiz;
    free((void *) (intptr_t) h);
}

JNIEXPORT jbyteArray JNICALL
Java_com_aurora_music_data_cast_receiver_airplay_FairPlayBridge_nativeSetup(
    JNIEnv *env, jobject thiz, jlong h, jbyteArray request) {
    (void) thiz;
    fairplay_t *fp = (fairplay_t *) (intptr_t) h;
    if (!fp || !request) return NULL;
    jsize len = (*env)->GetArrayLength(env, request);
    if (len != 16) return NULL;
    jbyte *in = (*env)->GetByteArrayElements(env, request, NULL);
    unsigned char out[142];
    int rc = fairplay_setup(fp, (const unsigned char *) in, out);
    (*env)->ReleaseByteArrayElements(env, request, in, JNI_ABORT);
    if (rc != 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, 142);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, 142, (const jbyte *) out);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_aurora_music_data_cast_receiver_airplay_FairPlayBridge_nativeHandshake(
    JNIEnv *env, jobject thiz, jlong h, jbyteArray request) {
    (void) thiz;
    fairplay_t *fp = (fairplay_t *) (intptr_t) h;
    if (!fp || !request) return NULL;
    jsize len = (*env)->GetArrayLength(env, request);
    if (len != 164) return NULL;
    jbyte *in = (*env)->GetByteArrayElements(env, request, NULL);
    unsigned char out[32];
    int rc = fairplay_handshake(fp, (const unsigned char *) in, out);
    (*env)->ReleaseByteArrayElements(env, request, in, JNI_ABORT);
    if (rc != 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, 32);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, 32, (const jbyte *) out);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_aurora_music_data_cast_receiver_airplay_FairPlayBridge_nativeDecrypt(
    JNIEnv *env, jobject thiz, jlong h, jbyteArray ekey) {
    (void) thiz;
    fairplay_t *fp = (fairplay_t *) (intptr_t) h;
    if (!fp || !ekey) return NULL;
    jsize len = (*env)->GetArrayLength(env, ekey);
    if (len != 72) return NULL;
    jbyte *in = (*env)->GetByteArrayElements(env, ekey, NULL);
    unsigned char out[16];
    int rc = fairplay_decrypt(fp, (const unsigned char *) in, out);
    (*env)->ReleaseByteArrayElements(env, ekey, in, JNI_ABORT);
    if (rc != 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, 16);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, 16, (const jbyte *) out);
    return result;
}
