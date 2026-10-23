// SPDX-License-Identifier: AGPL-3.0-or-later
//! 이북식 보기(F-28): rhwp 문서 모델 → 단순 XHTML.
//!
//! 앱은 이 XHTML 을 MuPDF 로 다시 배치하므로 글꼴·크기·줄 간격·테마가 그대로 먹고,
//! 글자 선택·형광펜·검색·목차(h1~h3 → MuPDF 목차)도 DOCX 이북식과 같은 경로로 동작한다.
//!
//! 옮기는 것: 문단(가운데·오른쪽 맞춤, 굵게·기울임), 제목(개요 문단·큰 글자 문단),
//! 표(칸 합치기 포함, 단순 격자), 그림(PNG·JPEG·GIF·BMP → images/ 파일), 글상자·묶음 개체 안 글,
//! 그림·표 캡션, 각주·미주(문서 끝에 모음), 수식(스크립트 글자).
//! 옮기지 않는 것: 머리말·꼬리말, 쪽 번호, 정밀 배치(글 뒤·앞 개체 위치), WMF·EMF 그림.

use rhwp::model::control::Control;
use rhwp::model::document::Document;
use rhwp::model::paragraph::Paragraph;
use rhwp::model::shape::{CommonObjAttr, ShapeObject};
use rhwp::model::style::{Alignment, HeadType};
use rhwp::model::table::Table;
use std::fmt::Write as _;
use std::path::Path;

const MAX_IMAGE_BYTES: usize = 32 * 1024 * 1024;

const CSS: &str = "table.hwp-table{border-collapse:collapse;margin:0.5em 0}\
table.hwp-table td{border:0.5pt solid #000;padding:2pt 4pt;vertical-align:top}\
td p,td div{text-indent:0;margin:0}\
p.c,h1.c,h2.c,h3.c{text-align:center;text-indent:0}p.r{text-align:right;text-indent:0}\
p.cap{text-align:center;text-indent:0;font-size:0.85em}\
img{display:block;margin:0.3em auto;max-width:100%}img.ch{display:inline;margin:0;vertical-align:-0.15em}\
a.noteref{vertical-align:super;font-size:0.75em}\
aside.footnote{font-size:0.85em}aside.footnote p{text-indent:0}\
.eq{font-style:italic}";

struct Ctx<'a> {
    doc: &'a Document,
    img_dir: &'a Path,
    out: String,
    notes: Vec<(u32, String)>,
    note_no: u32,
    img_no: u32,
    /// bin_data 순번 → 이미 쓴 그림 이름(같은 그림을 여러 번 쓰지 않는다)
    written: std::collections::HashMap<u16, Option<String>>,
    body_size: i32,
    depth: u32,
}

pub fn to_xhtml(doc: &Document, img_dir: &Path, title: &str) -> String {
    let mut c = Ctx {
        doc,
        img_dir,
        out: String::with_capacity(64 * 1024),
        notes: Vec::new(),
        note_no: 0,
        img_no: 0,
        written: std::collections::HashMap::new(),
        body_size: body_size(doc),
        depth: 0,
    };
    c.out.push_str("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n");
    c.out.push_str("<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"ko\">\n<head>\n<meta charset=\"UTF-8\"/>\n<title>");
    c.out.push_str(&esc(title));
    c.out.push_str("</title>\n<style>");
    c.out.push_str(CSS);
    c.out.push_str("</style>\n</head>\n<body>\n");
    for sec in &doc.sections {
        c.paragraphs(&sec.paragraphs, false);
    }
    if !c.notes.is_empty() {
        c.out.push_str("<hr/>\n<aside class=\"footnote\">\n");
        let notes = std::mem::take(&mut c.notes);
        for (n, html) in notes {
            let _ = write!(c.out, "<div id=\"fn{n}\"><b>{n})</b> {html}</div>\n");
        }
        c.out.push_str("</aside>\n");
    }
    c.out.push_str("</body>\n</html>\n");
    c.out
}

/// 한 문단에 붙은 개체를 읽는 순서로 돌려준다(I-782).
/// 버스 시간표처럼 '종이' 기준 부동 표 여러 개를 단처럼 나란히 놓은 문서는 저장 순서가
/// 화면 순서와 달라(z 순서 등) 이북식에서 오른쪽 표가 먼저 나왔다. 그런 표가 셋 이상이면
/// 다른 모든 표보다 위에 있는 표(제목 띠)를 먼저, 나머지를 왼쪽→오른쪽으로 둔다.
/// 나머지 표끼리 가로 범위가 겹치면(단 배치가 아님) 저장 순서를 그대로 쓴다.
/// 다른 개체(글자처럼 취급한 표·그림 등)는 원래 순서대로 앞에 둔다.
fn reading_order(ctls: &[Control]) -> Vec<&Control> {
    use rhwp::model::shape::{HorzRelTo, VertRelTo};
    // (왼, 오른, 위, 아래)
    let rect = |c: &Control| -> Option<(i64, i64, i64, i64)> {
        let Control::Table(t) = c else { return None };
        let m = &t.common;
        (!m.treat_as_char && m.horz_rel_to == HorzRelTo::Paper && m.vert_rel_to == VertRelTo::Paper)
            .then(|| {
                let x = m.horizontal_offset as i32 as i64;
                let y = m.vertical_offset as i32 as i64;
                (x, x + m.width as i64, y, y + m.height as i64)
            })
    };
    let floats: Vec<((i64, i64, i64, i64), &Control)> =
        ctls.iter().filter_map(|c| rect(c).map(|r| (r, c))).collect();
    if floats.len() < 3 {
        return ctls.iter().collect();
    }
    let is_header = |i: usize| {
        let b = floats[i].0 .3;
        floats.iter().enumerate().all(|(j, f)| j == i || b <= f.0 .2)
    };
    let (mut head, mut rest): (Vec<_>, Vec<_>) = (Vec::new(), Vec::new());
    for (i, f) in floats.iter().enumerate() {
        if is_header(i) {
            head.push(*f)
        } else {
            rest.push(*f)
        }
    }
    rest.sort_by_key(|f| f.0 .0);
    if rest.windows(2).any(|w| w[1].0 .0 < w[0].0 .1) {
        return ctls.iter().collect(); // 가로로 겹침 → 단 배치가 아님
    }
    let mut out: Vec<&Control> = ctls.iter().filter(|c| rect(c).is_none()).collect();
    out.extend(head.into_iter().chain(rest).map(|f| f.1));
    out
}

/// 본문 글자 크기 = 글자 수 가중 최빈 기준 크기.
fn body_size(doc: &Document) -> i32 {
    let mut hist: std::collections::HashMap<i32, usize> = std::collections::HashMap::new();
    for sec in &doc.sections {
        for p in &sec.paragraphs {
            let n = p.text.chars().count();
            if n == 0 {
                continue;
            }
            if let Some(cs) = p.char_shapes.first().and_then(|r| doc.doc_info.char_shapes.get(r.char_shape_id as usize)) {
                *hist.entry(cs.base_size).or_default() += n;
            }
        }
    }
    hist.into_iter().max_by_key(|(_, n)| *n).map(|(s, _)| s).unwrap_or(1000)
}

/// "제1장 총칙"·"제 2 편"·"제3절" 꼴 → 제목 단계(편·장 = 2, 절·관 = 3). 조·항은 제목으로 보지 않는다.
fn chapter_level(text: &str) -> Option<u32> {
    let t = text.trim_start();
    let rest = t.strip_prefix('제')?.trim_start();
    let digits = rest.chars().take_while(|c| c.is_ascii_digit()).count();
    if digits == 0 {
        return None;
    }
    match rest[digits..].trim_start().chars().next()? {
        '편' | '장' => Some(2),
        '절' | '관' => Some(3),
        _ => None,
    }
}

fn to_png(bytes: &[u8]) -> Option<Vec<u8>> {
    let img = image::load_from_memory(bytes).ok()?;
    let mut out = std::io::Cursor::new(Vec::new());
    img.write_to(&mut out, image::ImageFormat::Png).ok()?;
    Some(out.into_inner())
}

fn esc(s: &str) -> String {
    let mut o = String::with_capacity(s.len());
    for ch in s.chars() {
        match ch {
            '&' => o.push_str("&amp;"),
            '<' => o.push_str("&lt;"),
            '>' => o.push_str("&gt;"),
            '"' => o.push_str("&quot;"),
            _ => o.push(ch),
        }
    }
    o
}

impl<'a> Ctx<'a> {
    fn paragraphs(&mut self, ps: &[Paragraph], in_cell: bool) {
        if self.depth > 16 {
            return; // 끝없이 중첩된 개체 방어
        }
        self.depth += 1;
        for p in ps {
            self.paragraph(p, in_cell);
        }
        self.depth -= 1;
    }

    fn paragraph(&mut self, p: &Paragraph, in_cell: bool) {
        let inline = self.inline_text(p);
        let has_text = !inline.trim().is_empty();
        if has_text {
            let doc: &'a Document = self.doc;
            let info = &doc.doc_info;
            let ps = info.para_shapes.get(p.para_shape_id as usize);
            let align = ps.map(|s| s.alignment).unwrap_or_default();
            let cls = match align {
                Alignment::Center => " class=\"c\"",
                Alignment::Right => " class=\"r\"",
                _ => "",
            };
            let plain_len = p.text.chars().filter(|c| !c.is_whitespace()).count();
            let size = p
                .char_shapes
                .first()
                .and_then(|r| info.char_shapes.get(r.char_shape_id as usize))
                .map(|c| c.base_size)
                .unwrap_or(self.body_size);
            let outline = ps.filter(|s| s.head_type == HeadType::Outline).map(|s| s.para_level);
            let all_bold = !p.char_shapes.is_empty()
                && p.char_shapes.iter().all(|r| info.char_shapes.get(r.char_shape_id as usize).map(|c| c.bold).unwrap_or(false));
            let chapter = if all_bold && plain_len <= 40 { chapter_level(&p.text) } else { None };
            let level = if in_cell || plain_len > 80 {
                0
            } else if size * 10 >= self.body_size * 16 {
                1
            } else if size * 10 >= self.body_size * 13 {
                2
            } else if let Some(l) = chapter {
                l
            } else if all_bold && plain_len <= 40 && align == Alignment::Center {
                3
            } else if let Some(l) = outline {
                if l == 0 { 2 } else { 3 }
            } else {
                0
            };
            if level > 0 {
                let _ = write!(self.out, "<h{level}{cls}>{inline}</h{level}>\n");
            } else if in_cell {
                let _ = write!(self.out, "<div{cls}>{inline}</div>");
            } else {
                let _ = write!(self.out, "<p{cls}>{inline}</p>\n");
            }
        }
        // 문단에 딸린 개체(표·그림·글상자). 대부분 빈 문단에 붙어 있으므로 문단 뒤에 둔다.
        for ctl in reading_order(&p.controls) {
            self.control(ctl);
        }
    }

    /// 문단 글자 → 인라인 XHTML(굵게·기울임·줄바꿈·각주 표시).
    fn inline_text(&mut self, p: &Paragraph) -> String {
        let doc: &'a Document = self.doc;
        let info = &doc.doc_info;
        let chars: Vec<char> = p.text.chars().collect();
        let mut out = String::new();
        let mut cur: (bool, bool) = (false, false);
        let mut run_i = 0usize;
        for (i, &ch) in chars.iter().enumerate() {
            let pos = p.char_offsets.get(i).copied().unwrap_or(i as u32);
            while run_i + 1 < p.char_shapes.len() && p.char_shapes[run_i + 1].start_pos <= pos {
                run_i += 1;
            }
            let style = p
                .char_shapes
                .get(run_i)
                .and_then(|r| info.char_shapes.get(r.char_shape_id as usize))
                .map(|c| (c.bold, c.italic))
                .unwrap_or((false, false));
            if style != cur {
                if cur.1 { out.push_str("</i>"); }
                if cur.0 { out.push_str("</b>"); }
                if style.0 { out.push_str("<b>"); }
                if style.1 { out.push_str("<i>"); }
                cur = style;
            }
            match ch {
                '\n' | '\r' => out.push_str("<br/>"),
                '\t' => out.push(' '),
                c if (c as u32) < 0x20 => {}
                c => {
                    let mut buf = [0u8; 4];
                    let s: &str = c.encode_utf8(&mut buf);
                    let surf = rhwp::renderer::composer::pua_to_text_surface(s);
                    out.push_str(&esc(&surf));
                }
            }
        }
        if cur.1 { out.push_str("</i>"); }
        if cur.0 { out.push_str("</b>"); }
        // 각주·미주 표시는 글 끝에 붙인다(정확한 글자 위치는 모델에서 제어문자로만 남는다).
        for ctl in &p.controls {
            let paras = match ctl {
                Control::Footnote(f) => Some(&f.paragraphs),
                Control::Endnote(e) => Some(&e.paragraphs),
                _ => None,
            };
            if let Some(ps) = paras {
                self.note_no += 1;
                let n = self.note_no;
                let _ = write!(out, "<a class=\"noteref\" href=\"#fn{n}\">{n})</a>");
                let mut body = String::new();
                for np in ps.iter() {
                    let t = self.inline_text(np);
                    if !t.trim().is_empty() {
                        if !body.is_empty() { body.push(' '); }
                        body.push_str(&t);
                    }
                }
                self.notes.push((n, body));
            }
        }
        out
    }

    fn control(&mut self, ctl: &Control) {
        match ctl {
            Control::Table(t) => self.table(t),
            Control::Picture(pic) => {
                self.picture(pic.image_attr.bin_data_id, &pic.common);
                if let Some(cap) = &pic.caption {
                    self.caption(&cap.paragraphs);
                }
            }
            Control::Shape(s) => self.shape(s),
            Control::Equation(eq) => {
                let s = eq.script.trim();
                if !s.is_empty() {
                    let _ = write!(self.out, "<p class=\"c\"><span class=\"eq\">{}</span></p>\n", esc(s));
                }
            }
            _ => {}
        }
    }

    fn caption(&mut self, ps: &[Paragraph]) {
        let mut t = String::new();
        for p in ps {
            let s = self.inline_text(p);
            if !s.trim().is_empty() {
                if !t.is_empty() { t.push(' '); }
                t.push_str(&s);
            }
        }
        if !t.is_empty() {
            let _ = write!(self.out, "<p class=\"cap\">{t}</p>\n");
        }
    }

    fn shape(&mut self, s: &ShapeObject) {
        if self.depth > 16 {
            return;
        }
        match s {
            ShapeObject::Group(g) => {
                self.depth += 1;
                for ch in &g.children {
                    self.shape(ch);
                }
                self.depth -= 1;
                if let Some(cap) = &g.caption {
                    self.caption(&cap.paragraphs);
                }
            }
            ShapeObject::Picture(p) => {
                self.picture(p.image_attr.bin_data_id, &p.common);
                if let Some(cap) = &p.caption {
                    self.caption(&cap.paragraphs);
                }
            }
            other => {
                if let Some(tb) = other.drawing().and_then(|d| d.text_box.as_ref()) {
                    self.paragraphs(&tb.paragraphs, false);
                }
            }
        }
    }

    fn picture(&mut self, bin_data_id: u16, common: &CommonObjAttr) {
        let Some(name) = self.image_file(bin_data_id) else { return };
        // 글자처럼 취급하는 작은 그림(한자·기호 글자 그림 등)은 글줄 안에 둔다. 높이는 본문 글자 대비 em.
        let em = common.height as f32 / self.body_size.max(1) as f32;
        if common.treat_as_char && em > 0.0 && em <= 3.0 {
            let _ = write!(self.out, "<img class=\"ch\" src=\"{name}\" alt=\"\" style=\"height:{:.2}em\"/>", em.max(0.6));
        } else {
            let _ = write!(self.out, "<img src=\"{name}\" alt=\"\"/>\n");
        }
    }

    /// bin_data 를 images/ 아래 파일로 쓰고 상대 경로를 돌려준다. MuPDF 가 못 그리는 형식은 PNG 로 바꾸고,
    /// 바꿀 수 없으면(WMF·EMF·OLE) None.
    fn image_file(&mut self, bin_data_id: u16) -> Option<String> {
        if bin_data_id == 0 {
            return None;
        }
        if let Some(done) = self.written.get(&bin_data_id) {
            return done.clone();
        }
        let r = self.write_image(bin_data_id);
        self.written.insert(bin_data_id, r.clone());
        r
    }

    fn write_image(&mut self, bin_data_id: u16) -> Option<String> {
        let doc: &'a Document = self.doc;
        let list = &doc.bin_data_content;
        // rhwp renderer/layout/utils.rs find_bin_data 와 같은 규칙: 1부터 순번, 범위 밖이면 id 검색
        let idx = (bin_data_id - 1) as usize;
        let item = if idx < list.len() { list.get(idx) } else { list.iter().find(|c| c.id == bin_data_id) }?;
        let ext = item.extension.to_ascii_lowercase();
        let bytes = item.data.load_limited(MAX_IMAGE_BYTES)?;
        if bytes.is_empty() {
            return None;
        }
        let (ext, bytes) = match ext.as_str() {
            "png" | "gif" => (ext.clone(), bytes),
            "jpg" | "jpeg" => ("jpg".to_string(), bytes),
            "bmp" | "tif" | "tiff" => ("png".to_string(), to_png(&bytes)?),
            _ => return None, // WMF·EMF·OLE 등
        };
        self.img_no += 1;
        let name = format!("images/img{}.{}", self.img_no, ext);
        let path = self.img_dir.join(&name);
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        std::fs::write(&path, &bytes).ok()?;
        Some(name)
    }

    fn table(&mut self, t: &Table) {
        if self.depth > 16 {
            return;
        }
        let rows = t.row_count as usize;
        if rows == 0 || t.cells.is_empty() {
            return;
        }
        let mut by_row: Vec<Vec<&rhwp::model::table::Cell>> = vec![Vec::new(); rows];
        for cell in &t.cells {
            let r = cell.row as usize;
            if r < rows {
                by_row[r].push(cell);
            }
        }
        self.out.push_str("<table class=\"hwp-table\">\n");
        for row in by_row.iter_mut() {
            row.sort_by_key(|c| c.col);
            self.out.push_str("<tr>");
            for cell in row.iter() {
                self.out.push_str("<td");
                if cell.col_span > 1 {
                    let _ = write!(self.out, " colspan=\"{}\"", cell.col_span);
                }
                if cell.row_span > 1 {
                    let _ = write!(self.out, " rowspan=\"{}\"", cell.row_span);
                }
                self.out.push('>');
                self.depth += 1;
                for p in &cell.paragraphs {
                    self.paragraph(p, true);
                }
                self.depth -= 1;
                self.out.push_str("</td>");
            }
            self.out.push_str("</tr>\n");
        }
        self.out.push_str("</table>\n");
        if let Some(cap) = &t.caption {
            self.caption(&cap.paragraphs);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rhwp::model::shape::{HorzRelTo, VertRelTo};

    fn paper(x: u32, y: u32, w: u32, h: u32) -> Control {
        let mut t = Table::default();
        t.common.horz_rel_to = HorzRelTo::Paper;
        t.common.vert_rel_to = VertRelTo::Paper;
        t.common.horizontal_offset = x;
        t.common.vertical_offset = y;
        t.common.width = w;
        t.common.height = h;
        Control::Table(Box::new(t))
    }

    fn xs(v: Vec<&Control>) -> Vec<u32> {
        v.iter()
            .map(|c| if let Control::Table(t) = c { t.common.horizontal_offset } else { u32::MAX })
            .collect()
    }

    /// I-782: 나란한 단(저장 순서 뒤섞임) → 제목 띠 먼저, 나머지 왼쪽→오른쪽
    #[test]
    fn side_by_side_paper_tables_read_left_to_right() {
        let c = vec![
            paper(61_568, 4_600, 20_000, 100_000),
            paper(24_064, 1_776, 33_672, 2_380), // 제목 띠
            paper(1_724, 4_600, 11_673, 100_000),
            paper(37_596, 4_600, 11_000, 100_000),
        ];
        assert_eq!(xs(reading_order(&c)), vec![24_064, 1_724, 37_596, 61_568]);
    }

    /// 가로로 겹치는 표(위아래로 쌓인 표 등)는 저장 순서를 지킨다
    #[test]
    fn overlapping_tables_keep_stored_order() {
        let c = vec![
            paper(5_000, 50_000, 20_000, 10_000),
            paper(1_000, 10_000, 20_000, 10_000),
            paper(3_000, 30_000, 20_000, 10_000),
        ];
        assert_eq!(xs(reading_order(&c)), vec![5_000, 1_000, 3_000]);
    }
}
