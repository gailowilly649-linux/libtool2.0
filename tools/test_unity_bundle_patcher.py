#!/usr/bin/env python3
import os
import struct
import subprocess
import tempfile
import lz4.block

def unpack_lz4_unityfs(path):
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
    ver = struct.unpack(">I", data[pos:pos+4])[0]; pos += 4
    u_ver = read_cstr()
    r_ver = read_cstr()
    size, c_info, u_info, flags = struct.unpack(">QIII", data[pos:pos+20]); pos += 20
    if ver >= 7:
        pos = (pos + 15) & ~15
    assert flags == 0x43, f"Expected flags 0x43, got 0x{flags:x}"
    assert size == len(data), f"Header size {size} != actual {len(data)}"
    b_info = lz4.block.decompress(data[pos:pos+c_info], uncompressed_size=u_info)
    b_pos = 16
    num_blocks = struct.unpack(">I", b_info[b_pos:b_pos+4])[0]; b_pos += 4
    cur = pos + c_info
    out = bytearray()
    for _ in range(num_blocks):
        u_sz, c_sz, b_flags = struct.unpack(">IIH", b_info[b_pos:b_pos+10]); b_pos += 10
        chunk = data[cur:cur+c_sz]; cur += c_sz
        if (b_flags & 0x3f) in (2, 3):
            out.extend(lz4.block.decompress(chunk, uncompressed_size=u_sz))
        else:
            out.extend(chunk)
    return bytes(out)

def main():
    patcher_path = "app/src/main/java/com/android/support/shizuku/UnityBundlePatcher.java"
    assert os.path.exists(patcher_path), f"Missing {patcher_path}"

    classes_dir = "/tmp/unity_patcher_classes"
    os.makedirs(classes_dir, exist_ok=True)
    # Create minimal android.util.Log stub for host JVM execution
    log_stub = "/tmp/unity_patcher_classes/Log.java"
    with open(log_stub, "w") as f:
        f.write("package android.util; public class Log { public static int w(String t, String m) { System.err.println(m); return 0; } }\n")

    res = subprocess.run(["javac", "-d", classes_dir, log_stub, patcher_path], capture_output=True, text=True)
    assert res.returncode == 0, f"javac failed:\n{res.stderr}\n{res.stdout}"
    src_bundle = "app/src/main/assets/mlbb_drone/base/Art/android/nextnextninja_skill03_fire_skin07_add.unity3d"
    with tempfile.NamedTemporaryFile(suffix="_skin_add.unity3d", delete=False) as tmp:
        dst_bundle = tmp.name

    try:
        run_res = subprocess.run(
            ["java", "-cp", classes_dir, "com.android.support.shizuku.UnityBundlePatcher",
             src_bundle, dst_bundle, "nextnextninja_skill03_fire_skin_add.unity3d"],
            capture_output=True, text=True
        )
        assert run_res.returncode == 0, f"Java patcher failed:\n{run_res.stderr}\n{run_res.stdout}"
        unpacked = unpack_lz4_unityfs(dst_bundle)
        assert b"nextnextninja_skill03_fire_skin_add.prefab" in unpacked, "Missing _skin_add.prefab in container"
        assert b"nextnextninja_skill03_fire_skin01_add.prefab" in unpacked, "Missing _skin01_add.prefab in container"
        assert b"nextnextninja_skill03_fire_skin07_add.prefab" in unpacked, "Missing original _skin07_add.prefab in container"
        print("PASS: UnityBundlePatcher compiled, decoded LZMA bundle, remapped container keys, and repacked LZ4 0x43 bundle!")
    finally:
        if os.path.exists(dst_bundle):
            os.remove(dst_bundle)

if __name__ == "__main__":
    main()
