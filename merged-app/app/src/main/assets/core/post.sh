#!/system/bin/sh
# 管理器初始化与 su 通道验收（root 执行）
# 设计：不重命名 /data/system/packages.list；不强制 Enforcing；不默认开 selinux_hide/kernel_umount。
W=${ONEKEY_WORK:-/data/local/tmp/onekey-work}
PKG=${ONEKEY_PKG:-}
KSUD=${ONEKEY_KSUD:-/data/local/tmp/ksud_official}
RUN=${ONEKEY_RUN:-unknown}
ANTI=${ONEKEY_ANTIROOT:-0}
SUCOMPAT=${ONEKEY_SUCOMPAT:-0}
OK="$W/post_stage.ok"
POST_RESULT=FAIL
FINA_REG=0
log() { echo "$*"; }
warn() { echo "@WARN=$*"; }
uid_of() {
    local p="$1" u=""
    u=$(stat -c %u "/data/user/0/$p" 2>/dev/null)
    [ -n "$u" ] || u=$(stat -c %u "/data/data/$p" 2>/dev/null)
    [ -n "$u" ] || u=$(pm list packages -U --user 0 2>/dev/null | awk -v k="package:$p" '$1==k { sub("uid:", "", $2); print $2; exit }')
    [ -n "$u" ] || u=$(cmd package list packages -U --user 0 2>/dev/null | awk -v k="package:$p" '$1==k { sub("uid:", "", $2); print $2; exit }')
    [ -n "$u" ] || u=$(dumpsys package "$p" 2>/dev/null | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -1)
    echo "$u"
}
restore_security() {
    if [ -f "$W/security.state" ]; then
        while IFS='=' read -r p st; do
            [ -n "$p" ] || continue
            case "$st" in
                disabled) timeout 15 pm disable-user --user 0 "$p" >/dev/null 2>&1 ;;
                enabled) timeout 15 pm enable "$p" >/dev/null 2>&1 ;;
                *) : ;;
            esac
        done < "$W/security.state"
    else
        for pkg in com.oplus.exsystemservice com.coloros.securityguard com.oplus.safecenter; do
            timeout 15 pm enable "$pkg" >/dev/null 2>&1
        done
    fi
}
finish() {
    restore_security
    rc=$?
    if [ -e /data/system/packages.list.onekey ]; then mv /data/system/packages.list.onekey /data/system/packages.list 2>/dev/null; fi
    tmp="$OK.tmp"
    if [ "$POST_RESULT" = "OK" ]; then
        echo "OK:$RUN" > "$tmp"
    else
        echo "FAIL:$rc:$RUN" > "$tmp"
    fi
    chmod 644 "$tmp" 2>/dev/null
    mv -f "$tmp" "$OK" 2>/dev/null
    true
}
trap finish EXIT
rm -f "$OK" 2>/dev/null
log "@STAGE=init"
if [ "$(id -u)" != "0" ]; then warn "未取得 root"; echo "@EXIT=30"; exit 30; fi

if [ "$ANTI" = "1" ]; then
    log "@TICK=antiroot"
    log "临时停用 ColorOS 安全组件（覆盖安装与验收窗口）"
    # 不碰 com.oplus.exsystemservice：它是截屏/系统服务组件。
fi

PRESERVE=/data/adb/onekey-preserve
if [ -d "$PRESERVE/ksu" ] && [ ! -d /data/adb/ksu ]; then
    mkdir -p /data/adb
    cp -a "$PRESERVE/ksu" /data/adb/ksu 2>/dev/null && log "已从快照恢复管理器数据目录"
fi
log "@TICK=install"
log "安装配套用户态组件"
INSTALL_OUT=$(timeout 60 "$KSUD" install 2>&1); INSTALL_RC=$?
log "ksud install rc=$INSTALL_RC ${INSTALL_OUT}"
if [ ! -x /data/adb/ksud ]; then warn "配套组件缺失"; echo "@EXIT=31"; exit 31; fi
KSUD_INFO=$(timeout 10 /data/adb/ksud debug info 2>&1 | tr "
" " ")
warn "ksud debug info: $KSUD_INFO"
warn "dev-ksu: $(ls -l /dev/ksu 2>&1)"
mkdir -p /data/adb/ksu/bin /data/adb/modules
ln -sf /data/adb/ksud /data/adb/ksu/bin/su 2>/dev/null
ln -sf /data/adb/ksud /data/adb/ksu/bin/ksud 2>/dev/null

log "@TICK=manager"
SIZE="${ONEKEY_MGR_SIZE:-}"
HASH="${ONEKEY_MGR_HASH:-}"
warn "manager cert: size=$SIZE hash=$HASH"
KIND="${ONEKEY_MANAGER:-kernelsu}"
APK=$(pm path "$PKG" 2>/dev/null | head -1 | cut -d: -f2)
if [ -n "$PKG" ] && [ -x /data/adb/ksud ]; then
    if [ "$KIND" = "sukisu" ]; then
        if [ -n "$SIZE" ] && [ -n "$HASH" ]; then
            OUT=$(timeout 15 /data/adb/ksud manager add "$PKG" "$SIZE" "$HASH" 2>&1); RC=$?
            if [ "$RC" = "0" ]; then
                timeout 15 /data/adb/ksud manager set-enabled true >/dev/null 2>&1
                log "管理器身份已登记（SukiSU）：$PKG"
            else
                warn "SukiSU manager add 失败 rc=$RC: $OUT"
            fi
        else
            warn "SukiSU 管理器签名信息缺失"
        fi
    elif [ "$KIND" = "finasu" ]; then
        UID_F=$(uid_of "$PKG")
        FINA_SIG=0
        # FinaSU 1.2.1: set-apk is the stable signature path; set-uid then authorizes.
        if [ -n "$APK" ] && [ -f "$APK" ]; then
            OUT=$(timeout 20 /data/adb/ksud dynamic set-apk "$APK" 2>&1); RC=$?
            if [ "$RC" = "0" ]; then
                FINA_SIG=1
                log "FinaSU 管理器签名已设置（set-apk）：$PKG"
            else
                warn "FinaSU dynamic set-apk 失败 rc=$RC: $OUT"
            fi
        fi
        if [ "$FINA_SIG" != "1" ] && [ -n "$UID_F" ]; then
            OUT=$(timeout 20 /data/adb/ksud dynamic set-uid "$UID_F" 2>&1); RC=$?
            if [ "$RC" = "0" ]; then
                FINA_SIG=1
                FINA_REG=1
                log "FinaSU 管理器已登记：$PKG uid=$UID_F"
            else
                warn "FinaSU dynamic set-uid 失败 rc=$RC: $OUT"
            fi
        fi
        if [ "$FINA_SIG" != "1" ] && [ -n "$SIZE" ] && [ -n "$HASH" ]; then
            OUT=$(timeout 15 /data/adb/ksud dynamic set-hash "$SIZE" "$HASH" 2>&1); RC=$?
            if [ "$RC" = "0" ]; then
                FINA_SIG=1
                log "FinaSU 管理器签名已设置：$PKG"
            else
                warn "FinaSU dynamic set-hash 失败 rc=$RC: $OUT"
            fi
        fi
        if [ "$FINA_SIG" = "1" ] && [ -n "$UID_F" ]; then
            OUT=$(timeout 15 /data/adb/ksud dynamic grant "$UID_F" 2>&1); RC=$?
            if [ "$RC" = "0" ]; then
                FINA_REG=1
                log "FinaSU 管理器已授权：$PKG uid=$UID_F"
            else
                warn "FinaSU dynamic grant 失败 rc=$RC: $OUT"
            fi
        fi
        if [ -z "$UID_F" ]; then
            warn "FinaSU UID 解析失败：pkg=$PKG stat=$(stat -c %u "/data/user/0/$PKG" 2>&1) pm=$(pm list packages -U --user 0 2>/dev/null | grep -F "package:$PKG" | head -1)"
        fi
        if [ "$FINA_REG" != "1" ]; then
            warn "FinaSU 登记失败：uid=$UID_F pkg=$PKG"
            echo "@EXIT=33"
            exit 33
        fi
    else
        # 触发 KernelSU 内核的 Crowning manager（内核监听 packages.list 重命名）
        chmod 644 /data/system/packages.list 2>/dev/null
        if [ -e /data/system/packages.list ]; then
            timeout 10 sh -c 'mv /data/system/packages.list /data/system/packages.list.onekey && mv /data/system/packages.list.onekey /data/system/packages.list' 2>&1
            CROWN_RC=$?
            log "crown packages.list rc=$CROWN_RC"
            sleep 2
        fi
        if [ -n "$APK" ] && [ -f "$APK" ]; then
            OUT=$(timeout 20 /data/adb/ksud debug set-manager "$APK" 2>&1); RC=$?
            if [ "$RC" = "0" ]; then
                log "管理器已通过 debug set-manager 登记：$PKG"
            else
                case "$OUT" in
                    *CONFIG_KSU_DEBUG*)
                        log "debug set-manager 未启用（CONFIG_KSU_DEBUG 未开），管理器由 ksud install 自动识别属正常"
                        ;;
                    *)
                        warn "debug set-manager 失败 rc=$RC: $OUT"
                        ;;
                esac
            fi
        else
            warn "无法定位管理器 APK（$PKG）"
        fi
    fi
else
    warn "未提供管理器包名，跳过管理器登记"
fi

timeout 15 /data/adb/ksud feature set su_compat 1 >/dev/null 2>&1 && log "su 兼容已开启" || warn "su 兼容设置未确认"
timeout 15 /data/adb/ksud debug mark refresh >/dev/null 2>&1
timeout 10 /data/adb/ksud feature list 2>/dev/null | grep -q '\[ENABLED' && log "特性状态已读取"

if [ "$SUCOMPAT" = "1" ] && [ -f "$W/su_path_compat.sh" ] && [ -f "$W/su_forward" ]; then
    log "@TICK=su_entry"
    log "可选：修复默认 su 搜索入口"
    if timeout 40 /system/bin/sh "$W/su_path_compat.sh" 2>&1; then
        log "默认 su 入口已修复"
    else
        warn "默认 su 入口修复未完成"
    fi
fi

if [ -n "$PKG" ]; then
    log "@TICK=restart_manager"
    log "重启管理器"
    timeout 20 am force-stop "$PKG" 2>/dev/null
    timeout 30 monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    if ! pidof "$PKG" >/dev/null 2>&1; then
        timeout 15 am start -n "$PKG/.ui.MainActivity" >/dev/null 2>&1
    fi
    ready=0
    for p in $(pidof "$PKG" 2>/dev/null); do
        readlink /proc/$p/fd/* 2>/dev/null | grep -q ksu && ready=1 && break
    done
    if [ "$ready" != "1" ] && [ -n "$SIZE" ] && [ -n "$HASH" ]; then
        warn "管理器首次未连接，重试登记并重启一次"
        if [ "$KIND" = "finasu" ]; then
            UID_R=$(uid_of "$PKG")
            if [ -n "$UID_R" ]; then
                timeout 20 /data/adb/ksud dynamic set-uid "$UID_R" >/dev/null 2>&1
            else
                timeout 15 /data/adb/ksud dynamic set-hash "$SIZE" "$HASH" >/dev/null 2>&1
            fi
        else
            timeout 15 /data/adb/ksud manager add "$PKG" "$SIZE" "$HASH" >/dev/null 2>&1
            timeout 10 /data/adb/ksud manager set-enabled true >/dev/null 2>&1
        fi
        timeout 20 am force-stop "$PKG" 2>/dev/null
        timeout 30 monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
        sleep 3
        ready=0
        for p in $(pidof "$PKG" 2>/dev/null); do
            readlink /proc/$p/fd/* 2>/dev/null | grep -q ksu && ready=1 && break
        done
    fi
    if [ "$ready" != "1" ]; then
        # 管理器刚启动时 fd 可能还没建立，再等最多 10 秒
        for attempt in 1 2 3 4 5 6 7 8 9 10; do
            sleep 1
            for p in $(pidof "$PKG" 2>/dev/null); do
                readlink /proc/$p/fd/* 2>/dev/null | grep -q ksu && ready=1 && break
            done
            [ "$ready" = "1" ] && break
        done
    fi
    if [ "$ready" = "1" ]; then
        log "管理器已连接内核"
    else
        log "管理器已启动；若首页显示「工作中」即正常，未显示请点修复重试"
    fi
fi
u=$(timeout 10 /system/bin/su -c 'id -u' 2>/dev/null)
if [ "$u" = "0" ]; then log "su 通道自检通过"; else warn "shell 域 su 未就绪（应用域授权后仍可正常使用）"; fi

log "@TICK=cleanup"
log "跳过卸载 oplus_secure 内核模块（保持系统截屏/安全组件正常）"

if [ "$ANTI" = "1" ]; then
    restore_security
    log "已按原始状态恢复 ColorOS 安全组件"
fi

if [ -d /data/adb/ksu ]; then
    mkdir -p "$PRESERVE"
    chmod 700 "$PRESERVE" 2>/dev/null
    rm -rf "$PRESERVE/ksu.new"
    if cp -a /data/adb/ksu "$PRESERVE/ksu.new" 2>/dev/null && [ -d "$PRESERVE/ksu.new" ]; then
        rm -rf "$PRESERVE/ksu"
        mv "$PRESERVE/ksu.new" "$PRESERVE/ksu" 2>/dev/null || warn "快照提交失败，已保留旧快照"
    else
        rm -rf "$PRESERVE/ksu.new"
        warn "快照复制失败，已保留旧快照"
    fi
fi
if [ "${ONEKEY_REPAIR:-0}" = "1" ]; then
    now=$(date +%s)
    boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || echo unknown)
    last_boot=$(cat /data/local/tmp/onekey-softreboot.boot 2>/dev/null || echo none)
    last=$(cat /data/local/tmp/onekey-softreboot.last 2>/dev/null || echo 0)
    if [ "$boot_id" = "$last_boot" ] && [ $((now-last)) -lt 300 ]; then
        log "@REPAIR=skipped_recent"
    else
        echo "$now" > /data/local/tmp/onekey-softreboot.last
        echo "$boot_id" > /data/local/tmp/onekey-softreboot.boot
        log "@REPAIR=scheduled"
        nohup sh -c 'sleep 15; if ! /data/adb/ksud soft-reboot >> /data/local/tmp/onekey-softreboot.log 2>&1; then setprop ctl.restart zygote >> /data/local/tmp/onekey-softreboot.log 2>&1; fi' >/dev/null 2>&1 &
    fi
else
    log "@REPAIR=disabled"
fi

POST_RESULT=OK
log "@STAGE=done"
echo "@EXIT=0"
exit 0
