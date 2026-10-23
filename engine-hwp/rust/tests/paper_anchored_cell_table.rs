// SPDX-License-Identifier: AGPL-3.0-or-later
//! I-781 회귀 시험: 표 칸 안에 든 '종이 기준' 부동 표(rhwp 패치 engine-hwp/patches/0001).
//!
//! 실제 문서(제천시 버스 시간표 1쪽)와 같은 구조의 HWP 를 시험 시점에 합성한다 —
//! 빈 문서 → 제목 1×1 표(용지 위 띠) 칸 안에, 그 아래 용지 자리를 가리키는 1×1 표를 넣고
//! HWP 5.0 으로 저장한 뒤 다시 연다(파서의 개체 공통 속성 비트 → 패치 → 조판까지 한 번에 지난다).
//! 개인 문서는 저장소에 싣지 않는다.

use rhwp::model::control::Control;
use rhwp::model::shape::{HorzAlign, HorzRelTo, TextWrap, VertAlign, VertRelTo};
use rhwp::model::table::Table;
use rhwp::renderer::render_tree::{RenderNode, RenderNodeType};
use rhwp::DocumentCore;

const DPI: f64 = 96.0;

fn px(hu: u32) -> f64 {
    hu as f64 * DPI / 7200.0
}

/// 버스 시간표 실물과 같은 위치 속성: 글자처럼 취급 아님, 가로·세로 '종이', 왼쪽·위, 자리차지.
fn set_paper_anchor(t: &mut Table, x: u32, y: u32, w: u32, h: u32) {
    let c = &mut t.common;
    c.treat_as_char = false;
    c.vert_rel_to = VertRelTo::Paper;
    c.horz_rel_to = HorzRelTo::Paper;
    c.vert_align = VertAlign::Top;
    c.horz_align = HorzAlign::Left;
    c.text_wrap = TextWrap::TopAndBottom;
    c.horizontal_offset = x;
    c.vertical_offset = y;
    c.width = w;
    c.height = h;
    c.attr = 0; // 저장 시 위 의미 필드로 속성 비트를 다시 만든다
    t.raw_ctrl_data.clear(); // 새로 만든 표의 원래 CTRL_HEADER 바이트 대신 위 값을 쓰게 한다
    let rows = t.row_count.max(1) as u32;
    for cell in &mut t.cells {
        cell.width = w;
        cell.height = h / rows;
    }
}

fn first_table_mut(core: &mut DocumentCore, para: usize) -> &mut Table {
    let p = &mut core.document_mut().sections[0].paragraphs[para];
    p.controls
        .iter_mut()
        .find_map(|c| if let Control::Table(t) = c { Some(t.as_mut()) } else { None })
        .expect("표")
}

/// 바깥 2×1 표(10000,2000 / 30000×2400) 아래 칸 안에, 그 밖 용지 자리(12000,6000 / 11000×40000)를
/// 가리키는 표가 든 HWP 바이트. 1×1 표는 rhwp 가 '감싼 표'로 풀어 따로 다루므로 2행으로 만든다
/// (실물: 21번 표의 칸 안 31번 표, 글자 든 제목 칸 안 46번 표).
fn synth_hwp() -> Vec<u8> {
    let mut core = DocumentCore::new_empty();
    core.create_blank_document_native().expect("빈 문서");
    core.create_table_native(0, 0, 0, 2, 1).expect("바깥 표");
    let last = core.document().sections[0].paragraphs.len() - 1;
    core.create_table_native(0, last, 0, 1, 1).expect("안쪽 표");

    let paras = &core.document().sections[0].paragraphs;
    let host = paras.iter().position(|p| p.controls.iter().any(|c| matches!(c, Control::Table(_)))).unwrap();
    let inner_para = paras
        .iter()
        .rposition(|p| p.controls.iter().any(|c| matches!(c, Control::Table(_))))
        .unwrap();
    assert_ne!(host, inner_para, "표 두 개가 서로 다른 문단에 있어야 한다");

    // 안쪽 표를 꺼내 종이 기준으로 바꾸고 제목 표 칸의 첫 문단으로 옮긴다
    let mut inner = {
        let p = &mut core.document_mut().sections[0].paragraphs[inner_para];
        let i = p.controls.iter().position(|c| matches!(c, Control::Table(_))).unwrap();
        if i < p.ctrl_data_records.len() {
            p.ctrl_data_records.remove(i);
        }
        match p.controls.remove(i) {
            Control::Table(t) => t,
            _ => unreachable!(),
        }
    };
    set_paper_anchor(&mut inner, 12_000, 6_000, 11_000, 40_000);
    let outer = first_table_mut(&mut core, host);
    set_paper_anchor(outer, 10_000, 2_000, 30_000, 2_400);
    let cp = &mut outer.cells[1].paragraphs[0];
    cp.controls.push(Control::Table(inner));
    cp.align_ctrl_data_records();
    core.export_hwp_native().expect("HWP 저장")
}

fn table_boxes(n: &RenderNode, out: &mut Vec<(f64, f64, f64, f64)>) {
    if let RenderNodeType::Table(_) = n.node_type {
        out.push((n.bbox.x, n.bbox.y, n.bbox.width, n.bbox.height));
    }
    for c in &n.children {
        table_boxes(c, out);
    }
}

#[test]
fn paper_anchored_table_in_cell_is_laid_out_on_paper() {
    let bytes = synth_hwp();
    if let Ok(dir) = std::env::var("ASH_SYNTH_OUT") {
        // 수동 확인용: 합성 문서를 파일로 남긴다
        std::fs::write(format!("{dir}/synth-paper-anchored-cell-table.hwp"), &bytes).unwrap();
    }
    let core = DocumentCore::from_bytes(&bytes).expect("합성 HWP 열기");
    if std::env::var("ASH_SYNTH_OUT").is_ok() {
        let tree = core.build_page_render_tree(0).unwrap();
        let mut b = Vec::new();
        table_boxes(&tree.root, &mut b);
        eprintln!("표 상자: {b:?}");
    }
    assert_eq!(core.page_count(), 1);

    // 패치: 안쪽 표가 칸 밖(본문 문단)으로 올라온다
    let top: usize = core.document().sections[0]
        .paragraphs
        .iter()
        .map(|p| p.controls.iter().filter(|c| matches!(c, Control::Table(_))).count())
        .sum();
    assert_eq!(top, 2, "칸 안 종이 기준 표는 본문 문단으로 올라와야 한다");

    let tree = core.build_page_render_tree(0).expect("1쪽 렌더 트리");
    let mut boxes = Vec::new();
    table_boxes(&tree.root, &mut boxes);
    let near = |a: f64, b: f64| (a - b).abs() < 3.0;
    assert!(
        boxes.iter().any(|b| near(b.0, px(12_000)) && near(b.1, px(6_000)) && near(b.2, px(11_000))),
        "안쪽 표는 용지 (12000, 6000) 에 저장 폭 그대로 놓여야 한다(종전: 제목 칸 안에 눌림): {boxes:?}"
    );
    assert!(
        boxes.iter().any(|b| near(b.0, px(10_000)) && b.3 < px(2_400) + 6.0),
        "바깥 표는 안쪽 표 높이만큼 부풀면 안 된다(종전: 칸이 부풀어 이웃 표를 덮음): {boxes:?}"
    );
}
