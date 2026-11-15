#!/bin/bash
# 把 onekeyjb 快照同步到 CNB 私有备份仓（backupgit）。
# 用法：CNB_TOKEN=xxxx ./sync_backup_repo.sh
set -u
: "${CNB_TOKEN:?需要先 export CNB_TOKEN}"
D=${TMPDIR:-/tmp}/bkgit
rm -rf "$D"
git clone --depth 1 --filter=blob:none --sparse "https://cnb:${CNB_TOKEN}@cnb.cool/xingranya/backupgit.git" "$D" 2>&1 | tail -3
cd "$D" || exit 2
git sparse-checkout set onekeyjb 2>&1 | tail -2
echo "sparse size: $(du -sm onekeyjb 2>/dev/null | cut -f1) MB"
EXCL="--exclude=.git --exclude=node_modules --exclude=dist --exclude=build --exclude=out --exclude=target --exclude=.next --exclude=.venv --exclude=__pycache__ --exclude=.gradle --exclude=.idea --exclude=.vscode --exclude=.codegraph --exclude=vendor --exclude=artifacts --exclude=.DS_Store"
rsync -a --delete "$HOME/Downloads/onekeyjb-private/" "$D/onekeyjb/" $EXCL
find "$D/onekeyjb" -type f -size +15M 2>/dev/null | while read f; do
  case "$f" in *.ttf|*.otf|*.woff|*.woff2) ;; *) rm -f "$f" ;; esac
done
echo "after: $(du -sm onekeyjb | cut -f1) MB"
git add -A onekeyjb
VER=$(grep -m1 'versionName' "$HOME/Downloads/onekeyjb-private/merged-app/app/build.gradle.kts" | sed 's/.*"\(.*\)".*/\1/')
git -c user.email=dev@local -c user.name=xingranya commit -q -m "backup: refresh onekeyjb snapshot to ${VER:-latest}" || { echo NOTHING_TO_COMMIT; exit 0; }
git config http.postBuffer 1048576000
git -c http.lowSpeedLimit=0 -c http.lowSpeedTime=999999 push origin HEAD:main 2>&1 | tail -3
echo SYNC_DONE
