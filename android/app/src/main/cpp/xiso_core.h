/*
 * xiso_core.h - public API of the Android port's native core.
 *
 * The core wraps the UNMODIFIED upstream extract-xiso.c (it #includes it), so
 * upstream updates can be merged without conflicts.
 *
 * Every operation writes line based, tab separated messages to the output fd:
 *   P <done> <total>          progress in bytes
 *   T D <path>                tree entry: directory      (list)
 *   T F <size> <path>         tree entry: file           (list)
 *   W <text>                  warning
 *   X <text>                  error message
 *   R <key> <value>           result value
 * Anything else is raw text printed by upstream code (mostly error messages).
 */
#ifndef XISO_CORE_H
#define XISO_CORE_H

#ifdef __cplusplus
extern "C" {
#endif

enum {
	XISO_OP_LIST    = 1,  /* argv: iso                                              */
	XISO_OP_EXTRACT = 2,  /* argv: iso destDir skipSystemUpdate(0/1) [selectionFile] */
	XISO_OP_CREATE  = 3,  /* argv: folder outDir outName mediaPatch(0/1)            */
	XISO_OP_REBUILD = 4,  /* argv: iso outDir outName editsFile mediaPatch(0/1)     */
	XISO_OP_CLI     = 5   /* argv: workingDir args...  (the original command line)  */
};

void xiso_set_out_fd(int fd);
int  xiso_run(int op, int argc, char **argv);

#ifdef __cplusplus
}
#endif

#endif
