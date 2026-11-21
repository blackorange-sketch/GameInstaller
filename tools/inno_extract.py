#!/usr/bin/env python3
"""Прототип: розбір записів файлів Inno Setup 5.5.0 (unicode) і розпаковка Setup-1a.bin.
Формат за innoextract (zlib/libpng-ліцензія, Daniel Scharrer)."""
import hashlib, lzma, os, struct, sys, zlib

sys.path.insert(0, os.path.dirname(__file__))
from inno_probe import read_loader_table, read_block, SETUP_ID_LEN


class R:
    def __init__(self, b): self.b, self.p = b, 0
    def u8(self): v = self.b[self.p]; self.p += 1; return v
    def u16(self): v = struct.unpack_from("<H", self.b, self.p)[0]; self.p += 2; return v
    def i16(self): v = struct.unpack_from("<h", self.b, self.p)[0]; self.p += 2; return v
    def u32(self): v = struct.unpack_from("<I", self.b, self.p)[0]; self.p += 4; return v
    def i32(self): v = struct.unpack_from("<i", self.b, self.p)[0]; self.p += 4; return v
    def u64(self): v = struct.unpack_from("<Q", self.b, self.p)[0]; self.p += 8; return v
    def raw(self, n): v = self.b[self.p:self.p + n]; self.p += n; return v
    def s(self):  # u32 довжина в байтах + дані
        n = self.u32(); return self.raw(n)
    def us(self): return self.s().decode("utf-16le", "replace")
    def skip(self, n): self.p += n


def winver(r): r.skip(20)  # діапазон версій Windows: min + only-below, по 10 байт


def flags(r, nbits):
    n = (nbits + 7) // 8
    if n == 3: n = 4
    return int.from_bytes(r.raw(n), "little")


def parse_header(b1):
    r = R(b1)
    names = ["app_name", "app_versioned_name", "app_id", "app_copyright", "app_publisher", "app_publisher_url",
             "app_support_phone", "app_support_url", "app_updates_url", "app_version", "default_dir_name",
             "default_group_name", "base_filename", "uninstall_files_dir", "uninstall_name", "uninstall_icon",
             "app_mutex", "default_user_name", "default_user_organisation", "default_serial",
             "app_readme_file", "app_contact", "app_comments", "app_modify_path",
             "create_uninstall_registry_key", "uninstallable", "close_applications_filter",
             "license_text", "info_before", "info_after", "compiled_code"]
    h = {}
    for n in names:
        h[n] = r.s() if n in ("license_text", "info_before", "info_after", "compiled_code") else r.us()
    cnt = {}
    for n in ("language", "message", "permission", "type", "component", "task"):
        cnt[n] = r.u32()
    for n in ("directory", "file", "data", "icon", "ini", "registry", "delete", "uninstall_delete", "run", "uninstall_run"):
        cnt[n] = r.u32()
    winver(r)                            # winver (10 байт)
    r.u32(); r.u32(); r.u32()            # back_color, back_color2, image_back_color
    r.skip(20)                           # password sha1
    r.skip(8)                            # password salt
    r.skip(8)                            # extra_disk_space_required
    h["slices_per_disk"] = r.u32()
    r.skip(1)                            # uninstall_log_mode
    r.skip(1)                            # dir_exists_warning
    r.skip(1)                            # privileges_required
    r.skip(2)                            # show_language_dialog, language_detection
    h["compression"] = r.u8()            # 0 stored, 1 zlib, 2 bzip2, 3 lzma1, 4 lzma2
    r.skip(2)                            # architectures_allowed, installed_in_64bit
    r.skip(2)                            # disable_dir_page, disable_program_group_page
    r.skip(8)                            # uninstall_display_size
    h["flags"] = flags(r, 49)  # у цій збірці 7 байт
    return r, h, cnt


def parse_entries(b1):
    r, h, cnt = parse_header(b1)
    for _ in range(cnt["language"]):
        for _ in range(10): r.s()
        r.u32(); r.skip(16); r.skip(1)   # language_id, 4 font sizes, right_to_left
    for _ in range(cnt["message"]):
        r.s(); r.s(); r.i32()
    for _ in range(cnt["permission"]): r.s()
    for _ in range(cnt["type"]):
        for _ in range(4): r.s()
        winver(r); r.skip(1); r.skip(1); r.u64()
    for _ in range(cnt["component"]):
        for _ in range(5): r.s()
        r.u64(); r.i32(); r.skip(1); winver(r); r.skip(1); r.u64()
    for _ in range(cnt["task"]):
        for _ in range(6): r.s()
        r.i32(); r.skip(1); winver(r); r.skip(1)
    for _ in range(cnt["directory"]):
        for _ in range(7): r.s()
        r.u32(); winver(r); r.i16(); r.skip(1)
    files = []
    for _ in range(cnt["file"]):
        src, dst, font, strong = r.us(), r.us(), r.us(), r.us()
        for _ in range(6): r.s()
        winver(r)
        loc = r.u32(); r.u32(); ext = r.u64(); r.i16(); fl = flags(r, 32); typ = r.u8()
        files.append({"source": src, "dest": dst, "location": loc, "external_size": ext, "flags": fl, "type": typ})
    return h, cnt, files


def parse_data_entries(b2, count):
    r = R(b2)
    out = []
    for _ in range(count):
        first, last = r.u32(), r.u32()
        offset = r.u32(); foff = r.u64(); fsize = r.u64(); csize = r.u64()
        sha1 = r.raw(20); r.skip(8); r.u32(); r.u32()
        fl = flags(r, 9)
        out.append({"slice": first, "last": last, "offset": offset, "foffset": foff, "size": fsize,
                    "csize": csize, "sha1": sha1, "flags": fl})
    return out


if __name__ == "__main__":
    exe = open(sys.argv[1], "rb").read()
    t = read_loader_table(exe)
    off = t["header_offset"] + SETUP_ID_LEN
    b1 = read_block(exe, off)
    h, cnt, files = parse_entries(b1)
    print({k: v for k, v in h.items() if k in ("app_name", "base_filename", "slices_per_disk", "compression", "flags")})
    print(cnt)
    d = parse_data_entries(read_block(exe, off + 9 + struct.unpack_from("<I", exe, off + 4)[0]), cnt["data"])
    for f in files:
        if f["dest"].startswith("{tmp}"): continue
        e = d[f["location"]] if f["location"] < len(d) else None
        print(f["dest"], f["location"], f["type"], {k: e[k] for k in ("slice","offset","foffset","size","csize","flags")} if e else None)
    print(len(d), "data;", sum(1 for e in d if e["flags"]&0x40), "enc")


# ---------------------------------------------------------------- розпаковка

def exe_filter_5309(data: bytes) -> bytes:
    out = bytearray()
    n, i, offset = len(data), 0, 0
    while i < n:
        b = data[i]; i += 1; offset += 1
        out.append(b)
        if b != 0xE8 and b != 0xE9:
            continue
        if 0x10000 - ((offset - 1) % 0x10000) < 5:
            continue
        buf = bytearray(data[i:i + 4]); i += len(buf)
        if len(buf) < 4:
            out += buf; break
        offset += 4
        if buf[3] in (0x00, 0xFF):
            addr = offset & 0xFFFFFF
            rel = (buf[0] | buf[1] << 8 | buf[2] << 16)
            rel = (rel - addr) & 0xFFFFFFFF
            buf[0], buf[1], buf[2] = rel & 255, (rel >> 8) & 255, (rel >> 16) & 255
            if rel & 0x800000:
                buf[3] = (~buf[3]) & 255
        out += buf
    return bytes(out)


def slice_name(base: str, first_slice: int, spd: int) -> str:
    if spd == 1:
        return f"{base}-{first_slice + 1}.bin"
    return f"{base}-{first_slice // spd + 1}{chr(ord('a') + first_slice % spd)}.bin"


def read_chunk(src_dir: str, base: str, spd: int, comp: int, e: dict) -> bytes:
    """Повертає розпакований вміст чанка (потік файлів, що йдуть підряд)."""
    path = os.path.join(src_dir, slice_name(base, e["slice"], spd))
    with open(path, "rb") as f:
        head = f.read(12)
        assert head[:7] == b"idska32", head
        f.seek(e["offset"])  # зсув уже враховує 12-байтовий заголовок слайса
        assert f.read(4) == b"zlb\x1a", "bad chunk magic"
        raw = f.read(e["csize"])
    if not (e["flags"] & 0x80):          # ChunkCompressed
        return raw
    if comp == 1:                        # zlib
        return zlib.decompress(raw)
    if comp == 3:                        # LZMA1: props(1) + dict(4) + потік
        props = raw[0]
        lc, lp, pb = props % 9, (props // 9) % 5, props // 45
        dic = struct.unpack_from("<I", raw, 1)[0]
        flt = [{"id": lzma.FILTER_LZMA1, "lc": lc, "lp": lp, "pb": pb, "dict_size": dic}]
        return lzma.LZMADecompressor(lzma.FORMAT_RAW, filters=flt).decompress(raw[5:])
    if comp == 4:                        # LZMA2: 1 байт розміру словника + потік
        b = raw[0]
        dic = 0xFFFFFFFF if b == 40 else (2 | (b & 1)) << (b // 2 + 11)
        flt = [{"id": lzma.FILTER_LZMA2, "dict_size": dic}]
        return lzma.LZMADecompressor(lzma.FORMAT_RAW, filters=flt).decompress(raw[1:])
    raise NotImplementedError(f"compression {comp}")


def extract_file(src_dir, h, e) -> bytes:
    chunk = read_chunk(src_dir, h["base_filename"] or "Setup", h["slices_per_disk"], h["compression"], e)
    data = chunk[e["foffset"]:e["foffset"] + e["size"]]
    if e["flags"] & 0x10:                # CallInstructionOptimized
        data = exe_filter_5309(data)
    assert hashlib.sha1(b"\x00" * 0 + data).digest() == e["sha1"], "SHA-1 mismatch"
    return data
