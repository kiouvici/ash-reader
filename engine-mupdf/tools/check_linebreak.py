#!/usr/bin/env python3
"""실험 ④: MuPDF 한국어 줄바꿈(F-37)·금칙(F-38) 자동 검사.

사용법:
  python3 check_linebreak.py --mutool PATH --corpus corpus/sentences.txt --out out/

하는 일:
  1. 시험 문장 묶음을 XHTML로 만들고 (방식별 사용자 CSS / 텍스트 전처리 적용)
  2. mutool convert -F stext 로 여러 화면 폭에서 조판해 줄 단위 텍스트를 얻고
  3. 줄마다 아래 규칙을 검사해 위반 수를 센다.
     - 낱말 중간 줄바꿈: 줄 끝 글자와 다음 줄 첫 글자가 원문에서 공백 없이 붙어 있던 경우
       (단, 그 '낱말'이 한 줄 폭보다 길어 어쩔 수 없이 끊긴 경우는 제외 = 허용 예외)
     - 줄머리 금칙(F-38): 줄 첫 글자가 . , ! ? … 」 』 ” ’ ) ] } 。 、 : ; 인 경우
     - 줄끝 금칙(F-38): 줄 끝 글자가 「 『 “ ‘ ( [ { 인 경우
  4. 방식·폭별 결과를 result.json, result.md 로 쓰고, 대표 폭의 첫 쪽 PNG를 남긴다.

방식:
  baseline : 패치 없는 동작과 같음(word-break 지정 없음)
  keepall  : 사용자 CSS에 word-break:keep-all + overflow-wrap:break-word (MuPDF 패치 필요)
  wj       : 낱말 안 글자 사이에 U+2060 삽입 + overflow-wrap:break-word (패치 불필요 대안)
  charunit : '글자 단위' 설정(F-37 선택지) = baseline과 같은 CSS, 위반 집계는 참고용
"""
import argparse, html, json, os, re, subprocess, sys, xml.etree.ElementTree as ET

WJ = "⁠"
LINE_START_FORBIDDEN = set(".,!?…」』”’)]}。、:;%℃")
LINE_END_FORBIDDEN = set("「『“‘([{")

BASE_CSS = """
@font-face { font-family: ash-serif; src: url("{fontdir}/NanumMyeongjo-Regular.ttf"); }
@font-face { font-family: ash-serif; font-weight: bold; src: url("{fontdir}/NanumMyeongjo-Bold.ttf"); }
body {{ margin: 0; font-family: ash-serif; line-height: 1.7; }}
p {{ margin: 0 0 0.5em 0; text-align: justify; text-indent: 1em; }}
"""

MODES = {
    "baseline": "",
    "keepall": "p { word-break: keep-all; overflow-wrap: break-word; }",
    "wj": "p { overflow-wrap: break-word; }",
    "charunit": "",
}


def read_paragraphs(path):
    paras, cur = [], []
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if line.startswith("#"):
            continue
        if not line.strip():
            if cur:
                paras.append(" ".join(cur)); cur = []
        else:
            cur.append(line.strip())
    if cur:
        paras.append(" ".join(cur))
    return paras


def insert_wj(text):
    # 공백이 아닌 글자 두 개 사이에 WORD JOINER를 넣는다(낱말 안 줄바꿈 금지).
    out = []
    for i, ch in enumerate(text):
        out.append(ch)
        if i + 1 < len(text) and not ch.isspace() and not text[i + 1].isspace():
            out.append(WJ)
    return "".join(out)


def build_xhtml(paras, mode):
    body = []
    for p in paras:
        t = insert_wj(p) if mode == "wj" else p
        body.append("<p>%s</p>" % html.escape(t, quote=False))
    return ('<?xml version="1.0" encoding="utf-8"?>\n<html xmlns="http://www.w3.org/1999/xhtml" lang="ko">'
            "<head><meta charset=\"utf-8\"/><title>t</title></head><body>%s</body></html>" % "".join(body))


def stext_lines(mutool, xhtml_path, css_path, width, height, size):
    xml_path = xhtml_path + ".%d.stext.xml" % width
    cmd = [mutool, "convert", "-F", "stext", "-W", str(width), "-H", str(height), "-S", str(size),
           "-U", css_path, "-o", xml_path, xhtml_path]
    subprocess.run(cmd, check=True, capture_output=True)
    lines = []
    root = ET.parse(xml_path).getroot()
    for page in root.iter("page"):
        for line in page.iter("line"):
            chars = [c.get("c") for c in line.iter("char")]
            xs = [float(c.get("x")) for c in line.iter("char")]
            txt = "".join(chars).replace(WJ, "")
            if txt.strip():
                lines.append({"text": txt, "x0": min(xs) if xs else 0})
    return lines


def tokens_with_width(paras):
    # 원문 기준 '낱말'(공백으로 나뉜 덩어리) 목록
    toks = []
    for p in paras:
        toks.extend(p.split())
    return toks


def check(lines, paras, line_width_chars_hint):
    """줄 경계마다 원문에서 공백이 있었는지 확인한다."""
    full = "\n".join(paras)
    # 원문을 공백 정규화한 글자열과, 각 글자 뒤에 공백이 있었는지 표
    stream = []  # (char, space_after)
    for p in paras:
        norm = re.sub(r"\s+", " ", p.strip())
        for i, ch in enumerate(norm):
            if ch == " ":
                if stream:
                    stream[-1] = (stream[-1][0], True)
                continue
            stream.append((ch, False))
        if stream:
            stream[-1] = (stream[-1][0], True)  # 문단 끝
    # 조판 결과 줄을 원문 스트림에 맞춰 걷는다
    pos = 0
    res = {"lines": len(lines), "mid_word": 0, "mid_word_allowed": 0, "start_forbidden": 0,
           "end_forbidden": 0, "examples": []}
    token_len_at = {}
    # 각 스트림 위치가 속한 낱말 길이(글자 수)
    i = 0
    while i < len(stream):
        j = i
        while j < len(stream) and not stream[j][1]:
            j += 1
        L = j - i + 1
        for k in range(i, j + 1):
            token_len_at[k] = (i, L)
        i = j + 1
    for li, ln in enumerate(lines):
        chars = [c for c in ln["text"] if not c.isspace()]
        if not chars:
            continue
        # 줄 머리/끝 금칙
        if chars[0] in LINE_START_FORBIDDEN:
            res["start_forbidden"] += 1
            res["examples"].append(("줄머리 금칙", lines[li - 1]["text"][-12:] if li else "", ln["text"][:12]))
        if chars[-1] in LINE_END_FORBIDDEN:
            res["end_forbidden"] += 1
            res["examples"].append(("줄끝 금칙", ln["text"][-12:], lines[li + 1]["text"][:12] if li + 1 < len(lines) else ""))
        # 스트림 맞추기
        for ch in chars:
            # 줄 머리 들여쓰기·목록 기호 등 원문에 없는 글자는 건너뜀
            while pos < len(stream) and stream[pos][0] != ch:
                pos += 1
            if pos >= len(stream):
                break
            pos += 1
        last = pos - 1
        if 0 <= last < len(stream) and not stream[last][1] and li + 1 < len(lines):
            start, L = token_len_at.get(last, (0, 0))
            if L > line_width_chars_hint:
                res["mid_word_allowed"] += 1
            else:
                res["mid_word"] += 1
                if len(res["examples"]) < 40:
                    res["examples"].append(("낱말 중간", ln["text"][-12:], lines[li + 1]["text"][:12]))
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mutool", required=True)
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--fonts", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "fonts"))
    ap.add_argument("--out", required=True)
    ap.add_argument("--widths", default="200,260,320,400,520")
    ap.add_argument("--size", type=int, default=12)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    paras = read_paragraphs(args.corpus)
    results = {}
    for mode, extra in MODES.items():
        css_path = os.path.join(args.out, "user-%s.css" % mode)
        with open(css_path, "w", encoding="utf-8") as f:
            f.write(BASE_CSS.replace("{fontdir}", os.path.abspath(args.fonts)).replace("{{", "{").replace("}}", "}") + extra)
        xhtml_path = os.path.join(args.out, "doc-%s.xhtml" % mode)
        with open(xhtml_path, "w", encoding="utf-8") as f:
            f.write(build_xhtml(paras, mode))
        results[mode] = {}
        for w in [int(x) for x in args.widths.split(",")]:
            lines = stext_lines(args.mutool, xhtml_path, css_path, w, 20000, args.size)
            # 한 줄에 들어가는 한글 글자 수 근사: (폭 - 여백 0) / 글자 크기
            hint = int(w / args.size)
            r = check(lines, paras, hint)
            results[mode][w] = r
        # 대표 폭(320pt) 첫 쪽 이미지
        png = os.path.join(args.out, "%s-320.png" % mode)
        subprocess.run([args.mutool, "draw", "-q", "-r", "144", "-W", "320", "-H", "560", "-S", str(args.size),
                        "-U", css_path, "-o", png, xhtml_path, "1"], check=True, capture_output=True)
    with open(os.path.join(args.out, "result.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)
    md = ["| 방식 | 폭(pt) | 줄 수 | 낱말 중간 줄바꿈(위반) | 긴 낱말 글자 단위(허용) | 줄머리 금칙 | 줄끝 금칙 |", "|---|---|---|---|---|---|---|"]
    for mode in results:
        for w, r in results[mode].items():
            md.append("| %s | %s | %d | %d | %d | %d | %d |" % (mode, w, r["lines"], r["mid_word"], r["mid_word_allowed"], r["start_forbidden"], r["end_forbidden"]))
    with open(os.path.join(args.out, "result.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(md) + "\n")
        for mode in ("keepall", "wj"):
            ex = [e for w in results[mode].values() for e in w["examples"]]
            f.write("\n### %s 위반 예시\n" % mode + ("\n".join("- %s: `%s` / `%s`" % e for e in ex[:20]) or "- 없음") + "\n")
    print("\n".join(md))
    fail = sum(results["keepall"][w]["mid_word"] + results["keepall"][w]["start_forbidden"] + results["keepall"][w]["end_forbidden"] for w in results["keepall"])
    sys.exit(1 if fail else 0)


if __name__ == "__main__":
    main()
