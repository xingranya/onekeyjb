#!/system/bin/sh
# grab.sh -- 抓 sched_blocked_reason 的 raw（per_cpu/trace_pipe_raw）与 %pS 文本锚点
D=$(cat /data/local/tmp/onekey_dir 2>/dev/null || true)
case "$D" in
    /data/local/tmp/onekey*) ;;
    *) D=/data/local/tmp/onekey ;;
esac
cd "$D" || exit 1
pkill -f "perfetto --txt" 2>/dev/null
rm -f bl_*.bin bl_text.txt c_bl.txt
printf 'duration_ms: 20000\nbuffers { size_kb: 16384 }\ndata_sources { config { name: "linux.ftrace" ftrace_config { ftrace_events: "sched/sched_blocked_reason" buffer_size_kb: 16384 drain_period_ms: 900000 } } }\n' > c_bl.txt
nohup sh -c "cat '$D/c_bl.txt' | perfetto --txt -c - -o /dev/null" >/dev/null 2>&1 &
sleep 2
grep "sched_blocked_reason" /sys/kernel/tracing/trace > bl_text.txt 2>/dev/null
for c in 0 1 2 3 4 5 6 7; do
  cat /sys/kernel/tracing/per_cpu/cpu$c/trace_pipe_raw > bl_$c.bin 2>/dev/null &
done
i=0
while [ $i -lt 120 ]; do ./forker 2 20 >/dev/null 2>&1; i=$((i+1)); done
sleep 1
pkill -f trace_pipe_raw
sleep 0.3
# 文本锚点兜底：perfetto 退出后事件会被 disable，trace 里可能没有残留行
echo 1 > /sys/kernel/tracing/events/sched/sched_blocked_reason/enable 2>/dev/null
echo 1 > /sys/kernel/tracing/tracing_on 2>/dev/null
i=0
while [ $i -lt 120 ]; do ./forker 2 20 >/dev/null 2>&1; i=$((i+1)); done
sleep 0.5
grep "sched_blocked_reason" /sys/kernel/tracing/trace > bl_text.txt 2>/dev/null
chmod 644 bl_*.bin bl_text.txt 2>/dev/null
echo "text_lines=$(wc -l < bl_text.txt 2>/dev/null)"
ls -l bl_*.bin 2>/dev/null | awk '{print $5, $9}'
echo GRAB_DONE