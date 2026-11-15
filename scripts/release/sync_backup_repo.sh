#!/bin/bash
set -u
D=/tmp/bkgit
rm -rf "$D"
TOK = os.environ["CNB_TOKEN"]
git clone --depth 1 --filter=blob:none --sparse "https://cnb:${TOK}@cnb.cool/xingranya/backupgit.git" "$D" 2>&1 | tail -3
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
git -c user.email=dev@local -c user.name=xingranya commit -q -m "backup: refresh onekeyjb snapshot to 2.19.6" || { echo NOTHING_TO_COMMIT; exit 0; }
git config http.postBuffer 1048576000
git -c http.lowSpeedLimit=0 -c http.lowSpeedTime=999999 push origin HEAD:main 2>&1 | tail -3
echo SYNC_DONE
