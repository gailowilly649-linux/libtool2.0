#!/usr/bin/env python3
"""
Builds and verifies MLBB 2.2.16.12322 GameResAlternative.unity3d with:
1. Genuine UnityFS v7 header flags = 0x43 (BlocksInfo at start, 16-byte aligned).
2. Rewritten 4-byte little-endian SkinID inside every mulonggame_table_1 row for Default (<heroId>1)
   and Basic (1<heroId>1 / <heroId>2) skins so GetGameResAlternativeElement(resName, skinId)
   succeeds at runtime and loads skill VFX, SFX, and circular skill icons (Atlas_SkillIcon*).
"""

import os
import re
import struct
import sys
import lz4.block

STOCK_PATH = "New patch update Mobile Legends_ Bang Bang_2.2.16.12322/dump_mlbb/Document/android/GameResAlternative.unity3d"
OUT_PATH = "app/src/main/assets/mlbb_drone/base/Document/android/GameResAlternative.unity3d"
CHUNK_SIZE = 131072

# Mapping of (source premium GameResAlternative key) -> list of target Default & Basic SkinIDs (int)
HERO_SKIN_TABLE_REDIRECTS = [
    # Fanny (Hero 17): Aspirant (101708) / Mikasa (101712) -> Default (171), Basic (1171, 172)
    ("gameresalternative101712.bytes", [171, 1171, 172]),
    # Ling (Hero 84): Collector (584 / 1845) -> Default (841), Basic (1841, 842)
    ("gameresalternative1845.bytes", [841, 1841, 842]),
    # Gusion (Hero 56): 11.11 / KOF (1566 / 1567) -> Default (561), Basic (1561, 562)
    ("gameresalternative1566.bytes", [561, 1561, 562]),
    # Hayabusa (Hero 21): Shura 11.11 / Shadow (1215 / 1217) -> Default (211), Basic (1211, 212)
    ("gameresalternative1215.bytes", [211, 1211, 212]),
    # Lancelot (Hero 47): Bren / Swordmaster (1474 / 1476) -> Default (471), Basic (1471, 472)
    ("gameresalternative1476.bytes", [471, 1471, 472]),
    # Nolan (Hero 122): The Navigator (2223 / 1223) -> Default (1221), Basic (2221, 1222)
    ("gameresalternative2222.bytes", [1221, 2221, 1222]),
    # Aamon (Hero 109): Noble Crest / Collector (2093 / 2097) -> Default (1091), Basic (2091, 1092)
    ("gameresalternative2097.bytes", [1091, 2091, 1092]),
    # Saber (Hero 3): Codename Storm Legend (1034 / 1035) -> Default (31), Basic (1031, 32)
    ("gameresalternative1035.bytes", [31, 1031, 32]),
    # Julian (Hero 116): JJK / HxH (2163 / 2169) -> Default (1161), Basic (2161, 1162)
    ("gameresalternative2169.bytes", [1161, 2161, 1162]),
    # Kagura (Hero 25): Water Lily / Exorcist (1256 / 1258) -> Default (251), Basic (1251, 252)
    ("gameresalternative1258.bytes", [251, 1251, 252]),
    # Lunox (Hero 68): Eyes of Eternity / Legend (1686 / 1687) -> Default (681), Basic (1681, 682)
    ("gameresalternative1686.bytes", [681, 1681, 682]),
    # Chou (Hero 26): Iori Yagami KOF (1266 / 1269) -> Default (261), Basic (1261, 262)
    ("gameresalternative1266.bytes", [261, 1261, 262]),
    # Franco (Hero 10): King of Hell Legend (1107 / 1108) -> Default (101), Basic (1101, 102)
    ("gameresalternative1107.bytes", [101, 1101, 102]),
    # Claude (Hero 65): Blazing / Sanrio (1658 / 61065111) -> Default (651), Basic (1651, 652)
    ("gameresalternative1658.bytes", [651, 1651, 652]),
    # Miya (Hero 1): Collector / Atomic (1019 / 100114) -> Default (11, 10011), Basic (1011, 12)
    ("gameresalternative100114.bytes", [11, 1011, 10011]),
    # Karrie (Hero 40): Gill-Girl / M5 (1408 / 104011) -> Default (401), Basic (1401, 402)
    ("gameresalternative104011.bytes", [401, 1401, 402]),
]


def unpack_unityfs(path):
    with open(path, "rb") as f:
        data = f.read()
    pos = 0

    def read_cstr():
        nonlocal pos
        end = data.index(b"\x00", pos)
        s = data[pos:end].decode()
        pos = end + 1
        return s

    sig = read_cstr()
    ver = struct.unpack(">I", data[pos:pos + 4])[0]
    pos += 4
    u_ver = read_cstr()
    r_ver = read_cstr()
    size, c_info, u_info, flags = struct.unpack(">QIII", data[pos:pos + 20])
    pos += 20
    if ver >= 7:
        pos = (pos + 15) & ~15
    if flags & 0x80:
        b_info_raw = data[-c_info:]
        data_pos = pos
    else:
        b_info_raw = data[pos:pos + c_info]
        data_pos = pos + c_info
        if flags & 0x200:
            data_pos = (data_pos + 15) & ~15

    comp_type = flags & 0x3F
    b_info = lz4.block.decompress(b_info_raw, uncompressed_size=u_info) if comp_type in (2, 3) else b_info_raw
    b_pos = 16
    num_blocks = struct.unpack(">I", b_info[b_pos:b_pos + 4])[0]
    b_pos += 4
    out = bytearray()
    cur = data_pos
    for _ in range(num_blocks):
        u_sz, c_sz, b_flags = struct.unpack(">IIH", b_info[b_pos:b_pos + 10])
        b_pos += 10
        chunk = data[cur:cur + c_sz]
        cur += c_sz
        out.extend(lz4.block.decompress(chunk, uncompressed_size=u_sz) if (b_flags & 0x3F) in (2, 3) else chunk)

    num_nodes = struct.unpack(">I", b_info[b_pos:b_pos + 4])[0]
    b_pos += 4
    nodes = []
    for _ in range(num_nodes):
        n_off, n_sz, n_flags = struct.unpack(">QQI", b_info[b_pos:b_pos + 20])
        b_pos += 20
        end = b_info.index(b"\x00", b_pos)
        n_name = b_info[b_pos:end].decode()
        b_pos = end + 1
        nodes.append((n_off, n_sz, n_flags, n_name))

    return flags, u_ver, r_ver, nodes, bytes(out)


def rewrite_mulong_table_skin_id(script_bytes: bytes, new_skin_id: int) -> bytes:
    """
    Rewrites the 4-byte little-endian SkinID column in every row of a mulonggame_table_1 binary table.
    Header layout:
      [0] = 0x24 ('$') + "mulonggame_table_1\x00" + "0" (21 bytes total)
      [21..25] = uint32 row_count
      [25..29] = uint32 col_count (always 3: OldRes(str), SkinID(uint32), NewRes(str))
    """
    if not script_bytes.startswith(b"$mulonggame_table_1\x000"):
        return script_bytes
    pos = 21
    row_count, col_count = struct.unpack("<II", script_bytes[pos:pos + 8])
    pos += 8
    if col_count != 3:
        return script_bytes

    out = bytearray(script_bytes[:pos])
    new_id_bytes = struct.pack("<I", new_skin_id)
    for _ in range(row_count):
        if pos >= len(script_bytes):
            break
        old_len = script_bytes[pos]
        pos += 1
        old_str = script_bytes[pos:pos + old_len]
        pos += old_len
        # Skip original 4-byte skin_id and replace with new_id_bytes
        pos += 4
        new_len = script_bytes[pos]
        pos += 1
        new_str = script_bytes[pos:pos + new_len]
        pos += new_len

        out.append(old_len)
        out.extend(old_str)
        out.extend(new_id_bytes)
        out.append(new_len)
        out.extend(new_str)

    # Append trailing bytes (typically 4 zero bytes)
    out.extend(script_bytes[pos:])
    return bytes(out)


def pack_unityfs_0x43(u_ver: str, r_ver: str, node_name: str, cab_bytes: bytes) -> bytes:
    total_len = len(cab_bytes)
    num_blocks = (total_len + CHUNK_SIZE - 1) // CHUNK_SIZE
    comp_blocks = bytearray()
    block_meta = []
    for i in range(num_blocks):
        slice_b = cab_bytes[i * CHUNK_SIZE:min(total_len, (i + 1) * CHUNK_SIZE)]
        u_sz = len(slice_b)
        c_slice = lz4.block.compress(slice_b, mode="high_compression", store_size=False)
        if len(c_slice) < u_sz:
            block_meta.append((u_sz, len(c_slice), 3))
            comp_blocks.extend(c_slice)
        else:
            block_meta.append((u_sz, u_sz, 0))
            comp_blocks.extend(slice_b)

    b_info_u = bytearray(16)  # 16-byte hash
    b_info_u.extend(struct.pack(">I", len(block_meta)))
    for u_sz, c_sz, bf in block_meta:
        b_info_u.extend(struct.pack(">IIH", u_sz, c_sz, bf))
    b_info_u.extend(struct.pack(">I", 1))
    b_info_u.extend(struct.pack(">QQI", 0, total_len, 4))
    b_info_u.extend(node_name.encode() + b"\x00")

    b_info_c = lz4.block.compress(bytes(b_info_u), mode="high_compression", store_size=False)

    prefix = b"UnityFS\x00" + struct.pack(">I", 7) + u_ver.encode() + b"\x00" + r_ver.encode() + b"\x00"
    pre_len = len(prefix) + 20
    aligned_hdr = (pre_len + 15) & ~15
    file_size = aligned_hdr + len(b_info_c) + len(comp_blocks)

    out = bytearray(prefix)
    out.extend(struct.pack(">QIII", file_size, len(b_info_c), len(b_info_u), 0x43))
    while len(out) < aligned_hdr:
        out.append(0)
    out.extend(b_info_c)
    out.extend(comp_blocks)
    return bytes(out)


def build():
    flags, u_ver, r_ver, nodes, cab = unpack_unityfs(STOCK_PATH)
    node_name = nodes[0][3]
    meta_sz, file_sz, ver, data_off = struct.unpack(">IIII", cab[:16])

    # Find AssetBundle object (PathID = 1)
    ab_str = b"assets/streamingassets/document/android/gameresalternative.unity3d"
    ab_rel = cab.find(ab_str) - 4 - data_off
    ab_info_idx = cab.find(struct.pack("<qI", 1, ab_rel), 0, data_off)
    assert ab_info_idx > 0, "Could not locate PathID=1 ObjectInfo in GameResAlternative"
    _, ab_start, ab_size, ab_type = struct.unpack("<qIII", cab[ab_info_idx:ab_info_idx + 20])

    # Parse AssetBundle (PathID = 1)
    ab_bytes = cab[data_off + ab_start:data_off + ab_start + ab_size]
    pos = 0

    def read_aligned_str(buf):
        nonlocal pos
        ln = struct.unpack("<I", buf[pos:pos + 4])[0]
        pos += 4
        s = buf[pos:pos + ln].decode("utf-8")
        pos += (ln + 3) & ~3
        return s

    m_name = read_aligned_str(ab_bytes)
    preload_cnt = struct.unpack("<I", ab_bytes[pos:pos + 4])[0]
    pos += 4
    preload_raw = ab_bytes[pos:pos + preload_cnt * 12]
    pos += preload_cnt * 12

    cont_cnt = struct.unpack("<I", ab_bytes[pos:pos + 4])[0]
    pos += 4
    container = {}
    container_order = []
    for _ in range(cont_cnt):
        k = read_aligned_str(ab_bytes)
        info = ab_bytes[pos:pos + 20]
        pos += 20
        container[k] = info
        container_order.append(k)

    tail_ab = ab_bytes[pos:]

    # Also rewrite existing TextAsset tables in-place where target Default/Basic aliases point to them,
    # and add container entries for Default & Basic skins.
    new_cab = bytearray(cab)

    for src_suffix, target_skin_ids in HERO_SKIN_TABLE_REDIRECTS:
        src_key = f"assets/ab/{src_suffix}"
        if src_key not in container:
            continue
        info = container[src_key]
        preload_idx, preload_sz, file_id, path_id = struct.unpack("<iiIq", info)

        # Locate the source TextAsset in ObjectInfo table by path_id to also patch its Default SkinID row copy if needed
        obj_needle = struct.pack("<q", path_id)
        search_pos = 20
        found_text_asset = None
        while search_pos < data_off - 20:
            idx = new_cab.find(obj_needle, search_pos, data_off)
            if idx < 0:
                break
            _, t_start, t_size, t_type = struct.unpack("<qIII", new_cab[idx:idx + 20])
            if 0 <= t_start < len(new_cab) - data_off and 32 < t_size < 200000:
                found_text_asset = (idx, t_start, t_size, t_type)
                break
            search_pos = idx + 4

        if found_text_asset is not None:
            _, t_start, t_size, _ = found_text_asset
            t_obj = bytes(new_cab[data_off + t_start:data_off + t_start + t_size])
            tbl_pos = t_obj.find(b"$mulonggame_table_1\x000")
            if tbl_pos > 0:
                rewritten_tbl = rewrite_mulong_table_skin_id(t_obj[tbl_pos:], target_skin_ids[0])
                if len(rewritten_tbl) == len(t_obj[tbl_pos:]):
                    new_cab[data_off + t_start:data_off + t_start + t_size] = t_obj[:tbl_pos] + rewritten_tbl

        # Add container bindings for all target Default / Basic skin IDs
        for tid in target_skin_ids:
            t_key = f"assets/ab/gameresalternative{tid}.bytes"
            if t_key not in container:
                container[t_key] = info
                container_order.append(t_key)

    # Also rewrite Fanny's 101712 table or add Default SkinID=171 rows inside GameResAlternative0 (the global fallback table!)
    # Let's check gameresalternative0.bytes!
    zero_key = "assets/ab/gameresalternative0.bytes"
    if zero_key in container:
        _, _, _, zero_pid = struct.unpack("<iiIq", container[zero_key])
        z_idx = new_cab.find(struct.pack("<q", zero_pid), 20, data_off)
        if z_idx > 0:
            _, z_start, z_size, _ = struct.unpack("<qIII", new_cab[z_idx:z_idx + 20])
            # Locate Fanny 101712 TextAsset to copy its rows with SkinID=171
            f_info = container.get("assets/ab/gameresalternative101712.bytes")
            if f_info:
                _, _, _, f_pid = struct.unpack("<iiIq", f_info)
                f_idx = new_cab.find(struct.pack("<q", f_pid), 20, data_off)
                if f_idx > 0:
                    _, f_start, f_size, _ = struct.unpack("<qIII", new_cab[f_idx:f_idx + 20])
                    f_obj = bytes(new_cab[data_off + f_start:data_off + f_start + f_size])
                    # Extract mulonggame_table_1 from f_obj and rewrite skin_id to 171 in-place on a copy
                    tbl_pos = f_obj.find(b"$mulonggame_table_1\x000")
                    if tbl_pos > 0:
                        rewritten_tbl = rewrite_mulong_table_skin_id(f_obj[tbl_pos:], 171)
                        if len(rewritten_tbl) == len(f_obj[tbl_pos:]):
                            new_f_obj = f_obj[:tbl_pos] + rewritten_tbl
                            new_cab[data_off + f_start:data_off + f_start + f_size] = new_f_obj

    # Rebuild AssetBundle (PathID = 1)
    def write_aligned_str(out_b, s):
        b = s.encode("utf-8")
        out_b.extend(struct.pack("<I", len(b)))
        out_b.extend(b)
        pad = ((len(b) + 3) & ~3) - len(b)
        out_b.extend(b"\x00" * pad)

    rebuilt_ab = bytearray()
    write_aligned_str(rebuilt_ab, m_name)
    rebuilt_ab.extend(struct.pack("<I", preload_cnt))
    rebuilt_ab.extend(preload_raw)
    rebuilt_ab.extend(struct.pack("<I", len(container_order)))
    for k in container_order:
        write_aligned_str(rebuilt_ab, k)
        rebuilt_ab.extend(container[k])
    rebuilt_ab.extend(tail_ab)

    # Append rebuilt_ab at the end of new_cab (8-byte aligned)
    while len(new_cab) % 8 != 0:
        new_cab.append(0)
    new_ab_rel = len(new_cab) - data_off
    new_cab.extend(rebuilt_ab)

    # Update CAB fileSize and PathID=1 ObjectInfo
    struct.pack_into(">I", new_cab, 4, len(new_cab))
    struct.pack_into("<II", new_cab, ab_info_idx + 8, new_ab_rel, len(rebuilt_ab))

    packed = pack_unityfs_0x43(u_ver, r_ver, node_name, bytes(new_cab))
    with open(OUT_PATH, "wb") as f:
        f.write(packed)
    print(f"BUILT {OUT_PATH} ({len(packed)} bytes, flags=0x43)")


def verify():
    assert os.path.exists(OUT_PATH), f"Missing {OUT_PATH}"
    flags, u_ver, r_ver, nodes, unpacked = unpack_unityfs(OUT_PATH)
    assert flags == 0x43, f"Expected stock UnityFS flags 0x43, got 0x{flags:x}"
    assert b"assets/ab/gameresalternative171.bytes" in unpacked, "Missing gameresalternative171.bytes container entry"
    assert b"assets/ab/gameresalternative1171.bytes" in unpacked, "Missing gameresalternative1171.bytes container entry"
    assert b"assets/ab/gameresalternative561.bytes" in unpacked, "Missing gameresalternative561.bytes container entry"
    assert b"assets/ab/gameresalternative841.bytes" in unpacked, "Missing gameresalternative841.bytes container entry"
    assert b"fanny_skill01\xab\x00\x00\x00" in unpacked, "Missing rewritten SkinID=171 (0xab) record in GameResAlternative"
    print("PASS: GameResAlternative.unity3d verified with flags=0x43 and valid SkinID rows")


if __name__ == "__main__":
    if "--verify" in sys.argv:
        verify()
    else:
        build()
        verify()
