# Plan to Fix: Incompatible/Stopped Gradle Daemons & SDK XML Version 4 Warning

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permanently eliminate both messages during `./gradlew assembleDebug`:
1. `Starting a Gradle Daemon, 1 incompatible and 2 stopped Daemons could not be reused, use --status for details`
2. `Warning: This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered. This can happen if you use versions of Android Studio and the command-line tools that were released at different times.`

---

## Root-Cause Analysis

### 1. Why `Warning: This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered` Appears
When Android Gradle Plugin (`sdklib`) initializes on a fresh Gradle Daemon, `LocalRepoLoaderImpl` scans every `package.xml` inside `/usr/lib/android-sdk/`.
- Inspecting `/usr/lib/android-sdk/**/package.xml` revealed **8 `package.xml` files** (`cmake/3.22.1`, `ndk/27.0.12077973`, `ndk/28.2.13676358`, `build-tools/34.0.0`, `build-tools/35.0.0`, `build-tools/36.0.0`, `platforms/android-35`, `platforms/android-36`) that contain **unused JAXB namespace attributes** on their root `<ns2:repository>` element:
  - `xmlns:ns6="http://schemas.android.com/sdk/android/repo/repository2/04"` (`cmake/3.22.1`, `ndk/27.0.12077973`)
  - `xmlns:ns13="http://schemas.android.com/sdk/android/repo/addon2/04"` (`cmake/3.22.1`, `ndk/27.0.12077973`)
  - `xmlns:ns14="http://schemas.android.com/sdk/android/repo/sys-img2/05"` (`cmake/3.22.1`, `ndk/27.0.12077973`)
  - `xmlns:ns12="http://schemas.android.com/sdk/android/repo/sys-img2/04"` (all 8 files)
- None of those 8 files actually use any elements from `/04` or `/05` (they only use `common/02`, `generic/02`, and `repository2/03`).
- Removing the unused `/04` and `/05` `xmlns:` attributes from `/usr/lib/android-sdk/**/package.xml` stops `sdklib` from ever triggering the version 4/5 schema warning on **any** AGP version.

### 2. Why `Starting a Gradle Daemon, 1 incompatible and 2 stopped Daemons could not be reused` Appears
- **Incompatible Daemon (`1 incompatible`)**: The system default `java` is OpenJDK 26 (`/usr/lib/jvm/java-26-openjdk-amd64`), while IDE/CLI tools and `gradle.properties` had differing `org.gradle.java.home` (`java-11` vs `java-21`) and `org.gradle.jvmargs` (`-Xmx2048m` vs `-Xmx4096m`). When a daemon is already running with different JVM options or JDK path, Gradle cannot reuse it and reports `1 incompatible`.
- **Stopped Daemons (`2 stopped`)**: `~/.gradle/daemon/<version>/registry.bin` retains historical records of previously killed/stopped daemons and prints `N stopped Daemons could not be reused` whenever a new daemon is started.
- **Solution**:
  1. Unify `org.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64` and `org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8` in both project `gradle.properties` and global `~/.gradle/gradle.properties`.
  2. Stop old mismatched daemons (`./gradlew --stop`) and clear stale `~/.gradle/daemon/*/registry.bin` files, then pre-warm the single unified Gradle daemon so subsequent `./gradlew assembleDebug` invocations attach immediately with zero daemon startup messages.

---

## Implementation Tasks

### Task 1: Sanitize Unused `/04` and `/05` Schema Namespaces in `/usr/lib/android-sdk/**/package.xml`
- [ ] **Step 1:** Back up and strip unused `repository2/04`, `addon2/04`, `sys-img2/05`, and `sys-img2/04` `xmlns` attributes from the 8 `/usr/lib/android-sdk/**/package.xml` files owned by `willygailo`.
- [ ] **Step 2:** Verify with `grep -n "repository2/04\|addon2/04\|sys-img2/04\|sys-img2/05" /usr/lib/android-sdk/*/*/package.xml` that zero v4/v5 schema URIs remain, and validate XML well-formedness with `python3 -c "import xml.etree.ElementTree as ET..."`.

### Task 2: Unify Global & Project Gradle Daemon Configuration and Purge Stale Daemon Registries
- [ ] **Step 1:** Align `~/.gradle/gradle.properties` and project `gradle.properties` to use the exact same `org.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64` and `org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8`.
- [ ] **Step 2:** Stop all running mismatched Gradle daemons (`./gradlew --stop`) and clean stale `~/.gradle/daemon/*/registry.bin` entries.
- [ ] **Step 3:** Pre-warm the unified daemon and run `./gradlew assembleDebug` to verify **zero** `incompatible/stopped Daemons` messages and **zero** `SDK XML file of version 4` warnings.
