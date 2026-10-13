// JNI bridge for vendored vo-aacenc (VisualOn, Apache 2.0).
// File-to-file encode: raw 48 kHz mono 16-bit PCM -> ADTS AAC frames
// (adtsUsed=1, AAC-LC 128 kbps). One encoder instance per call, so batch
// encodes run fully parallel with no shared state.
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "voAAC.h"
#include "voAudio.h"
#include "voMem.h"
#include "cmnMemory.h"

#define IN_BUF_SIZE (1024 * 8)
#define OUT_BUF_SIZE (1024 * 8)
#define SAMPLE_RATE 48000
#define CHANNELS 1
#define BITRATE 128000

static VO_S32 voCompare(VO_S32 uID, VO_PTR pBuffer1, VO_PTR pBuffer2, VO_U32 uSize) {
  (void)uID;
  return memcmp(pBuffer1, pBuffer2, uSize) == 0 ? VO_ERR_NONE : VO_ERR_FAILED;
}

static VO_U32 voMove(VO_S32 uID, VO_PTR pDest, VO_PTR pSource, VO_U32 uSize) {
  (void)uID;
  memmove(pDest, pSource, uSize);
  return VO_ERR_NONE;
}

JNIEXPORT jboolean JNICALL
Java_com_aurora_music_data_ebook_export_EbookAacEncoder_encodeFile(JNIEnv *env, jclass clazz,
                                                      jstring pcmPath, jstring aacPath) {
  (void)clazz;
  const char *pcmC = (*env)->GetStringUTFChars(env, pcmPath, NULL);
  const char *aacC = (*env)->GetStringUTFChars(env, aacPath, NULL);
  if (!pcmC || !aacC) {
    if (pcmC) (*env)->ReleaseStringUTFChars(env, pcmPath, pcmC);
    if (aacC) (*env)->ReleaseStringUTFChars(env, aacPath, aacC);
    return JNI_FALSE;
  }
  FILE *fin = fopen(pcmC, "rb");
  FILE *fout = fopen(aacC, "wb");
  (*env)->ReleaseStringUTFChars(env, pcmPath, pcmC);
  (*env)->ReleaseStringUTFChars(env, aacPath, aacC);
  if (!fin || !fout) {
    if (fin) fclose(fin);
    if (fout) fclose(fout);
    return JNI_FALSE;
  }

  jboolean ok = JNI_FALSE;
  unsigned char *inBuf = (unsigned char *)malloc(IN_BUF_SIZE);
  unsigned char *outBuf = (unsigned char *)malloc(OUT_BUF_SIZE);
  if (!inBuf || !outBuf) goto done;

  VO_AUDIO_CODECAPI api;
  if (voGetAACEncAPI(&api) != VO_ERR_NONE) goto done;

  VO_MEM_OPERATOR moper;
  moper.Alloc = cmnMemAlloc;
  moper.Free = cmnMemFree;
  moper.Set = cmnMemSet;
  moper.Copy = cmnMemCopy;
  moper.Check = cmnMemCheck;
  moper.Compare = voCompare;
  moper.Move = voMove;

  VO_CODEC_INIT_USERDATA useData;
  memset(&useData, 0, sizeof(useData));
  useData.memflag = VO_IMF_USERMEMOPERATOR;
  useData.memData = &moper;

  VO_HANDLE hCodec = 0;
  if (api.Init(&hCodec, VO_AUDIO_CodingAAC, &useData) != VO_ERR_NONE || !hCodec) goto done;

  AACENC_PARAM param;
  param.sampleRate = SAMPLE_RATE;
  param.bitRate = BITRATE;
  param.nChannels = CHANNELS;
  param.adtsUsed = 1;
  if (api.SetParam(hCodec, VO_PID_AAC_ENCPARAM, &param) != VO_ERR_NONE) {
    api.Uninit(hCodec);
    goto done;
  }

  VO_CODECBUFFER inData, outData;
  VO_AUDIO_OUTPUTINFO outInfo;
  long totalOut = 0;
  int eof = 0;
  size_t nRead = fread(inBuf, 1, IN_BUF_SIZE, fin);
  if (nRead == 0) goto uninit; // empty input

  while (!eof) {
    inData.Buffer = inBuf;
    inData.Length = (VO_U32)nRead;
    inData.Time = 0;
    if (api.SetInputData(hCodec, &inData) != VO_ERR_NONE) break;
    for (;;) {
      outData.Buffer = outBuf;
      outData.Length = OUT_BUF_SIZE;
      VO_U32 rc = api.GetOutputData(hCodec, &outData, &outInfo);
      if (rc == VO_ERR_NONE) {
        if (outData.Length > 0) {
          if (fwrite(outData.Buffer, 1, outData.Length, fout) != outData.Length) goto uninit;
          totalOut += outData.Length;
        }
        continue;
      }
      break; // VO_ERR_INPUT_BUFFER_SMALL or fatal
    }
    nRead = fread(inBuf, 1, IN_BUF_SIZE, fin);
    if (nRead == 0) eof = 1;
  }

  // Flush attempt: drain delay frames with empty input (bounded; encoders
  // that can't flush just report INPUT_BUFFER_SMALL immediately).
  {
    for (int k = 0; k < 8; k++) {
      long mark = totalOut;
      inData.Buffer = inBuf;
      inData.Length = 0;
      inData.Time = 0;
      if (api.SetInputData(hCodec, &inData) != VO_ERR_NONE) break;
      for (;;) {
        outData.Buffer = outBuf;
        outData.Length = OUT_BUF_SIZE;
        VO_U32 rc = api.GetOutputData(hCodec, &outData, &outInfo);
        if (rc != VO_ERR_NONE) break;
        if (outData.Length > 0) {
          if (fwrite(outData.Buffer, 1, outData.Length, fout) != outData.Length) goto uninit;
          totalOut += outData.Length;
        }
      }
      if (totalOut == mark) break; // no progress: nothing left to flush
    }
  }

  ok = totalOut > 0 ? JNI_TRUE : JNI_FALSE;

uninit:
  api.Uninit(hCodec);
done:
  if (inBuf) free(inBuf);
  if (outBuf) free(outBuf);
  fclose(fin);
  fclose(fout);
  return ok;
}
