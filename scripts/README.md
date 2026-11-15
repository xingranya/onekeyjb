# scripts · 项目脚本

一次性与可复用脚本集中在此，按用途分类。**不要放 /tmp**：换会话就丢了。

## 目录

| 目录 | 用途 |
| --- | --- |
| `build/` | 构建期批量改动（清理死 import、统一形状） |
| `diagnose/` | 只读检查（弹窗、形状、布局统计） |
| `release/` | 发版：CNB 上传、提交信息、备份仓同步 |
| `kb/` | 知识库：版本史更新、合集合并 |
| `ui-refactor/` | 界面重构的批处理脚本（含历史记录） |
| `upstream/` | 上游（Shark）对照脚本 |

## 约定

1. **凭据只走环境变量**：`CNB_TOKEN` 等一律 `os.environ`，脚本内不出现真实值。
2. 每个脚本首行写明用途与用法。
3. 脚本改动界面代码时，锚点用**函数签名或唯一注释**，绝不按行号切片。
4. 用过的临时脚本先归档到这里，再删 /tmp 副本。

## 常用

```bash
# 发版到 CNB（需先 export CNB_TOKEN=...）
python3 scripts/release/publish_cnb_latest.py

# 合并知识库合集
python3 scripts/kb/merge_knowledge.py

# 检查界面是否还残留自绘形状
python3 scripts/diagnose/count_shapes.py
```
