#!/usr/bin/env python3
"""
MLBB Skin Generator & AssetBundle Remapper
Extracts genuine premium skins from MLBB directory on device,
patches the internal Unity AssetBundle m_Container to bind BOTH
Default Skin (_skin_high_add) and Basic Skin (_skin01_high_add),
and stages them for Shizuku injection.
"""

import os
import subprocess
import tempfile
import UnityPy

DEVICE_DATA = "/sdcard/Android/data/com.mobile.legends/files/dragon2017/assets"
DEST_DIR = "/sdcard/Download/WillyLibTool/skins"

HERO_SWAP_CONFIG = [
    {
        "id": "fanny",
        "name": "Fanny",
        "num_id": "17",
        "prefix": "hero_fanny",
        "source_skin": "skin12",     # Mikasa Ackerman (AoT)
        "ui_card": "Hero101712_add.unity3d",
        "ui_target": "Hero171_add.unity3d",
        "ui_prefab_target": "hero171_add.prefab"
    },
    {
        "id": "gusion",
        "name": "Gusin",
        "num_id": "56",
        "prefix": "hero_Gusin",
        "source_skin": "skin06",     # K' (KOF)
        "ui_card": "Hero656_add.unity3d",
        "ui_target": "Hero056_add.unity3d",
        "ui_prefab_target": "hero056_add.prefab"
    },
    {
        "id": "ling",
        "name": "pofa",
        "num_id": "84",
        "prefix": "hero_pofa",
        "source_skin": "skin05",     # Seraphic Scourge (Collector)
        "ui_card": "Hero584_add.unity3d",
        "ui_target": "Hero084_add.unity3d",
        "ui_prefab_target": "hero084_add.prefab"
    },
    {
        "id": "nolan",
        "name": "NolanOsborne",
        "num_id": "122",
        "prefix": "hero_NolanOsborne",
        "source_skin": "skin03",     # The Navigator
        "ui_card": "Hero3122_add.unity3d",
        "ui_target": "Hero1221_add.unity3d",
        "ui_prefab_target": "hero1221_add.prefab"
    },
    {
        "id": "miya",
        "name": "gongjianshou",
        "num_id": "1",
        "prefix": "hero_gongjianshou",
        "source_skin": "skin14",     # Doom Catalyst (Collector)
        "ui_card": "Hero1401_add.unity3d",
        "ui_target": "Hero1001_add.unity3d",
        "ui_prefab_target": "hero1001_add.prefab"
    },
    {
        "id": "claude",
        "name": "claude",
        "num_id": "65",
        "prefix": "hero_claude",
        "source_skin": "skin08",     # Bad Bro (Sanrio)
        "ui_card": "Hero865_add.unity3d",
        "ui_target": "Hero065_add.unity3d",
        "ui_prefab_target": "hero065_add.prefab"
    },
    {
        "id": "karrie",
        "name": "feilun",
        "num_id": "40",
        "prefix": "hero_feilun",
        "source_skin": "skin11",     # Starstruck (M5 Champion)
        "ui_card": "Hero1140_add.unity3d",
        "ui_target": "Hero401_add.unity3d",
        "ui_prefab_target": "hero401_add.prefab"
    },
    {
        "id": "chou",
        "name": "Chou",
        "num_id": "26",
        "prefix": "hero_chou",
        "source_skin": "skin09",     # Iori Yagami (KOF) / Thunderfist
        "ui_card": "Hero926_add.unity3d",
        "ui_target": "Hero026_add.unity3d",
        "ui_prefab_target": "hero026_add.prefab"
    },
    {
        "id": "hayabusa",
        "name": "Hayabusa",
        "num_id": "21",
        "prefix": "hero_haya",
        "source_skin": "skin05",     # Shadow of Obscurity (Epic/Collector)
        "ui_card": "Hero521_add.unity3d",
        "ui_target": "Hero021_add.unity3d",
        "ui_prefab_target": "hero021_add.prefab"
    },
    {
        "id": "lancelot",
        "name": "Lancelot",
        "num_id": "47",
        "prefix": "hero_lance",
        "source_skin": "skin06",     # Bren Esports (M2 Champion)
        "ui_card": "Hero647_add.unity3d",
        "ui_target": "Hero047_add.unity3d",
        "ui_prefab_target": "hero047_add.prefab"
    },
    {
        "id": "granger",
        "name": "Granger",
        "num_id": "79",
        "prefix": "hero_granger",
        "source_skin": "skin05",     # Starfall Knight (Legend)
        "ui_card": "Hero579_add.unity3d",
        "ui_target": "Hero079_add.unity3d",
        "ui_prefab_target": "hero079_add.prefab"
    },
    {
        "id": "benedetta",
        "name": "Benedetta",
        "num_id": "97",
        "prefix": "hero_benedetta",
        "source_skin": "skin03",     # Death Oath (Collector)
        "ui_card": "Hero397_add.unity3d",
        "ui_target": "Hero097_add.unity3d",
        "ui_prefab_target": "hero097_add.prefab"
    }
]

def adb_run(cmd):
    return subprocess.run(["adb", "shell", cmd], capture_output=True, text=True)

def adb_pull(remote, local):
    return subprocess.run(["adb", "pull", remote, local], capture_output=True, text=True)

def adb_push(local, remote):
    return subprocess.run(["adb", "push", local, remote], capture_output=True, text=True)

def patch_3d_bundle(src_local, dst_local, src_skin_tag, hero_cfg):
    """
    Opens AssetBundle, finds m_Container entries with src_skin_tag,
    and replaces them with BOTH default (_skin_) and basic (_skin01_).
    """
    env = UnityPy.load(src_local)
    for obj in env.objects:
        if obj.type.name == "AssetBundle":
            d = obj.read()
            new_container = []
            for path_str, asset_info in d.m_Container:
                # 1. Default skin mapping (replace e.g. skin12 with skin)
                path_default = path_str.replace(src_skin_tag, "skin")
                # Handle double underscore if skin tag was e.g. skin12_high -> skin_high
                new_container.append((path_default, asset_info))
                
                # 2. Skin01 basic skin mapping
                path_skin01 = path_str.replace(src_skin_tag, "skin01")
                if path_skin01 != path_default:
                    new_container.append((path_skin01, asset_info))
                
                # Also retain original tag
                new_container.append((path_str, asset_info))

            d.m_Container = new_container
            d.m_AssetBundleName = d.m_AssetBundleName.replace(src_skin_tag, "skin")
            d.save()

    with open(dst_local, "wb") as f:
        f.write(env.file.save(packer="lz4"))

def patch_ui_bundle(src_local, dst_local, target_prefab_name, target_bundle_name):
    """
    Patches UI card AssetBundle container to match the default hero card prefab.
    """
    env = UnityPy.load(src_local)
    for obj in env.objects:
        if obj.type.name == "AssetBundle":
            d = obj.read()
            new_container = []
            for path_str, asset_info in d.m_Container:
                # Replace the filename at the end of the prefab container path
                dir_path = path_str.rsplit("/", 1)[0]
                new_path = f"{dir_path}/{target_prefab_name}"
                new_container.append((new_path, asset_info))
                # Also keep original for safety
                new_container.append((path_str, asset_info))
            
            d.m_Container = new_container
            d.m_AssetBundleName = f"assets/res_version/android/ui/android/{target_bundle_name}"
            d.save()

    with open(dst_local, "wb") as f:
        f.write(env.file.save(packer="lz4"))

def main():
    print("=== Mobile Legends: Bang Bang Skin Generator & Container Remapper ===")
    os.makedirs("/tmp/mlbb_patch", exist_ok=True)
    adb_run(f"mkdir -p {DEST_DIR}")

    for cfg in HERO_SWAP_CONFIG:
        h_id = cfg["id"]
        prefix = cfg["prefix"]
        skin_tag = cfg["source_skin"]
        print(f"\nProcessing {cfg['name']} ({skin_tag})...")

        # 1. 3D High Quality Model Bundle
        remote_high = f"{DEVICE_DATA}/Art/android/h/{prefix}_{skin_tag}_high_add.unity3d"
        local_raw = f"/tmp/mlbb_patch/{prefix}_{skin_tag}_high_raw.unity3d"
        local_patched_high = f"/tmp/mlbb_patch/{prefix}_skin_high_add.unity3d"
        local_patched_skin01 = f"/tmp/mlbb_patch/{prefix}_skin01_high_add.unity3d"

        print(f"  Pulling {remote_high}...")
        pull_res = adb_pull(remote_high, local_raw)
        if pull_res.returncode == 0 and os.path.exists(local_raw) and os.path.getsize(local_raw) > 0:
            print("  Patching container mappings...")
            patch_3d_bundle(local_raw, local_patched_high, skin_tag, cfg)
            
            # Push as BOTH _skin_high_add.unity3d AND _skin01_high_add.unity3d
            print(f"  Pushing to {DEST_DIR}/{prefix}_skin_high_add.unity3d...")
            adb_push(local_patched_high, f"{DEST_DIR}/{prefix}_skin_high_add.unity3d")
            print(f"  Pushing to {DEST_DIR}/{prefix}_skin01_high_add.unity3d...")
            adb_push(local_patched_high, f"{DEST_DIR}/{prefix}_skin01_high_add.unity3d")
            # Low / base model fallback
            adb_push(local_patched_high, f"{DEST_DIR}/{prefix}_skin.unity3d")
        else:
            print(f"  WARNING: Could not pull {remote_high}!")

        # 2. Shadow Bundle
        remote_shadow = f"{DEVICE_DATA}/Art/android/h/{prefix}_{skin_tag}_shadow_high_add.unity3d"
        local_shadow_raw = f"/tmp/mlbb_patch/{prefix}_{skin_tag}_shadow_raw.unity3d"
        local_shadow_patched = f"/tmp/mlbb_patch/{prefix}_skin_shadow_high_add.unity3d"
        pull_shadow = adb_pull(remote_shadow, local_shadow_raw)
        if pull_shadow.returncode == 0 and os.path.exists(local_shadow_raw) and os.path.getsize(local_shadow_raw) > 0:
            patch_3d_bundle(local_shadow_raw, local_shadow_patched, skin_tag, cfg)
            adb_push(local_shadow_patched, f"{DEST_DIR}/{prefix}_skin_shadow_high_add.unity3d")
            adb_push(local_shadow_patched, f"{DEST_DIR}/{prefix}_skin01_shadow_high_add.unity3d")

        # 3. UI Portrait Card
        remote_ui = f"{DEVICE_DATA}/UI/android/{cfg['ui_card']}"
        local_ui_raw = f"/tmp/mlbb_patch/{cfg['ui_card']}"
        local_ui_patched = f"/tmp/mlbb_patch/{cfg['ui_target']}"
        pull_ui = adb_pull(remote_ui, local_ui_raw)
        if pull_ui.returncode == 0 and os.path.exists(local_ui_raw) and os.path.getsize(local_ui_raw) > 0:
            patch_ui_bundle(local_ui_raw, local_ui_patched, cfg["ui_prefab_target"], cfg["ui_target"])
            adb_push(local_ui_patched, f"{DEST_DIR}/{cfg['ui_target']}")
            print(f"  Pushed UI Card {cfg['ui_target']}")

    print("\n All 12 hero skins generated with dual-container mapping and staged on device!")

if __name__ == "__main__":
    main()
