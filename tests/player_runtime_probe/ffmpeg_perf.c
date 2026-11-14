/* CI-only A/B microbenchmark: Android ARM, private H.264 callback. No game. */
#include <inttypes.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "libavcodec/avcodec.h"
#include "libavcodec/codec_internal.h"
#include "libavutil/frame.h"
#include "libavutil/log.h"
#include "ci_perf_h264.h"

static uint64_t ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec;
}
int main(void) {
    if (sizeof(AVCodec) != 104 || offsetof(FFCodec, cb) != 152) return 2;
    av_log_set_level(AV_LOG_ERROR);
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_H264);
    if (!codec || ffcodec(codec)->cb_type != FF_CODEC_CB_TYPE_DECODE || !ffcodec(codec)->cb.decode) return 3;
    AVCodecContext *ctx = avcodec_alloc_context3(codec);
    AVFrame *frame = av_frame_alloc();
    AVPacket *packet = av_packet_alloc();
    if (!ctx || !frame || !packet || avcodec_open2(ctx, codec, NULL) < 0 || av_new_packet(packet, ci_perf_h264_len) < 0) return 4;
    memcpy(packet->data, ci_perf_h264, ci_perf_h264_len);
    int successes = 0;
    uint64_t checksum = 0;
    uint64_t begin = ns();
    for (int i = 0; i < 100; ++i) {
        av_frame_unref(frame);
        int got = 0;
        int rc = ffcodec(codec)->cb.decode(ctx, frame, &got, packet);
        if (rc < 0 || !got || frame->width != 1280 || frame->height != 720) return 5;
        checksum += frame->data[0][0];
        successes++;
    }
    uint64_t elapsed = ns() - begin;
    printf("FFMPEG_PRIVATE_H264_720P frames=%d elapsed_ns=%" PRIu64 " checksum=%" PRIu64 " version=%u\n",
           successes, elapsed, checksum, avcodec_version());
    av_packet_free(&packet);
    av_frame_free(&frame);
    avcodec_free_context(&ctx);
    return 0;
}
