// SPDX-License-Identifier: Apache-2.0
#include <opencore-amrnb/interf_enc.h>
#include <stdio.h>
#include <stdlib.h>
int main(int argc, char **argv) {
    if (argc < 2) return 2;
    FILE *file = fopen(argv[1], "wb");
    if (!file) return 3;
    void *encoder = Encoder_Interface_init(1);
    if (!encoder) { fclose(file); return 4; }
    fwrite("#!AMR\n", 1, 6, file);
    short input[160] = {0};
    unsigned char output[64];
    int sid = 0, noData = 0;
    int frames = argc > 2 ? atoi(argv[2]) : 200;
    for (int i = 0; i < frames; i++) {
        for (int j = 0; j < 160; j++)
            input[j] = argc > 3 && i < 8 ? ((i * 160 + j) % 32 < 16 ? 8192 : -8192) : 0;
        int count = Encoder_Interface_Encode(encoder, MR122, input, output, 0);
        int type = (output[0] >> 3) & 15;
        sid += type == 8; noData += type == 15;
        fwrite(output, 1, count, file);
    }
    Encoder_Interface_exit(encoder); fclose(file);
    printf("generated %d frames; SID=%d NO_DATA=%d\n", frames, sid, noData);
    return sid ? 0 : 5;
}
