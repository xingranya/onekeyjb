# Third-Party Notices

## GhostKey / CVE-2026-98260-GhostKey

- Repository: https://github.com/Again1996-bit/CVE-2026-98260-GhostKey
- Source snapshot: upstream main as vendored on 2026-10-30
- Upstream author: Again1996-bit and GhostKey contributors
- License: user has confirmed permission to reuse the components in this project.
- Vendored components:
  - app/src/main/cpp/ghostkey/* (native exploit sources)
  - app/src/main/assets/ghostkey/ghostkey98260 (built from those sources)
  - app/src/main/assets/ghostkey_profiles/* (kernel profiles)
  - Kotlin profile model/parser, boot image parser, kallsyms extractor,
    failure diagnoser and ksud extraction code under
    app/src/main/java/com/pyb110/onekey/ghost/
- Modifications:
  - package name changed to com.pyb110.onekey.ghost
  - Shizuku UserService/AIDL/UI not copied
  - adapted to OneKeyJB StellarShell and manager catalog
  - native executable built for arm64-v8a, PIE, 16KB page alignment

Original copyright and source headers are retained.
