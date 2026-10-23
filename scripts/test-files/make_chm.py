#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""시험용 CHM 만들기(무압축 section 0 만 쓰는 최소 ITSF v3). 사용: make_chm.py <출력.chm>

한국어 CHM 처럼 HTML·목차(HHC)를 CP949 로 쓴다. 내용은 직접 쓴 문장(저작권 없음).
libmspack chmd 가 읽는 구조(chmd_read_headers)만 맞춘다: ITSF 머리 → 머리 구역 표 → HS0 → ITSP(HS1) → PMGL 한 덩이 → 내용(section 0).
"""
import struct
import sys

GUIDS = bytes([0x10, 0xFD, 0x01, 0x7C, 0xAA, 0x7B, 0xD0, 0x11, 0x9E, 0x0C, 0x00, 0xA0, 0xC9, 0x22, 0xE6, 0xEC,
               0x11, 0xFD, 0x01, 0x7C, 0xAA, 0x7B, 0xD0, 0x11, 0x9E, 0x0C, 0x00, 0xA0, 0xC9, 0x22, 0xE6, 0xEC])
CHUNK = 4096


def encint(v):
    out = [v & 0x7F]
    v >>= 7
    while v:
        out.append(0x80 | (v & 0x7F))
        v >>= 7
    return bytes(reversed(out))


def page(title, body):
    return ("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=euc-kr\">"
            "<title>%s</title><script>alert('x')</script></head><body><h1>%s</h1>%s</body></html>" % (title, title, body)).encode("cp949")


def files():
    hhc = """<HTML><BODY>
<OBJECT type="text/site properties"><param name="ImageType" value="Folder"></OBJECT>
<UL>
 <LI><OBJECT type="text/sitemap"><param name="Name" value="1장 바닷가 마을"><param name="Local" value="ch/1.htm"></OBJECT>
 <UL>
  <LI><OBJECT type="text/sitemap"><param name="Name" value="1.1 새벽 배"><param name="Local" value="ch/1.htm#s1"></OBJECT>
 </UL>
 <LI><OBJECT type="text/sitemap"><param name="Name" value="2장 똠방각하"><param name="Local" value="ch/2.htm"></OBJECT>
</UL></BODY></HTML>""".encode("cp949")
    gif = bytes.fromhex("47494638396101000100800000ffffff00000021f90401000000002c00000000010001000002024401003b")
    return [
        ("/#SYSTEM", b"\x03\x00\x00\x00"),
        ("/ch/1.htm", page("1장 바닷가 마을", "<p>바닷가 마을에는 아침마다 배가 들어왔다.</p><p id=\"s1\">새벽 배는 안개 속에서 천천히 다가왔다.</p><img src=\"../img/dot.gif\">")),
        ("/ch/2.htm", page("2장 똠방각하", "<p>똠방각하는 마을 사람들이 붙인 별명이었다. 뷁!</p>")),
        ("/img/dot.gif", gif),
        ("/toc.hhc", hhc),
    ]


def build():
    fl = sorted(files(), key=lambda x: x[0].lower())
    content = b""
    entries = b""
    for name, data in fl:
        nb = name.encode("utf-8")
        entries += encint(len(nb)) + nb + encint(0) + encint(len(content)) + encint(len(data))
        content += data
    assert 0x14 + len(entries) + 2 <= CHUNK
    free = CHUNK - 0x14 - len(entries)
    pmgl = b"PMGL" + struct.pack("<IIii", free, 0, -1, -1) + entries
    pmgl += b"\x00" * (CHUNK - len(pmgl) - 2) + struct.pack("<H", len(fl))
    hs1_off = 0x60 + 0x18
    itsp = b"ITSP" + struct.pack("<IIIIIiIIIiII", 1, 0x54, 0x0A, CHUNK, 2, 1, 0xFFFFFFFF, 0, 0, -1, 1, 0x412)
    itsp += GUIDS[:16] + struct.pack("<IiII", 0x54, -1, 0xFFFFFFFF, 0xFFFFFFFF)
    assert len(itsp) == 0x54
    cs0 = hs1_off + 0x54 + CHUNK
    total = cs0 + len(content)
    head = b"ITSF" + struct.pack("<IIIII", 3, 0x60, 1, 0x12345678, 0x412) + GUIDS
    table = struct.pack("<QQQQQ", 0x60, 0x18, hs1_off, 0x54 + CHUNK, cs0)
    hs0 = struct.pack("<IIQII", 0x1FE, 0, total, 0, 0)
    out = head + table + hs0 + itsp + pmgl + content
    assert len(out) == total
    return out


if __name__ == "__main__":
    with open(sys.argv[1], "wb") as f:
        f.write(build())
