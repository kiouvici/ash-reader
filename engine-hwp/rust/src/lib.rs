// SPDX-License-Identifier: AGPL-3.0-or-later
//! ASH Reader :engine-hwp 네이티브 라이브러리.
//! rhwp(DocumentCore) → 쪽 SVG → resvg(usvg + tiny-skia) → RGBA → Android Bitmap(RGBA_8888).
//!
//! JNI (kr.vici.ashreader.engine.hwp.HwpNative):
//!   nativeInitFonts(dir)            번들 글꼴 폴더(+기기 CJK 폴더) 등록
//!   nativeOpen(path) -> long        핸들(0이면 실패, 오류는 nativeLastError)
//!   nativePageCount(h) -> int
//!   nativePageSizePx96(h, i) -> int[2]
//!   nativeRenderPage(h, i, bitmap) -> int  0=성공. 비트맵 크기에 맞춰 축척
//!   nativePageTextChars(h, i) -> int  공백 제외 글자 수(F-28 판정용)
//!   nativeToXhtml(h, dir, title) -> String?  이북식 보기용 XHTML(그림은 dir/images 에 씀). 실패 시 null
//!   nativeImageRects(h, i) -> float[]  쪽 안 그림 영역(쪽 기준 0..1, l t r b 반복) — 야간 '그림은 반전 안 함'(F-21)
//!   nativeClose(h)
//! 패닉은 catch_unwind로 잡아 오류로 돌려준다(앱 종료 없음, N-03).

use jni::objects::{JClass, JString};
#[cfg(target_os = "android")]
use jni::objects::JObject;
use jni::sys::{jfloatArray, jint, jintArray, jlong, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, Mutex, OnceLock};

pub mod reflow;

pub struct Doc {
    core: rhwp::DocumentCore,
}

// 스레드 안전(F-28): 문서마다 핸들(Box<Doc>, 각자의 DocumentCore)이 따로라, 10초를 넘겨 버려진 원본 열기 스레드와
// 이북식 변환 스레드가 동시에 돌아도 서로의 문서를 건드리지 않는다. 이 라이브러리의 전역 값은 아래 둘뿐이다.
// - FONTDB: Mutex 로 감싼 Arc(읽을 때 복제본을 가져가므로 그리는 동안 잠그지 않음)
// - LAST_ERROR: 스레드마다 따로(thread_local) — 다른 스레드의 오류 문구가 섞이지 않게(JNI 호출은 부른 자바 스레드에서 돈다)
// rhwp v0.8.7 쪽 전역 값도 확인함: static mut 없음. thread_local!(파서·그림 변환 메모·글꼴 캐시),
// OnceLock/LazyLock(처음 한 번 채운 뒤 읽기만 하는 표), Atomic 성능 계수기뿐이라 문서끼리 공유하는 바뀌는 상태가 없다.
// 같은 핸들을 여러 스레드가 쓰는 일은 Kotlin 쪽(HwpReaderDoc 의 lock)에서 막는다.
static FONTDB: OnceLock<Mutex<Arc<resvg::usvg::fontdb::Database>>> = OnceLock::new();
thread_local! {
    static LAST_ERROR: std::cell::RefCell<String> = const { std::cell::RefCell::new(String::new()) };
}

/// 잠금이 패닉으로 오염돼도(poisoned) 값은 그대로 쓴다(JNI 경계에서 다시 패닉하지 않게).
fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

fn set_err(s: String) {
    LAST_ERROR.with(|e| *e.borrow_mut() = s);
}

fn last_err() -> String {
    LAST_ERROR.with(|e| e.borrow().clone())
}

fn fontdb() -> Arc<resvg::usvg::fontdb::Database> {
    lock(FONTDB.get_or_init(|| Mutex::new(Arc::new(resvg::usvg::fontdb::Database::new())))).clone()
}

fn panic_msg(p: Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = p.downcast_ref::<&str>() {
        format!("네이티브 패닉: {s}")
    } else if let Some(s) = p.downcast_ref::<String>() {
        format!("네이티브 패닉: {s}")
    } else {
        "네이티브 패닉".to_string()
    }
}

/// 모든 JNI 진입점의 경계(필수 3): 패닉을 잡아 자바 예외(IllegalStateException)로 바꾸고 [default]를 돌려준다.
/// 패닉이 JNI 경계를 넘으면 정의되지 않은 동작(앱 강제 종료)이므로 진입점마다 반드시 이 함수로 감싼다.
fn guarded<'l, R>(env: &mut JNIEnv<'l>, default: R, f: impl FnOnce(&mut JNIEnv<'l>) -> R) -> R {
    match catch_unwind(AssertUnwindSafe(|| f(env))) {
        Ok(v) => v,
        Err(p) => {
            let msg = panic_msg(p);
            set_err(msg.clone());
            if !env.exception_check().unwrap_or(false) {
                let _ = env.throw_new("java/lang/IllegalStateException", msg);
            }
            default
        }
    }
}

/// 글꼴 폴더 등록(앱 번들 글꼴 + 기기 CJK 글꼴 폴더). 여러 폴더는 ':'로 구분.
pub fn init_fonts(dirs: &str) {
    let mut db = resvg::usvg::fontdb::Database::new();
    for d in dirs.split(':').filter(|s| !s.is_empty()) {
        db.load_fonts_dir(d);
    }
    db.set_serif_family("NanumMyeongjo");
    db.set_sans_serif_family("NanumGothic");
    *lock(FONTDB.get_or_init(|| Mutex::new(Arc::new(resvg::usvg::fontdb::Database::new())))) = Arc::new(db);
}

pub fn open(path: &str) -> Result<Doc, String> {
    let data = std::fs::read(path).map_err(|e| format!("읽기 실패: {e}"))?;
    match catch_unwind(AssertUnwindSafe(|| rhwp::DocumentCore::from_bytes(&data))) {
        Ok(Ok(core)) => Ok(Doc { core }),
        Ok(Err(e)) => Err(format!("{e:?}")),
        Err(_) => Err("파서 패닉".into()),
    }
}

impl Doc {
    pub fn page_count(&self) -> Result<u32, String> {
        catch_unwind(AssertUnwindSafe(|| self.core.page_count())).map_err(|_| "쪽 수 패닉".to_string())
    }

    fn tree(&self, i: u32) -> Result<resvg::usvg::Tree, String> {
        let svg = catch_unwind(AssertUnwindSafe(|| self.core.render_page_svg_native(i)))
            .map_err(|_| "렌더 패닉".to_string())?
            .map_err(|e| format!("{e:?}"))?;
        let mut opt = resvg::usvg::Options::default();
        opt.fontdb = fontdb();
        opt.font_family = "NanumMyeongjo".into();
        catch_unwind(AssertUnwindSafe(|| resvg::usvg::Tree::from_str(&svg, &opt)))
            .map_err(|_| "SVG 해석 패닉".to_string())?
            .map_err(|e| format!("usvg: {e:?}"))
    }

    /// 96dpi 기준 쪽 크기(px)
    pub fn page_size_px96(&self, i: u32) -> Result<(f32, f32), String> {
        let t = self.tree(i)?;
        Ok((t.size().width(), t.size().height()))
    }

    /// RGBA(사전 곱셈) 버퍼에 쪽을 그린다. 흰 바탕.
    pub fn render_rgba(&self, i: u32, w: u32, h: u32, out: &mut [u8], stride: usize) -> Result<(), String> {
        let tree = self.tree(i)?;
        let mut pix = resvg::tiny_skia::Pixmap::new(w, h).ok_or("비트맵 크기 오류")?;
        pix.fill(resvg::tiny_skia::Color::WHITE);
        let sx = w as f32 / tree.size().width();
        let sy = h as f32 / tree.size().height();
        let s = sx.min(sy);
        catch_unwind(AssertUnwindSafe(|| {
            resvg::render(&tree, resvg::tiny_skia::Transform::from_scale(s, s), &mut pix.as_mut())
        }))
        .map_err(|_| "래스터 패닉".to_string())?;
        let src = pix.data();
        let row = (w as usize) * 4;
        for y in 0..h as usize {
            out[y * stride..y * stride + row].copy_from_slice(&src[y * row..y * row + row]);
        }
        Ok(())
    }

    /// 이북식 보기용 XHTML. 그림은 [img_dir]/images 아래에 쓴다.
    pub fn to_xhtml(&self, img_dir: &str, title: &str) -> Result<String, String> {
        catch_unwind(AssertUnwindSafe(|| reflow::to_xhtml(self.core.document(), std::path::Path::new(img_dir), title)))
            .map_err(|_| "이북식 변환 패닉".to_string())
    }

    /// 쪽 안 그림(래스터·내장 SVG 그림) 영역. 쪽 기준 0..1 비율로 l, t, r, b 를 차례로 담는다.
    /// SVG 트리를 한 번 더 만들 뿐이라 싸다(렌더 없음).
    pub fn image_rects(&self, i: u32) -> Result<Vec<f32>, String> {
        let tree = self.tree(i)?;
        let (w, h) = (tree.size().width(), tree.size().height());
        if w <= 0.0 || h <= 0.0 {
            return Ok(Vec::new());
        }
        fn walk(g: &resvg::usvg::Group, w: f32, h: f32, out: &mut Vec<f32>) {
            for n in g.children() {
                match n {
                    resvg::usvg::Node::Group(c) => walk(c, w, h, out),
                    resvg::usvg::Node::Image(_) => {
                        let r = n.abs_bounding_box();
                        let l = (r.left() / w).clamp(0.0, 1.0);
                        let t = (r.top() / h).clamp(0.0, 1.0);
                        let rr = (r.right() / w).clamp(0.0, 1.0);
                        let b = (r.bottom() / h).clamp(0.0, 1.0);
                        if rr - l > 0.002 && b - t > 0.002 {
                            out.extend_from_slice(&[l, t, rr, b]);
                        }
                    }
                    _ => {}
                }
            }
        }
        let mut out = Vec::new();
        catch_unwind(AssertUnwindSafe(|| walk(tree.root(), w, h, &mut out))).map_err(|_| "그림 영역 패닉".to_string())?;
        Ok(out)
    }

    pub fn page_text_chars(&self, i: u32) -> u32 {
        catch_unwind(AssertUnwindSafe(|| {
            self.core
                .extract_page_text_native(i)
                .map(|t| t.chars().filter(|c| !c.is_whitespace()).count() as u32)
                .unwrap_or(0)
        }))
        .unwrap_or(0)
    }
}

/// 잉크 비율(가장자리 2% 제외, 흰색과 차이 합 > 60 인 픽셀 비율). F-28 빈 쪽 판정에 쓴다.
pub fn ink_ratio(rgba: &[u8], w: usize, h: usize) -> f64 {
    let (mx, my) = ((w as f64 * 0.02) as usize, (h as f64 * 0.02) as usize);
    let (mut ink, mut total) = (0usize, 0usize);
    for y in my..h.saturating_sub(my) {
        for x in mx..w.saturating_sub(mx) {
            let i = (y * w + x) * 4;
            total += 1;
            if (765 - rgba[i] as i32 - rgba[i + 1] as i32 - rgba[i + 2] as i32) > 60 {
                ink += 1;
            }
        }
    }
    if total == 0 { 0.0 } else { ink as f64 / total as f64 }
}

// ---------------------------------------------------------------- JNI

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

fn doc<'a>(h: jlong) -> Option<&'a Doc> {
    if h == 0 { None } else { Some(unsafe { &*(h as *const Doc) }) }
}

#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativeInitFonts(mut env: JNIEnv, _c: JClass, dirs: JString) {
    guarded(&mut env, (), |env| {
        let d = jstr(env, &dirs);
        init_fonts(&d);
    })
}

#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativeOpen(mut env: JNIEnv, _c: JClass, path: JString) -> jlong {
    guarded(&mut env, 0, |env| {
        let p = jstr(env, &path);
        match open(&p) {
            Ok(d) => Box::into_raw(Box::new(d)) as jlong,
            Err(e) => {
                set_err(e);
                0
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativeLastError(mut env: JNIEnv, _c: JClass) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let s = last_err();
        env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
    })
}

#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativePageCount(mut env: JNIEnv, _c: JClass, h: jlong) -> jint {
    guarded(&mut env, -1, |_| match doc(h).map(|d| d.page_count()) {
        Some(Ok(n)) => n as jint,
        Some(Err(e)) => {
            set_err(e);
            -1
        }
        None => -1,
    })
}

#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativePageTextChars(mut env: JNIEnv, _c: JClass, h: jlong, i: jint) -> jint {
    guarded(&mut env, 0, |_| doc(h).map(|d| d.page_text_chars(i as u32) as jint).unwrap_or(0))
}

#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativePageSizePx96(mut env: JNIEnv, _c: JClass, h: jlong, i: jint) -> jintArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let (w, hh) = doc(h).and_then(|d| d.page_size_px96(i as u32).map_err(set_err).ok()).unwrap_or((0.0, 0.0));
        let Ok(arr) = env.new_int_array(2) else { return std::ptr::null_mut() };
        env.set_int_array_region(&arr, 0, &[w.round() as i32, hh.round() as i32]).ok();
        arr.into_raw()
    })
}

// libjnigraphics (NDK android/bitmap.h) 최소 선언
#[cfg(target_os = "android")]
#[repr(C)]
struct AndroidBitmapInfo {
    width: u32,
    height: u32,
    stride: u32,
    format: i32,
    flags: u32,
}
#[cfg(target_os = "android")]
const ANDROID_BITMAP_FORMAT_RGBA_8888: i32 = 1;

#[cfg(target_os = "android")]
#[link(name = "jnigraphics")]
extern "C" {
    fn AndroidBitmap_getInfo(env: *mut jni::sys::JNIEnv, bitmap: jni::sys::jobject, info: *mut AndroidBitmapInfo) -> i32;
    fn AndroidBitmap_lockPixels(env: *mut jni::sys::JNIEnv, bitmap: jni::sys::jobject, addr: *mut *mut std::ffi::c_void) -> i32;
    fn AndroidBitmap_unlockPixels(env: *mut jni::sys::JNIEnv, bitmap: jni::sys::jobject) -> i32;
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativeRenderPage(mut env: JNIEnv, _c: JClass, h: jlong, i: jint, bitmap: JObject) -> jint {
    guarded(&mut env, -6, |env| {
        let Some(d) = doc(h) else { return -1 };
        unsafe {
            let raw_env = env.get_raw();
            let bmp = bitmap.as_raw();
            let mut info: AndroidBitmapInfo = std::mem::zeroed();
            if AndroidBitmap_getInfo(raw_env, bmp, &mut info) != 0 {
                set_err("getInfo 실패".into());
                return -2;
            }
            if info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 {
                set_err("RGBA_8888 비트맵만 지원".into());
                return -3;
            }
            let mut ptr: *mut std::ffi::c_void = std::ptr::null_mut();
            if AndroidBitmap_lockPixels(raw_env, bmp, &mut ptr) != 0 {
                return -4;
            }
            let stride = info.stride as usize;
            let buf = std::slice::from_raw_parts_mut(ptr as *mut u8, stride * info.height as usize);
            // 그리는 중 패닉이 나도 비트맵 잠금은 반드시 푼다
            let r = catch_unwind(AssertUnwindSafe(|| d.render_rgba(i as u32, info.width, info.height, buf, stride)))
                .unwrap_or_else(|_| Err("렌더 패닉".to_string()));
            AndroidBitmap_unlockPixels(raw_env, bmp);
            match r {
                Ok(()) => 0,
                Err(e) => {
                    set_err(e);
                    -5
                }
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativeToXhtml(mut env: JNIEnv, _c: JClass, h: jlong, dir: JString, title: JString) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let d = jstr(env, &dir);
        let t = jstr(env, &title);
        let Some(doc) = doc(h) else { return std::ptr::null_mut() };
        match doc.to_xhtml(&d, &t) {
            Ok(x) => env.new_string(x).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
            Err(e) => {
                set_err(e);
                std::ptr::null_mut()
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativeImageRects(mut env: JNIEnv, _c: JClass, h: jlong, i: jint) -> jfloatArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let v = doc(h).and_then(|d| d.image_rects(i as u32).map_err(set_err).ok()).unwrap_or_default();
        let Ok(arr) = env.new_float_array(v.len() as i32) else { return std::ptr::null_mut() };
        if !v.is_empty() {
            env.set_float_array_region(&arr, 0, &v).ok();
        }
        arr.into_raw()
    })
}

#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativeClose(mut env: JNIEnv, _c: JClass, h: jlong) {
    guarded(&mut env, (), |_| {
        if h != 0 {
            unsafe { drop(Box::from_raw(h as *mut Doc)) };
        }
    })
}
