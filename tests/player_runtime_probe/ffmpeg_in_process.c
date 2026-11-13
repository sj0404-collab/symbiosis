/* CI-only: same Android process/namespace, real bundled FFmpeg, no game. */
#include <android/log.h>
#include <dlfcn.h>
#include <stddef.h>
#include <string.h>
#include "libavcodec/avcodec.h"
#include "libavcodec/codec_internal.h"
#include "libavutil/frame.h"
#include "ci_h264.h"

#define TAG "SymbiosisKenji"
int ci_ffmpeg_in_process(void) {
    /* Kenji's related C# FFCodec<AVCodec>.CodecCallback offset on ARM64.
       This is a necessary ABI check, not proof of the shipped binary's layout. */
    if (sizeof(AVCodec) != 104 || offsetof(FFCodec, cb) != 152) return 1;
    void *util = dlopen("libavutil.so", RTLD_NOW | RTLD_LOCAL);
    void *codecLib = dlopen("libavcodec.so", RTLD_NOW | RTLD_LOCAL);
    if (!util || !codecLib || !dlsym(util, "av_log_set_callback") ||
        !dlsym(codecLib, "avcodec_find_decoder")) return 2;
    unsigned version = avcodec_version();
    if (version != ((59u << 16) | (37u << 8) | 100u)) return 3;
    const AVCodec *h264 = avcodec_find_decoder(AV_CODEC_ID_H264);
    const AVCodec *vp8 = avcodec_find_decoder(AV_CODEC_ID_VP8);
    if (!h264 || !vp8 || ffcodec(h264)->cb_type != FF_CODEC_CB_TYPE_DECODE ||
        ffcodec(vp8)->cb_type != FF_CODEC_CB_TYPE_DECODE || !ffcodec(h264)->cb.decode) return 4;
    AVCodecContext *ctx = avcodec_alloc_context3(h264);
    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    if (!ctx || !packet || !frame || avcodec_open2(ctx, h264, NULL) < 0 ||
        av_new_packet(packet, (int)ci_h264_len) < 0) return 5;
    memcpy(packet->data, ci_h264, ci_h264_len);
    int got = 0;
    int rc = ffcodec(h264)->cb.decode(ctx, frame, &got, packet);
    if (!got) {
        av_packet_unref(packet);
        rc = ffcodec(h264)->cb.decode(ctx, frame, &got, packet);
    }
    int ok = rc >= 0 && got && frame->width == 16 && frame->height == 16;
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "CI_FFMPEG_PRIVATE_DECODE version=%u.%u.%u h264=%d vp8=1 frame=%dx%d result=%d",
        version >> 16, (version >> 8) & 255, version & 255, got, frame->width, frame->height, rc);
    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&ctx);
    return ok ? 0 : 6;
}
