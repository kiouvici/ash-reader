#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""C1 오픈소스 고지 생성기.

실제 빌드 입력에서 목록을 뽑아 THIRD_PARTY_NOTICES(저장소 루트)와
app/src/main/assets/licenses/{index.json,texts/*.txt}(앱 S-52 화면)를 만든다. 손으로 목록을 적지 않는다.

입력(모두 빌드가 쓰는 것과 같은 판):
  --cargo-metadata  engine-hwp/rust 에서
                    `cargo metadata --format-version 1 --filter-platform aarch64-linux-android` 결과(JSON)
                    → ash-hwp-jni 에서 normal 의존으로 닿는 크레이트만(빌드·시험 전용 제외)
  --android-deps    `./gradlew --no-configuration-cache :app:ashThirdPartyDeps` 결과 폴더
                    (app/build/third-party: android-deps.tsv + notices/)
  --mupdf           MuPDF 1.28.5 소스(서브모듈 포함). platform/java/Android.mk 가 묶는 서드파티만
  --libarchive      libarchive 3.8.10 소스
  --ncnn-zip        ncnn-20261020-full-source.zip
  --realcugan       nihui/realcugan-ncnn-vulkan 395302c 체크아웃(LICENSE)
  --canonical       SPDX 표준 원문 폴더(Apache-2.0.txt, MIT.txt, OFL-1.1.txt …; rustup 의 share/doc/rust/licenses)
  --convert-tp      engine-convert/build/third_party (fetch-third-party.sh 결과: libmobi, libmspack)
  --djvu-tp         engine-djvu/build/third_party (fetch-third-party.sh 결과: djvulibre)
사용 예:
  (cd engine-hwp/rust && cargo metadata --format-version 1 --filter-platform aarch64-linux-android > /tmp/meta.json)
  ./gradlew --no-configuration-cache :app:ashThirdPartyDeps   # 또는 CI compile 작업의 third-party-android 아티팩트
  python3 scripts/third-party/gen_notices.py --cargo-metadata /tmp/meta.json --android-deps app/build/third-party \
      --mupdf engine-mupdf/build/mupdf-src --libarchive engine-comic/build/third_party/libarchive \
      --ncnn-zip ncnn-20261020-full-source.zip --realcugan realcugan-ncnn-vulkan \
      --canonical "$(rustc --print sysroot)/share/doc/rust/licenses" \
      --convert-tp engine-convert/build/third_party --djvu-tp engine-djvu/build/third_party
의존성(Cargo.lock·libs.versions.toml·엔진 판)을 바꾸면 다시 돌려 함께 커밋한다.
"""
import argparse
import hashlib
import json
import os
import re
import struct
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


class Notices:
    def __init__(self):
        self.items = []  # dict(name, version, license, group, copyright, note, texts=[ids])
        self.texts = {}  # id -> (title, text)

    def text(self, body, title):
        norm = " ".join(body.split())
        tid = hashlib.sha1(norm.encode("utf-8")).hexdigest()[:12]
        if tid not in self.texts:
            self.texts[tid] = (title, body.strip() + "\n")
        return tid

    def add(self, group, name, version, license, texts, copyright="", note=""):
        self.items.append(dict(group=group, name=name, version=version, license=license,
                               copyright=copyright, note=note, texts=texts))


def read(p):
    with open(p, encoding="utf-8", errors="replace") as f:
        return f.read()


def canon(args, spdx):
    p = os.path.join(args.canonical, spdx + ".txt")
    return read(p) if os.path.exists(p) else None


# ---------------------------------------------------------------- 앱 자신
def app_self(n, args):
    agpl = read(os.path.join(ROOT, "LICENSE"))
    n.add("ASH Reader", "ASH Reader", "", "AGPL-3.0-or-later", [n.text(agpl, "GNU AGPL 3.0")],
          "",
          "소스 코드: https://github.com/kiouvici/ash-reader")


# ---------------------------------------------------------------- MuPDF (engine-mupdf)
MUPDF_THIRD = [
    # (Android.mk 모듈, 폴더, 표시 이름, SPDX, 원문 파일들)
    ("freetype", "freetype", "FreeType", "FTL OR GPL-2.0-or-later", ["LICENSE.TXT", "docs/FTL.TXT"]),
    ("gumbo", "gumbo-parser", "Gumbo HTML5 parser", "Apache-2.0", ["COPYING"]),
    ("jbig2dec", "jbig2dec", "jbig2dec", "AGPL-3.0-or-later", ["LICENSE"]),
    ("harfbuzz", "harfbuzz", "HarfBuzz", "MIT-Modern-Variant", ["COPYING"]),
    ("lcms2", "lcms2", "Little CMS (lcms2mt, Artifex 판)", "MIT", ["LICENSE"]),
    ("libjpeg", "libjpeg", "IJG libjpeg", "IJG", ["README"]),
    ("mujs", "mujs", "MuJS", "ISC", ["COPYING"]),
    ("openjpeg", "openjpeg", "OpenJPEG", "BSD-2-Clause", ["LICENSE"]),
    ("brotli", "brotli", "Brotli", "MIT", ["LICENSE"]),
    ("cmarkgfm", "cmark-gfm", "cmark-gfm", "BSD-2-Clause AND MIT", ["COPYING"]),
    ("extract", "extract", "Extract", "AGPL-3.0-or-later", ["COPYING"]),
]


def libjpeg_legal(readme):
    m = re.search(r"\nLEGAL ISSUES\n=+\n(.*?)\n(?:REFERENCES|ARCHIVE LOCATIONS|ACKNOWLEDGMENTS)\n=+", readme, re.S)
    return ("IJG libjpeg README — LEGAL ISSUES\n\n" + m.group(1).strip()) if m else readme


def mupdf(n, args):
    d = args.mupdf
    mk = read(os.path.join(d, "platform/java/Android.mk"))
    ver = "1.28.5"
    vh = os.path.join(d, "include/mupdf/fitz/version.h")
    if os.path.exists(vh):
        m = re.search(r'FZ_VERSION "([^"]+)"', read(vh))
        if m:
            ver = m.group(1)
    g = "PDF·EPUB 엔진 (C)"
    n.add(g, "MuPDF", ver, "AGPL-3.0-or-later", [n.text(read(os.path.join(d, "COPYING")), "GNU AGPL 3.0")],
          "Copyright (C) Artifex Software, Inc.",
          "한국어 줄바꿈·금칙 패치를 더해 빌드(engine-mupdf/patches). 자바 바인딩(platform/java) 포함.")
    for mod, folder, name, spdx, files in MUPDF_THIRD:
        if "mupdf_thirdparty_" + mod not in mk:
            continue
        base = os.path.join(d, "thirdparty", folder)
        ids = []
        for f in files:
            p = os.path.join(base, f)
            if not os.path.exists(p):
                continue
            t = read(p)
            if folder == "libjpeg":
                t = libjpeg_legal(t)
            ids.append(n.text(t, name + " — " + f))
        n.add(g, name, "", spdx, ids, note="MuPDF 에 함께 들어 있는 판(thirdparty/%s)" % folder)
    # 내장 글꼴(URW base35) — Android.mk 가 generated/resources/fonts/urw 를 넣는다
    if "fonts/urw" in mk:
        ofl = read(os.path.join(d, "resources/fonts/urw/OFL.txt"))
        n.add(g, "URW base35 글꼴 (Nimbus 등)", "", "OFL-1.1", [n.text(ofl, "URW base35 — OFL 1.1")],
              "Copyright 2016 by (URW)++ Design & Development.", "MuPDF 내장 PDF 기본 글꼴")
    # 하이픈 패턴 — Android.mk 가 generated/resources/hyphen 을 넣는다. 언어별 저작권·허락 문구 원문(.info)을 모은다.
    if "resources/hyphen" in mk:
        ld = os.path.join(d, "resources/hyphen/license")
        parts = []
        for f in sorted(os.listdir(ld)):
            if f.endswith(".info"):
                # 저작권·허락 문구가 있는 앞부분(제목~licence)만. hyphenmins·changes 등 조판 정보는 뺀다.
                keep = []
                for line in read(os.path.join(ld, f)).splitlines():
                    if re.match(r"%\s*(hyphenmins|texlive|changes|known_bugs|ignore)\s*:", line):
                        break
                    keep.append(line)
                parts.append("==== " + f + " ====\n" + "\n".join(keep).strip())
        readme = os.path.join(d, "resources/hyphen/README")
        head = read(readme).strip() + "\n\n" if os.path.exists(readme) else ""
        n.add(g, "TeX 하이픈 패턴 (hyph-utf8)", "", "언어별 (MIT·LPPL·LGPL 등)",
              [n.text(head + "\n\n".join(parts), "hyph-utf8 언어별 고지")], note="MuPDF 내장, 서양 언어 낱말 나누기")


# ---------------------------------------------------------------- 만화 엔진 (engine-comic)
def comic(n, args):
    g = "만화 엔진 (C·C++)"
    la = args.libarchive
    m = re.search(r'ARCHIVE_VERSION_ONLY_STRING "([^"]+)"', read(os.path.join(la, "libarchive/archive.h")))
    ver = m.group(1) if m else ""
    n.add(g, "libarchive", ver, "BSD-2-Clause", [n.text(read(os.path.join(la, "COPYING")), "libarchive COPYING")],
          "", "CBR(RAR·RAR5)·CBZ 읽기만, 외부 압축 라이브러리 없이 정적 링크")
    tag = re.search(r"^TAG=(\S+)", read(os.path.join(ROOT, "engine-comic/scripts/build-ncnn.sh")), re.M)
    rcsha = re.search(r"realcugan-ncnn-vulkan/([0-9a-f]{7})", read(os.path.join(ROOT, "engine-comic/scripts/fetch-third-party.sh")))
    with zipfile.ZipFile(args.ncnn_zip) as z:
        ncnn_lic = z.read("LICENSE.txt").decode("utf-8", "replace")
        n.add(g, "ncnn", tag.group(1) if tag else "", "BSD-3-Clause (+ 포함 구성요소 각 라이선스)", [n.text(ncnn_lic, "ncnn LICENSE.txt")],
              "Copyright (C) 2017 Tencent", "AI 화질 개선(Real-CUGAN) 추론, Vulkan 켬")
        names = z.namelist()
        if "glslang/LICENSE.txt" in names:
            n.add(g, "glslang (ncnn 포함)", "", "BSD-3-Clause AND BSD-2-Clause AND MIT AND Apache-2.0 외",
                  [n.text(z.read("glslang/LICENSE.txt").decode("utf-8", "replace"), "glslang LICENSE.txt")],
                  "", "ncnn 에 함께 들어 있는 판, Vulkan 셰이더 컴파일")
    rc = read(os.path.join(args.realcugan, "LICENSE"))
    n.add(g, "Real-CUGAN 2x 모델 (realcugan-ncnn-vulkan)", rcsha.group(1) if rcsha else "", "MIT", [n.text(rc, "realcugan-ncnn-vulkan LICENSE")],
          "Copyright (c) 2019 nihui",
          "모델 파일(se-up2x-conservative, nose-up2x-no-denoise)을 앱에 넣음. 원래 가중치: bilibili/ailab Real-CUGAN (MIT)")
    # 원래 모델 가중치(bilibili/ailab Real-CUGAN)의 고지: MIT 표준 원문에 저작권자를 넣어 싣는다
    mit = canon(args, "MIT")
    if mit:
        body = re.sub(r"Copyright \(c\) <year> <copyright holders>", "Copyright (c) bilibili / ailab", mit)
        n.add(g, "Real-CUGAN 원래 모델 가중치 (bilibili/ailab Real-CUGAN)", "", "MIT",
              [n.text(body, "Real-CUGAN (bilibili / ailab) MIT License")],
              "Real-CUGAN © bilibili / ailab, MIT License",
              "realcugan-ncnn-vulkan 모델 파일이 바탕으로 한 원래 가중치")
    agpl = read(os.path.join(ROOT, "LICENSE"))
    n.add(g, "Kumiko (칸 인식 방식)", "1.5", "AGPL-3.0", [n.text(agpl, "GNU AGPL 3.0")],
          "", "njean42/kumiko 의 처리 순서를 순수 Kotlin 으로 옮겨 씀(engine-comic PanelDetector)")


# ---------------------------------------------------------------- 문서 변환·DjVu 엔진 (engine-convert, engine-djvu)
def convert_djvu(n, args):
    g = "문서 변환·DjVu 엔진 (C·C++)"
    conv = read(os.path.join(ROOT, "engine-convert/scripts/fetch-third-party.sh"))
    mobi_dir = os.path.join(args.convert_tp, "libmobi")
    m = re.search(r"AC_INIT\(\[libmobi\], \[([^\]]+)\]", read(os.path.join(mobi_dir, "configure.ac")))
    ids = [n.text(read(os.path.join(mobi_dir, "COPYING")), "GNU LGPL 3.0")]
    gpl3 = canon(args, "GPL-3.0-or-later")  # LGPL 3.0 은 GPL 3.0 원문과 함께 준다
    if gpl3:
        ids.append(n.text(gpl3, "GNU GPL 3.0"))
    n.add(g, "libmobi", m.group(1) if m else "", "LGPL-3.0-or-later", ids,
          "Copyright (c) Bartek Fabiszewski",
          "MOBI·AZW3(KF8) → EPUB 재구성. 암호 해제(USE_ENCRYPTION) 빼고 정적 링크(engine-convert). 내장 miniz(MIT)·xmlwriter 포함")
    mz = read(os.path.join(mobi_dir, "src/miniz.c"))
    i = mz.find("This is free and unencumbered software")
    unl = mz[i:].split("*/", 1)[0].strip() if i >= 0 else mz.split("*/", 1)[0]
    n.add(g, "miniz (libmobi 포함)", "1.15", "Unlicense", [n.text(unl, "miniz.c — unlicense")], "Rich Geldreich",
          "libmobi 에 함께 들어 있는 판(src/miniz.c), 공개 도메인")
    msp = os.path.join(args.convert_tp, "libmspack/libmspack")
    sha = re.search(r"libmspack\.git - ([0-9a-f]{40})", conv)
    n.add(g, "libmspack", sha.group(1)[:7] if sha else "", "LGPL-2.1-only", [n.text(read(os.path.join(msp, "COPYING.LIB")), "GNU LGPL 2.1")],
          "(C) 2003-2019 Stuart Caie", "CHM 풀기(chmd·lzxd)만 정적 링크(engine-convert)")
    dj = os.path.join(args.djvu_tp, "djvulibre")
    cr = read(os.path.join(dj, "COPYRIGHT"))
    n.add(g, "DjVuLibre", "3.5.30", "GPL-2.0-or-later",
          [n.text(read(os.path.join(dj, "COPYING")), "GNU GPL 2.0"), n.text(cr, "DjVuLibre COPYRIGHT")],
          "Copyright (c) 2002 Leon Bottou and Yann Le Cun; Copyright (c) 2001 AT&T; Copyright (c) 1999-2001 LizardTech, Inc.",
          "DjVu 쪽 그리기(libdjvu, engine-djvu). 'GPL v2 또는 (선택에 따라) 그 이후 판'이라 AGPL-3.0-or-later 앱과 함께 배포(D-001 A안)")


# ---------------------------------------------------------------- Rust (engine-hwp)
LIC_FILE = re.compile(r"(?i)^(licen[cs]e|copying|notice|unlicense|copyright)")


def rust(n, args):
    g = "한글 엔진 (Rust)"
    m = json.load(open(args.cargo_metadata))
    pk = {p["id"]: p for p in m["packages"]}
    nodes = {x["id"]: x for x in m["resolve"]["nodes"]}
    root = m["resolve"]["root"]
    seen, st = set(), [root]
    while st:
        i = st.pop()
        if i in seen:
            continue
        seen.add(i)
        for dep in nodes[i]["deps"]:
            q = pk[dep["pkg"]]
            # 컴파일할 때만 도는 proc-macro(와 그 의존)는 결과물에 들어가지 않는다
            if any(t["kind"] == ["proc-macro"] for t in q["targets"]):
                continue
            if any(k["kind"] is None for k in dep["dep_kinds"]):
                st.append(dep["pkg"])
    seen.discard(root)
    rhwp_lic = None
    for i in seen:
        if pk[i]["name"] == "rhwp":
            p = os.path.join(os.path.dirname(pk[i]["manifest_path"]), "LICENSE")
            if os.path.exists(p):
                rhwp_lic = read(p)
    apache = canon(args, "Apache-2.0")
    mit = canon(args, "MIT")
    for i in sorted(seen, key=lambda x: (pk[x]["name"], pk[x]["version"])):
        p = pk[i]
        d = os.path.dirname(p["manifest_path"])
        files = sorted(f for f in os.listdir(d) if LIC_FILE.match(f))
        if p.get("license_file"):
            files.append(p["license_file"])
        ids = []
        for f in files:
            fp = os.path.join(d, f)
            if os.path.isdir(fp):
                for h in sorted(os.listdir(fp)):
                    ids.append(n.text(read(os.path.join(fp, h)), "%s — %s/%s" % (p["name"], f, h)))
            elif os.path.isfile(fp):
                ids.append(n.text(read(fp), "%s — %s" % (p["name"], f)))
        lic = p.get("license") or ""
        note = ""
        if not ids:
            if p["source"] is None and p["name"].startswith("rhwp") and rhwp_lic:
                ids.append(n.text(rhwp_lic, "rhwp LICENSE"))
                note = "rhwp 저장소의 LICENSE 를 따름"
            elif "Apache-2.0" in lic and apache:
                ids.append(n.text(apache, "Apache License 2.0"))
                note = "크레이트에 원문 파일이 없어 SPDX 표준 원문을 붙임"
            elif "MIT" in lic and mit:
                ids.append(n.text(mit, "MIT License"))
                note = "크레이트에 원문 파일이 없어 SPDX 표준 원문을 붙임"
        authors = ", ".join(re.sub(r"\s*<[^>]*>", "", a) for a in p.get("authors") or [])
        if p["name"] == "rhwp":
            note = (note + " " if note else "") + "HWP·HWPX 파서·조판(v0.8.7, 1a76570). 칸 안 종이 기준 표 배치 패치를 더해 빌드(engine-hwp/patches)"
        n.add(g, p["name"], p["version"], lic or "(표기 없음)", ids, authors, note.strip())


# ---------------------------------------------------------------- 자바·코틀린 (APK)
def android(n, args):
    g = "자바·코틀린 라이브러리"
    d = args.android_deps
    rows = [l.rstrip("\n").split("\t") for l in open(os.path.join(d, "android-deps.tsv"), encoding="utf-8") if l.strip()]
    notices_dir = os.path.join(d, "notices")
    notice_files = sorted(os.listdir(notices_dir)) if os.path.isdir(notices_dir) else []
    apache = canon(args, "Apache-2.0")
    for r in rows:
        r += [""] * (5 - len(r))
        coord, lic, url, pname, purl = r[:5]
        gid, aid, ver = coord.split(":")
        ids = []
        for f in notice_files:
            if f.startswith("%s__%s__" % (gid, aid)):
                ids.append(n.text(read(os.path.join(notices_dir, f)), "%s:%s — %s" % (gid, aid, f.split("__")[-1])))
        if "apache" in lic.lower() and apache and not any(n.texts[t][1].lstrip().startswith("Apache License") or
                                                          "Apache License" in n.texts[t][1][:200] for t in ids):
            ids.append(n.text(apache, "Apache License 2.0"))
        # 저장소에 넣어 둔 원문(jar 에 원문이 없는 것): scripts/third-party/texts
        if "protobuf" in aid and not ids:
            ids.append(n.text(read(os.path.join(ROOT, "scripts/third-party/texts/protobuf-LICENSE")), "Protocol Buffers LICENSE"))
        spdx = "Apache-2.0" if "apache" in lic.lower() and "2" in lic else lic
        n.add(g, "%s:%s" % (gid, aid), ver, spdx, ids, "", pname)


# ---------------------------------------------------------------- 글꼴 (앱 res/font)
def font_names(path):
    d = open(path, "rb").read()
    num = struct.unpack(">H", d[4:6])[0]
    out = {}
    for i in range(num):
        tag, _, off, _ = struct.unpack(">4sIII", d[12 + 16 * i:28 + 16 * i])
        if tag == b"name":
            _, cnt, so = struct.unpack(">HHH", d[off:off + 6])
            for j in range(cnt):
                pid, _, lid, nid, ln, o = struct.unpack(">HHHHHH", d[off + 6 + 12 * j:off + 18 + 12 * j])
                if pid == 3 and lid == 0x409 and nid not in out:
                    out[nid] = d[off + so + o:off + so + o + ln].decode("utf-16-be", "replace")
    return out


# 줄인 수정판(OFL 3항: 수정판은 원래 이름을 쓰지 않음) → 고지에는 원래 글꼴 이름으로 싣는다.
FONT_ORIGIN = {"ASH Batang": "RIDIBatang", "ASH Buri": "MaruBuri"}


def fonts(n, args):
    g = "글꼴"
    ofl = canon(args, "OFL-1.1")
    fd = os.path.join(ROOT, "app/src/main/res/font")
    fam = {}
    for f in sorted(os.listdir(fd)):
        if not f.endswith((".ttf", ".otf")):
            continue
        nm = font_names(os.path.join(fd, f))
        family = nm.get(16) or re.sub(r"\s+(Regular|Bold|Light|ExtraBold|Heavy)$", "", nm.get(1, f))
        fam.setdefault(FONT_ORIGIN.get(family, family), []).append((f, nm))
    for family, files in fam.items():
        nm = files[0][1]
        ver = (nm.get(5) or "").replace("Version ", "").split(";")[0]
        cur = nm.get(16) or nm.get(1, "")
        mod = ("한글·라틴·문장부호·기호만 남기고 힌팅을 뺀 수정판(글꼴 이름 %s). " % cur) if family in FONT_ORIGIN.values() else ""
        n.add(g, family, ver, "OFL-1.1", [n.text(ofl, "SIL Open Font License 1.1")], nm.get(0, ""),
              mod + "앱에 넣은 파일: " + ", ".join(f for f, _ in files))


def main():
    ap = argparse.ArgumentParser()
    for a in ["cargo-metadata", "android-deps", "mupdf", "libarchive", "ncnn-zip", "realcugan", "canonical", "convert-tp", "djvu-tp"]:
        ap.add_argument("--" + a, required=True)
    args = ap.parse_args()
    n = Notices()
    app_self(n, args)
    mupdf(n, args)
    rust(n, args)
    comic(n, args)
    convert_djvu(n, args)
    android(n, args)
    fonts(n, args)

    out = os.path.join(ROOT, "app/src/main/assets/licenses")
    td = os.path.join(out, "texts")
    os.makedirs(td, exist_ok=True)
    for f in os.listdir(td):
        os.remove(os.path.join(td, f))
    for tid, (_, body) in n.texts.items():
        with open(os.path.join(td, tid + ".txt"), "w", encoding="utf-8") as f:
            f.write(body)
    idx = {"generatedBy": "scripts/third-party/gen_notices.py",
           "components": [dict(c, texts=["texts/%s.txt" % t for t in c["texts"]]) for c in n.items]}
    with open(os.path.join(out, "index.json"), "w", encoding="utf-8") as f:
        json.dump(idx, f, ensure_ascii=False, indent=1)
        f.write("\n")

    # THIRD_PARTY_NOTICES (저장소 루트)
    L = []
    L.append("ASH Reader — THIRD PARTY NOTICES / 오픈소스 고지")
    L.append("=" * 60)
    L.append("ASH Reader 는 GNU Affero General Public License v3.0 or later 로 배포된다.")
    L.append("소스 코드: https://github.com/kiouvici/ash-reader")
    L.append("이 파일은 scripts/third-party/gen_notices.py 가 실제 빌드 입력(Cargo 해석 결과, Gradle 실행 클래스패스,")
    L.append("MuPDF Android.mk, engine-comic·engine-convert·engine-djvu 받기 스크립트, 앱 글꼴)에서 만든다. 손으로 고치지 말 것.")
    L.append("")
    groups = []
    for c in n.items:
        if c["group"] not in groups:
            groups.append(c["group"])
    for g in groups:
        rows = [c for c in n.items if c["group"] == g]
        L.append("")
        L.append("## %s (%d)" % (g, len(rows)))
        L.append("")
        for c in rows:
            line = "- %s%s — %s" % (c["name"], " " + c["version"] if c["version"] else "", c["license"])
            L.append(line)
            if c["copyright"]:
                L.append("    " + c["copyright"])
            if c["note"]:
                L.append("    " + c["note"])
            if c["texts"]:
                L.append("    원문: " + ", ".join("[%s]" % t for t in c["texts"]))
    L.append("")
    L.append("")
    L.append("=" * 60)
    L.append("라이선스 원문 (같은 원문은 한 번만 싣는다. [번호] 로 위 목록과 잇는다)")
    L.append("=" * 60)
    for tid, (title, body) in n.texts.items():
        users = [c["name"] for c in n.items if tid in c["texts"]]
        L.append("")
        L.append("-" * 60)
        L.append("[%s] %s" % (tid, title))
        L.append("적용: " + ", ".join(users[:12]) + (" 외 %d개" % (len(users) - 12) if len(users) > 12 else ""))
        L.append("-" * 60)
        L.append(body.rstrip())
    with open(os.path.join(ROOT, "THIRD_PARTY_NOTICES"), "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")
    print("components=%d texts=%d" % (len(n.items), len(n.texts)))


if __name__ == "__main__":
    main()
