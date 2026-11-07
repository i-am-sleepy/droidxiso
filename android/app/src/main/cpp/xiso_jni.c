/*
 * xiso_jni.c - JNI glue.
 *
 * Every operation runs in a forked CHILD process:
 *   - it can be cancelled instantly (SIGKILL),
 *   - a crash on a damaged iso cannot take the app down,
 *   - upstream's global state / chdir() / exit() never touch the app process.
 * The child writes its messages into a pipe; the parent forwards them to Kotlin.
 */
#include <jni.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>

#include "xiso_core.h"

static volatile pid_t g_child = 0;

JNIEXPORT jint JNICALL
Java_dev_xisotool_core_Native_exec( JNIEnv *env, jclass clazz, jint op, jobjectArray jargs, jobject cb ) {
	jint argc = ( *env )->GetArrayLength( env, jargs ), i;
	char **argv = (char **) calloc( (size_t) argc + 1, sizeof( char * ) );
	jclass cbcls = ( *env )->GetObjectClass( env, cb );
	jmethodID mid = ( *env )->GetMethodID( env, cbcls, "onData", "([BI)V" );
	int fds[ 2 ], status = 0, rc;
	pid_t pid;
	(void) clazz;

	if ( ! argv || ! mid ) return -1002;

	/* arguments arrive as raw UTF-8 byte arrays (JNI's own UTF-8 is a modified variant) */
	for ( i = 0; i < argc; ++i ) {
		jbyteArray ba = (jbyteArray) ( *env )->GetObjectArrayElement( env, jargs, i );
		jsize len = ( *env )->GetArrayLength( env, ba );
		argv[ i ] = (char *) malloc( (size_t) len + 1 );
		if ( argv[ i ] ) {
			( *env )->GetByteArrayRegion( env, ba, 0, len, (jbyte *) argv[ i ] );
			argv[ i ][ len ] = 0;
		}
		( *env )->DeleteLocalRef( env, ba );
	}

	if ( pipe( fds ) != 0 ) rc = -1000;
	else if ( ( pid = fork() ) < 0 ) { close( fds[ 0 ] ); close( fds[ 1 ] ); rc = -1001; }
	else if ( pid == 0 ) {
		/* ---- child: pure C from here on, never touch the JVM ---- */
		close( fds[ 0 ] );
		prctl( PR_SET_PDEATHSIG, SIGKILL );
		dup2( fds[ 1 ], 1 );
		dup2( fds[ 1 ], 2 );
		if ( fds[ 1 ] > 2 ) close( fds[ 1 ] );
		xiso_set_out_fd( 1 );
		rc = xiso_run( op, argc, argv );
		fflush( NULL );
		_exit( rc & 0xFF );
	} else {
		/* ---- parent: forward the child's output until it closes the pipe ---- */
		unsigned char buf[ 8192 ];

		g_child = pid;
		close( fds[ 1 ] );

		for ( ;; ) {
			ssize_t n = read( fds[ 0 ], buf, sizeof( buf ) );
			if ( n < 0 ) { if ( errno == EINTR ) continue; break; }
			if ( n == 0 ) break;
			{
				jbyteArray arr = ( *env )->NewByteArray( env, (jsize) n );
				if ( arr ) {
					( *env )->SetByteArrayRegion( env, arr, 0, (jsize) n, (const jbyte *) buf );
					( *env )->CallVoidMethod( env, cb, mid, arr, (jint) n );
					( *env )->DeleteLocalRef( env, arr );
				}
				if ( ( *env )->ExceptionCheck( env ) ) ( *env )->ExceptionClear( env );
			}
		}
		close( fds[ 0 ] );

		while ( waitpid( pid, &status, 0 ) < 0 && errno == EINTR ) ;
		g_child = 0;

		if ( WIFEXITED( status ) ) rc = WEXITSTATUS( status );
		else if ( WIFSIGNALED( status ) ) rc = 128 + WTERMSIG( status );
		else rc = -1003;
	}

	for ( i = 0; i < argc; ++i ) free( argv[ i ] );
	free( argv );
	return rc;
}

JNIEXPORT void JNICALL
Java_dev_xisotool_core_Native_cancel( JNIEnv *env, jclass clazz ) {
	pid_t pid = g_child;
	(void) env;
	(void) clazz;
	if ( pid > 0 ) kill( pid, SIGKILL );
}
