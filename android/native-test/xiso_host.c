/* Host test driver: runs the same native core in-process on Linux.
 * usage: xiso_host <op> <args...>   (op numbers: see xiso_core.h) */
#include <stdio.h>
#include <stdlib.h>
#include "xiso_core.h"

int main( int argc, char **argv ) {
	if ( argc < 2 ) { fprintf( stderr, "usage: %s <op> <args...>\n", argv[ 0 ] ); return 2; }
	xiso_set_out_fd( 1 );
	return xiso_run( atoi( argv[ 1 ] ), argc - 2, argv + 2 );
}
