// SPDX-License-Identifier: AGPL-3.0-or-later
// libash_convert.so JNI: MOBI·AZW3(libmobi) → EPUB 재료 파일, CHM(libmspack chmd) → 파일 풀기.
// 결과 파일은 Kotlin(EpubWriter)이 EPUB(zip)로 묶어 MuPDF 로 연다(기술 설계서 K-3).
#include <jni.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#include "mobi.h"
#include "mspack.h"

// ---- 공통 ----

/** dir 아래 rel 경로의 파일을 쓴다. rel 은 이미 검사된 상대 경로('/' 구분). 중간 폴더를 만든다. */
static int ensure_parent(char *path) {
    for (char *p = path + 1; *p; p++) {
        if (*p == '/') {
            *p = 0;
            if (mkdir(path, 0700) != 0 && errno != EEXIST) { *p = '/'; return -1; }
            *p = '/';
        }
    }
    return 0;
}

/** 압축 안 이름이 안전한 상대 경로인지(절대 경로·'..'·역슬래시·빈 이름 거부, zip slip 방지). */
static int safe_rel(const char *rel) {
    if (rel == NULL || rel[0] == 0 || rel[0] == '/') return 0;
    if (strchr(rel, '\\') != NULL || strchr(rel, ':') != NULL) return 0;
    const char *p = rel;
    while (*p) {
        const char *s = strchr(p, '/');
        size_t n = s ? (size_t)(s - p) : strlen(p);
        if (n == 0) return 0;
        if (n == 2 && p[0] == '.' && p[1] == '.') return 0;
        if (n == 1 && p[0] == '.') return 0;
        if (!s) break;
        p = s + 1;
    }
    return 1;
}

static int write_file(const char *dir, const char *rel, const unsigned char *data, size_t size) {
    if (!safe_rel(rel)) return -1;
    size_t n = strlen(dir) + strlen(rel) + 2;
    char *path = malloc(n);
    if (!path) return -1;
    snprintf(path, n, "%s/%s", dir, rel);
    int rc = ensure_parent(path);
    if (rc == 0) {
        FILE *f = fopen(path, "wb");
        if (!f) rc = -1;
        else {
            if (size > 0 && fwrite(data, 1, size, f) != size) rc = -1;
            if (fclose(f) != 0) rc = -1;
        }
    }
    free(path);
    return rc;
}

// ---- MOBI·AZW3 ----

// 결과 코드 (MobiEpub.kt 와 같게)
#define R_OK 0
#define R_DRM 1
#define R_REPLICA 2
#define R_LOAD 3
#define R_PARSE 4
#define R_WRITE 5
#define R_MEMORY 6

static int write_parts(const char *dir, const MOBIPart *curr, const char *prefix, int skip_first) {
    char name[64];
    if (skip_first && curr) curr = curr->next;
    for (; curr != NULL; curr = curr->next) {
        MOBIFileMeta meta = mobi_get_filemeta_by_type(curr->type);
        if (prefix == NULL) { // resources: opf 는 고정 이름
            if (curr->size == 0) continue;
            if (meta.type == T_OPF) snprintf(name, sizeof(name), "OEBPS/content.opf");
            else snprintf(name, sizeof(name), "OEBPS/resource%05zu.%s", curr->uid, meta.extension);
        } else {
            snprintf(name, sizeof(name), "OEBPS/%s%05zu.%s", prefix, curr->uid, meta.extension);
        }
        if (write_file(dir, name, curr->data, curr->size) != 0) return -1;
    }
    return 0;
}

/**
 * MOBI·AZW3 를 풀어 EPUB 재료(OEBPS/part*.html, flow*.css, resource*.*, content.opf)를 [outDir] 에 쓴다.
 * libmobi 의 mobitool create_epub 과 같은 이름 규칙(링크가 이 이름으로 다시 쓰여 있다). KF8 겸용 파일은 KF8 쪽을 쓴다.
 */
JNIEXPORT jint JNICALL
Java_kr_vici_ashreader_engine_convert_NativeConvert_mobiToDir(JNIEnv *env, jclass cls, jstring jpath, jstring jdir) {
    (void)cls;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    const char *dir = (*env)->GetStringUTFChars(env, jdir, NULL);
    int rc = R_OK;
    MOBIData *m = mobi_init();
    MOBIRawml *rawml = NULL;
    if (!m) { rc = R_MEMORY; goto done; }
    MOBI_RET r = mobi_load_filename(m, path);
    if (r != MOBI_SUCCESS) { rc = (r == MOBI_FILE_ENCRYPTED) ? R_DRM : R_LOAD; goto done; }
    if (mobi_is_encrypted(m)) { rc = R_DRM; goto done; }
    if (mobi_is_replica(m)) { rc = R_REPLICA; goto done; }
    rawml = mobi_init_rawml(m);
    if (!rawml) { rc = R_MEMORY; goto done; }
    r = mobi_parse_rawml(rawml, m);
    if (r != MOBI_SUCCESS) { rc = (r == MOBI_FILE_ENCRYPTED) ? R_DRM : R_PARSE; goto done; }
    if (write_parts(dir, rawml->markup, "part", 0) != 0 ||
        write_parts(dir, rawml->flow, "flow", 1) != 0 ||   // 첫 flow = 원문 html(본문과 겹침)
        write_parts(dir, rawml->resources, NULL, 0) != 0) {
        rc = R_WRITE;
    }
done:
    if (rawml) mobi_free_rawml(rawml);
    if (m) mobi_free(m);
    (*env)->ReleaseStringUTFChars(env, jdir, dir);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return rc;
}

// ---- CHM ----

/**
 * CHM 안 일반 파일을 모두 [outDir] 아래에 같은 경로로 푼다(시스템 파일 '::', '/#', '/$' 제외).
 * 이름이 안전하지 않은 항목은 건너뛴다. 풀린 합계가 [maxTotal] 바이트를 넘으면 멈춘다(N-09).
 * 반환: 푼 파일 수(0 이상) / -1 열기 실패 / -2 크기 상한 초과 / -3 풀기 실패(손상).
 */
JNIEXPORT jint JNICALL
Java_kr_vici_ashreader_engine_convert_NativeConvert_chmExtractAll(JNIEnv *env, jclass cls, jstring jpath, jstring jdir, jlong maxTotal) {
    (void)cls;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    const char *dir = (*env)->GetStringUTFChars(env, jdir, NULL);
    int count = 0;
    struct mschm_decompressor *chmd = mspack_create_chm_decompressor(NULL);
    struct mschmd_header *chm = NULL;
    if (!chmd) { count = -1; goto done; }
    chm = chmd->open(chmd, path);
    if (!chm) { count = -1; goto done; }
    long long total = 0;
    for (struct mschmd_file *f = chm->files; f != NULL; f = f->next) {
        const char *name = f->filename;
        if (name == NULL || name[0] != '/' || name[1] == 0 || name[1] == '#' || name[1] == '$') continue;
        if (name[strlen(name) - 1] == '/') continue; // 폴더 항목
        const char *rel = name + 1;
        if (!safe_rel(rel)) continue;
        total += (long long)f->length;
        if (total > (long long)maxTotal) { count = -2; goto done; }
        size_t n = strlen(dir) + strlen(rel) + 2;
        char *out = malloc(n);
        if (!out) { count = -3; goto done; }
        snprintf(out, n, "%s/%s", dir, rel);
        int bad = ensure_parent(out) != 0 || chmd->extract(chmd, f, out) != MSPACK_ERR_OK;
        free(out);
        if (bad) { count = -3; goto done; }
        count++;
    }
done:
    if (chm) chmd->close(chmd, chm);
    if (chmd) mspack_destroy_chm_decompressor(chmd);
    (*env)->ReleaseStringUTFChars(env, jdir, dir);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return count;
}
