/*
 * JNI bridge for Dilithium3 post-quantum signatures.
 * Exposes key generation, signing and verification to Kotlin/Java.
 *
 * Copyright (c) 2025-present The Q-BitX Core developers
 * MIT License
 */
#include <jni.h>
#include <string.h>
#include <android/log.h>

#include "api.h"
#include "params.h"
#include "sign.h"
#include "randombytes.h"

#define TAG "QBitX-Dilithium"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ---- Key Generation ---- */
JNIEXPORT jobjectArray JNICALL
Java_org_qbitx_wallet_crypto_DilithiumNative_generateKeypair(JNIEnv *env, jobject thiz) {
    uint8_t pk[CRYPTO_PUBLICKEYBYTES];
    uint8_t sk[CRYPTO_SECRETKEYBYTES];

    if (pqcrystals_dilithium3_ref_keypair(pk, sk) != 0) {
        LOGE("Key generation failed");
        return NULL;
    }

    /* Return [pubkey, privkey] as byte[][] */
    jclass byteArrayClass = (*env)->FindClass(env, "[B");
    jobjectArray result = (*env)->NewObjectArray(env, 2, byteArrayClass, NULL);

    jbyteArray pubkey = (*env)->NewByteArray(env, CRYPTO_PUBLICKEYBYTES);
    (*env)->SetByteArrayRegion(env, pubkey, 0, CRYPTO_PUBLICKEYBYTES, (jbyte*)pk);
    (*env)->SetObjectArrayElement(env, result, 0, pubkey);

    jbyteArray privkey = (*env)->NewByteArray(env, CRYPTO_SECRETKEYBYTES);
    (*env)->SetByteArrayRegion(env, privkey, 0, CRYPTO_SECRETKEYBYTES, (jbyte*)sk);
    (*env)->SetObjectArrayElement(env, result, 1, privkey);

    /* Wipe secret key from stack */
    memset(sk, 0, CRYPTO_SECRETKEYBYTES);

    LOGI("Keypair generated: pk=%d bytes, sk=%d bytes", CRYPTO_PUBLICKEYBYTES, CRYPTO_SECRETKEYBYTES);
    return result;
}

/* ---- Sign ---- */
JNIEXPORT jbyteArray JNICALL
Java_org_qbitx_wallet_crypto_DilithiumNative_sign(JNIEnv *env, jobject thiz,
                                                   jbyteArray message, jbyteArray secretKey) {
    jsize msgLen = (*env)->GetArrayLength(env, message);
    jsize skLen  = (*env)->GetArrayLength(env, secretKey);

    if (skLen != CRYPTO_SECRETKEYBYTES) {
        LOGE("Invalid secret key size: %d (expected %d)", skLen, CRYPTO_SECRETKEYBYTES);
        return NULL;
    }

    jbyte *msgBytes = (*env)->GetByteArrayElements(env, message, NULL);
    jbyte *skBytes  = (*env)->GetByteArrayElements(env, secretKey, NULL);

    uint8_t sig[CRYPTO_BYTES];
    size_t siglen = 0;

    int rc = pqcrystals_dilithium3_ref_signature(
        sig, &siglen,
        (const uint8_t*)msgBytes, (size_t)msgLen,
        NULL, 0,                  /* no context */
        (const uint8_t*)skBytes
    );

    (*env)->ReleaseByteArrayElements(env, message, msgBytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, secretKey, skBytes, JNI_ABORT);

    if (rc != 0) {
        LOGE("Signing failed (rc=%d)", rc);
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize)siglen);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)siglen, (jbyte*)sig);
    return result;
}

/* ---- Verify ---- */
JNIEXPORT jboolean JNICALL
Java_org_qbitx_wallet_crypto_DilithiumNative_verify(JNIEnv *env, jobject thiz,
                                                     jbyteArray signature, jbyteArray message,
                                                     jbyteArray publicKey) {
    jsize sigLen = (*env)->GetArrayLength(env, signature);
    jsize msgLen = (*env)->GetArrayLength(env, message);
    jsize pkLen  = (*env)->GetArrayLength(env, publicKey);

    if (pkLen != CRYPTO_PUBLICKEYBYTES) {
        LOGE("Invalid public key size: %d (expected %d)", pkLen, CRYPTO_PUBLICKEYBYTES);
        return JNI_FALSE;
    }

    jbyte *sigBytes = (*env)->GetByteArrayElements(env, signature, NULL);
    jbyte *msgBytes = (*env)->GetByteArrayElements(env, message, NULL);
    jbyte *pkBytes  = (*env)->GetByteArrayElements(env, publicKey, NULL);

    int rc = pqcrystals_dilithium3_ref_verify(
        (const uint8_t*)sigBytes, (size_t)sigLen,
        (const uint8_t*)msgBytes, (size_t)msgLen,
        NULL, 0,
        (const uint8_t*)pkBytes
    );

    (*env)->ReleaseByteArrayElements(env, signature, sigBytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, message, msgBytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, publicKey, pkBytes, JNI_ABORT);

    return (rc == 0) ? JNI_TRUE : JNI_FALSE;
}

/* ---- Constants ---- */
JNIEXPORT jint JNICALL
Java_org_qbitx_wallet_crypto_DilithiumNative_getPublicKeyBytes(JNIEnv *env, jobject thiz) {
    return CRYPTO_PUBLICKEYBYTES;
}

JNIEXPORT jint JNICALL
Java_org_qbitx_wallet_crypto_DilithiumNative_getSecretKeyBytes(JNIEnv *env, jobject thiz) {
    return CRYPTO_SECRETKEYBYTES;
}

JNIEXPORT jint JNICALL
Java_org_qbitx_wallet_crypto_DilithiumNative_getSignatureBytes(JNIEnv *env, jobject thiz) {
    return CRYPTO_BYTES;
}
