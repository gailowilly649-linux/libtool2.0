package com.android.support.shizuku;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java UnityFS (v7 / Unity 2019.4+) AssetBundle Decompressor, Container Remapper, and Repacker.
 *
 * Solves the "bilog" (blue fallback sphere) and invisible skin issue in MLBB 2.2.16.12322 by:
 * 1. Decompressing both LZMA (block flag 0x41) and LZ4/LZ4HC (block flags 0x02/0x03) UnityFS bundles.
 * 2. Parsing the SerializedFile (CAB-*) AssetBundle object (PathID = 1) and expanding m_Container
 *    so that Default Skin (_skin_high_add), Basic Skin (_skin01_high_add), and Secondary Skin
 *    (_skin02_high_add) prefab paths all resolve to the genuine premium 3D model AssetInfo.
 * 3. Appending the updated AssetBundle object at the end of the CAB-* node (8-byte aligned) and
 *    updating only PathID=1's ObjectInfo (byteStart, byteSize) so zero other Mesh/Shader/Animation
 *    offsets are shifted.
 * 4. Repacking into a standard UnityFS v7 archive (flags = 0x43) with 128 KB LZ4 blocks.
 */
public final class UnityBundlePatcher {

    private static final String TAG = "UnityBundlePatcher";
    private static final int CHUNK_SIZE = 131072; // 128 KB standard UnityFS block size
    private static final Pattern SKIN_TAG_PATTERN = Pattern.compile("_skin(\\d*)_");
    private static final Pattern SKIN_DIR_PATTERN = Pattern.compile("/skin(\\d+)/");
    private static final Pattern UI_HERO_PATTERN = Pattern.compile("hero(\\d+)(_add\\.prefab|\\.prefab)", Pattern.CASE_INSENSITIVE);

    private UnityBundlePatcher() {}

    /**
     * Patches a source UnityFS bundle (3D model or UI portrait) and writes the remapped bundle to dstBundle.
     */
    public static boolean patchBundleContainer(File srcBundle, File dstBundle, String targetBundleFileName, String uiTargetPrefabLower) {
        if (srcBundle == null || !srcBundle.exists() || srcBundle.length() < 64) {
            return false;
        }
        try {
            byte[] rawFile = readAllBytes(srcBundle);
            byte[] patched = patchUnityFsBytes(rawFile, targetBundleFileName, uiTargetPrefabLower);
            if (patched == null || patched.length < 64) {
                return false;
            }
            File parent = dstBundle.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            writeAllBytes(dstBundle, patched);
            return true;
        } catch (Throwable t) {
            try {
                Log.w(TAG, "patchBundleContainer fallback on " + srcBundle.getName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
                // Host JVM execution without android.util.Log
            }
            return false;
        }
    }

    public static boolean patchBundleContainer(File srcBundle, File dstBundle, String targetBundleFileName) {
        return patchBundleContainer(srcBundle, dstBundle, targetBundleFileName, null);
    }

    /**
     * Patches all 3D model variants in hDir for a given hero so Default (_skin_), Basic (_skin01_),
     * and Elite (_skin02_, _skin03_) files contain multi-mapped m_Container entries across all quality tiers.
     * NEVER creates or overwrites <prefix>.unity3d or <prefix>_skin.unity3d (which are base skeleton rigs).
     */
    public static void patchHeroModelSet(File hDir, String heroCodeName, String targetSkinId) {
        if (hDir == null || !hDir.exists() || !hDir.isDirectory()) return;
        File[] files = hDir.listFiles();
        if (files == null) return;

        // 1. Purge any accidental fake <prefix>_skin.unity3d that was cloned from an _add.unity3d bundle
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith("_skin.unity3d")) {
                if (isClonedAddBundle(f)) {
                    f.delete();
                }
            }
        }

        // 2. Collect unique hero prefixes present in hDir
        Set<String> prefixes = new LinkedHashSet<>();
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".unity3d") || f.length() <= 64) continue;
            String name = f.getName();
            int idx = name.indexOf("_skin");
            if (idx > 0) {
                prefixes.add(name.substring(0, idx));
            }
        }
        if (prefixes.isEmpty() && heroCodeName != null && !heroCodeName.isEmpty()) {
            prefixes.add(heroCodeName);
        }

        // 3. For each prefix, snapshot the primary source bytes for each category BEFORE writing any files
        for (String prefix : prefixes) {
            byte[] shadowHighBytes = null;
            byte[] shadowAddBytes = null;
            byte[] meshHighBytes = null;
            byte[] meshAddBytes = null;
            byte[] meshLowBytes = null;
            byte[] meshExtLowBytes = null;

            for (File f : files) {
                if (!f.isFile() || !f.exists() || f.length() <= 64) continue;
                String name = f.getName();
                if (!name.startsWith(prefix + "_skin") || !name.endsWith(".unity3d")) continue;

                try {
                    if (name.contains("_shadow_high_add.unity3d")) {
                        if (shadowHighBytes == null || f.length() > shadowHighBytes.length) {
                            shadowHighBytes = readAllBytes(f);
                        }
                    } else if (name.contains("_shadow_add.unity3d")) {
                        if (shadowAddBytes == null || f.length() > shadowAddBytes.length) {
                            shadowAddBytes = readAllBytes(f);
                        }
                    } else if (name.contains("_high_add.unity3d")) {
                        if (meshHighBytes == null || f.length() > meshHighBytes.length) {
                            meshHighBytes = readAllBytes(f);
                        }
                    } else if (name.contains("_extlow_add.unity3d")) {
                        if (meshExtLowBytes == null || f.length() > meshExtLowBytes.length) {
                            meshExtLowBytes = readAllBytes(f);
                        }
                    } else if (name.contains("_low_add.unity3d")) {
                        if (meshLowBytes == null || f.length() > meshLowBytes.length) {
                            meshLowBytes = readAllBytes(f);
                        }
                    } else if (name.contains("_add.unity3d")) {
                        if (meshAddBytes == null || f.length() > meshAddBytes.length) {
                            meshAddBytes = readAllBytes(f);
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            // Cross-fill missing quality tiers so Smooth/Medium/High/Ultra graphics all work
            if (meshHighBytes == null && meshAddBytes != null) meshHighBytes = meshAddBytes;
            if (meshAddBytes == null && meshHighBytes != null) meshAddBytes = meshHighBytes;
            if (meshLowBytes == null && meshAddBytes != null) meshLowBytes = meshAddBytes;
            if (meshExtLowBytes == null && meshLowBytes != null) meshExtLowBytes = meshLowBytes;
            if (shadowAddBytes == null && shadowHighBytes != null) shadowAddBytes = shadowHighBytes;
            if (shadowHighBytes == null && shadowAddBytes != null) shadowHighBytes = shadowAddBytes;

            String[] skinSlots = new String[]{"_skin", "_skin01", "_skin02", "_skin03"};
            for (String slot : skinSlots) {
                if (meshHighBytes != null) {
                    patchBytesToFile(meshHighBytes, hDir, prefix + slot + "_high_add.unity3d");
                }
                if (meshAddBytes != null) {
                    patchBytesToFile(meshAddBytes, hDir, prefix + slot + "_add.unity3d");
                }
                if (meshLowBytes != null) {
                    patchBytesToFile(meshLowBytes, hDir, prefix + slot + "_low_add.unity3d");
                }
                if (meshExtLowBytes != null) {
                    patchBytesToFile(meshExtLowBytes, hDir, prefix + slot + "_extlow_add.unity3d");
                }
                if (shadowHighBytes != null) {
                    patchBytesToFile(shadowHighBytes, hDir, prefix + slot + "_shadow_high_add.unity3d");
                }
                if (shadowAddBytes != null) {
                    patchBytesToFile(shadowAddBytes, hDir, prefix + slot + "_shadow_add.unity3d");
                }
            }
        }
    }

    /**
     * Returns true if the given file (e.g. <prefix>_skin.unity3d) is actually a cloned _add.unity3d
     * mesh bundle rather than a genuine MLBB base skeleton rig.
     */
    public static boolean isClonedAddBundle(File f) {
        if (f == null || !f.exists() || f.length() < 64) return false;
        try {
            byte[] raw = readAllBytes(f);
            int[] posRef = new int[]{0};
            String sig = readCString(raw, posRef);
            if (!"UnityFS".equals(sig)) return false;
            int formatVersion = readIntBE(raw, posRef);
            readCString(raw, posRef);
            readCString(raw, posRef);
            readLongBE(raw, posRef);
            int cInfoSize = readIntBE(raw, posRef);
            int uInfoSize = readIntBE(raw, posRef);
            int archiveFlags = readIntBE(raw, posRef);
            if (formatVersion >= 7) {
                posRef[0] = (posRef[0] + 15) & ~15;
            }
            byte[] bInfoRaw = Arrays.copyOfRange(raw, posRef[0], posRef[0] + cInfoSize);
            int dataPos = posRef[0] + cInfoSize;
            if ((archiveFlags & 0x200) != 0) {
                dataPos = (dataPos + 15) & ~15;
            }
            byte[] bInfo = ((archiveFlags & 0x3F) == 0) ? bInfoRaw : decompressLz4(bInfoRaw, uInfoSize);
            int[] bPos = new int[]{16};
            int numBlocks = readIntBE(bInfo, bPos);
            if (numBlocks <= 0) return false;
            int uSz = readIntBE(bInfo, bPos);
            int cSz = readIntBE(bInfo, bPos);
            int bFlags = readUShortBE(bInfo, bPos);
            // If block 0 is LZ4 (0x02) — which our packUnityFs writes — or if its decompressed first block contains "_add.prefab"
            if ((bFlags & 0x3F) == 2) {
                return true;
            }
            if ((bFlags & 0x3F) == 3 && numBlocks == 1 && uSz <= CHUNK_SIZE) {
                byte[] dec = decompressLz4(Arrays.copyOfRange(raw, dataPos, dataPos + cSz), uSz);
                if (indexOf(dec, "_add.prefab".getBytes(StandardCharsets.UTF_8), 0) >= 0) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void patchBytesToFile(byte[] srcBytes, File dir, String targetFileName) {
        try {
            File dst = new File(dir, targetFileName);
            byte[] patched = patchUnityFsBytes(srcBytes, targetFileName, null);
            if (patched != null && patched.length > 64) {
                writeAllBytes(dst, patched);
            } else {
                writeAllBytes(dst, srcBytes);
            }
        } catch (Exception ignored) {
        }
    }

    private static final class DirectoryNode {
        long offset;
        long size;
        int flags;
        String name;
        byte[] data;
    }

    public static byte[] patchUnityFsBytes(byte[] data, String targetBundleFileName, String uiTargetPrefabLower) throws Exception {
        if (data == null || data.length < 64) return null;
        int[] posRef = new int[]{0};
        String sig = readCString(data, posRef);
        if (!"UnityFS".equals(sig)) {
            return null;
        }
        int formatVersion = readIntBE(data, posRef);
        String unityVersion = readCString(data, posRef);
        String generatorVersion = readCString(data, posRef);
        long totalFileSize = readLongBE(data, posRef);
        int cInfoSize = readIntBE(data, posRef);
        int uInfoSize = readIntBE(data, posRef);
        int archiveFlags = readIntBE(data, posRef);

        if (formatVersion >= 7) {
            posRef[0] = (posRef[0] + 15) & ~15;
        }

        byte[] bInfoRaw;
        int dataPos;
        if ((archiveFlags & 0x80) != 0) {
            bInfoRaw = Arrays.copyOfRange(data, data.length - cInfoSize, data.length);
            dataPos = posRef[0];
        } else {
            bInfoRaw = Arrays.copyOfRange(data, posRef[0], posRef[0] + cInfoSize);
            dataPos = posRef[0] + cInfoSize;
            if ((archiveFlags & 0x200) != 0) {
                dataPos = (dataPos + 15) & ~15;
            }
        }

        int metaCompType = archiveFlags & 0x3F;
        byte[] bInfo;
        if (metaCompType == 2 || metaCompType == 3) {
            bInfo = decompressLz4(bInfoRaw, uInfoSize);
        } else if (metaCompType == 0) {
            bInfo = bInfoRaw;
        } else {
            return null;
        }

        int[] bPos = new int[]{16}; // skip 16-byte hash
        int numBlocks = readIntBE(bInfo, bPos);
        int[][] blockSpecs = new int[numBlocks][3];
        long totalUncompressed = 0;
        for (int i = 0; i < numBlocks; i++) {
            blockSpecs[i][0] = readIntBE(bInfo, bPos); // uSize
            blockSpecs[i][1] = readIntBE(bInfo, bPos); // cSize
            blockSpecs[i][2] = readUShortBE(bInfo, bPos); // bFlags
            totalUncompressed += (blockSpecs[i][0] & 0xFFFFFFFFL);
        }

        int numNodes = readIntBE(bInfo, bPos);
        List<DirectoryNode> nodes = new ArrayList<>(numNodes);
        for (int i = 0; i < numNodes; i++) {
            DirectoryNode node = new DirectoryNode();
            node.offset = readLongBE(bInfo, bPos);
            node.size = readLongBE(bInfo, bPos);
            node.flags = readIntBE(bInfo, bPos);
            node.name = readCString(bInfo, bPos);
            nodes.add(node);
        }

        // Decompress all storage blocks into a contiguous stream
        byte[] combined = new byte[(int) totalUncompressed];
        int outOffset = 0;
        int cur = dataPos;
        for (int i = 0; i < numBlocks; i++) {
            int uSz = blockSpecs[i][0];
            int cSz = blockSpecs[i][1];
            int bFlags = blockSpecs[i][2];
            byte[] chunk = Arrays.copyOfRange(data, cur, cur + cSz);
            cur += cSz;
            int cType = bFlags & 0x3F;
            byte[] dec;
            if (cType == 0) {
                dec = chunk;
            } else if (cType == 2 || cType == 3) {
                dec = decompressLz4(chunk, uSz);
            } else if (cType == 1) {
                dec = decompressLzma(chunk, uSz);
            } else {
                return null;
            }
            System.arraycopy(dec, 0, combined, outOffset, uSz);
            outOffset += uSz;
        }

        // Slice nodes
        for (DirectoryNode node : nodes) {
            node.data = Arrays.copyOfRange(combined, (int) node.offset, (int) (node.offset + node.size));
        }

        // Patch the primary CAB-* SerializedFile node
        boolean patchedAny = false;
        for (DirectoryNode node : nodes) {
            if (!node.name.endsWith(".resS") && !node.name.endsWith(".resource") && node.data.length > 64) {
                byte[] patchedCab = patchSerializedCab(node.data, targetBundleFileName, uiTargetPrefabLower);
                if (patchedCab != null) {
                    node.data = patchedCab;
                    node.size = patchedCab.length;
                    patchedAny = true;
                    break;
                }
            }
        }

        if (!patchedAny) {
            return null;
        }

        // Recompute node offsets and recombine
        long runningOffset = 0;
        for (DirectoryNode node : nodes) {
            node.offset = runningOffset;
            runningOffset += node.data.length;
        }

        byte[] newCombined = new byte[(int) runningOffset];
        for (DirectoryNode node : nodes) {
            System.arraycopy(node.data, 0, newCombined, (int) node.offset, node.data.length);
        }

        return packUnityFs(unityVersion, generatorVersion, nodes, newCombined);
    }

    private static byte[] patchSerializedCab(byte[] cab, String targetBundleFileName, String uiTargetPrefabLower) {
        if (cab.length < 32) return null;
        ByteBuffer hdr = ByteBuffer.wrap(cab, 0, 20).order(ByteOrder.BIG_ENDIAN);
        int metaSize = hdr.getInt();
        int fileSize = hdr.getInt();
        int version = hdr.getInt();
        int dataOffset = hdr.getInt();
        if (version < 15 || version > 22 || dataOffset <= 0 || dataOffset >= cab.length) {
            return null;
        }

        // Locate AssetBundle object (PathID = 1) inside cab[dataOffset..]
        // It starts with m_Name (aligned string) followed by m_PreloadTable and m_Container,
        // and contains "assets/" paths.
        int assetsStrIdx = indexOf(cab, "assets/".getBytes(StandardCharsets.UTF_8), dataOffset);
        if (assetsStrIdx < 0) {
            return null;
        }

        // Find PathID = 1 entry in ObjectInfo table inside metadata [0 .. dataOffset)
        // In SerializedFile v21 (little-endian metadata), each ObjectInfo is 20 bytes:
        // int64 pathID (8), uint32 byteStart (4), uint32 byteSize (4), int32 typeID (4)
        int objInfoPos = -1;
        byte[] newAbBytes = null;

        for (int i = 20; i <= dataOffset - 20; i++) {
            // Check pathID == 1L (little endian: 01 00 00 00 00 00 00 00)
            if (cab[i] == 1 && cab[i + 1] == 0 && cab[i + 2] == 0 && cab[i + 3] == 0
                    && cab[i + 4] == 0 && cab[i + 5] == 0 && cab[i + 6] == 0 && cab[i + 7] == 0) {
                int candStart = readIntLE(cab, i + 8);
                int candSize = readIntLE(cab, i + 12);
                int absStart = dataOffset + candStart;
                if (candStart >= 0 && candSize > 32 && absStart + candSize <= cab.length) {
                    int strPos = indexOf(cab, "assets/".getBytes(StandardCharsets.UTF_8), absStart);
                    if (strPos >= absStart && strPos < absStart + candSize) {
                        byte[] candAb = Arrays.copyOfRange(cab, absStart, absStart + candSize);
                        byte[] rebuilt = rebuildAssetBundleObject(candAb, targetBundleFileName, uiTargetPrefabLower);
                        if (rebuilt != null) {
                            objInfoPos = i;
                            newAbBytes = rebuilt;
                            break;
                        }
                    }
                }
            }
        }

        if (objInfoPos < 0 || newAbBytes == null) {
            return null;
        }

        // Append newAbBytes at the end of cab (16-byte aligned) so no other object's byteStart changes!
        int alignedEnd = (cab.length + 15) & ~15;
        int newAbStartRel = alignedEnd - dataOffset;
        int newCabSize = alignedEnd + newAbBytes.length;

        byte[] newCab = Arrays.copyOf(cab, newCabSize);
        System.arraycopy(newAbBytes, 0, newCab, alignedEnd, newAbBytes.length);

        // Update fileSize in big-endian CAB header (offset 4)
        ByteBuffer.wrap(newCab, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(newCabSize);

        // Update PathID=1 ObjectInfo (byteStart at objInfoPos + 8, byteSize at objInfoPos + 12)
        ByteBuffer.wrap(newCab, objInfoPos + 8, 8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(newAbStartRel)
                .putInt(newAbBytes.length);

        return newCab;
    }

    private static byte[] rebuildAssetBundleObject(byte[] abBytes, String targetBundleFileName, String uiTargetPrefabLower) {
        try {
            int[] pos = new int[]{0};
            String mName = readAlignedStringLE(abBytes, pos);
            int preloadCount = readIntLE(abBytes, pos[0]);
            pos[0] += 4;
            if (preloadCount < 0 || preloadCount > 100000) return null;
            int preloadBytesLen = preloadCount * 12;
            byte[] preloadBytes = Arrays.copyOfRange(abBytes, pos[0], pos[0] + preloadBytesLen);
            pos[0] += preloadBytesLen;

            int containerCount = readIntLE(abBytes, pos[0]);
            pos[0] += 4;
            if (containerCount < 0 || containerCount > 50000) return null;

            Map<String, byte[]> expandedContainer = new TreeMap<>();
            for (int i = 0; i < containerCount; i++) {
                String key = readAlignedStringLE(abBytes, pos);
                byte[] assetInfo = Arrays.copyOfRange(abBytes, pos[0], pos[0] + 20);
                pos[0] += 20;

                // Keep original key
                expandedContainer.put(key, assetInfo);

                // Generate Default (_skin_), Basic (_skin01_), and Elite (_skin02_, _skin03_) container aliases
                for (String alias : generateContainerAliases(key, uiTargetPrefabLower)) {
                    if (!expandedContainer.containsKey(alias)) {
                        expandedContainer.put(alias, assetInfo);
                    }
                }
            }

            // Remaining bytes: m_MainAsset (20 bytes) + m_RuntimeCompatibility (4 bytes) + m_AssetBundleName + tail
            byte[] mainAndCompat = Arrays.copyOfRange(abBytes, pos[0], pos[0] + 24);
            pos[0] += 24;
            String abName = readAlignedStringLE(abBytes, pos);
            byte[] tail = Arrays.copyOfRange(abBytes, pos[0], abBytes.length);

            String newAbName = rewriteBundleName(abName, targetBundleFileName);
            String newMName = mName.isEmpty() ? mName : rewriteBundleName(mName, targetBundleFileName);

            ByteArrayOutputStream out = new ByteArrayOutputStream(abBytes.length + 1024);
            writeAlignedStringLE(out, newMName);
            writeIntLE(out, preloadCount);
            out.write(preloadBytes);

            writeIntLE(out, expandedContainer.size());
            for (Map.Entry<String, byte[]> entry : expandedContainer.entrySet()) {
                writeAlignedStringLE(out, entry.getKey());
                out.write(entry.getValue());
            }

            out.write(mainAndCompat);
            writeAlignedStringLE(out, newAbName);
            out.write(tail);
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<String> generateContainerAliases(String originalKey, String uiTargetPrefabLower) {
        List<String> aliases = new ArrayList<>();
        String lower = originalKey.toLowerCase(Locale.US);

        // 1. Handle 3D hero mesh, shadow, & skill effect prefab paths (e.g., .../hero_fanny_skin08_high_add.prefab)
        Matcher mSkin = SKIN_TAG_PATTERN.matcher(lower);
        if (mSkin.find()) {
            String[] skinTargets = new String[]{"_", "_skin_", "_skin01_", "_skin02_", "_skin03_"};
            for (String target : skinTargets) {
                String replaced = mSkin.replaceAll(target);
                addWithQualityVariants(aliases, replaced);
                Matcher mDir = SKIN_DIR_PATTERN.matcher(replaced);
                if (mDir.find()) {
                    addWithQualityVariants(aliases, mDir.replaceAll("/skin/"));
                    addWithQualityVariants(aliases, mDir.replaceAll("/skin01/"));
                    addWithQualityVariants(aliases, mDir.replaceAll("/skin02/"));
                    addWithQualityVariants(aliases, mDir.replaceAll("/skin03/"));
                }
            }
        }

        // 2. Handle skill effects / indicators with skin directory (e.g., .../skin07/...)
        Matcher mDirOnly = SKIN_DIR_PATTERN.matcher(lower);
        if (mDirOnly.find()) {
            aliases.add(mDirOnly.replaceAll("/skin01/"));
            aliases.add(mDirOnly.replaceAll("/skin02/"));
            aliases.add(mDirOnly.replaceAll("/skin/"));
        }

        // 3. Handle UI Portrait cards (e.g., assets/ui/ui_icon/hero/hero101712_add.prefab -> hero171_add.prefab)
        if (uiTargetPrefabLower != null && !uiTargetPrefabLower.isEmpty()) {
            int slash = lower.lastIndexOf('/');
            if (slash >= 0) {
                String prefixPath = lower.substring(0, slash + 1);
                String targetLower = uiTargetPrefabLower.toLowerCase(Locale.US);
                if (targetLower.endsWith(".prefab")) {
                    aliases.add(prefixPath + targetLower);
                } else {
                    aliases.add(prefixPath + targetLower + ".prefab");
                    aliases.add(prefixPath + targetLower + "_add.prefab");
                }
            }
        }
        Matcher mUi = UI_HERO_PATTERN.matcher(lower);
        if (mUi.find()) {
            String digits = mUi.group(1);
            String suffix = mUi.group(2);
            if (digits != null && digits.length() >= 3) {
                if (digits.length() == 6 && digits.startsWith("10")) {
                    String hId = digits.substring(2, 4);
                    aliases.add(mUi.replaceAll("hero" + hId + "1" + suffix));
                    aliases.add(mUi.replaceAll("hero0" + hId + suffix));
                    aliases.add(mUi.replaceAll("hero" + hId + "2" + suffix));
                    aliases.add(mUi.replaceAll("hero" + hId + "3" + suffix));
                } else if (digits.length() == 3) {
                    String hId2 = digits.substring(1);
                    String hIdLead = digits.substring(0, 2);
                    aliases.add(mUi.replaceAll("hero0" + hId2 + suffix));
                    aliases.add(mUi.replaceAll("hero" + hId2 + "1" + suffix));
                    aliases.add(mUi.replaceAll("hero" + hIdLead + "1" + suffix));
                    aliases.add(mUi.replaceAll("hero" + hIdLead + "2" + suffix));
                    aliases.add(mUi.replaceAll("hero" + hIdLead + "3" + suffix));
                } else if (digits.length() == 4) {
                    String hId3 = digits.substring(1);
                    aliases.add(mUi.replaceAll("hero" + hId3 + "1" + suffix));
                    aliases.add(mUi.replaceAll("hero" + digits.substring(0, 3) + "1" + suffix));
                    aliases.add(mUi.replaceAll("hero" + digits.substring(0, 3) + "2" + suffix));
                }
            }
        }

        return aliases;
    }

    private static void addWithQualityVariants(List<String> aliases, String path) {
        aliases.add(path);
        // For 3D hero mesh prefabs (excluding shadow), cross-map quality tiers so Smooth/Medium/High/Ultra all resolve
        if (!path.contains("_shadow_") && (path.contains("/char/hero/") || path.contains("hero_"))) {
            String base = null;
            if (path.endsWith("_high_add.prefab")) {
                base = path.substring(0, path.length() - "_high_add.prefab".length());
            } else if (path.endsWith("_extlow_add.prefab")) {
                base = path.substring(0, path.length() - "_extlow_add.prefab".length());
            } else if (path.endsWith("_low_add.prefab")) {
                base = path.substring(0, path.length() - "_low_add.prefab".length());
            } else if (path.endsWith("_add.prefab")) {
                base = path.substring(0, path.length() - "_add.prefab".length());
            }
            if (base != null && base.contains("_skin")) {
                aliases.add(base + "_high_add.prefab");
                aliases.add(base + "_add.prefab");
                aliases.add(base + "_low_add.prefab");
                aliases.add(base + "_extlow_add.prefab");
            }
        }
    }

    private static String rewriteBundleName(String origAbName, String targetBundleFileName) {
        if (targetBundleFileName == null || targetBundleFileName.isEmpty()) return origAbName;
        int slash = origAbName.lastIndexOf('/');
        String lowerTarget = targetBundleFileName.toLowerCase(Locale.US);
        if (!lowerTarget.endsWith(".unity3d")) {
            lowerTarget = lowerTarget + ".unity3d";
        }
        if (slash >= 0) {
            return origAbName.substring(0, slash + 1) + lowerTarget;
        }
        return lowerTarget;
    }

    private static byte[] packUnityFs(String unityVer, String genVer, List<DirectoryNode> nodes, byte[] combined) throws IOException {
        // Compress combined data in 128KB LZ4 chunks (flag = 0x02)
        int totalLen = combined.length;
        int numBlocks = (totalLen + CHUNK_SIZE - 1) / CHUNK_SIZE;
        if (numBlocks == 0) numBlocks = 1;

        ByteArrayOutputStream compressedDataOut = new ByteArrayOutputStream(totalLen);
        int[][] blockMeta = new int[numBlocks][3];

        int offset = 0;
        for (int i = 0; i < numBlocks; i++) {
            int uSz = Math.min(CHUNK_SIZE, totalLen - offset);
            byte[] slice = Arrays.copyOfRange(combined, offset, offset + uSz);
            offset += uSz;

            byte[] comp = compressLz4(slice);
            if (comp != null && comp.length < uSz) {
                blockMeta[i][0] = uSz;
                blockMeta[i][1] = comp.length;
                blockMeta[i][2] = 0x02; // LZ4
                compressedDataOut.write(comp);
            } else {
                blockMeta[i][0] = uSz;
                blockMeta[i][1] = uSz;
                blockMeta[i][2] = 0x00; // Uncompressed
                compressedDataOut.write(slice);
            }
        }

        // Build uncompressed BlocksInfo + DirectoryInfo
        ByteArrayOutputStream bInfoUncomp = new ByteArrayOutputStream(256);
        bInfoUncomp.write(new byte[16]); // 16-byte hash
        writeIntBE(bInfoUncomp, numBlocks);
        for (int i = 0; i < numBlocks; i++) {
            writeIntBE(bInfoUncomp, blockMeta[i][0]);
            writeIntBE(bInfoUncomp, blockMeta[i][1]);
            writeShortBE(bInfoUncomp, blockMeta[i][2]);
        }
        writeIntBE(bInfoUncomp, nodes.size());
        for (DirectoryNode node : nodes) {
            writeLongBE(bInfoUncomp, node.offset);
            writeLongBE(bInfoUncomp, node.size);
            writeIntBE(bInfoUncomp, node.flags);
            writeCString(bInfoUncomp, node.name);
        }

        byte[] uInfoBytes = bInfoUncomp.toByteArray();
        byte[] cInfoBytes = compressLz4(uInfoBytes);
        int archiveFlags = 0x43; // kArchiveCombinedData (0x40) | kCompressionLz4HC (0x03)

        // Calculate header size with v7 16-byte alignment
        ByteArrayOutputStream prefixHdr = new ByteArrayOutputStream(64);
        writeCString(prefixHdr, "UnityFS");
        writeIntBE(prefixHdr, 7);
        writeCString(prefixHdr, unityVer != null && !unityVer.isEmpty() ? unityVer : "5.x.x");
        writeCString(prefixHdr, genVer != null && !genVer.isEmpty() ? genVer : "2019.4.33f1");

        int preAlignLen = prefixHdr.size() + 20; // size(8) + cInfo(4) + uInfo(4) + flags(4)
        int alignedHdrLen = (preAlignLen + 15) & ~15;
        byte[] compDataBytes = compressedDataOut.toByteArray();
        long finalFileSize = (long) alignedHdrLen + cInfoBytes.length + compDataBytes.length;

        ByteArrayOutputStream finalOut = new ByteArrayOutputStream((int) finalFileSize);
        finalOut.write(prefixHdr.toByteArray());
        writeLongBE(finalOut, finalFileSize);
        writeIntBE(finalOut, cInfoBytes.length);
        writeIntBE(finalOut, uInfoBytes.length);
        writeIntBE(finalOut, archiveFlags);
        while (finalOut.size() < alignedHdrLen) {
            finalOut.write(0);
        }
        finalOut.write(cInfoBytes);
        finalOut.write(compDataBytes);
        return finalOut.toByteArray();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PURE-JAVA LZ4 DECOMPRESSOR & COMPRESSOR
    // ─────────────────────────────────────────────────────────────────────────

    public static byte[] decompressLz4(byte[] src, int uncompressedSize) {
        byte[] dst = new byte[uncompressedSize];
        int sIdx = 0;
        int dIdx = 0;
        int sEnd = src.length;

        while (sIdx < sEnd && dIdx < uncompressedSize) {
            int token = src[sIdx++] & 0xFF;
            int literalLen = token >>> 4;
            if (literalLen == 15) {
                int b;
                do {
                    b = src[sIdx++] & 0xFF;
                    literalLen += b;
                } while (b == 255 && sIdx < sEnd);
            }
            if (literalLen > 0) {
                System.arraycopy(src, sIdx, dst, dIdx, literalLen);
                sIdx += literalLen;
                dIdx += literalLen;
            }
            if (sIdx >= sEnd || dIdx >= uncompressedSize) {
                break;
            }
            int matchOffset = (src[sIdx++] & 0xFF) | ((src[sIdx++] & 0xFF) << 8);
            int matchLen = (token & 0x0F) + 4;
            if (matchLen == 19) {
                int b;
                do {
                    b = src[sIdx++] & 0xFF;
                    matchLen += b;
                } while (b == 255 && sIdx < sEnd);
            }
            int matchPos = dIdx - matchOffset;
            for (int i = 0; i < matchLen; i++) {
                dst[dIdx++] = dst[matchPos + i];
            }
        }
        return dst;
    }

    public static byte[] compressLz4(byte[] src) {
        int srcLen = src.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(srcLen + (srcLen / 255) + 16);
        int[] hashTable = new int[4096];
        Arrays.fill(hashTable, -1);

        int anchor = 0;
        int ip = 0;
        int matchLimit = srcLen - 5;
        int mflimit = srcLen - 12;

        while (ip < mflimit) {
            int h = ((readIntLE(src, ip) * 0x9E3779B1) >>> 20) & 0xFFF;
            int ref = hashTable[h];
            hashTable[h] = ip;

            if (ref >= 0 && (ip - ref) <= 0xFFFF && readIntLE(src, ref) == readIntLE(src, ip)) {
                int litLen = ip - anchor;
                int matchLen = 4;
                while (ip + matchLen < matchLimit && src[ref + matchLen] == src[ip + matchLen]) {
                    matchLen++;
                }
                int mlCode = matchLen - 4;
                int token = (Math.min(litLen, 15) << 4) | Math.min(mlCode, 15);
                out.write(token);

                if (litLen >= 15) {
                    int rem = litLen - 15;
                    while (rem >= 255) {
                        out.write(255);
                        rem -= 255;
                    }
                    out.write(rem);
                }
                out.write(src, anchor, litLen);

                int offset = ip - ref;
                out.write(offset & 0xFF);
                out.write((offset >>> 8) & 0xFF);

                if (mlCode >= 15) {
                    int rem = mlCode - 15;
                    while (rem >= 255) {
                        out.write(255);
                        rem -= 255;
                    }
                    out.write(rem);
                }

                ip += matchLen;
                anchor = ip;
            } else {
                ip++;
            }
        }

        // Write trailing literals
        int lastLit = srcLen - anchor;
        int token = Math.min(lastLit, 15) << 4;
        out.write(token);
        if (lastLit >= 15) {
            int rem = lastLit - 15;
            while (rem >= 255) {
                out.write(255);
                rem -= 255;
            }
            out.write(rem);
        }
        out.write(src, anchor, lastLit);
        return out.toByteArray();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PURE-JAVA LZMA RAW STREAM DECOMPRESSOR (Unity 5-byte props + stream)
    // ─────────────────────────────────────────────────────────────────────────

    public static byte[] decompressLzma(byte[] chunk, int uncompressedSize) throws IOException {
        if (chunk.length < 5) throw new IOException("Invalid LZMA chunk");
        int props = chunk[0] & 0xFF;
        if (props >= 9 * 5 * 5) throw new IOException("Invalid LZMA props");
        int lc = props % 9;
        int rest = props / 9;
        int lp = rest % 5;
        int pb = rest / 5;

        byte[] out = new byte[uncompressedSize];
        LzmaRangeDecoder rc = new LzmaRangeDecoder(chunk, 5);

        int numPosStates = 1 << pb;
        int numLitStates = 1 << (lc + lp);
        int posStateMask = numPosStates - 1;
        int lpMask = (1 << lp) - 1;

        short[] isMatch = initProbs(12 << 4);
        short[] isRep = initProbs(12);
        short[] isRepG0 = initProbs(12);
        short[] isRepG1 = initProbs(12);
        short[] isRepG2 = initProbs(12);
        short[] isRep0Long = initProbs(12 << 4);
        short[][] posSlotDecoder = new short[4][];
        for (int i = 0; i < 4; i++) posSlotDecoder[i] = initProbs(1 << 6);
        short[] posDecoders = initProbs(114);
        short[] alignDecoder = initProbs(1 << 4);
        short[] literals = initProbs(0x300 * numLitStates);

        LzmaLenDecoder lenDecoder = new LzmaLenDecoder(numPosStates);
        LzmaLenDecoder repLenDecoder = new LzmaLenDecoder(numPosStates);

        int state = 0;
        int rep0 = 0, rep1 = 0, rep2 = 0, rep3 = 0;
        int outPos = 0;
        byte prevByte = 0;

        while (outPos < uncompressedSize) {
            int posState = outPos & posStateMask;
            if (rc.decodeBit(isMatch, (state << pb) + posState) == 0) {
                int litState = ((outPos & lpMask) << lc) + ((prevByte & 0xFF) >>> (8 - lc));
                int probsOffset = 0x300 * litState;
                int symbol = 1;
                if (state >= 7) {
                    int matchByte = out[outPos - rep0 - 1] & 0xFF;
                    do {
                        int matchBit = (matchByte >>> 7) & 1;
                        matchByte <<= 1;
                        int bit = rc.decodeBit(literals, probsOffset + ((1 + matchBit) << 8) + symbol);
                        symbol = (symbol << 1) | bit;
                        if (matchBit != bit) {
                            while (symbol < 0x100) {
                                symbol = (symbol << 1) | rc.decodeBit(literals, probsOffset + symbol);
                            }
                            break;
                        }
                    } while (symbol < 0x100);
                } else {
                    while (symbol < 0x100) {
                        symbol = (symbol << 1) | rc.decodeBit(literals, probsOffset + symbol);
                    }
                }
                prevByte = (byte) symbol;
                out[outPos++] = prevByte;
                state = (state < 4) ? 0 : (state < 10) ? state - 3 : state - 6;
            } else {
                int len;
                if (rc.decodeBit(isRep, state) == 1) {
                    if (rc.decodeBit(isRepG0, state) == 0) {
                        if (rc.decodeBit(isRep0Long, (state << pb) + posState) == 0) {
                            state = state < 7 ? 9 : 11;
                            prevByte = out[outPos - rep0 - 1];
                            out[outPos++] = prevByte;
                            continue;
                        }
                    } else {
                        int dist;
                        if (rc.decodeBit(isRepG1, state) == 0) {
                            dist = rep1;
                        } else {
                            if (rc.decodeBit(isRepG2, state) == 0) {
                                dist = rep2;
                            } else {
                                dist = rep3;
                                rep3 = rep2;
                            }
                            rep2 = rep1;
                        }
                        rep1 = rep0;
                        rep0 = dist;
                    }
                    len = repLenDecoder.decode(rc, posState) + 2;
                    state = state < 7 ? 8 : 11;
                } else {
                    rep3 = rep2;
                    rep2 = rep1;
                    rep1 = rep0;
                    len = lenDecoder.decode(rc, posState) + 2;
                    state = state < 7 ? 7 : 10;
                    int lenToPosState = Math.min(len - 2, 3);
                    int posSlot = rc.decodeBitTree(posSlotDecoder[lenToPosState], 6);
                    if (posSlot >= 4) {
                        int numDirectBits = (posSlot >>> 1) - 1;
                        rep0 = (2 | (posSlot & 1)) << numDirectBits;
                        if (posSlot < 14) {
                            rep0 += rc.decodeReverseBitTree(posDecoders, rep0 - posSlot - 1, numDirectBits);
                        } else {
                            rep0 += (rc.decodeDirectBits(numDirectBits - 4) << 4);
                            rep0 += rc.decodeReverseBitTree(alignDecoder, 0, 4);
                        }
                    } else {
                        rep0 = posSlot;
                    }
                }
                for (int i = 0; i < len && outPos < uncompressedSize; i++) {
                    prevByte = out[outPos - rep0 - 1];
                    out[outPos++] = prevByte;
                }
            }
        }
        return out;
    }

    private static short[] initProbs(int size) {
        short[] p = new short[size];
        Arrays.fill(p, (short) 1024);
        return p;
    }

    private static final class LzmaRangeDecoder {
        private final byte[] buf;
        private int pos;
        private int range = 0xFFFFFFFF;
        private int code = 0;

        LzmaRangeDecoder(byte[] buf, int startPos) {
            this.buf = buf;
            this.pos = startPos;
            for (int i = 0; i < 5; i++) {
                code = (code << 8) | (pos < buf.length ? (buf[pos++] & 0xFF) : 0);
            }
        }

        int decodeBit(short[] probs, int index) {
            int prob = probs[index];
            int bound = (range >>> 11) * prob;
            if ((code ^ 0x80000000) < (bound ^ 0x80000000)) {
                range = bound;
                probs[index] = (short) (prob + ((2048 - prob) >>> 5));
                if ((range & 0xFF000000) == 0) {
                    code = (code << 8) | (pos < buf.length ? (buf[pos++] & 0xFF) : 0);
                    range <<= 8;
                }
                return 0;
            } else {
                range -= bound;
                code -= bound;
                probs[index] = (short) (prob - (prob >>> 5));
                if ((range & 0xFF000000) == 0) {
                    code = (code << 8) | (pos < buf.length ? (buf[pos++] & 0xFF) : 0);
                    range <<= 8;
                }
                return 1;
            }
        }

        int decodeBitTree(short[] probs, int numBits) {
            int m = 1;
            for (int i = 0; i < numBits; i++) {
                m = (m << 1) | decodeBit(probs, m);
            }
            return m - (1 << numBits);
        }

        int decodeReverseBitTree(short[] probs, int offset, int numBits) {
            int m = 1;
            int symbol = 0;
            for (int i = 0; i < numBits; i++) {
                int bit = decodeBit(probs, offset + m);
                m = (m << 1) | bit;
                symbol |= (bit << i);
            }
            return symbol;
        }

        int decodeDirectBits(int numBits) {
            int res = 0;
            for (int i = numBits; i > 0; i--) {
                range >>>= 1;
                int t = (code - range) >>> 31;
                code -= range & (t - 1);
                res = (res << 1) | (1 - t);
                if ((range & 0xFF000000) == 0) {
                    code = (code << 8) | (pos < buf.length ? (buf[pos++] & 0xFF) : 0);
                    range <<= 8;
                }
            }
            return res;
        }
    }

    private static final class LzmaLenDecoder {
        private final short[] choice = initProbs(2);
        private final short[][] lowCoder;
        private final short[][] midCoder;
        private final short[] highCoder = initProbs(1 << 8);

        LzmaLenDecoder(int numPosStates) {
            lowCoder = new short[numPosStates][];
            midCoder = new short[numPosStates][];
            for (int i = 0; i < numPosStates; i++) {
                lowCoder[i] = initProbs(1 << 3);
                midCoder[i] = initProbs(1 << 3);
            }
        }

        int decode(LzmaRangeDecoder rc, int posState) {
            if (rc.decodeBit(choice, 0) == 0) {
                return rc.decodeBitTree(lowCoder[posState], 3);
            }
            if (rc.decodeBit(choice, 1) == 0) {
                return 8 + rc.decodeBitTree(midCoder[posState], 3);
            }
            return 16 + rc.decodeBitTree(highCoder, 8);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BINARY IO HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private static String readCString(byte[] data, int[] posRef) {
        int start = posRef[0];
        int end = start;
        while (end < data.length && data[end] != 0) {
            end++;
        }
        String s = new String(data, start, end - start, StandardCharsets.UTF_8);
        posRef[0] = end + 1;
        return s;
    }

    private static int readIntBE(byte[] data, int[] posRef) {
        int p = posRef[0];
        posRef[0] += 4;
        return ((data[p] & 0xFF) << 24)
                | ((data[p + 1] & 0xFF) << 16)
                | ((data[p + 2] & 0xFF) << 8)
                | (data[p + 3] & 0xFF);
    }

    private static int readUShortBE(byte[] data, int[] posRef) {
        int p = posRef[0];
        posRef[0] += 2;
        return ((data[p] & 0xFF) << 8) | (data[p + 1] & 0xFF);
    }

    private static long readLongBE(byte[] data, int[] posRef) {
        long hi = readIntBE(data, posRef) & 0xFFFFFFFFL;
        long lo = readIntBE(data, posRef) & 0xFFFFFFFFL;
        return (hi << 32) | lo;
    }

    private static int readIntLE(byte[] data, int p) {
        return (data[p] & 0xFF)
                | ((data[p + 1] & 0xFF) << 8)
                | ((data[p + 2] & 0xFF) << 16)
                | ((data[p + 3] & 0xFF) << 24);
    }

    private static String readAlignedStringLE(byte[] data, int[] posRef) {
        int len = readIntLE(data, posRef[0]);
        posRef[0] += 4;
        if (len <= 0) return "";
        String s = new String(data, posRef[0], len, StandardCharsets.UTF_8);
        posRef[0] += (len + 3) & ~3;
        return s;
    }

    private static void writeAlignedStringLE(ByteArrayOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeIntLE(out, b.length);
        out.write(b);
        int pad = ((b.length + 3) & ~3) - b.length;
        for (int i = 0; i < pad; i++) out.write(0);
    }

    private static void writeIntLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static void writeIntBE(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeShortBE(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeLongBE(ByteArrayOutputStream out, long v) {
        writeIntBE(out, (int) (v >>> 32));
        writeIntBE(out, (int) v);
    }

    private static void writeCString(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    private static int indexOf(byte[] data, byte[] needle, int fromIndex) {
        outer:
        for (int i = Math.max(0, fromIndex); i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] readAllBytes(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
        }
        return buf;
    }

    private static void writeAllBytes(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
            out.flush();
        }
    }

    // CLI entrypoint for host-side verification tests
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: UnityBundlePatcher <src.unity3d> <dst.unity3d> [targetName]");
            System.exit(1);
        }
        File src = new File(args[0]);
        File dst = new File(args[1]);
        String targetName = args.length >= 3 ? args[2] : dst.getName();
        boolean ok = patchBundleContainer(src, dst, targetName, null);
        if (!ok) {
            System.err.println("FAILED to patch " + src);
            System.exit(2);
        }
        System.out.println("PATCHED_OK: " + dst.getAbsolutePath() + " (" + dst.length() + " bytes)");
    }
}
