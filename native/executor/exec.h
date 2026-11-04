#ifndef ONEKEY_EXEC_H
#define ONEKEY_EXEC_H

/* 推导 KASLR slide；emit=1 时打印 SYMS/SLIDE_OK 等调试行，emit=0 时只打印 SLIDE_OK/ERROR */
int leak_probe(const char *symbols_path, int budget_sec, int emit);

#endif
