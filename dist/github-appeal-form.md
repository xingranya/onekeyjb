# GitHub 恢复申请 · 填写稿（xingranya）

> 用途：support.github.com 的 GitHub reinstatement request 表单。
> 核心口径：**整个账号被封**（不是单个仓库受限）+ 迁移是**雇主要求的代码资产归集**。
> 不含任何密钥、口令或 token。

## 一、主题（字段 1，限 80 字符）

```
Whole account suspended - employer-requested transfer to company org
```

（68 字符。只陈述两件事：整号被封、迁移是公司要求。）

## 二、额外背景情况（字段 2，最重要）

```
My ENTIRE GitHub account "xingranya" is suspended - this is not a single-repository
restriction. Right now I cannot log in, and every repository, release asset and API
call of the account is unavailable:

  git push  -> remote: Your account is suspended.
               fatal: unable to access 'https://github.com/xingranya/onekeyjb.git/':
               The requested URL returned error: 403
  REST API  -> 403 {"message": "Sorry, your account was suspended"}
  Web       -> https://github.com/xingranya and every repository URL now return 404
               for anonymous visitors, including previously published releases.

I am therefore locked out of my whole account and of every project in it, which is
why I am filing this reinstatement request for the account itself, not for a
specific repository.

WHY I WAS MOVING REPOSITORIES (context I want to disclose proactively):

I recently joined a company, and as part of onboarding my manager instructed me to
move the code assets I maintain into the company's GitHub organization, "seeway-co".
Because I am new, that organization had just been created and was not yet in active
use - which is exactly why the incoming repositories suddenly appeared in bulk, from
one person, into a brand-new, otherwise empty organization.

On 2026-11-05/06 I transferred roughly a dozen repositories that I own from
github.com/xingranya into seeway-co, following that instruction. This was an
internal consolidation of my own work:

  - every transferred repository was authored by me (personal or company work);
  - no content was copied, scraped or republished from third parties;
  - no external users, forks or collaborators were affected or removed;
  - no stars, follows, packages or automated actions were involved.

I fully understand that a burst of repository transfers from one account into a
newly created organization can look like automated abuse or a spam/repo-farming
pattern to detection systems. That was not the intent: it was a one-off, human-
driven migration requested by my employer.

TIMELINE:
  - 2026-11-05/06 : batch transfer of my repositories into seeway-co (employer request)
  - 2026-11-07 13:34 UTC : last successful push (commit da35a2b on xingranya/onekeyjb)
  - 2026-11-07 ~14:21 UTC : all git/API/web access started returning 403/404

ABOUT THE ACCOUNT AND ITS NORMAL USE:
  - single-user account, used only by me from my own development machine;
  - no bots, crawlers, mass-follow/star, crypto or affiliate promotion, no npm packages;
  - representative public project: xingranya/onekeyjb, a GPL-3.0 Android application,
    with source code, release notes and technical documentation;
  - I authenticate with a personal access token; I have not recently created OAuth
    Apps or GitHub Apps, and I do not run CI that performs bulk repository operations.

WHAT I AM WILLING TO DO:
  - pause or fully revert the transfers and re-do the migration gradually, or hand it
    over to the organization's admins, if the bulk transfer is what triggered this;
  - remove anything that the review considers spam or automated activity;
  - rotate my personal access token and enable/confirm two-factor authentication;
  - provide documentation of the employer's migration request, the repository list and
    the exact transfer timeline if that helps the review.

I am not aware of any intentional violation of the Acceptable Use Policies or Terms of
Service, and I am fully willing to remediate anything that was flagged. Could you please
review the suspension of my account and let me know what additional information you need?

Thank you.
```

## 三、受影响的用户名 / 存储库（字段 4）

```
The whole account is suspended: xingranya (user account, cannot log in).

All repositories of this account are inaccessible, for example:
  xingranya/onekeyjb  (public project, releases now return 404)
  and the other public/private repositories of the same account.

Additional context: the repositories I was transferring belong to the organization
"seeway-co" (created for my employer; I was asked to move my code assets there).
Both the account and its transferred repositories are affected.
```

## 四、其它选项

| 字段 | 选择 |
| --- | --- |
| 主张是否涉及 GitHub 或 npm.js | **GitHub**（本项目不发布 npm 包） |
| 您为何申请恢复 | **我的账户已禁用，我无法登录** |
| 之前是否联系过 GitHub | **否**（若已提交过→"是"，正文补工单号） |
| 可接受使用政策 | ✅ 勾选 |

## 五、附件建议（可选但有用）

1. `git push` 的完整报错截图（含 `remote: Your account is suspended.`）；
2. `gh api user` 返回的 403 JSON；
3. 匿名浏览器打开 `github.com/xingranya` 的 404；
4. 如果方便：公司要求迁移的聊天记录/邮件截图（**打码掉内部链接与个人信息**）。

## 六、提交后

1. 记下工单号，只在该工单里追加，不要重复新建；
2. 若被问 "how do you plan to use GitHub"：回答具体项目与公司用途，别只写 "coding"；
3. 等待期间不要反复重试 push；
4. 恢复后立刻换 PAT、确认 2FA、批量操作改为分批慢速。
