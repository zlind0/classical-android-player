#ifndef AURORA_FAIRPLAY_H
#define AURORA_FAIRPLAY_H

#include <stdint.h>

typedef struct fairplay_s {
    unsigned char keymsg[164];
    unsigned int keymsglen;
} fairplay_t;

int fairplay_setup(fairplay_t *fp, const unsigned char req[16], unsigned char res[142]);
int fairplay_handshake(fairplay_t *fp, const unsigned char req[164], unsigned char res[32]);
int fairplay_decrypt(fairplay_t *fp, const unsigned char input[72], unsigned char output[16]);

#endif
