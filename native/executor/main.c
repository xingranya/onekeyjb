/*
 * onekey-exec —— OneKey 自研 98260 执行器。
 *
 * 协议（stdout，供 App 流式解析）：
 *   VER=<版本>
 *   STAGE=<preflight|leak|plant|load|verify>
 *   PROGRESS=<0-100> <说明>
 *   SLIDE_OK=0x...
 *   WARN=<说明>
 *   ERROR=<分类> <细节>
 *   DONE=ok|fail
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/utsname.h>

#include "exec.h"

static const char *VERSION = "0.1.0";

static void usage(void) {
    printf("usage: onekey-exec probe [symbols] [budget]\n");
    printf("       onekey-exec run <workspace> <backend> [symbols]\n");
    printf("       onekey-exec version\n");
}

int main(int argc, char **argv) {
    if (argc < 2) { usage(); return 2; }
    const char *cmd = argv[1];

    if (!strcmp(cmd, "version")) { printf("VER=%s\n", VERSION); return 0; }

    if (!strcmp(cmd, "probe")) {
        const char *sym = argc > 2 ? argv[2] : "device-symbols.txt";
        int budget = argc > 3 ? atoi(argv[3]) : 4;
        return leak_probe(sym, budget, 1);
    }

    if (!strcmp(cmd, "run")) {
        if (argc < 4) { usage(); return 2; }
        const char *ws = argv[2];
        const char *backend = argv[3];
        char sym[512];
        snprintf(sym, sizeof sym, "%s/device-symbols.txt", ws);
        printf("VER=%s\n", VERSION);
        printf("STAGE=preflight\n");
        struct utsname u;
        if (uname(&u) != 0) { printf("ERROR=preflight uname\nDONE=fail\n"); return 1; }
        printf("PROGRESS=5 kernel=%s\n", u.release);
        printf("INFO=backend=%s\n", backend);
        if (access(sym, R_OK) != 0) {
            printf("ERROR=preflight 缺少符号表 %s\nDONE=fail\n", sym);
            return 1;
        }
        printf("STAGE=leak\n");
        printf("PROGRESS=15 正在推导内核滑动量\n");
        if (leak_probe(sym, 4, 0) != 0) {
            printf("ERROR=leak 滑动量校验未通过\nDONE=fail\n");
            return 1;
        }
        printf("PROGRESS=35 滑动量已确认\n");
        printf("STAGE=plant\n");
        printf("ERROR=plant 尚未实现（M2 进行中）\n");
        printf("DONE=fail\n");
        return 1;
    }

    usage();
    return 2;
}
