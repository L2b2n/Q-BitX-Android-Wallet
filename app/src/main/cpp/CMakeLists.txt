cmake_minimum_required(VERSION 3.22.1)
project("qbitx_wallet" C)

# Dilithium3 reference implementation
add_library(dilithium3 STATIC
    dilithium/fips202.c
    dilithium/ntt.c
    dilithium/packing.c
    dilithium/poly.c
    dilithium/polyvec.c
    dilithium/randombytes.c
    dilithium/reduce.c
    dilithium/rounding.c
    dilithium/sign.c
    dilithium/symmetric-shake.c
)

target_include_directories(dilithium3 PUBLIC dilithium)
target_compile_definitions(dilithium3 PUBLIC
    DILITHIUM_MODE=3
    DILITHIUM_NAMESPACE=pqcrystals_dilithium3_ref_
)

# JNI bridge
add_library(qbitx_crypto SHARED dilithium_jni.c)
target_link_libraries(qbitx_crypto dilithium3 log)
target_include_directories(qbitx_crypto PRIVATE dilithium)
target_compile_definitions(qbitx_crypto PRIVATE
    DILITHIUM_MODE=3
    DILITHIUM_NAMESPACE=pqcrystals_dilithium3_ref_
)
