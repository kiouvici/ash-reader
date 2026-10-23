// SPDX-License-Identifier: AGPL-3.0-or-later
// libash_djvu.so JNI: DjVuLibre ddjvuapi 로 DjVu 쪽 그리기·목차·쪽 글자(숨은 글자층).
// 모든 호출은 Kotlin(DjvuDoc)이 한 문서당 하나의 잠금 안에서 부른다.
#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>
#include "ddjvuapi.h"
#include "miniexp.h"
#ifdef __ANDROID__
#include <android/bitmap.h>
#endif

namespace {
struct Doc {
    ddjvu_context_t* ctx = nullptr;
    ddjvu_document_t* doc = nullptr;
};

void pump(ddjvu_context_t* ctx, bool wait) {
    if (wait) ddjvu_message_wait(ctx);
    while (ddjvu_message_peek(ctx)) ddjvu_message_pop(ctx);
}

void throwIo(JNIEnv* env, const char* msg) {
    jclass c = env->FindClass("java/io/IOException");
    if (c) env->ThrowNew(c, msg);
}

std::string jstr(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

Doc* D(jlong h) { return reinterpret_cast<Doc*>(h); }

/** JNI NewStringUTF 는 '수정 UTF-8'을 받으므로, 4바이트 글자가 있는 문자열은 UTF-16 으로 바꿔 만든다. */
jstring newString(JNIEnv* env, const char* s) {
    std::vector<jchar> out;
    const unsigned char* p = reinterpret_cast<const unsigned char*>(s);
    while (*p) {
        unsigned int c = *p, cp; int n;
        if (c < 0x80) { cp = c; n = 0; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; n = 1; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; n = 2; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; n = 3; }
        else { p++; out.push_back(0xFFFD); continue; }
        p++;
        bool bad = false;
        for (int i = 0; i < n; i++) {
            if ((*p & 0xC0) != 0x80) { bad = true; break; }
            cp = (cp << 6) | (*p & 0x3F); p++;
        }
        if (bad) { out.push_back(0xFFFD); continue; }
        if (cp >= 0x10000) { cp -= 0x10000; out.push_back(0xD800 + (cp >> 10)); out.push_back(0xDC00 + (cp & 0x3FF)); }
        else out.push_back((jchar)cp);
    }
    return env->NewString(out.data(), (jsize)out.size());
}

void flattenOutline(ddjvu_document_t* doc, miniexp_t list, int depth, std::vector<std::string>& titles, std::vector<int>& depths, std::vector<int>& pages) {
    for (miniexp_t p = list; miniexp_consp(p); p = miniexp_cdr(p)) {
        miniexp_t e = miniexp_car(p);
        if (!miniexp_consp(e)) continue;
        const char* title = miniexp_to_str(miniexp_car(e));
        const char* url = miniexp_to_str(miniexp_cadr(e));
        int page = -1;
        if (url && url[0] == '#') {
            const char* q = url + 1;
            char* end = nullptr;
            long n = strtol(q, &end, 10);
            if (end && *end == 0 && n > 0) page = (int)n - 1;
            else page = ddjvu_document_search_pageno(doc, q);
        }
        titles.emplace_back(title ? title : "");
        depths.push_back(depth);
        pages.push_back(page);
        if (depth < 16) flattenOutline(doc, miniexp_cddr(e), depth + 1, titles, depths, pages);
    }
}

void collectLines(miniexp_t e, std::vector<std::string>& lines, std::string& cur) {
    if (!miniexp_consp(e)) return;
    miniexp_t kind = miniexp_car(e);
    const char* k = miniexp_symbolp(kind) ? miniexp_to_name(kind) : "";
    // (kind x0 y0 x1 y1 child-or-string...)
    miniexp_t rest = miniexp_cdr(e);
    for (int i = 0; i < 4 && miniexp_consp(rest); i++) rest = miniexp_cdr(rest);
    for (; miniexp_consp(rest); rest = miniexp_cdr(rest)) {
        miniexp_t c = miniexp_car(rest);
        if (miniexp_stringp(c)) {
            if (!cur.empty() && std::strcmp(k, "char") != 0) cur.push_back(' ');
            cur += miniexp_to_str(c);
        } else collectLines(c, lines, cur);
    }
    if (std::strcmp(k, "line") == 0 || std::strcmp(k, "para") == 0) {
        if (!cur.empty()) { lines.push_back(cur); cur.clear(); }
    }
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_kr_vici_ashreader_engine_djvu_NativeDjvu_open(JNIEnv* env, jclass, jstring jpath) {
    std::string path = jstr(env, jpath);
    Doc* d = new Doc();
    d->ctx = ddjvu_context_create("ash-reader");
    if (!d->ctx) { delete d; throwIo(env, "DjVu 엔진을 시작하지 못함"); return 0; }
    ddjvu_cache_set_size(d->ctx, 32L << 20);
    d->doc = ddjvu_document_create_by_filename_utf8(d->ctx, path.c_str(), 1);
    if (!d->doc) { ddjvu_context_release(d->ctx); delete d; throwIo(env, "DjVu 파일을 열 수 없음"); return 0; }
    while (!ddjvu_document_decoding_done(d->doc)) pump(d->ctx, true);
    if (ddjvu_document_decoding_error(d->doc)) {
        ddjvu_document_release(d->doc); ddjvu_context_release(d->ctx); delete d;
        throwIo(env, "DjVu 파일이 손상되었거나 읽을 수 없음");
        return 0;
    }
    return reinterpret_cast<jlong>(d);
}

JNIEXPORT jint JNICALL
Java_kr_vici_ashreader_engine_djvu_NativeDjvu_pageCount(JNIEnv*, jclass, jlong h) {
    return ddjvu_document_get_pagenum(D(h)->doc);
}

/** [폭, 높이, dpi, 회전] — 회전을 반영한 화면 기준 크기. */
JNIEXPORT jintArray JNICALL
Java_kr_vici_ashreader_engine_djvu_NativeDjvu_pageInfo(JNIEnv* env, jclass, jlong h, jint page) {
    Doc* d = D(h);
    ddjvu_pageinfo_t info;
    ddjvu_status_t r;
    while ((r = ddjvu_document_get_pageinfo(d->doc, page, &info)) < DDJVU_JOB_OK) pump(d->ctx, true);
    if (r >= DDJVU_JOB_FAILED) { throwIo(env, "DjVu 쪽 정보를 읽을 수 없음"); return nullptr; }
    int w = info.width, hh = info.height;
    if (info.rotation % 2 == 1) { int t = w; w = hh; hh = t; }
    jint v[4] = { w, hh, info.dpi, info.rotation };
    jintArray a = env->NewIntArray(4);
    env->SetIntArrayRegion(a, 0, 4, v);
    return a;
}

/** 쪽 전체를 [bitmap](ARGB_8888) 크기에 맞춰 그린다. 그릴 것이 없는 쪽은 흰색. */
JNIEXPORT void JNICALL
Java_kr_vici_ashreader_engine_djvu_NativeDjvu_render(JNIEnv* env, jclass, jlong h, jint pageNo, jobject bitmap) {
#ifdef __ANDROID__
    Doc* d = D(h);
    AndroidBitmapInfo bi;
    if (AndroidBitmap_getInfo(env, bitmap, &bi) != ANDROID_BITMAP_RESULT_SUCCESS || bi.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        throwIo(env, "비트맵 형식 오류"); return;
    }
    ddjvu_page_t* page = ddjvu_page_create_by_pageno(d->doc, pageNo);
    if (!page) { throwIo(env, "DjVu 쪽을 만들 수 없음"); return; }
    while (!ddjvu_page_decoding_done(page)) pump(d->ctx, true);
    if (ddjvu_page_decoding_error(page)) { ddjvu_page_release(page); throwIo(env, "DjVu 쪽이 손상됨"); return; }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        ddjvu_page_release(page); throwIo(env, "비트맵 잠금 실패"); return;
    }
    // 메모리 순서 R,G,B,A (리틀 엔디언 32비트 값의 아래 바이트부터). 네 번째 값 = xor → 알파를 불투명으로
    unsigned int masks[4] = { 0x000000FFu, 0x0000FF00u, 0x00FF0000u, 0xFF000000u };
    ddjvu_format_t* fmt = ddjvu_format_create(DDJVU_FORMAT_RGBMASK32, 4, masks);
    ddjvu_format_set_row_order(fmt, 1);
    ddjvu_format_set_y_direction(fmt, 1);
    ddjvu_rect_t rect = { 0, 0, bi.width, bi.height };
    int ok = ddjvu_page_render(page, DDJVU_RENDER_COLOR, &rect, &rect, fmt, bi.stride, static_cast<char*>(pixels));
    if (!ok) {
        for (uint32_t y = 0; y < bi.height; y++) std::memset(static_cast<char*>(pixels) + (size_t)y * bi.stride, 0xFF, (size_t)bi.width * 4);
    }
    ddjvu_format_release(fmt);
    AndroidBitmap_unlockPixels(env, bitmap);
    ddjvu_page_release(page);
#else
    (void)env; (void)h; (void)pageNo; (void)bitmap;
#endif
}

/** 목차: [제목들], 깊이·쪽(0부터, -1 = 모름)은 [outInts] 에 깊이0, 쪽0, 깊이1, 쪽1 … 로 채운다. */
JNIEXPORT jobjectArray JNICALL
Java_kr_vici_ashreader_engine_djvu_NativeDjvu_outline(JNIEnv* env, jclass, jlong h) {
    Doc* d = D(h);
    miniexp_t r;
    while ((r = ddjvu_document_get_outline(d->doc)) == miniexp_dummy) pump(d->ctx, true);
    std::vector<std::string> titles; std::vector<int> depths, pages;
    if (miniexp_consp(r) && miniexp_car(r) == miniexp_symbol("bookmarks")) flattenOutline(d->doc, miniexp_cdr(r), 0, titles, depths, pages);
    if (r) ddjvu_miniexp_release(d->doc, r);
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray((jsize)titles.size() * 3, sc, nullptr);
    for (size_t i = 0; i < titles.size(); i++) {
        jstring t = newString(env, titles[i].c_str());
        env->SetObjectArrayElement(out, (jsize)(i * 3), t); env->DeleteLocalRef(t);
        jstring dd = env->NewStringUTF(std::to_string(depths[i]).c_str());
        env->SetObjectArrayElement(out, (jsize)(i * 3 + 1), dd); env->DeleteLocalRef(dd);
        jstring pp = env->NewStringUTF(std::to_string(pages[i]).c_str());
        env->SetObjectArrayElement(out, (jsize)(i * 3 + 2), pp); env->DeleteLocalRef(pp);
    }
    return out;
}

/** 숨은 글자층(OCR)이 있으면 줄 단위 글자, 없으면 빈 배열. */
JNIEXPORT jobjectArray JNICALL
Java_kr_vici_ashreader_engine_djvu_NativeDjvu_pageLines(JNIEnv* env, jclass, jlong h, jint page) {
    Doc* d = D(h);
    miniexp_t r;
    while ((r = ddjvu_document_get_pagetext(d->doc, page, "line")) == miniexp_dummy) pump(d->ctx, true);
    std::vector<std::string> lines; std::string cur;
    collectLines(r, lines, cur);
    if (!cur.empty()) lines.push_back(cur);
    if (r) ddjvu_miniexp_release(d->doc, r);
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray((jsize)lines.size(), sc, nullptr);
    for (size_t i = 0; i < lines.size(); i++) {
        jstring t = newString(env, lines[i].c_str());
        env->SetObjectArrayElement(out, (jsize)i, t); env->DeleteLocalRef(t);
    }
    return out;
}

JNIEXPORT void JNICALL
Java_kr_vici_ashreader_engine_djvu_NativeDjvu_close(JNIEnv*, jclass, jlong h) {
    Doc* d = D(h);
    if (!d) return;
    if (d->doc) ddjvu_document_release(d->doc);
    if (d->ctx) ddjvu_context_release(d->ctx);
    delete d;
}

}  // extern "C"
