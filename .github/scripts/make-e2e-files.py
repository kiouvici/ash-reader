#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
7D 종단(E2E) UI 시험용 합성 문서를 만든다(공개 시험 파일에 없는 형식: EPUB·PDF·그림 PDF·CBZ·FB2·HTML·MOBI·ZIP).
외부 모듈 없이 표준 라이브러리만 쓴다. 사용법: make-e2e-files.py <출력 폴더>

파일 이름(확장자 뺀 것)이 서재 제목이 되므로 시험 코드(E2eFiles.kt)의 이름과 맞춘다.
"""
import io
import os
import struct
import sys
import time
import zipfile
import zlib

OUT = sys.argv[1] if len(sys.argv) > 1 else "e2e-files"
# 인텐트(ACTION_VIEW) 시험용: 서재가 모으지 않는 곳(Documents)에 둘 파일 → '한 번만 보기'로 열린다
OUT_VIEW = sys.argv[2] if len(sys.argv) > 2 else OUT + "-view"
os.makedirs(OUT, exist_ok=True)
os.makedirs(OUT_VIEW, exist_ok=True)

# 검색 시험 낱말(EPUB 3장·TXT 에 한 번씩)
NEEDLE = "반딧불이"

PARA = (
    "그는 창밖을 오래 바라보았다. 비는 그칠 기색이 없었고, 골목 끝 가로등은 젖은 길 위에 노란 빛을 길게 늘어뜨리고 있었다. "
    "오래된 책장에서 꺼낸 책은 손때가 묻어 모서리가 둥글었고, 넘길 때마다 종이 냄새가 났다. "
    "그날 밤 우리는 늦게까지 이야기를 나누었고, 아무도 먼저 일어나자는 말을 하지 않았다. "
)


def chapter_text(n, paras=14):
    out = []
    for i in range(paras):
        p = PARA
        if n == 3 and i == 5:
            p = p + f"그때 마당 끝에서 {NEEDLE} 한 마리가 깜빡였다. "
        out.append(f"<p>{n}장 {i + 1}문단. {p}</p>")
    return "\n".join(out)


# ───────────────────────── EPUB ─────────────────────────
def make_epub(path, title="e2e-epub 소설", chapters=5):
    z = zipfile.ZipFile(path, "w")
    z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
    z.writestr("META-INF/container.xml",
               '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
               '<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>',
               compress_type=zipfile.ZIP_DEFLATED)
    items, spine, navli, ncx = [], [], [], []
    for n in range(1, chapters + 1):
        name = f"ch{n}.xhtml"
        z.writestr("OEBPS/" + name,
                   f'<?xml version="1.0" encoding="utf-8"?><!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xml:lang="ko">'
                   f'<head><title>{n}장</title></head><body><h1>제{n}장 시험 장</h1>\n{chapter_text(n)}</body></html>',
                   compress_type=zipfile.ZIP_DEFLATED)
        items.append(f'<item id="c{n}" href="{name}" media-type="application/xhtml+xml"/>')
        spine.append(f'<itemref idref="c{n}"/>')
        navli.append(f'<li><a href="{name}">제{n}장 시험 장</a></li>')
        ncx.append(f'<navPoint id="n{n}" playOrder="{n}"><navLabel><text>제{n}장 시험 장</text></navLabel><content src="{name}"/></navPoint>')
    z.writestr("OEBPS/nav.xhtml",
               '<?xml version="1.0" encoding="utf-8"?><!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">'
               f'<head><title>목차</title></head><body><nav epub:type="toc"><ol>{"".join(navli)}</ol></nav></body></html>',
               compress_type=zipfile.ZIP_DEFLATED)
    z.writestr("OEBPS/toc.ncx",
               '<?xml version="1.0" encoding="utf-8"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">'
               f'<head><meta name="dtb:uid" content="e2e-epub"/></head><docTitle><text>{title}</text></docTitle><navMap>{"".join(ncx)}</navMap></ncx>',
               compress_type=zipfile.ZIP_DEFLATED)
    z.writestr("OEBPS/content.opf",
               '<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">'
               '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
               f'<dc:identifier id="uid">e2e-epub-{title}</dc:identifier><dc:title>{title}</dc:title><dc:language>ko</dc:language>'
               '<meta property="dcterms:modified">2026-10-23T00:00:00Z</meta></metadata>'
               f'<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
               f'<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>{"".join(items)}</manifest>'
               f'<spine toc="ncx">{"".join(spine)}</spine></package>',
               compress_type=zipfile.ZIP_DEFLATED)
    z.close()


# ───────────────────────── PDF ─────────────────────────
def pdf_write(path, objects):
    """objects: list of bytes (obj 본문). 1번부터 차례로 번호. 1번 = Catalog."""
    buf = io.BytesIO()
    buf.write(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offs = []
    for i, body in enumerate(objects, start=1):
        offs.append(buf.tell())
        buf.write(f"{i} 0 obj\n".encode() + body + b"\nendobj\n")
    xref = buf.tell()
    buf.write(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
    for o in offs:
        buf.write(f"{o:010d} 00000 n \n".encode())
    buf.write(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    with open(path, "wb") as f:
        f.write(buf.getvalue())


def stream(dict_extra, data, compress=True):
    if compress:
        data = zlib.compress(data)
        dict_extra += b" /Filter /FlateDecode"
    return b"<< /Length %d %s >>\nstream\n" % (len(data), dict_extra) + data + b"\nendstream"


def make_text_pdf(path, pages=6):
    # 1 Catalog, 2 Pages, 3 Font, 이후 쪽마다 (Page, Contents)
    kids = []
    objs = [None, None, b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    for p in range(pages):
        page_no = len(objs) + 1
        kids.append(page_no)
        lines = [f"ASH e2e PDF page {p + 1}", ""] + [
            f"Line {i + 1}: The quick brown fox jumps over the lazy dog." + (" Firefly" if (p == 3 and i == 4) else "")
            for i in range(30)
        ]
        ops = ["BT /F1 14 Tf 60 780 Td 18 TL"]
        for ln in lines:
            ops.append("(" + ln.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ") Tj T*")
        ops.append("ET")
        content = "\n".join(ops).encode()
        objs.append(b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents %d 0 R >>" % (page_no + 1))
        objs.append(stream(b"", content))
    objs[0] = b"<< /Type /Catalog /Pages 2 0 R >>"
    objs[1] = b"<< /Type /Pages /Kids [%s] /Count %d >>" % (b" ".join(b"%d 0 R" % k for k in kids), pages)
    pdf_write(path, objs)


def comic_page_rgb(w, h, page, panels):
    """흰 바탕에 검은 테두리 칸(panels: [(x0,y0,x1,y1) 비율]) + 칸마다 다른 회색 + 쪽 번호 막대."""
    row_bg = bytearray(b"\xff" * (w * 3))
    img = [bytearray(row_bg) for _ in range(h)]
    for k, (fx0, fy0, fx1, fy1) in enumerate(panels):
        x0, y0, x1, y1 = int(fx0 * w), int(fy0 * h), int(fx1 * w), int(fy1 * h)
        shade = (60 + 40 * ((k + page) % 4)) & 0xFF
        for y in range(y0, y1):
            r = img[y]
            border = y - y0 < 6 or y1 - y <= 6
            for x in range(x0, x1):
                if border or x - x0 < 6 or x1 - x <= 6:
                    r[x * 3:x * 3 + 3] = b"\x00\x00\x00"
                else:
                    c = shade if ((x // 40 + y // 40) % 2) else min(255, shade + 60)
                    r[x * 3:x * 3 + 3] = bytes((c, c, (c + 30) & 0xFF))
    # 쪽 번호: 아래쪽 막대 page+1 개
    for b in range(page + 1):
        bx = 20 + b * 22
        for y in range(h - 40, h - 16):
            img[y][bx * 3:(bx + 14) * 3] = b"\x20\x20\xc0" * 14
    return img


def png_bytes(w, h, rows):
    raw = b"".join(b"\x00" + bytes(r) for r in rows)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


LAYOUTS = [
    [(0.04, 0.03, 0.96, 0.32), (0.04, 0.35, 0.48, 0.66), (0.52, 0.35, 0.96, 0.66), (0.04, 0.69, 0.96, 0.95)],
    [(0.04, 0.03, 0.60, 0.48), (0.63, 0.03, 0.96, 0.48), (0.04, 0.51, 0.96, 0.95)],
    [(0.04, 0.03, 0.96, 0.22), (0.04, 0.25, 0.96, 0.47), (0.04, 0.50, 0.48, 0.95), (0.52, 0.50, 0.96, 0.95)],
]


def make_image_pdf(path, pages=4, w=600, h=850):
    objs = [None, None]
    kids = []
    for p in range(pages):
        rows = comic_page_rgb(w, h, p, LAYOUTS[p % len(LAYOUTS)])
        raw = b"".join(bytes(r) for r in rows)
        page_no = len(objs) + 1
        kids.append(page_no)
        content = b"q 595 0 0 842 0 0 cm /Im0 Do Q"
        objs.append(b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << /Im0 %d 0 R >> >> /Contents %d 0 R >>" % (page_no + 1, page_no + 2))
        objs.append(stream(b"/Type /XObject /Subtype /Image /Width %d /Height %d /ColorSpace /DeviceRGB /BitsPerComponent 8" % (w, h), raw))
        objs.append(stream(b"", content))
    objs[0] = b"<< /Type /Catalog /Pages 2 0 R >>"
    objs[1] = b"<< /Type /Pages /Kids [%s] /Count %d >>" % (b" ".join(b"%d 0 R" % k for k in kids), pages)
    pdf_write(path, objs)


# ───────────────────────── CBZ ─────────────────────────
def make_cbz(path, pages=8, w=800, h=1200):
    z = zipfile.ZipFile(path, "w", zipfile.ZIP_STORED)
    for p in range(pages):
        rows = comic_page_rgb(w, h, p, LAYOUTS[p % len(LAYOUTS)])
        z.writestr(f"{p + 1:03d}.png", png_bytes(w, h, rows))
    z.close()


# ───────────────────────── FB2·HTML·TXT ─────────────────────────
def make_fb2(path):
    secs = "".join(f"<section><title><p>제{n}장</p></title>" + "".join(f"<p>{PARA}</p>" for _ in range(10)) + "</section>" for n in range(1, 4))
    s = ('<?xml version="1.0" encoding="utf-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">'
         '<description><title-info><book-title>e2e-fb2</book-title><lang>ko</lang></title-info></description>'
         f"<body>{secs}</body></FictionBook>")
    open(path, "w", encoding="utf-8").write(s)


def make_html(path):
    body = "".join(f"<h2>제{n}절</h2>" + "".join(f"<p>{PARA}</p>" for _ in range(8)) for n in range(1, 4))
    open(path, "w", encoding="utf-8").write(f'<!DOCTYPE html><html lang="ko"><head><meta charset="utf-8"><title>e2e-html</title></head><body>{body}</body></html>')


def make_txt(path):
    parts = []
    for n in range(1, 6):
        parts.append(f"제{n}장 시험\n")
        for i in range(12):
            parts.append(PARA + (f"{NEEDLE}가 날았다." if n == 2 and i == 3 else "") + "\n\n")
    open(path, "w", encoding="utf-8").write("".join(parts))


# ───────────────────────── MOBI(PalmDOC, 압축 없음) ─────────────────────────
def make_mobi(path, title="e2e-mobi"):
    html = ("<html><head><guide></guide></head><body>"
            + "".join(f"<h2>제{n}장</h2>" + "".join(f"<p>{PARA}</p>" for _ in range(6)) + "<mbp:pagebreak/>" for n in range(1, 4))
            + "</body></html>").encode("utf-8")
    recs = [html[i:i + 4096] for i in range(0, len(html), 4096)]
    n = len(recs)
    name = title.encode()
    mobi = bytearray(232)

    def put(off, fmt, v):
        struct.pack_into(fmt, mobi, off, v)
    mobi[0:4] = b"MOBI"
    put(4, ">I", 232); put(8, ">I", 2); put(12, ">I", 65001); put(16, ">I", 0x5A5A1234); put(20, ">I", 6)
    for off in range(24, 64, 4):
        put(off, ">I", 0xFFFFFFFF)
    put(64, ">I", n + 1)                       # first non-book index
    put(68, ">I", 16 + 232)                    # full name offset (레코드 0 기준)
    put(72, ">I", len(name))
    put(76, ">I", 0x12)                        # locale(한국어)
    put(88, ">I", 6)                           # min version
    put(92, ">I", 0xFFFFFFFF)                  # first image index
    put(112, ">I", 0)                          # EXTH 없음
    put(148, ">I", 0xFFFFFFFF); put(152, ">I", 0xFFFFFFFF)
    struct.pack_into(">HH", mobi, 176, 1, n)  # first/last content record
    put(180, ">I", 1); put(184, ">I", 0xFFFFFFFF); put(188, ">I", 1); put(192, ">I", 0xFFFFFFFF); put(196, ">I", 1)
    put(208, ">I", 0xFFFFFFFF); put(216, ">I", 0xFFFFFFFF); put(220, ">I", 0xFFFFFFFF)
    put(224, ">I", 0)                          # 레코드 끝 덧붙임 없음
    put(228, ">I", 0xFFFFFFFF)
    palm = struct.pack(">HHIHHHH", 1, 0, len(html), n, 4096, 0, 0)
    rec0 = palm + bytes(mobi) + name + b"\x00" * (4 - len(name) % 4 + 4)
    records = [rec0] + recs + [b"\xe9\x8e\r\n"]  # 끝 표시 레코드
    hdr_len = 78 + 8 * len(records) + 2
    now = int(time.time()) + 2082844800
    pdb = bytearray(name[:31].ljust(32, b"\x00"))
    pdb += struct.pack(">HHIIIIII", 0, 0, now, now, 0, 0, 0, 0) + b"BOOKMOBI" + struct.pack(">IIH", 2 * len(records), 0, len(records))
    off = hdr_len
    for i, r in enumerate(records):
        pdb += struct.pack(">IB", off, 0) + struct.pack(">I", 2 * i)[1:]
        off += len(r)
    pdb += b"\x00\x00"
    for r in records:
        pdb += r
    open(path, "wb").write(bytes(pdb))


def make_zip(path, epub_path, txt_path):
    z = zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED)
    z.write(epub_path, "묶음/첫째 책.epub")
    z.write(txt_path, "묶음/둘째 글.txt")
    z.close()


make_epub(os.path.join(OUT, "e2e-epub 소설.epub"))
make_text_pdf(os.path.join(OUT, "e2e-pdf 글자.pdf"))
make_image_pdf(os.path.join(OUT, "e2e-pdf 그림.pdf"))
make_cbz(os.path.join(OUT, "e2e-cbz 만화.cbz"))
make_fb2(os.path.join(OUT, "e2e-fb2.fb2"))
make_html(os.path.join(OUT, "e2e-html.html"))
make_txt(os.path.join(OUT, "e2e-txt utf8.txt"))
make_mobi(os.path.join(OUT, "e2e-mobi.mobi"))
# 인텐트 시험(같은 파일 두 번·다른 파일)용 작은 EPUB 두 권
make_epub(os.path.join(OUT_VIEW, "e2e-intent 하나.epub"), title="e2e-intent 하나", chapters=2)
make_epub(os.path.join(OUT_VIEW, "e2e-intent 둘.epub"), title="e2e-intent 둘", chapters=2)
make_text_pdf(os.path.join(OUT_VIEW, "e2e-intent 셋.pdf"), pages=3)
tmp_txt = os.path.join(OUT, ".tmp.txt")
make_txt(tmp_txt)
make_zip(os.path.join(OUT, "e2e-zip 묶음.zip"), os.path.join(OUT_VIEW, "e2e-intent 하나.epub"), tmp_txt)
os.remove(tmp_txt)
for d in (OUT, OUT_VIEW):
    for f in sorted(os.listdir(d)):
        print(f"{os.path.getsize(os.path.join(d, f)):>10}  {d}/{f}")
