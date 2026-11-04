#!/usr/bin/env python3
"""重写内核模块的 .modinfo：对齐 vermagic 并写入 onuce_* 云端契约字段。

与 App 内 RuntimeExtractor.patchVermagic 完全同一算法：
把重建后的 .modinfo 追加到文件末尾，改写该 section 头的 sh_offset / sh_size，
其余 section 原样保留，保证 ELF 结构依然可被 onuce 与 App 侧解析。

用法：
  ko_stamp.py IN.ko OUT.ko --release 6.18.21-android17-5-gf957...-4k \
      --set onuce_schema=2 --set onuce_key=finasu ... [--drop 前缀]...
"""
import argparse
import struct
import sys


def parse_sections(data):
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise SystemExit("不是 ELF64 小端文件")
    shoff, = struct.unpack_from("<Q", data, 0x28)
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    if shoff == 0 or shentsize == 0 or shnum == 0 or shstrndx >= shnum:
        raise SystemExit("section header 无效")
    str_hdr = shoff + shstrndx * shentsize
    str_off, = struct.unpack_from("<Q", data, str_hdr + 0x18)
    str_size, = struct.unpack_from("<Q", data, str_hdr + 0x20)
    sections = []
    for i in range(shnum):
        base = shoff + i * shentsize
        name_off, = struct.unpack_from("<I", data, base)
        end = str_off + name_off
        while data[end] != 0:
            end += 1
        name = data[str_off + name_off:end].decode("latin-1")
        offset, size = struct.unpack_from("<QQ", data, base + 0x18)
        sections.append({"index": i, "base": base, "name": name, "offset": offset, "size": size})
    return sections, str_off, str_size


def split_modinfo(blob):
    parts = []
    start = 0
    while start < len(blob):
        end = start
        while end < len(blob) and blob[end] != 0:
            end += 1
        if end > start:
            parts.append(blob[start:end].decode("latin-1"))
        start = end + 1
    return parts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--release", default=None, help="目标内核 release，改写到 vermagic 前缀")
    ap.add_argument("--set", action="append", default=[], metavar="K=V", help="写入或覆盖的 modinfo 键")
    ap.add_argument("--drop", action="append", default=[], metavar="KEY", help="删除指定键")
    args = ap.parse_args()

    data = bytearray(open(args.src, "rb").read())
    sections, _, _ = parse_sections(bytes(data))
    mod = next((s for s in sections if s["name"] == ".modinfo"), None)
    if mod is None:
        raise SystemExit("模块缺少 .modinfo")
    old = bytes(data[mod["offset"]:mod["offset"] + mod["size"]])
    parts = split_modinfo(old)

    wanted = {}
    for item in args.set:
        if "=" not in item:
            raise SystemExit("--set 需要 K=V：" + item)
        k, v = item.split("=", 1)
        wanted[k] = v

    out = []
    seen = set()
    for part in parts:
        key = part.split("=", 1)[0]
        if key in args.drop:
            continue
        if key == "vermagic" and args.release:
            old_value = part.split("=", 1)[1]
            sp = old_value.find(" ")
            suffix = old_value[sp:] if sp >= 0 else ""
            out.append("vermagic=" + args.release + suffix)
            seen.add("vermagic")
            continue
        if key in wanted:
            out.append(key + "=" + wanted[key])
            seen.add(key)
            continue
        out.append(part)
    for key, value in wanted.items():
        if key not in seen:
            out.append(key + "=" + value)

    payload = b"".join(p.encode("latin-1") + b"\x00" for p in out) + b"\x00"
    new_off = len(data)
    data.extend(payload)
    # 旧的 .modinfo 内容原地清零：section 头已指向文件尾部的新副本，
    # 不清零会在文件里留下重复的 vermagic，按首个匹配解析的校验方会读到旧值。
    data[mod["offset"]:mod["offset"] + mod["size"]] = b"\x00" * mod["size"]
    struct.pack_into("<QQ", data, mod["base"] + 0x18, new_off, len(payload))
    open(args.dst, "wb").write(bytes(data))
    print("modinfo: %d -> %d B, 文件 %d -> %d B" % (len(old), len(payload), new_off, len(data)))
    for part in out:
        if part.startswith(("vermagic=", "onuce_")):
            print("  " + part)


if __name__ == "__main__":
    sys.exit(main())
