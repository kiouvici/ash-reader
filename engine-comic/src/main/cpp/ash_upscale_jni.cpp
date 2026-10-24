// SPDX-License-Identifier: AGPL-3.0-or-later
// Real-CUGAN 2x(ncnn) 화질 향상 JNI. 타일 처리는 nihui/realcugan-ncnn-vulkan(MIT) realcugan.cpp 의 process_cpu
// (TTA 끔) 흐름을 따른다: 타일 + prepadding 18, 0~1 정규화, in0 → out0.
// 작업 03: SE 모델은 타일마다 전역 평균(gap0~3)을 따로 잡아 망점에 타일 모양 분홍 무늬가 생겼다(노트북 realcugan 으로 재현).
// realcugan 의 syncgap=3(very rough)을 그대로 옮긴다: 32px 작은 타일을 가로세로 3칸마다 하나씩 원래 크기로 통과시켜
// gap0~3 을 똑같이 평균하고, 실제 타일을 처리할 때 모두 넣는다(노트북 비교에서 무늬·네모가 사라짐).
// Vulkan 장치가 있고 useGpu=true 이면 ncnn Vulkan 연산, 아니면 CPU(NEON).
#include <jni.h>
#include <android/bitmap.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <algorithm>
#include <string>
#include <vector>
#include "net.h"
#include "gpu.h"
#include "cpu.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ASH-UP", __VA_ARGS__)

namespace {
struct Upscaler {
    ncnn::Net net;
    bool gpu = false;
    int tile = 200;
    int prepadding = 18;  // scale 2
    bool se = false;      // gap0~gap3 이 있는 SE 모델
};

const char* const kGap[4] = {"gap0", "gap1", "gap2", "gap3"};

// 타일 (xi, yi)(한 변 T)의 입력: prepadding 을 붙여 잘라 0~1 로 바꾸고 모자란 가장자리는 반사로 채운다. [th]·[tw] = 패딩 없는 크기.
ncnn::Mat tileInput(Upscaler* u, const unsigned char* px, int w, int h, int stride, int xi, int yi, int T, int& tw, int& th) {
    const int pre = u->prepadding;
    th = std::min((yi + 1) * T, h) - yi * T;
    tw = std::min((xi + 1) * T, w) - xi * T;
    const int preB = pre + ((th + 1) / 2 * 2 - th);
    const int preR = pre + ((tw + 1) / 2 * 2 - tw);
    const int y0 = std::max(yi * T - pre, 0), y1 = std::min((yi + 1) * T + preB, h);
    const int x0 = std::max(xi * T - pre, 0), x1 = std::min((xi + 1) * T + preR, w);
    ncnn::Mat in = ncnn::Mat::from_pixels_roi(px, ncnn::Mat::PIXEL_RGBA2RGB, w, h, stride, x0, y0, x1 - x0, y1 - y0);
    const float norm[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    in.substract_mean_normalize(nullptr, norm);
    const int pt = std::max(pre - yi * T, 0);
    const int pb = std::max(std::min((yi + 1) * T + preB - h, preB), 0);
    const int pl = std::max(pre - xi * T, 0);
    const int pr = std::max(std::min((xi + 1) * T + preR - w, preR), 0);
    ncnn::Mat padded;
    ncnn::copy_make_border(in, padded, pt, pb, pl, pr, 2, 0.f, u->net.opt);
    return padded;
}

// realcugan process_cpu_se_very_rough_stage0 + sync_gap: 32px 타일을 3칸마다 하나씩 통과시켜 gap0~3 을 평균.
bool veryRoughGaps(Upscaler* u, const unsigned char* px, int w, int h, int stride, ncnn::Mat gaps[4]) {
    const int S = 32;
    const int xt = (w + S - 1) / S, yt = (h + S - 1) / S;
    std::vector<float> sum[4];
    int n = 0;
    for (int yi = 0; yi + 2 < yt; yi += 3) {
        for (int xi = 0; xi + 2 < xt; xi += 3) {
            int tw, th;
            ncnn::Mat in = tileInput(u, px, w, h, stride, xi, yi, S, tw, th);
            ncnn::Extractor ex = u->net.create_extractor();
            ex.input("in0", in);
            for (int k = 0; k < 4; k++) {
                ncnn::Mat g;
                if (ex.extract(kGap[k], g) != 0) return false;
                const size_t cnt = g.total();
                if (sum[k].empty()) sum[k].assign(cnt, 0.f);
                if (sum[k].size() != cnt) return false;
                const float* p = (const float*)g.data;
                for (size_t j = 0; j < cnt; j++) sum[k][j] += p[j];
            }
            n++;
        }
    }
    if (n == 0) return false;
    for (int k = 0; k < 4; k++) {
        gaps[k].create((int)sum[k].size());
        float* p = (float*)gaps[k].data;
        for (size_t j = 0; j < sum[k].size(); j++) p[j] = sum[k][j] / n;
    }
    return true;
}

void throwIo(JNIEnv* env, const char* msg) { env->ThrowNew(env->FindClass("java/io/IOException"), msg); }
}  // namespace

extern "C" {

// Vulkan 정보: "" = Vulkan 없음, 아니면 "장치이름|apiVersion(major.minor.patch)|driverVersion"
JNIEXPORT jstring JNICALL
Java_kr_vici_ashreader_engine_comic_NativeComic_vulkanInfo(JNIEnv* env, jclass) {
    if (ncnn::get_gpu_count() <= 0) return env->NewStringUTF("");
    const ncnn::GpuInfo& gi = ncnn::get_gpu_info(ncnn::get_default_gpu_index());
    uint32_t v = gi.api_version();
    std::string s = std::string(gi.device_name()) + "|" + std::to_string(v >> 22) + "." + std::to_string((v >> 12) & 0x3ff) +
                    "." + std::to_string(v & 0xfff) + "|" + std::to_string(gi.driver_version());
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jlong JNICALL
Java_kr_vici_ashreader_engine_comic_NativeComic_upscalerCreate(JNIEnv* env, jclass, jobject jam, jstring jparam,
                                                            jstring jbin, jboolean useGpu, jint threads, jint tile) {
    auto* u = new Upscaler();
    u->gpu = useGpu && ncnn::get_gpu_count() > 0;
    u->tile = tile > 0 ? tile : 200;
    u->net.opt.use_vulkan_compute = u->gpu;
    u->net.opt.use_fp16_packed = true;
    u->net.opt.use_fp16_storage = u->gpu;
    u->net.opt.use_fp16_arithmetic = false;
    u->net.opt.use_int8_storage = true;
    u->net.opt.num_threads = threads > 0 ? threads : ncnn::get_big_cpu_count();
    if (u->gpu) u->net.set_vulkan_device(ncnn::get_default_gpu_index());
    AAssetManager* am = AAssetManager_fromJava(env, jam);
    const char* p = env->GetStringUTFChars(jparam, nullptr);
    const char* b = env->GetStringUTFChars(jbin, nullptr);
    int r1 = u->net.load_param(am, p);
    int r2 = u->net.load_model(am, b);
    env->ReleaseStringUTFChars(jparam, p);
    env->ReleaseStringUTFChars(jbin, b);
    if (r1 != 0 || r2 != 0) { delete u; throwIo(env, "모델을 읽을 수 없음"); return 0; }
    for (const ncnn::Blob& bl : u->net.blobs()) if (bl.name == "gap3") { u->se = true; break; }
    LOGI("upscaler gpu=%d threads=%d tile=%d se=%d", u->gpu, u->net.opt.num_threads, u->tile, u->se);
    return reinterpret_cast<jlong>(u);
}

JNIEXPORT jboolean JNICALL
Java_kr_vici_ashreader_engine_comic_NativeComic_upscalerIsGpu(JNIEnv*, jclass, jlong h) {
    return reinterpret_cast<Upscaler*>(h)->gpu;
}

JNIEXPORT void JNICALL
Java_kr_vici_ashreader_engine_comic_NativeComic_upscalerDestroy(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<Upscaler*>(h);
}

// src(ARGB_8888, w×h) → dst(ARGB_8888, 2w×2h). 알파는 무시(만화 쪽은 불투명).
JNIEXPORT void JNICALL
Java_kr_vici_ashreader_engine_comic_NativeComic_upscalerRun(JNIEnv* env, jclass, jlong h, jobject src, jobject dst) {
    auto* u = reinterpret_cast<Upscaler*>(h);
    AndroidBitmapInfo si, di;
    if (AndroidBitmap_getInfo(env, src, &si) < 0 || AndroidBitmap_getInfo(env, dst, &di) < 0 ||
        si.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || di.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        di.width != si.width * 2 || di.height != si.height * 2) { throwIo(env, "비트맵 형식·크기 오류"); return; }
    if (!u) { throwIo(env, "화질 향상기가 닫혔음"); return; }
    void* sp = nullptr; void* dp = nullptr;
    if (AndroidBitmap_lockPixels(env, src, &sp) < 0 || !sp) { throwIo(env, "비트맵 잠금 실패"); return; }
    if (AndroidBitmap_lockPixels(env, dst, &dp) < 0 || !dp) {
        AndroidBitmap_unlockPixels(env, src);
        throwIo(env, "비트맵 잠금 실패");
        return;
    }
    const int w = (int)si.width, hgt = (int)si.height, T = u->tile;
    const unsigned char* px = static_cast<const unsigned char*>(sp);
    bool ok = true;
    // 타일이 둘 이상이면 gap 동기화(한 타일이면 원래와 같음)
    ncnn::Mat gaps[4];
    const bool sync = u->se && (w > T || hgt > T) && veryRoughGaps(u, px, w, hgt, (int)si.stride, gaps);
    for (int yi = 0; ok && yi < (hgt + T - 1) / T; yi++) {
        for (int xi = 0; ok && xi < (w + T - 1) / T; xi++) {
            int tw, th;
            ncnn::Mat padded = tileInput(u, px, w, hgt, (int)si.stride, xi, yi, T, tw, th);
            ncnn::Mat out;
            ncnn::Extractor ex = u->net.create_extractor();
            ex.input("in0", padded);
            if (sync) for (int k = 0; k < 4; k++) ex.input(kGap[k], gaps[k]);
            if (ex.extract("out0", out) != 0) { ok = false; break; }
            const float mean[3] = {0, 0, 0};
            const float scale255[3] = {255.f, 255.f, 255.f};
            out.substract_mean_normalize(mean, scale255);
            ncnn::Mat crop;
            ncnn::copy_cut_border(out, crop, 0, out.h - th * 2, 0, out.w - tw * 2, u->net.opt);
            unsigned char* o = static_cast<unsigned char*>(dp) + (size_t)yi * 2 * T * di.stride + (size_t)xi * 2 * T * 4;
            crop.to_pixels(o, ncnn::Mat::PIXEL_RGB2RGBA, (int)di.stride);
        }
    }
    AndroidBitmap_unlockPixels(env, src);
    AndroidBitmap_unlockPixels(env, dst);
    if (!ok) throwIo(env, "추론 실패");
}

}  // extern "C"
