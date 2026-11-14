#!/usr/bin/env python3
"""由 knowledge/*.md 生成 AI 知识库 JSON（paragraphs 与 docs 两种格式）。"""
import json
import os
import re
import time

HERE = os.path.dirname(os.path.abspath(__file__))
KNOWLEDGE = os.path.dirname(HERE)
VERSION = '2.20.0'
NAME = 'OneKey 越狱工具知识库'
SKIP = {'README.md'}


def load_docs():
    docs = []
    for name in sorted(os.listdir(KNOWLEDGE)):
        if not name.endswith('.md') or name in SKIP:
            continue
        path = os.path.join(KNOWLEDGE, name)
        text = open(path, encoding='utf-8').read().strip()
        title = text.splitlines()[0].lstrip('# ').strip()
        docs.append({'file': name, 'title': title, 'content': text})
    return docs


def split_sections(doc):
    """按二级标题切段，丢弃空段。"""
    lines = doc['content'].splitlines()
    sections = []
    title = doc['title']
    buf = []
    for line in lines[1:]:
        if line.startswith('## '):
            if buf:
                body = '\n'.join(buf).strip()
                if body:
                    sections.append((title, body))
            title = line[3:].strip()
            buf = []
        else:
            buf.append(line)
    if buf:
        body = '\n'.join(buf).strip()
        if body:
            sections.append((title, body))
    return sections


def main():
    docs = load_docs()
    paragraphs = []
    for doc in docs:
        stem = doc['file'][:-3]
        for heading, body in split_sections(doc):
            paragraphs.append({
                'id': stem + '#' + heading,
                'title': doc['title'] + ' · ' + heading,
                'content': body,
                'tags': [stem, heading],
                'source': 'knowledge/' + doc['file'],
            })
    meta = {
        'name': NAME,
        'version': VERSION,
        'generated_at': time.strftime('%Y-%m-%d'),
    }
    paragraph_doc = dict(meta, count=len(paragraphs), paragraphs=paragraphs)
    docs_doc = dict(meta, count=len(docs), docs=[
        {'title': d['title'], 'content': d['content'], 'source': 'knowledge/' + d['file']}
        for d in docs
    ])
    with open(os.path.join(HERE, 'onekey-kb.json'), 'w', encoding='utf-8') as f:
        json.dump(paragraph_doc, f, ensure_ascii=False, indent=2)
        f.write('\n')
    with open(os.path.join(HERE, 'onekey-kb.docs.json'), 'w', encoding='utf-8') as f:
        json.dump(docs_doc, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print('paragraphs:', len(paragraphs), 'docs:', len(docs))


if __name__ == '__main__':
    main()
