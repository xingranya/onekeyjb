# AI 知识库

把 `knowledge/*.md` 转成两种机器可读格式，供 AI 助手（Dify / FastGPT / 自建 RAG / 各家知识库）导入。

## 文件

| 文件 | 格式 | 结构 |
| --- | --- | --- |
| `onekey-kb.json` | `paragraphs` | 每个 `## ` 小节一条，适合段落召回 |
| `onekey-kb.docs.json` | `docs` | 每篇 Markdown 一条，保留完整章节，适合整篇注入 |

## 结构说明

`onekey-kb.json`

```json
{
  "name": "OneKey 越狱工具知识库",
  "version": "2.17.16",
  "generated_at": "2026-11-04",
  "count": 42,
  "paragraphs": [
    {
      "id": "03-管理器适配#支持的五个管理器",
      "title": "03 · 管理器适配 · 支持的五个管理器",
      "content": "……",
      "tags": ["03-管理器适配", "管理器"],
      "source": "knowledge/03-管理器适配.md"
    }
  ]
}
```

`onekey-kb.docs.json`

```json
{
  "name": "OneKey 越狱工具知识库",
  "version": "2.17.16",
  "generated_at": "2026-11-04",
  "docs": [
    { "title": "03 · 管理器适配", "content": "……（整篇）", "source": "knowledge/03-管理器适配.md" }
  ]
}
```

## 重新生成

改完 Markdown 后：

```bash
python3 knowledge/ai-kb/build_kb.py
```

脚本只读 `knowledge/*.md`（不含本 README 与 `ai-kb/` 自身），幂等，输出带缩进的 JSON，方便 diff。

## 使用建议

- 段落式适合"管理器版本不匹配怎么办"这类精确问题；
- 文档式适合"讲一下 98260 的完整流程"这类需要上下文的提问；
- 导入时把 `title` 作为标题、`content` 作为正文；`tags` 可以映射成分类。
