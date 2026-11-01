# MLBB & Android Universal Masking / Device Spoofing Engine

Dokumentasyon para sa buong implementation ng device identity spoofing, hardware masking, display rate unlock, at Unity engine tier overriding para sa Mobile Legends: Bang Bang (`com.mobile.legends`).

---

## 1. Overview & Architecture

Ang engine na ito ay dinisenyo upang mag-operate nang **rootless** gamit ang Shizuku / ADB Shell (UID 2000), o direkta sa loob ng app/overlay. Gumagana ito sa lahat ng bersyon ng Android (**Android 7 Nougat hanggang Android 16 Baklava**).

```
+-------------------------------------------------------------------------------+
|                       Shizuku / ADB Shell (UID 2000)                          |
+-------------------------------------------------------------------------------+
        |                               |                               |
        v                               v                               v
[ Android OS Layer ]         [ Display & SurfaceFlinger ]     [ Game & Storage ]
- Android ID (SSAID)         - Dynamic 120/165Hz Lock        - AppOps Isolation
- Advertising ID (AAID)      - Multi-Vendor Props            - Unity boot.config
- Hidden API Exemption       - GameManager (Android 12+)     - DeviceConfig.json
- Privacy MAC Randomization  - VSync Disabler                - ResCheck MD5 Skip
```

---

## 2. Mga Nilalaman at Binagong Files

### 1. `app/src/main/java/com/android/support/shizuku/MlbbSpoofMaskingEnforcer.java`
- **Tungkulin:** Ang core orchestrator ng masking pipeline.
- **Hardware Profile:** Ginagaya ang **ASUS ROG Phone 8 Pro** (`ASUS_AI2401_A`):
  - **GPU:** Qualcomm Adreno 750
  - **SoC:** Snapdragon 8 Gen 3 (`SM8650-AB`)
  - **Refresh Rate:** 165Hz Extreme
  - **Fingerprint:** `asus/WW_AI2401/ASUS_AI2401:14/UKQ1.230924.001/34.1420.1420.426:user/release-keys`
- **Dynamic Identity Generation:**
  - `generateRandomAndroidId()`: Gumagawa ng bagong 16-hex character ID gamit ang `SecureRandom`.
  - `generateRandomAaid()`: Gumagawa ng bagong random UUID para sa Google Advertising ID.

### 2. `app/src/main/java/com/android/support/shizuku/DeviceIdentityHooker.java`
- **Tungkulin:** Non-Root In-Process Java ART & Reflection Hooking engine.
- **Dynamic Field Mutation:** Direkta nitong binabago sa memory ng process ang mga final static fields ng `android.os.Build`:
  - `Build.MODEL`, `Build.MANUFACTURER`, `Build.BRAND`, `Build.DEVICE`, `Build.PRODUCT`, `Build.HARDWARE`, `Build.BOARD`, `Build.FINGERPRINT`, `Build.SOC_MODEL`.
- **Double-Reflection Hidden API Exemption:** Ina-unlock ang `dalvik.system.VMRuntime.setHiddenApiExemptions` gamit ang meta-reflection upang walang reflection restriction error sa Android 9 hanggang Android 16.
- **System Properties Override:** Ino-override ang `ro.product.*` properties sa runtime gamit ang `android.os.SystemProperties.set` reflection.

### 3. `app/src/main/java/com/android/support/shizuku/MlbbModInjector.java`
- **Tungkulin:** Ini-integrate ang masking enforcer sa UI, Floating Menu, at ShizukuPanel.
- Sa method na `applyRog10ProSpoof(context, enable, callback)`, tinatawag nito ang buong 5-step universal masking procedure bago mag-deploy ng native RAM hooks.

---

## 3. Five-Stage Masking Pipeline

| Hakbang | Aksyon | Teknikal na Detalye |
|---|---|---|
| **Step 1** | **Identity Randomization** | Ini-inject ang randomized `android_id` at `advertising_id` sa `secure` settings table ng user 0; ini-enable ang enhanced Wi-Fi MAC randomization at pino-flush ang Bluetooth LE address. |
| **Step 2** | **Display & Hidden API** | Ipinapatupad ang hidden API exemptions (`*`) para maiwasan ang reflection blocks. Ina-unlock ang 165Hz/120Hz sa AOSP, ASUS, Tecno, Infinix, Xiaomi, Samsung, at MediaTek display controllers. |
| **Step 3** | **GameManager & AppOps** | Ina-activate ang Android 12+ Game Mode `PERFORMANCE` at `--fps 165`. Ipinagkakait ang telemetry access (`READ_DEVICE_IDENTIFIERS`, `ACCESS_FINE_LOCATION`) habang pinapayagan ang storage access. |
| **Step 4** | **Unity Engine Override** | Gumagawa at nag-iinject ng `boot.config` (`target-frame-rate=165`, `v-sync-count=0`), `DeviceConfig.json` (Tier 4 Ultra Graphics profile), at `ResCheckConf.xml` sa lahat ng active Dragon2017 storage slots. |
| **Step 5** | **SELinux Contexts** | Inaayos ang file context (`u:object_r:media_rw_data_file:s0`) at permissions (775) sa `/sdcard/Android/data/com.mobile.legends/files` upang mabasa ng laro nang walang file access error. |

---

## 4. Cross-Version Compatibility Matrix

| Android Version | API Level | Suportadong Pamamaraan |
|---|---|---|
| **Android 7.0 - 9.0** | API 24 - 28 | Legacy `settings put secure android_id`, `debug.sf.fps` properties, manual Unity asset replacement. |
| **Android 10 - 11** | API 29 - 30 | DeviceConfig overlay framework, scoped storage AppOps, MAC address randomization per connection. |
| **Android 12 - 14** | API 31 - 34 | `cmd game mode performance`, `cmd game set --fps 165`, hidden API exemptions, runtime SELinux permissive adjustments. |
| **Android 15 - 16** | API 35 - 36 | User 0 namespace interventions, advanced surface flinger high-refresh policy, dynamic hardware abstraction masking. |

---

## 5. Live ADB Verification Commands

Kung nais i-verify o manu-manong patakbuhin gamit ang terminal/PC:

### Tingnan ang Kasalukuyang Values:
```bash
# Tingnan ang Android ID
adb shell settings get secure android_id

# Tingnan ang Advertising ID
adb shell settings get secure advertising_id

# Tingnan ang Game Mode status ng MLBB
adb shell cmd game get com.mobile.legends

# Tingnan ang Max Refresh Rate setting
adb shell settings get system peak_refresh_rate
```

### Manu-manong Pag-revert sa Stock Settings:
```bash
# I-reset ang game mode
adb shell cmd game reset com.mobile.legends

# Ibalik sa default 60Hz
adb shell settings put system peak_refresh_rate 60.0
adb shell settings put global peak_refresh_rate 60.0

# Burahin ang custom boot.config
adb shell rm -f /sdcard/Android/data/com.mobile.legends/files/boot.config
adb shell rm -f /sdcard/Android/data/com.mobile.legends/files/dragon2017/assets/boot.config
```

---

## 6. How to Use in the App

1. Buksan ang **Android-LibTool** app sa device.
2. I-grant ang **Shizuku** permission.
3. Sa Floating Menu o sa **Shizuku Tools** card, pindutin ang **ROG 10 Pro+ Spoof / Full Masking**.
4. Maghintay ng notification na `🔥 Universal Hardware & MLBB Masking Active!`.
5. Buksan ang **Mobile Legends: Bang Bang**:
   - Makikita sa Settings ng laro na available na ang **Ultra / Super (120FPS+)** frame rate options.
   - Ang device information na binabasa ng game telemetry ay protektado at naka-mask sa ASUS ROG high-tier profile.

