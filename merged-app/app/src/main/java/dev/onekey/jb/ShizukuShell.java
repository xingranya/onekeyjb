package dev.onekey.jb;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import roro.stellar.Stellar;

/**
 * Minimal privileged shell channel.
 *
 * On ColorOS 17 (c17) the original Shizuku service reports "ADB 权限受限" and
 * refuses to authorize apps, so this app talks to **Stellar**
 * (github.com/roro2239/Stellar, a Shizuku deep fork) instead:
 *   - Stellar.checkSelfPermission("stellar") / requestPermission("stellar", code)
 *   - Stellar.newProcess(cmd, env, dir)  -> runs as shell uid (2000)
 *
 * The rest of the app is written against this thin wrapper, so switching the
 * privileged backend only touched this file.
 */
public final class ShizukuShell {

    public static final String TMP = "/data/local/tmp";
    public static final String WORK = "/data/local/tmp/onekey";
    public static final String PERMISSION = "stellar";

    private ShizukuShell() { }

    public static boolean available() {
        try { return Stellar.INSTANCE.pingBinder(); } catch (Throwable t) { return false; }
    }

    public static boolean hasPermission() {
        try {
            if (!available()) return false;
            return Stellar.INSTANCE.checkSelfPermission(PERMISSION);
        } catch (Throwable t) {
            return false;
        }
    }

    public static int serviceUid() {
        try { return Stellar.INSTANCE.getUid(); } catch (Throwable t) { return -1; }
    }

    public static void requestPermission(int code) {
        try {
            if (available()) Stellar.INSTANCE.requestPermission(PERMISSION, code);
        } catch (Throwable ignored) { }
    }

    /** Spawn a process as shell uid through Stellar. */
    public static Process newProcess(String[] cmd, String[] env, String dir) throws Exception {
        return Stellar.INSTANCE.newProcess(cmd, env, dir);
    }

    private static String[] env() {
        return new String[]{
                "PATH=/system/bin:/system/xbin:/system_ext/bin:/data/adb/ksu/bin",
                "HOME=" + TMP,
                "TMPDIR=" + TMP,
        };
    }

    public static Process shell(String script) throws Exception {
        return newProcess(new String[]{"sh", "-c", script}, env(), TMP);
    }

    public static final class Result {
        public final int code;
        public final String output;
        Result(int code, String output) { this.code = code; this.output = output; }
    }

    public interface LineSink { void line(String s); }

    public static Result run(String script, long timeoutSec) {
        return runStreaming(script, timeoutSec, null);
    }

    public static Result runStreaming(String script, long timeoutSec, LineSink sink) {
        try {
            ProcessRunner.Result result = ProcessRunner.await(shell(script), null, timeoutSec,
                    sink == null ? null : sink::line);
            return new Result(result.code, result.output);
        } catch (Exception e) {
            return new Result(-1, "Stellar 执行失败：" + e);
        }
    }

    /** Long-lived process with a background line reader (perf_cred / v42). */
    public static final class LineProc {
        public final Process proc;
        public final List<String> lines = new ArrayList<>();
        LineProc(Process p, LineSink sink) {
            this.proc = p;
            java.util.concurrent.atomic.AtomicReference<Exception> error = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.function.Consumer<String> collect = line -> {
                synchronized (lines) {
                    if (lines.size() == 2048) lines.remove(0);
                    lines.add(line);
                }
                if (sink != null) sink.line(line);
            };
            ProcessRunner.drain(p.getInputStream(), collect, error);
            ProcessRunner.drain(p.getErrorStream(), collect, error);
            try { p.getOutputStream().close(); } catch (IOException ignored) { }
        }

        public boolean isRunning() { return ProcessRunner.isAlive(proc); }

        public String waitFor(String needle, long timeoutSec) throws InterruptedException {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec);
            while (System.nanoTime() < end) {
                synchronized (lines) {
                    for (String line : lines) if (line.contains(needle)) return line;
                }
                if (!isRunning()) return null;
                Thread.sleep(200);
            }
            return null;
        }

        public String tail(int n) {
            StringBuilder sb = new StringBuilder();
            synchronized (lines) {
                for (int i = Math.max(0, lines.size() - n); i < lines.size(); i++) {
                    sb.append(lines.get(i)).append('\n');
                }
            }
            return sb.toString();
        }

        public void destroy() {
            ProcessRunner.stop(proc);
        }
    }

    public static LineProc start(String script, LineSink sink) throws Exception {
        return new LineProc(shell(script), sink);
    }

    // ---------------- staging ----------------

    public static Result stageAsset(Context ctx, String asset, String dest, int mode) {
        try (InputStream in = ctx.getAssets().open(asset)) {
            return stageBytes(Prep.readAsset(in), dest, mode);
        } catch (Exception e) {
            return new Result(-1, "读取组件失败：" + asset + " " + e);
        }
    }

    public static Result stageBytes(byte[] data, String dest, int mode) {
        // 临时文件写完再替换，避免 cat 失败后 chmod 成功掩盖错误。
        String script = stageScript(dest, mode, data.length);
        try (InputStream in = new ByteArrayInputStream(data)) {
            ProcessRunner.Result result = ProcessRunner.await(shell(script), in, 60, null);
            return new Result(result.code, result.code == 0 ? "已投放 " + dest : "投放失败：" + result.output);
        } catch (Exception e) {
            return new Result(-1, "投放失败：" + dest + " " + e);
        }
    }

    /** Stage bytes through the already-running rootd process (same mount namespace). */
    public static Result stageThroughRootd(byte[] data, String dest, int mode) {
        String script = stageScript(dest, mode, data.length);
        try (InputStream in = new ByteArrayInputStream(data)) {
            Process p = newProcess(
                    new String[]{"/data/local/tmp/pc98260_su", "-i", "-c", script},
                    env(), TMP);
            ProcessRunner.Result result = ProcessRunner.await(p, in, 60, null);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("@@PC98260_STATUS:(-?\\d+):(\\d+)@@")
                    .matcher(result.output);
            int code = -1;
            if (m.find()) {
                try { code = Integer.parseInt(m.group(1)); } catch (Exception ignored) { }
            }
            if (code == 0) return new Result(0, "已通过 rootd 投放 " + dest);
            return new Result(code, "rootd 投放失败：status=" + code + " " + result.output);
        } catch (Exception e) {
            return new Result(-1, "rootd 投放失败：" + dest + " " + e);
        }
    }

    static String stageScript(String dest, int mode, int size) {
        String quoted = "'" + dest.replace("'", "'\"'\"'") + "'";
        return "set -e; mkdir -p \"$(dirname " + quoted + ")\"; dst=" + quoted
                + "; tmp=\"$dst.tmp.$$\"; trap 'rm -f \"$tmp\"' EXIT; "
                + "cat > \"$tmp\"; [ \"$(wc -c < \"$tmp\")\" -eq " + size + " ]; "
                + "chmod " + mode + " \"$tmp\"; mv -f \"$tmp\" \"$dst\"";
    }

    /** App-side read of a world-readable file under /data/local/tmp (dirs are 0755). */
    public static byte[] readDeviceFile(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) throw new IOException("not a file: " + path);
        long len = f.length();
        if (len <= 0 || len > (64L << 20)) throw new IOException("bad size " + len);
        byte[] data = new byte[(int) len];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            if (off != data.length) throw new IOException("short read " + path);
        }
        return data;
    }

}
