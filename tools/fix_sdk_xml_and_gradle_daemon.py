#!/usr/bin/env python3
"""
Sanitizes unused SDK XML v4/v5 namespace declarations in /usr/lib/android-sdk/**/package.xml
so that Android sdklib (all AGP versions) never emits:
  'Warning: This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered.'
Also aligns ~/.gradle/gradle.properties with the project's JDK 21 + JVM args configuration.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

SDK_ROOT = "/usr/lib/android-sdk"
UNUSED_SCHEMA_PATTERNS = [
    r'\s+xmlns:ns\d+="http://schemas\.android\.com/sdk/android/repo/repository2/04"',
    r'\s+xmlns:ns\d+="http://schemas\.android\.com/sdk/android/repo/addon2/04"',
    r'\s+xmlns:ns\d+="http://schemas\.android\.com/sdk/android/repo/sys-img2/04"',
    r'\s+xmlns:ns\d+="http://schemas\.android\.com/sdk/android/repo/sys-img2/05"',
]


def process_sdk_package_xmls(dry_run=False):
    paths = sorted(
        set(
            glob.glob(os.path.join(SDK_ROOT, "*/package.xml"))
            + glob.glob(os.path.join(SDK_ROOT, "*/*/package.xml"))
            + glob.glob(os.path.join(SDK_ROOT, "*/*/*/package.xml"))
        )
    )
    patched = 0
    for path in paths:
        if os.path.islink(path):
            continue
        with open(path, "r", encoding="utf-8") as f:
            orig = f.read()
        updated = orig
        for pat in UNUSED_SCHEMA_PATTERNS:
            updated = re.sub(pat, "", updated)
        if updated != orig:
            # Verify XML is completely valid and well-formed before writing
            ET.fromstring(updated)
            patched += 1
            print(f"{'[DRY-RUN] Would patch' if dry_run else 'Patched'}: {path}")
            if not dry_run:
                with open(path, "w", encoding="utf-8") as f:
                    f.write(updated)
    print(f"Total SDK package.xml files {'to patch' if dry_run else 'patched'}: {patched}")
    return patched


def align_global_gradle_properties(dry_run=False):
    gradle_home = os.path.expanduser("~/.gradle")
    prop_path = os.path.join(gradle_home, "gradle.properties")
    content = (
        "# Global Gradle daemon alignment\n"
        "org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8\n"
        "org.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64\n"
        "org.gradle.daemon=true\n"
        "org.gradle.parallel=true\n"
        "org.gradle.caching=true\n"
        "org.gradle.vfs.watch=true\n"
    )
    if dry_run:
        print(f"[DRY-RUN] Would write {prop_path}")
    else:
        os.makedirs(gradle_home, exist_ok=True)
        with open(prop_path, "w", encoding="utf-8") as f:
            f.write(content)
        print(f"Updated {prop_path}")

    # Clean stale stopped/incompatible daemon registries in ~/.gradle/daemon/*/registry.bin
    registries = sorted(glob.glob(os.path.join(gradle_home, "daemon", "*", "registry.bin*")))
    for reg in registries:
        if dry_run:
            print(f"[DRY-RUN] Would remove stale daemon registry: {reg}")
        else:
            try:
                os.remove(reg)
                print(f"Removed stale daemon registry: {reg}")
            except OSError:
                pass


if __name__ == "__main__":
    dry = "--dry-run" in sys.argv
    process_sdk_package_xmls(dry_run=dry)
    align_global_gradle_properties(dry_run=dry)
