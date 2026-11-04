package dev.onekey.jb;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Map;

/** Desktop test harness for Prep (not shipped in the APK). */
public final class PrepMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: PrepMain solve <blockedDir> <syms.txt>");
            System.out.println("       PrepMain patch <template.ko> <syms.txt> <slide> <out.ko>");
            return;
        }
        String cmd = args[0];
        Map<String, Long> syms = Prep.loadSymbols(new FileInputStream(args[2]));
        System.out.println("symbols: " + syms.size());
        if (cmd.equals("solve")) {
            File dir = new File(args[1]);
            File[] bins = new File[8];
            for (int i = 0; i < 8; i++) bins[i] = new File(dir, "bl_" + i + ".bin");
            File text = new File(dir, "bl_text.txt");
            Prep.SlideResult r = Prep.solveSlide(bins, text, syms);
            System.out.println("ok=" + r.ok + " slide=" + Long.toHexString(r.slide)
                    + " selinux_state=" + Long.toHexString(r.selinuxState)
                    + " prepare_creds=" + Long.toHexString(r.prepareCreds)
                    + " votes=" + r.votes + " anchors=" + r.anchors + " observed=" + r.observed
                    + (r.error.isEmpty() ? "" : " err=" + r.error));
        } else if (cmd.equals("patch")) {
            if (args.length < 5) return;
            byte[] ko = Prep.readAll(new File(args[1]));
            long slide = Long.parseUnsignedLong(args[3].replace("0x", ""), 16);
            int n = Prep.patchKo(ko, syms, slide);
            try (FileOutputStream out = new FileOutputStream(args[4])) { out.write(ko); }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(ko);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            System.out.println("patched=" + n + " sha256=" + sb);
        }
    }
}
