#!/system/bin/sh
# 只补命令搜索入口；实际提权仍由 /system/bin/su 校验调用应用的授权。
set -eu
PATH=/system/bin:/system/xbin:/system_ext/bin
export PATH
[ "$(id -u)" = 0 ] || exit 1

probe() {
    entry=$(command -v su 2>/dev/null) || return 1
    value=$(timeout 10 su -c 'id -u') || return 1
    [ "$value" = 0 ] || return 1
    echo "默认 su 入口通过：$entry，uid=$value"
}
if probe; then exit 0; fi

target=
for candidate in /system/xbin /system_ext/bin; do
    [ -d "$candidate" ] && [ ! -L "$candidate" ] || continue
    candidate=$(readlink -f "$candidate")
    # 不覆盖其他 su，也不遮住已有的目录挂载或子挂载。
    [ ! -e "$candidate/su" ] && [ ! -L "$candidate/su" ] || continue
    if awk -v p="$candidate" '$2 == p || index($2, p "/") == 1 { found=1 } END { exit !found }' /proc/self/mounts; then
        continue
    fi
    target=$candidate
    break
done
if [ -z "$target" ]; then
    echo "默认 su 入口未修复：没有可保留现有内容的独立搜索目录"
    exit 1
fi
base=/data/adb/onekey-su-compat
umask 077
mkdir -p "$base/bin"
chmod 700 "$base"
chmod "$(stat -c '%a' "$target")" "$base/bin"
chown "$(stat -c '%u:%g' "$target")" "$base/bin"
cp "$(dirname "$0")/su_forward" "$base/bin/su"
chmod 755 "$base/bin/su"
label=$(ls -Zd "$target" | awk '{print $1}')
case "$label" in u:object_r:*:s0) ;; *) echo "无法确认目标目录安全上下文"; exit 1 ;; esac
chcon "$label" "$base/bin" "$base/bin/su"

# 只挂载只读叠加层；原目录作为 lower 保留，重启后自动消失。
if ! mount -t overlay onekey-su -o "ro,lowerdir=$base/bin:$target" "$target"; then
    echo "默认 su 入口未修复：设备不支持本次临时叠加挂载"
    exit 1
fi
if probe; then
    echo "默认 su 兼容入口已安装；目标应用需要彻底关闭后重新打开"
    exit 0
fi
# 检查失败只撤销刚添加且仍由本脚本独占的挂载。
if awk -v p="$target" '$2 == p { count++; own=($1 == "onekey-su" && $3 == "overlay") } END { exit !(count == 1 && own) }' /proc/self/mounts; then
    umount "$target" || echo "兼容挂载未能撤销，可通过重启清除"
fi
echo "默认 su 入口仍不可用；不报告修复成功"
exit 1
