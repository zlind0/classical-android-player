cmake_minimum_required(VERSION 3.22.1)
project(aurora_fp)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CP ${CMAKE_CURRENT_SOURCE_DIR}/chromaprint)
set(KISS ${CP}/3rdparty/kissfft)

# HAVE_CONFIG_H pulls in our hand-written config.h (KissFFT, internal resampler). The EXPORTS symbol
# makes the Chromaprint API visible since we compile it into the same shared object we call it from.
add_definitions(
    -DHAVE_CONFIG_H
    -DCHROMAPRINT_API_EXPORTS
    -D_USE_MATH_DEFINES
    -D__STDC_LIMIT_MACROS
    -D__STDC_CONSTANT_MACROS
)

include_directories(${CP} ${KISS})

add_library(aurora_fp SHARED
    aurora_fp.cpp
    ${CP}/chromaprint.cpp
    ${CP}/audio_processor.cpp
    ${CP}/chroma.cpp
    ${CP}/chroma_resampler.cpp
    ${CP}/chroma_filter.cpp
    ${CP}/spectrum.cpp
    ${CP}/fft.cpp
    ${CP}/fft_lib_kissfft.cpp
    ${CP}/fingerprinter.cpp
    ${CP}/image_builder.cpp
    ${CP}/silence_remover.cpp
    ${CP}/fingerprint_calculator.cpp
    ${CP}/fingerprint_compressor.cpp
    ${CP}/fingerprint_decompressor.cpp
    ${CP}/fingerprinter_configuration.cpp
    ${CP}/fingerprint_matcher.cpp
    ${CP}/simhash.cpp
    ${CP}/utils/base64.cpp
    ${CP}/avresample/resample2.c
    ${KISS}/kiss_fft.c
    ${KISS}/tools/kiss_fftr.c
)

find_library(log-lib log)
target_link_libraries(aurora_fp ${log-lib})

# ---- 电子书转有声书：vendored vo-aacenc (VisualOn, Apache 2.0) ----
# AAC-LC 128kbps mono 48kHz，纯 C 无汇编，arm64/x86_64 同源编译。
# JNI 入口见 ebook_aac_jni.c（每批独立编码器实例，可并行）。
set(VO_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vo-aacenc)

add_library(aurora_aac SHARED
    ebook_aac_jni.c
    ${VO_DIR}/common/cmnMemory.c
    ${VO_DIR}/aacenc/basic_op/basicop2.c
    ${VO_DIR}/aacenc/basic_op/oper_32b.c
    ${VO_DIR}/aacenc/src/aac_rom.c
    ${VO_DIR}/aacenc/src/aacenc.c
    ${VO_DIR}/aacenc/src/aacenc_core.c
    ${VO_DIR}/aacenc/src/adj_thr.c
    ${VO_DIR}/aacenc/src/band_nrg.c
    ${VO_DIR}/aacenc/src/bit_cnt.c
    ${VO_DIR}/aacenc/src/bitbuffer.c
    ${VO_DIR}/aacenc/src/bitenc.c
    ${VO_DIR}/aacenc/src/block_switch.c
    ${VO_DIR}/aacenc/src/channel_map.c
    ${VO_DIR}/aacenc/src/dyn_bits.c
    ${VO_DIR}/aacenc/src/grp_data.c
    ${VO_DIR}/aacenc/src/interface.c
    ${VO_DIR}/aacenc/src/line_pe.c
    ${VO_DIR}/aacenc/src/memalign.c
    ${VO_DIR}/aacenc/src/ms_stereo.c
    ${VO_DIR}/aacenc/src/pre_echo_control.c
    ${VO_DIR}/aacenc/src/psy_configuration.c
    ${VO_DIR}/aacenc/src/psy_main.c
    ${VO_DIR}/aacenc/src/qc_main.c
    ${VO_DIR}/aacenc/src/quantize.c
    ${VO_DIR}/aacenc/src/sf_estim.c
    ${VO_DIR}/aacenc/src/spreading.c
    ${VO_DIR}/aacenc/src/stat_bits.c
    ${VO_DIR}/aacenc/src/tns.c
    ${VO_DIR}/aacenc/src/transform.c
)

target_include_directories(aurora_aac PRIVATE
    ${VO_DIR}/aacenc/src
    ${VO_DIR}/aacenc/inc
    ${VO_DIR}/aacenc/basic_op
    ${VO_DIR}/common/include
)

target_compile_options(aurora_aac PRIVATE -O2)

target_link_libraries(aurora_aac ${log-lib})
