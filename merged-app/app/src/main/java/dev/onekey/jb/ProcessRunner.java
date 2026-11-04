package dev.onekey.jb;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import roro.stellar.StellarRemoteProcess;

/** 同时排空输出和错误；输入投放也受同一个超时限制。 */
final class ProcessRunner {
    static final class Result {
        final int code;
        final String output;
        Result(int code, String output) { this.code = code; this.output = output; }
    }

    static Result await(Process process, InputStream input, long seconds, Consumer<String> sink) {
        StringBuilder output = new StringBuilder();
        AtomicReference<Exception> error = new AtomicReference<>();
        Consumer<String> collect = line -> {
            synchronized (output) {
                int remaining = 65536 - output.length();
                if (remaining > 0) output.append((line + "\n"), 0, Math.min(remaining, line.length() + 1));
            }
            if (sink != null) sink.accept(line);
        };
        try {
            Thread out = drain(process.getInputStream(), collect, error);
            Thread err = drain(process.getErrorStream(), collect, error);
            Thread writer = new Thread(() -> {
                try (InputStream in = input; OutputStream stdin = process.getOutputStream()) {
                    if (in != null) {
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = in.read(buffer)) != -1) stdin.write(buffer, 0, count);
                    }
                } catch (Exception e) { error.compareAndSet(null, e); }
            }, "process-input");
            writer.setDaemon(true);
            writer.start();
            int code = -1;
            try {
                if (!waitFor(process, seconds, TimeUnit.SECONDS)) {
                    code = 124;
                    collect.accept("命令执行超时");
                } else {
                    code = process.exitValue();
                    writer.join(500);
                    out.join(500);
                    err.join(500);
                    if (writer.isAlive() || out.isAlive() || err.isAlive()) code = 124;
                    if (error.get() != null) {
                        collect.accept("命令输入输出失败：" + error.get());
                        if (code == 0) code = -1;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                collect.accept("任务已中断");
            }
            synchronized (output) { return new Result(code, output.toString()); }
        } finally {
            stop(process);
        }
    }

    static Thread drain(InputStream input, Consumer<String> sink, AtomicReference<Exception> error) {
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) sink.accept(line);
            } catch (Exception e) { error.compareAndSet(null, e); }
        }, "process-output");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    static void stop(Process process) {
        if (process == null) return;
        try { if (isAlive(process)) process.destroyForcibly(); } catch (Exception ignored) { }
    }

    // Binder 会丢失 IllegalThreadStateException 子类型，必须使用 Stellar 自带的状态接口。
    static boolean waitFor(Process process, long timeout, TimeUnit unit) throws InterruptedException {
        return process instanceof StellarRemoteProcess
                ? ((StellarRemoteProcess) process).waitForTimeout(timeout, unit)
                : process.waitFor(timeout, unit);
    }

    static boolean isAlive(Process process) {
        return process instanceof StellarRemoteProcess
                ? ((StellarRemoteProcess) process).alive() : process.isAlive();
    }
}
