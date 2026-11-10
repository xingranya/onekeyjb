Subject: Account suspended — request for review and reinstatement

Hello GitHub Support,

My GitHub account "xingranya" appears to have been suspended.

Starting around 2026-11-07 13:40 UTC, every request fails:

  git push -> remote: Your account is suspended. Please visit https://support.github.com for more information.
  fatal: unable to access 'https://github.com/xingranya/onekeyjb.git/': The requested URL returned error: 403

  GitHub REST API -> 403 {"message": "Sorry. Your account was suspended"}

  Anonymous web access to https://github.com/xingranya and
  https://github.com/xingranya/onekeyjb now returns 404.

Relevant information:

- GitHub username: xingranya
- Account email: [填写你的 GitHub 注册邮箱]
- Approximate time the issue started: 2026-11-07, between 13:34 UTC (last successful push) and 14:21 UTC (first 403)
- Affected repository: xingranya/onekeyjb (private? -> public, Android application project)
- Repository content: an open-source Android app (GPL-3.0) for a OnePlus device, with source code,
  release notes, design documents and a technical knowledge base. No user data, no scraped content,
  no spam, no crypto or financial content.
- Previous support ticket: none
- Credentials used: a personal access token for pushing from my own machine; no OAuth app,
  no GitHub App, no CI automation recently created.

I am not aware of any intentional activity on my account that violates GitHub's
Terms of Service or Acceptable Use Policies. The account has been used only by me,
from my own development machine, for developing and publishing my own open-source project.

If a credential, application, workflow, repository activity, or other behavior triggered
the restriction, I am willing to investigate and take any necessary remediation steps,
including rotating credentials and enabling additional 2FA protections.

Could you please review the restriction on my account and let me know if any additional
information or action is required?

Thank you.
