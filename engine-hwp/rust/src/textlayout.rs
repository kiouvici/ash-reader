// SPDX-License-Identifier: AGPL-3.0-or-later
//! 한글 원본 보기 글자 선택(7B): rhwp 쪽 글자 배치(get_page_text_layout_native JSON)를 평평한 실수 배열로 바꾼다.
//!
//! rhwp v0.8.7 은 렌더 트리(build_page_tree)를 crate 밖에 열지 않으므로, 편집기용 공개 API
//! `get_page_text_layout_native`(TextRun 마다 text·x·y·w·h·charX)와 `get_page_info_native`(쪽 width·height)의
//! JSON 을 읽는다. serde_json 의존을 늘리지 않으려고 작은 JSON 해석기를 둔다(형식이 고정이고 값만 읽음).
//!
//! 결과 배열(모두 f32, 좌표 단위 = rhwp 쪽 px, SVG 와 같음):
//!   [쪽 폭, 쪽 높이, 줄기(run) 수,
//!    줄기마다: x, y, w, h, n(글자 수), 글자 왼쪽 위치 n+1개(줄기 x 기준 상대값), 글자 코드 n개(유니코드 스칼라)]
//! 유니코드 스칼라(최대 0x10FFFF)는 f32 가수(24비트) 안에 정확히 들어간다.

use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq)]
pub enum Json {
    Null,
    Bool(bool),
    Num(f64),
    Str(String),
    Arr(Vec<Json>),
    Obj(BTreeMap<String, Json>),
}

impl Json {
    pub fn get(&self, k: &str) -> Option<&Json> {
        if let Json::Obj(m) = self { m.get(k) } else { None }
    }
    pub fn num(&self) -> Option<f64> {
        if let Json::Num(v) = self { Some(*v) } else { None }
    }
    pub fn str(&self) -> Option<&str> {
        if let Json::Str(s) = self { Some(s) } else { None }
    }
    pub fn arr(&self) -> Option<&[Json]> {
        if let Json::Arr(a) = self { Some(a) } else { None }
    }
}

/// 작은 JSON 해석기. 깊이 64 넘거나 형식이 틀리면 None. NaN·inf 같은 rhwp 의 비표준 수도 받아 준다.
pub fn parse(s: &str) -> Option<Json> {
    let b: Vec<char> = s.chars().collect();
    let mut i = 0usize;
    let v = value(&b, &mut i, 0)?;
    ws(&b, &mut i);
    if i == b.len() { Some(v) } else { None }
}

fn ws(b: &[char], i: &mut usize) {
    while *i < b.len() && b[*i].is_whitespace() {
        *i += 1;
    }
}

fn value(b: &[char], i: &mut usize, depth: u32) -> Option<Json> {
    if depth > 64 {
        return None;
    }
    ws(b, i);
    match *b.get(*i)? {
        '{' => {
            *i += 1;
            let mut m = BTreeMap::new();
            ws(b, i);
            if b.get(*i) == Some(&'}') {
                *i += 1;
                return Some(Json::Obj(m));
            }
            loop {
                ws(b, i);
                let Json::Str(k) = string(b, i)? else { return None };
                ws(b, i);
                if b.get(*i) != Some(&':') {
                    return None;
                }
                *i += 1;
                let v = value(b, i, depth + 1)?;
                m.insert(k, v);
                ws(b, i);
                match b.get(*i)? {
                    ',' => *i += 1,
                    '}' => {
                        *i += 1;
                        return Some(Json::Obj(m));
                    }
                    _ => return None,
                }
            }
        }
        '[' => {
            *i += 1;
            let mut a = Vec::new();
            ws(b, i);
            if b.get(*i) == Some(&']') {
                *i += 1;
                return Some(Json::Arr(a));
            }
            loop {
                a.push(value(b, i, depth + 1)?);
                ws(b, i);
                match b.get(*i)? {
                    ',' => *i += 1,
                    ']' => {
                        *i += 1;
                        return Some(Json::Arr(a));
                    }
                    _ => return None,
                }
            }
        }
        '"' => string(b, i),
        _ => {
            for (w, v) in [("true", Json::Bool(true)), ("false", Json::Bool(false)), ("null", Json::Null)] {
                let n = w.len();
                if b.get(*i..*i + n).map(|s| s.iter().collect::<String>()).as_deref() == Some(w) {
                    *i += n;
                    return Some(v);
                }
            }
            // 수(NaN·inf 포함)
            let st = *i;
            while *i < b.len() && (b[*i].is_ascii_alphanumeric() || matches!(b[*i], '.' | '-' | '+')) {
                *i += 1;
            }
            if st == *i {
                return None;
            }
            let t: String = b[st..*i].iter().collect();
            t.parse::<f64>().ok().map(Json::Num)
        }
    }
}

fn string(b: &[char], i: &mut usize) -> Option<Json> {
    if b.get(*i) != Some(&'"') {
        return None;
    }
    *i += 1;
    let mut s = String::new();
    loop {
        let c = *b.get(*i)?;
        *i += 1;
        match c {
            '"' => return Some(Json::Str(s)),
            '\\' => {
                let e = *b.get(*i)?;
                *i += 1;
                match e {
                    'n' => s.push('\n'),
                    'r' => s.push('\r'),
                    't' => s.push('\t'),
                    'b' => s.push('\u{08}'),
                    'f' => s.push('\u{0C}'),
                    'u' => {
                        let h: String = b.get(*i..*i + 4)?.iter().collect();
                        *i += 4;
                        let mut cp = u32::from_str_radix(&h, 16).ok()?;
                        // 대리 쌍(😀)
                        if (0xD800..0xDC00).contains(&cp) && b.get(*i) == Some(&'\\') && b.get(*i + 1) == Some(&'u') {
                            let h2: String = b.get(*i + 2..*i + 6)?.iter().collect();
                            if let Ok(lo) = u32::from_str_radix(&h2, 16) {
                                if (0xDC00..0xE000).contains(&lo) {
                                    *i += 6;
                                    cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                                }
                            }
                        }
                        s.push(char::from_u32(cp).unwrap_or('\u{FFFD}'));
                    }
                    other => s.push(other),
                }
            }
            c => s.push(c),
        }
    }
}

fn finite(v: f64) -> f32 {
    if v.is_finite() { v as f32 } else { 0.0 }
}

/// 쪽 정보 JSON(width·height) + 글자 배치 JSON(runs) → 평평한 배열(모듈 설명 참고). 형식이 틀리면 None.
pub fn flatten(page_info: &str, text_layout: &str) -> Option<Vec<f32>> {
    let info = parse(page_info)?;
    let pw = info.get("width")?.num()?;
    let ph = info.get("height")?.num()?;
    if !(pw > 0.0 && ph > 0.0) {
        return None;
    }
    let lay = parse(text_layout)?;
    let runs = lay.get("runs")?.arr()?;
    let mut out = vec![pw as f32, ph as f32, 0.0];
    let mut count = 0u32;
    for r in runs {
        let Some(text) = r.get("text").and_then(|t| t.str()) else { continue };
        let chars: Vec<char> = text.chars().collect();
        let n = chars.len();
        if n == 0 {
            continue;
        }
        let x = r.get("x").and_then(Json::num).unwrap_or(0.0);
        let y = r.get("y").and_then(Json::num).unwrap_or(0.0);
        let w = r.get("w").and_then(Json::num).unwrap_or(0.0);
        let h = r.get("h").and_then(Json::num).unwrap_or(0.0);
        let cx: Vec<f64> = r.get("charX").and_then(Json::arr).map(|a| a.iter().filter_map(Json::num).collect()).unwrap_or_default();
        out.extend_from_slice(&[finite(x), finite(y), finite(w), finite(h), n as f32]);
        if cx.len() == n + 1 {
            out.extend(cx.iter().map(|v| finite(*v)));
        } else {
            // 위치가 없거나 개수가 안 맞으면 줄기 폭을 글자 수로 고르게 나눈다
            for k in 0..=n {
                out.push(finite(w * k as f64 / n as f64));
            }
        }
        out.extend(chars.iter().map(|c| *c as u32 as f32));
        count += 1;
    }
    out[2] = count as f32;
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_rhwp_layout() {
        let info = r#"{"pageIndex":0,"width":793.7,"height":1122.5,"columns":[{"x":1.0,"width":2.0}]}"#;
        let lay = r#"{"runs":[{"text":"가\"나","x":10.0,"y":20.0,"w":30.0,"h":12.0,"charX":[0.0,10.0,20.0,30.0],"fontFamily":"함초롬\\바탕","bold":false},{"text":"","x":0,"y":0,"w":0,"h":0,"charX":[0.0]},{"text":"A😀","x":1,"y":2,"w":8,"h":4,"charX":[NaN]}]}"#;
        let v = flatten(info, lay).unwrap();
        assert_eq!(&v[..3], &[793.7, 1122.5, 2.0]);
        // 첫 줄기: x y w h n, charX 4개, 글자 3개
        assert_eq!(&v[3..8], &[10.0, 20.0, 30.0, 12.0, 3.0]);
        assert_eq!(&v[8..12], &[0.0, 10.0, 20.0, 30.0]);
        assert_eq!(&v[12..15], &['가' as u32 as f32, '"' as u32 as f32, '나' as u32 as f32]);
        // 둘째 줄기(빈 글은 건너뜀): charX 가 틀려 고르게 나눔
        assert_eq!(&v[15..20], &[1.0, 2.0, 8.0, 4.0, 2.0]);
        assert_eq!(&v[20..23], &[0.0, 4.0, 8.0]);
        assert_eq!(v[24], 0x1F600 as f32);
        assert_eq!(v.len(), 25);
    }

    #[test]
    fn rejects_broken() {
        assert!(flatten("{", "{}").is_none());
        assert!(flatten(r#"{"width":0,"height":1}"#, r#"{"runs":[]}"#).is_none());
        assert_eq!(parse(r#"{"a":"A😀"}"#).unwrap().get("a").unwrap().str(), Some("A😀"));
        assert!(parse("[1,2").is_none());
    }
}
