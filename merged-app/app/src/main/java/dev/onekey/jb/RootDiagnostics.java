package dev.onekey.jb;

import java.nio.charset.StandardCharsets;

/** 直接从应用 UID 验证 su，避免 Stellar 的 shell/root 身份掩盖授权问题。 */
public final class RootDiagnostics {
    private RootDiagnostics() { }

    public static final String PROBE = "u=$(id -u) || exit 1; echo ROOT_UID=$u; "
            + "[ \"$u\" = 0 ] || exit 1; id; "
            + "grep -E '^(Uid|Gid|CapEff|Seccomp):' /proc/self/status; "
            + "echo SELINUX=$(cat /proc/self/attr/current); "
            + "if ls /data/adb >/dev/null 2>&1; then echo PRIVATE_READ=1; else echo PRIVATE_READ=0; fi; "
            + "if ls /storage/emulated/0/Android/data >/dev/null 2>&1; "
            + "then echo STORAGE_READ=1; else echo STORAGE_READ=0; fi; "
            + "echo STDOUT_OK; echo STDERR_OK >&2; exit 0\n";

    public static boolean hasLine(String output, String expected) {
        for (String line : output.split("\\r?\\n")) {
            if (expected.equals(line.trim())) return true;
        }
        return false;
    }

    public static boolean suPassed(int code, String output) {
        return code == 0 && hasLine(output, "ROOT_UID=0")
                && hasLine(output, "STDOUT_OK") && hasLine(output, "STDERR_OK");
    }

    public static boolean stagePassed(int code, String output, String runId) {
        return code == 0 && !runId.isEmpty() && output.trim().equals("ROOT_STAGE_OK:" + runId);
    }

    public static String loadedBackend(int code, String info) {
        if (code != 0 || !hasLine(info, "uapi_version: 5")) return null;
        boolean next = hasLine(info, "version: 33355"), suki = hasLine(info, "version: 40994");
        return next == suki ? null : next ? "ksu" : "sukisu";
    }

    public static final class Result {
        public final int code;
        public final String output;
        Result(int code, String output) { this.code = code; this.output = output; }
    }

    public static Result run(String[] command, String input, long timeoutSeconds) {
        try {
            Process process = new ProcessBuilder(command).start();
            ProcessRunner.Result result = ProcessRunner.await(process,
                    new java.io.ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), timeoutSeconds, null);
            return new Result(result.code, result.output);
        } catch (Exception e) {
            return new Result(-1, "执行失败：" + e);
        }
    }

    public static boolean moduleLive(String line) {
        if (line == null) return false;
        String[] fields = line.trim().split("\\s+");
        return fields.length >= 6 && "kernelsu".equals(fields[0]) && "Live".equals(fields[4]);
    }
}
