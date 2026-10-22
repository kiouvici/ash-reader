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
//!   nativeClose(h)
//! 패닉은 catch_unwind로 잡아 오류로 돌려준다(앱 종료 없음, N-03).

use jni::objects::{JClass, JString};
#[cfg(target_os = "android")]
use jni::objects::JObject;
use jni::sys::{jint, jintArray, jlong, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, Mutex, OnceLock};

pub mod reflow;

pub struct Doc {
    core: rhwp::DocumentCore,
}

static FONTDB: OnceLock<Mutex<Arc<resvg::usvg::fontdb::Database>>> = OnceLock::new();
static LAST_ERROR: OnceLock<Mutex<String>> = OnceLock::new();

fn set_err(s: String) {
    *LAST_ERROR.get_or_init(|| Mutex::new(String::new())).lock().unwrap() = s;
}

fn fontdb() -> Arc<resvg::usvg::fontdb::Database> {
    FONTDB
        .get_or_init(|| Mutex::new(Arc::new(resvg::usvg::fontdb::Database::new())))
        .lock()
        .unwrap()
        .clone()
}

/// 글꼴 폴더 등록(앱 번들 글꼴 + 기기 CJK 글꼴 폴더). 여러 폴더는 ':'로 구분.
pub fn init_fonts(dirs: &str) {
    let mut db = resvg::usvg::fontdb::Database::new();
    for d in dirs.split(':').filter(|s| !s.is_empty()) {
        db.load_fonts_dir(d);
    }
    db.set_serif_family("NanumMyeongjo");
    db.set_sans_serif_family("NanumGothic");
    *FONTDB.get_or_init(|| Mutex::new(Arc::new(resvg::usvg::fontdb::Database::new()))).lock().unwrap() = Arc::new(db);
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
    pub fn page_count(&self) -> u32 {
        self.core.page_count()
    }

    fn tree(&self, i: u32) -> Result<resvg::usvg::Tree, String> {
        let svg = catch_unwind(AssertUnwindSafe(|| self.core.render_page_svg_native(i)))
            .map_err(|_| "렌더 패닉".to_string())?
            .map_err(|e| format!("{e:?}"))?;
        let mut opt = resvg::usvg::Options::default();
        opt.fontdb = fontdb();
        opt.font_family = "NanumMyeongjo".into();
        resvg::usvg::Tree::from_str(&svg, &opt).map_err(|e| format!("usvg: {e:?}"))
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

    pub fn page_text_chars(&self, i: u32) -> u32 {
        self.core
            .extract_page_text_native(i)
            .map(|t| t.chars().filter(|c| !c.is_whitespace()).count() as u32)
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
    let d = jstr(&mut env, &dirs);
    init_fonts(&d);
}

#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativeOpen(mut env: JNIEnv, _c: JClass, path: JString) -> jlong {
    let p = jstr(&mut env, &path);
    match open(&p) {
        Ok(d) => Box::into_raw(Box::new(d)) as jlong,
        Err(e) => {
            set_err(e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativeLastError(env: JNIEnv, _c: JClass) -> jstring {
    let s = LAST_ERROR.get_or_init(|| Mutex::new(String::new())).lock().unwrap().clone();
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativePageCount(_e: JNIEnv, _c: JClass, h: jlong) -> jint {
    doc(h).map(|d| d.page_count() as jint).unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativePageTextChars(_e: JNIEnv, _c: JClass, h: jlong, i: jint) -> jint {
    doc(h).map(|d| d.page_text_chars(i as u32) as jint).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativePageSizePx96(env: JNIEnv, _c: JClass, h: jlong, i: jint) -> jintArray {
    let (w, hh) = doc(h).and_then(|d| d.page_size_px96(i as u32).map_err(set_err).ok()).unwrap_or((0.0, 0.0));
    let arr = env.new_int_array(2).unwrap();
    env.set_int_array_region(&arr, 0, &[w.round() as i32, hh.round() as i32]).ok();
    arr.into_raw()
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
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativeRenderPage(env: JNIEnv, _c: JClass, h: jlong, i: jint, bitmap: JObject) -> jint {
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
        let r = d.render_rgba(i as u32, info.width, info.height, buf, stride);
        AndroidBitmap_unlockPixels(raw_env, bmp);
        match r {
            Ok(()) => 0,
            Err(e) => {
                set_err(e);
                -5
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativeToXhtml(mut env: JNIEnv, _c: JClass, h: jlong, dir: JString, title: JString) -> jstring {
    let d = jstr(&mut env, &dir);
    let t = jstr(&mut env, &title);
    let Some(doc) = doc(h) else { return std::ptr::null_mut() };
    match doc.to_xhtml(&d, &t) {
        Ok(x) => env.new_string(x).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(e) => {
            set_err(e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_kr_vici_ashreader_engine_hwp_HwpNative_nativeClose(_e: JNIEnv, _c: JClass, h: jlong) {
    if h != 0 {
        unsafe { drop(Box::from_raw(h as *mut Doc)) };
    }
}
