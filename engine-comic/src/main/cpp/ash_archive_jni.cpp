// SPDX-License-Identifier: AGPL-3.0-or-later
// libarchive JNI: CBR(RAR4·RAR5, solid 포함) 항목 목록·한 항목 읽기·전체 순차 풀기.
#include <jni.h>
#include <archive.h>
#include <archive_entry.h>
#include <string>
#include <vector>
#include <cstdio>
#include <sys/stat.h>

namespace {
struct Reader {
    archive* a;
    explicit Reader(const char* path) {
        a = archive_read_new();
        archive_read_support_format_rar(a);
        archive_read_support_format_rar5(a);
        archive_read_support_format_zip(a);
        if (archive_read_open_filename(a, path, 64 * 1024) != ARCHIVE_OK) { archive_read_free(a); a = nullptr; }
    }
    ~Reader() { if (a) archive_read_free(a); }
};

void throwIo(JNIEnv* env, const std::string& msg) {
    jclass c = env->FindClass("java/io/IOException");
    env->ThrowNew(c, msg.c_str());
}

std::string jstr(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

// 항목 이름의 원래 바이트. RAR4 는 CP949 이름이 그대로 올 수 있어 NewStringUTF 를 쓰지 않고
// byte[] 로 넘겨 Kotlin 쪽에서 UTF-8 엄격 → CP949 순으로 읽는다(CheckJNI 중단 방지, N-09).
const char* entryName(archive_entry* e) {
    const char* n = archive_entry_pathname_utf8(e);
    if (!n) n = archive_entry_pathname(e);
    return n ? n : "";
}

jbyteArray bytesOf(JNIEnv* env, const std::string& s) {
    jbyteArray a = env->NewByteArray((jsize)s.size());
    if (a && !s.empty()) env->SetByteArrayRegion(a, 0, (jsize)s.size(), reinterpret_cast<const jbyte*>(s.data()));
    return a;
}

// 결과 코드: 암호 항목은 Kotlin 쪽에서 안내 문구로 바꾼다
constexpr const char* ENCRYPTED = "ASH_ENCRYPTED";
}  // namespace

extern "C" {

// 반환: [이름0, 크기0, 이름1, 크기1, ...] 모두 byte[] (이름은 원래 바이트, 크기는 10진 ASCII). 디렉터리 제외.
JNIEXPORT jobjectArray JNICALL
Java_kr_vici_ashreader_engine_comic_NativeComic_archiveList(JNIEnv* env, jclass, jstring jpath) {
    Reader r(jstr(env, jpath).c_str());
    if (!r.a) { throwIo(env, "압축 파일을 열 수 없음"); return nullptr; }
    std::vector<std::string> out;
    archive_entry* e;
    int rc;
    while ((rc = archive_read_next_header(r.a, &e)) == ARCHIVE_OK || rc == ARCHIVE_WARN) {
        if (archive_entry_filetype(e) != AE_IFREG) continue;
        if (archive_entry_is_encrypted(e)) { throwIo(env, ENCRYPTED); return nullptr; }
        out.emplace_back(entryName(e));
        out.emplace_back(std::to_string((long long)archive_entry_size(e)));
    }
    if (rc != ARCHIVE_EOF) { throwIo(env, archive_error_string(r.a) ? archive_error_string(r.a) : "목록 읽기 실패"); return nullptr; }
    jobjectArray arr = env->NewObjectArray((jsize)out.size(), env->FindClass("[B"), nullptr);
    if (!arr) return nullptr;
    for (size_t i = 0; i < out.size(); i++) {
        jbyteArray b = bytesOf(env, out[i]);
        if (!b) return nullptr;
        env->SetObjectArrayElement(arr, (jsize)i, b);
        env->DeleteLocalRef(b);
    }
    return arr;
}

// 항목 하나를 읽는다(archiveList 순서의 일반 파일 번호 [want]). solid 압축은 앞 항목을 모두 풀어야 하므로 느리다(→ extractAll 사용).
JNIEXPORT jbyteArray JNICALL
Java_kr_vici_ashreader_engine_comic_NativeComic_archiveRead(JNIEnv* env, jclass, jstring jpath, jint want, jlong maxBytes) {
    Reader r(jstr(env, jpath).c_str());
    if (!r.a) { throwIo(env, "압축 파일을 열 수 없음"); return nullptr; }
    archive_entry* e;
    int rc, idx = -1;
    while ((rc = archive_read_next_header(r.a, &e)) == ARCHIVE_OK || rc == ARCHIVE_WARN) {
        if (archive_entry_filetype(e) != AE_IFREG) continue;
        if (++idx != want) continue;
        if (archive_entry_is_encrypted(e)) { throwIo(env, ENCRYPTED); return nullptr; }
        la_int64_t sz = archive_entry_size(e);
        if (sz < 0 || sz > maxBytes) { throwIo(env, "항목이 너무 큼"); return nullptr; }
        std::vector<char> buf((size_t)sz);
        la_ssize_t got = 0;
        while (got < sz) {
            la_ssize_t n = archive_read_data(r.a, buf.data() + got, (size_t)(sz - got));
            if (n < 0) { throwIo(env, archive_error_string(r.a) ? archive_error_string(r.a) : "읽기 실패"); return nullptr; }
            if (n == 0) break;
            got += n;
        }
        jbyteArray arr = env->NewByteArray((jsize)got);
        if (arr) env->SetByteArrayRegion(arr, 0, (jsize)got, reinterpret_cast<const jbyte*>(buf.data()));
        return arr;
    }
    throwIo(env, "항목 없음: " + std::to_string(want));
    return nullptr;
}

// 처음부터 끝까지 한 번만 훑으며 항목 i 를 outDir/<i 6자리>.bin 으로 쓴다(solid CBR 캐시, 기술 설계서 7.2).
// 쓴 항목마다 progress.onEntry(index) 호출(이름은 Kotlin 이 archiveList 순서로 안다).
// 상한 maxTotal 은 머리의 크기가 아니라 실제로 푼 바이트로 센다(N-09). 넘으면 그 항목을 지우고 멈춘 뒤 쓴 개수를 돌려준다.
JNIEXPORT jint JNICALL
Java_kr_vici_ashreader_engine_comic_NativeComic_archiveExtractAll(JNIEnv* env, jclass, jstring jpath, jstring jdir,
                                                               jlong maxTotal, jobject progress) {
    Reader r(jstr(env, jpath).c_str());
    if (!r.a) { throwIo(env, "압축 파일을 열 수 없음"); return -1; }
    std::string dir = jstr(env, jdir);
    jmethodID onEntry = nullptr;
    if (progress) onEntry = env->GetMethodID(env->GetObjectClass(progress), "onEntry", "(I)V");
    if (env->ExceptionCheck()) return -1;
    archive_entry* e;
    int rc, idx = 0;
    long long total = 0;
    std::vector<char> buf(256 * 1024);
    while ((rc = archive_read_next_header(r.a, &e)) == ARCHIVE_OK || rc == ARCHIVE_WARN) {
        if (archive_entry_filetype(e) != AE_IFREG) continue;
        if (archive_entry_is_encrypted(e)) { throwIo(env, ENCRYPTED); return -1; }
        la_int64_t sz = archive_entry_size(e);
        if (sz > 0 && total + sz > maxTotal) break;
        char fn[32];
        snprintf(fn, sizeof fn, "/%06d.bin", idx);
        std::string tmp = dir + fn + ".part", fin = dir + fn;
        FILE* f = fopen(tmp.c_str(), "wb");
        if (!f) { throwIo(env, "캐시 쓰기 실패"); return -1; }
        la_ssize_t n;
        bool over = false, wfail = false;
        long long start = total;
        while ((n = archive_read_data(r.a, buf.data(), buf.size())) > 0) {
            if (total + n > maxTotal) { over = true; break; }
            if (fwrite(buf.data(), 1, (size_t)n, f) != (size_t)n) { wfail = true; break; }
            total += n;
        }
        if (fclose(f) != 0) wfail = true;
        if (over) { remove(tmp.c_str()); total = start; break; }
        if (wfail) { remove(tmp.c_str()); throwIo(env, "캐시 쓰기 실패(저장 공간 부족)"); return -1; }
        if (n < 0) { remove(tmp.c_str()); throwIo(env, archive_error_string(r.a) ? archive_error_string(r.a) : "풀기 실패"); return -1; }
        if (rename(tmp.c_str(), fin.c_str()) != 0) { remove(tmp.c_str()); throwIo(env, "캐시 쓰기 실패"); return -1; }
        if (onEntry) {
            env->CallVoidMethod(progress, onEntry, idx);
            if (env->ExceptionCheck()) return -1;
        }
        idx++;
    }
    return idx;
}

}  // extern "C"
