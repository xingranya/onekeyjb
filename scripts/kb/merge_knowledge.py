import os, glob
root='/Users/xingranya/Downloads/onekeyjb-private/knowledge'
out=['# OneKey 知识库（合集）','','> 版本 2.24.0 / 223。不含任何密钥、口令或签名文件内容。','']
files=sorted(glob.glob(root+'/*.md'))
ordered=[p for p in files if os.path.basename(p)=='README.md']+[p for p in files if os.path.basename(p)!='README.md']
for p in ordered:
    out.append('\n\n---\n'); out.append(open(p,encoding='utf-8').read().rstrip())
t='/Users/xingranya/Downloads/onekeyjb-private/dist/OneKey-Knowledge.md'
open(t,'w',encoding='utf-8').write('\n'.join(out)+'\n')
print('合集', os.path.getsize(t), 'B')
