# Gradle 8.11.1 / AGP 8.11.1 & SDK XML v4 Warning Fix Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the `Warning: This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered` message, fix the `1 incompatible and 2 stopped Daemons could not be reused` startup overhead, and cut incremental build times from `1m 54s` (`35/35 executed`) down to seconds (`UP-TO-DATE` / `FROM-CACHE`).

**Architecture:** Upgrade the build toolchain from legacy **AGP `7.1.3` + Gradle `7.2` + JDK `11`** (2021–2022 era) to **AGP `8.11.1` + Gradle `8.11.1` + JDK `21 LTS`** (all already pre-installed and cached locally on this machine), migrate `app/build.gradle` and `AndroidManifest.xml` to AGP 8+ `namespace` / `packaging` DSL, and enable Gradle build caching (`org.gradle.caching=true`) and VFS watching (`org.gradle.vfs.watch=true`).

**Tech Stack:** Gradle `8.11.1`, Android Gradle Plugin `8.11.1`, OpenJDK `21` (`/usr/lib/jvm/java-21-openjdk-amd64`), Android SDK `34`/`35`, NDK `27.0.12077973`.

---

## Root-Cause Diagnosis

1. **`SDK XML file of version 4 was encountered` Warning:**
   - `/usr/lib/android-sdk` contains modern packages (`platforms/android-36/package.xml`, `build-tools/36.0.0/package.xml`, `ndk/28.2.13676358/package.xml`) that use the Android SDK `repository2/04` (`version 4`) XML schema.
   - `com.android.tools.build:gradle:7.1.3` bundles a 2022 `sdklib` parser that only understands SDK XML versions up to `3`.
   - Upgrading to **AGP `8.11.1`** (already cached in `~/.gradle/caches/modules-2/files-2.1/com.android.tools.build/gradle/8.11.1`) natively supports SDK XML v4 and `compileSdk 34/35` without needing `android.suppressUnsupportedCompileSdk=34`.
2. **`1 incompatible and 2 stopped Daemons could not be reused`:**
   - Default shell `java` is OpenJDK 26 while `gradle.properties` pins `java-11-openjdk-amd64` with `-Xmx2048m`. Pinning **OpenJDK 21 LTS** (`/usr/lib/jvm/java-21-openjdk-amd64`) with `-Xmx4096m` aligns the Gradle daemon with AGP 8.11.1 and prevents OOM/GC daemon restarts during NDK + D8 builds.
3. **Slow `1m 54s` Build (`35 actionable tasks: 35 executed`):**
   - `gradle.properties` currently sets `org.gradle.vfs.watch=false` (disabling incremental file-system watching) and lacks `org.gradle.caching=true`. Enabling VFS watching and the local Gradle build cache reduces repeat/incremental builds from ~2 minutes to <5 seconds.

---

### Task 1: Upgrade Gradle Wrapper & Top-Level Build Script to `8.11.1`

**Files:**
- Modify: `gradle/wrapper/gradle-wrapper.properties`
- Modify: `build.gradle`
- Modify: `gradle.properties`

- [ ] **Step 1: Update `gradle/wrapper/gradle-wrapper.properties` to `gradle-8.11.1-all.zip`**
- [ ] **Step 2: Update `build.gradle` classpath to `com.android.tools.build:gradle:8.11.1`**
- [ ] **Step 3: Optimize `gradle.properties` (JDK 21 LTS, `org.gradle.vfs.watch=true`, `org.gradle.caching=true`, `-Xmx4096m`)**

---

### Task 2: Migrate `app/build.gradle` & `AndroidManifest.xml` to AGP 8+ DSL

**Files:**
- Modify: `app/build.gradle`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add `namespace "com.android.support"`, `compileOptions` (Java 17), and `packaging` block in `app/build.gradle`**
- [ ] **Step 2: Remove deprecated `package="com.android.support"` attribute from `app/src/main/AndroidManifest.xml`**
- [ ] **Step 3: Run `./gradlew assembleDebug` twice to verify zero `SDK XML version 4` warnings and fast `UP-TO-DATE` incremental build**
