#!/usr/bin/env python3
"""Прототип парсера Inno Setup 5.x (unicode) для GameInstaller.

Читає Setup.exe, розпаковує заголовок і показує:
  - версію Inno, ім'я гри;
  - допоміжні файли інсталятора ({tmp}\\...);
  - команди unarc (архіви FreeArc, їхній порядок);
  - які зовнішні компресори згадуються (srep, precomp, packjpg, ...).
Пароль у виводі маскується. Kotlin-версія: InnoHeader.kt / ScriptScan.kt.

Використання: python3 -I inno_probe.py /шлях/до/Setup.exe
"""
import lzma
import re
import struct
import sys

LDR_ID_PREFIX = b"rDlPtS"
SETUP_ID_LEN = 64


def read_loader_table(d: bytes) -> dict:
    i = d.find(LDR_ID_PREFIX)
    if i < 0:
        raise SystemExit("Не Inno Setup: немає таблиці rDlPtS")
    f = struct.unpack_from("<8I", d, i + 12)
    # version, total, exe_offset, exe_unc_size, exe_crc, header_offset, ...
    return {"table_at": i, "version": f[0], "total": f[1], "exe_offset": f[2],
            "exe_size": f[3], "exe_crc": f[4], "header_offset": f[5]}


def read_block(d: bytes, off: int) -> bytes:
    """Блок Inno 5.x: crc32, stored size, flag; далі шматки по 4096 + crc32."""
    _crc, size, comp = struct.unpack_from("<IIB", d, off)
    p, rem, out = off + 9, size, bytearray()
    while rem > 0:
        n = min(rem, 4100)
        out += d[p + 4:p + n]
        p += n
        rem -= n
    out = bytes(out)
    if not comp:
        return out
    props = out[0]
    lc, lp, pb = props % 9, (props // 9) % 5, props // 45
    dic = struct.unpack_from("<I", out, 1)[0]
    flt = [{"id": lzma.FILTER_LZMA1, "lc": lc, "lp": lp, "pb": pb, "dict_size": dic}]
    return lzma.LZMADecompressor(lzma.FORMAT_RAW, filters=flt).decompress(out[5:])


def load_header(path: str):
    d = open(path, "rb").read()
    t = read_loader_table(d)
    off = t["header_offset"]
    setup_id = d[off:off + SETUP_ID_LEN].split(b"\0")[0].decode("ascii", "replace")
    hdr = read_block(d, off + SETUP_ID_LEN)
    return setup_id, hdr


def strings_utf16(b: bytes):
    return re.findall(r"[\x20-\x7eЀ-ӿ]{4,}", b.decode("utf-16le", "ignore"))


def strings_ansi(b: bytes):
    return [m.decode("latin1") for m in re.findall(rb"[\x20-\x7e]{4,}", b)]


UNARC_RE = re.compile(r'x\s+-o\+.*?-p"(?P<pwd>[^"]*)".*?"\{src\}\\(?P<arc>[^"]+)"')
TOOLS = ("srep", "precomp", "packjpg", "xtool", "lzma", "unrar", "7z", "zstd", "reflate")


def scan(setup_id: str, hdr: bytes) -> dict:
    u = strings_utf16(hdr)
    a = strings_ansi(hdr)
    title = next((s for s in u if "RePack" in s or "Setup" in s), u[0] if u else "")
    helpers = sorted({s for s in u if s.startswith("{tmp}\\") and s.lower().endswith((".dll", ".exe", ".ini", ".inf"))})
    app_files = sorted({s for s in u if s.startswith("{app}\\") and s.lower().endswith((".exe", ".dll"))})
    archives = []
    for s in a:
        m = UNARC_RE.search(s)
        if m:
            archives.append({"file": m["arc"], "password_len": len(m["pwd"])})
    api = sorted({s for s in a if re.fullmatch(r"IS[A-Za-z]+Extract|ISDone[A-Za-z]*|SrepInit|PrecompInit", s)})
    tools = sorted({t for s in a for t in TOOLS if t in s.lower()})
    return {"inno": setup_id, "title": title, "helpers": helpers, "app_files": app_files,
            "archives": archives, "isdone_api": api, "tools_mentioned": tools}


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    sid, hdr = load_header(sys.argv[1])
    info = scan(sid, hdr)
    print(f"Inno: {info['inno']}\nГра: {info['title']}")
    print("\nДопоміжні файли інсталятора:")
    for h in info["helpers"]:
        print("  ", h)
    print("\nАрхіви FreeArc (у порядку розпаковки):")
    for x in info["archives"]:
        print(f"   {x['file']}  (пароль: {'*' * min(x['password_len'], 8)}, {x['password_len']} симв.)")
    print("\nAPI ISDone:", ", ".join(info["isdone_api"]))
    print("Згадані компресори:", ", ".join(info["tools_mentioned"]))
