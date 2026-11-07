/*
 * xiso_core.c - Android port core around the UNMODIFIED upstream extract-xiso.c
 *
 * How it works
 * ------------
 * This file #includes upstream's extract-xiso.c directly (renaming its main()),
 * so all of upstream's logic (reading xdvdfs, the AVL layout, the writer and the
 * media-enable patch) is reused as-is and upstream can be synced freely.
 *
 * On top of it we add:
 *   - an I/O "overlay" (read/lseek hooks) so that replaced / added files can be
 *     fed into upstream's rewrite writer without extracting anything first,
 *   - a progress counter,
 *   - list / extract / create / rebuild operations that talk a small text
 *     protocol (see xiso_core.h) instead of printing for humans.
 *
 * Upstream's licence (BSD style, with an attribution clause) applies to
 * extract-xiso.c - see LICENSE.TXT in the repository root.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#ifndef _LARGEFILE64_SOURCE
#define _LARGEFILE64_SOURCE
#endif
#ifndef __LINUX__
#define __LINUX__ 1
#endif

/* Every system header upstream needs must be included BEFORE the hook macros
 * below, otherwise the macros would mangle the prototypes in those headers. */
#include <time.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <stdint.h>
#include <stdbool.h>
#include <dirent.h>
#include <limits.h>
#include <unistd.h>
#include <strings.h>
#include <ctype.h>

#include "xiso_core.h"

#define XC_PATH_MAX 4096

/* ------------------------------------------------------------------------ */
/* Output channel                                                            */
/* ------------------------------------------------------------------------ */

static int g_out = 1;

void xiso_set_out_fd( int fd ) { g_out = fd; }

static void xc_emit( const char *fmt, ... ) {
	char buf[XC_PATH_MAX + 512];
	va_list ap;
	int n, i;
	char *p = buf;

	va_start( ap, fmt );
	n = vsnprintf( buf, sizeof( buf ) - 2, fmt, ap );
	va_end( ap );
	if ( n < 0 ) return;
	if ( n > (int) sizeof( buf ) - 3 ) n = (int) sizeof( buf ) - 3;
	for ( i = 0; i < n; ++i ) if ( buf[ i ] == '\n' || buf[ i ] == '\r' ) buf[ i ] = ' ';
	buf[ n++ ] = '\n';

	while ( n > 0 ) {
		ssize_t w = write( g_out, p, (size_t) n );
		if ( w < 0 ) { if ( errno == EINTR ) continue; return; }
		p += w;
		n -= (int) w;
	}
}

/* ------------------------------------------------------------------------ */
/* Progress + I/O overlay                                                    */
/* ------------------------------------------------------------------------ */

typedef struct ov_range {
	uint64_t	start;		/* virtual offset inside the source "file"   */
	uint64_t	len;
	char	   *path;		/* real file that provides the bytes          */
	int			fd;
} ov_range;

static ov_range    *g_ov = NULL;
static int			g_ov_n = 0, g_ov_cap = 0;
static uint64_t		g_ov_next = 0;			/* next free virtual offset */
static int			g_src_fd = -1;
static uint64_t		g_src_size = 0, g_vpos = 0;

static int			g_count_reads = 0;
static uint64_t		g_done = 0, g_total = 0, g_last_emit = 0;

static void xc_progress( int force ) {
	if ( ! force && g_done - g_last_emit < ( 1u << 20 ) ) return;
	g_last_emit = g_done;
	xc_emit( "P\t%llu\t%llu", (unsigned long long) g_done, (unsigned long long) g_total );
}

static ssize_t xc_read_overlay( void *buf, size_t n ) {
	int i;
	ssize_t got;

	for ( i = 0; i < g_ov_n; ++i ) {
		ov_range *r = &g_ov[ i ];
		if ( g_vpos >= r->start && g_vpos < r->start + r->len ) {
			uint64_t avail = r->start + r->len - g_vpos;
			if ( n > avail ) n = (size_t) avail;
			if ( r->fd < 0 && ( r->fd = open( r->path, O_RDONLY | O_LARGEFILE ) ) < 0 ) return -1;
			got = pread64( r->fd, buf, n, (off64_t) ( g_vpos - r->start ) );
			if ( got > 0 ) g_vpos += (uint64_t) got;
			return got;
		}
	}

	if ( g_vpos >= g_src_size ) return 0;
	{
		uint64_t avail = g_src_size - g_vpos;
		if ( n > avail ) n = (size_t) avail;
	}
	got = pread64( g_src_fd, buf, n, (off64_t) g_vpos );
	if ( got > 0 ) g_vpos += (uint64_t) got;
	return got;
}

static ssize_t xc_read( int fd, void *buf, size_t n ) {
	ssize_t r;

	if ( fd == g_src_fd && g_ov_n > 0 ) r = xc_read_overlay( buf, n );
	else r = read( fd, buf, n );

	if ( r > 0 && g_count_reads ) {
		g_done += (uint64_t) r;
		xc_progress( 0 );
	}
	return r;
}

static off64_t xc_lseek64( int fd, off64_t off, int whence ) {
	if ( fd == g_src_fd && g_ov_n > 0 ) {
		int64_t t;
		if ( whence == SEEK_SET ) t = (int64_t) off;
		else if ( whence == SEEK_CUR ) t = (int64_t) g_vpos + (int64_t) off;
		else if ( whence == SEEK_END ) t = (int64_t) g_src_size + (int64_t) off;
		else { errno = EINVAL; return -1; }
		if ( t < 0 ) { errno = EINVAL; return -1; }
		g_vpos = (uint64_t) t;
		return (off64_t) t;
	}
	return lseek64( fd, off, whence );
}

static void xc_exit( int code ) {
	fflush( NULL );
	_exit( code );
}

/* Upstream's boyer_moore_init() reads one element past the end of its table
 * (the value is never used). Padding every allocation made by upstream code
 * makes that read harmless everywhere, including on hardened allocators. */
static void *xc_malloc( size_t n ) {
	return malloc( n + 64 );
}

/* Hooks: upstream's read()/lseek()/exit() calls land in the functions above. */
#define read( fd, buf, n )			xc_read( ( fd ), ( buf ), ( n ) )
#define lseek64( fd, off, whence )	xc_lseek64( ( fd ), ( off ), ( whence ) )
#define exit( code )				xc_exit( code )
#define malloc( n )					xc_malloc( n )
#define main						exiso_upstream_main

#include "extract-xiso.c"

/* ------------------------------------------------------------------------ */
/* Helpers                                                                   */
/* ------------------------------------------------------------------------ */

static int xc_fail( const char *fmt, ... ) {
	char buf[1024];
	va_list ap;
	va_start( ap, fmt );
	vsnprintf( buf, sizeof( buf ), fmt, ap );
	va_end( ap );
	xc_emit( "X\t%s", buf );
	return 1;
}

static void xc_warn( const char *fmt, ... ) {
	char buf[1024];
	va_list ap;
	va_start( ap, fmt );
	vsnprintf( buf, sizeof( buf ), fmt, ap );
	va_end( ap );
	xc_emit( "W\t%s", buf );
}

static int xc_is_real( dir_node_avl *n ) {
	return n != NULL && n != EMPTY_SUBDIRECTORY;
}

static void xc_reset_state( void ) {
	s_quiet = true;
	s_real_quiet = false;
	s_media_enable = true;
	s_remove_systemupdate = false;
	s_total_bytes = s_total_files = 0;
	s_total_bytes_all_isos = 0;
	s_total_files_all_isos = 0;
	s_warned = 0;
	s_xbox_disc_lseek = 0;

	g_ov = NULL;
	g_ov_n = g_ov_cap = 0;
	g_ov_next = 0;
	g_src_fd = -1;
	g_src_size = g_vpos = 0;
	g_count_reads = 0;
	g_done = g_total = g_last_emit = 0;
}

static int xc_mkdirs( const char *path ) {
	char tmp[ XC_PATH_MAX ], *p;
	size_t len = strlen( path );

	if ( len == 0 || len >= sizeof( tmp ) ) { errno = ENAMETOOLONG; return -1; }
	memcpy( tmp, path, len + 1 );
	for ( p = tmp + 1; *p; ++p ) {
		if ( *p == '/' ) {
			*p = 0;
			if ( mkdir( tmp, 0755 ) != 0 && errno != EEXIST ) return -1;
			*p = '/';
		}
	}
	if ( mkdir( tmp, 0755 ) != 0 && errno != EEXIST ) return -1;
	return 0;
}

typedef struct xiso_image {
	int				fd;
	dir_node_avl   *root;
	bool			empty;
	bool			optimized;
	uint64_t		size;
} xiso_image;

static void xc_close_image( xiso_image *im ) {
	if ( im->fd != -1 ) close( im->fd );
	im->fd = -1;
}

static void xc_free_tree( dir_node_avl *root ) {
	if ( xc_is_real( root ) ) avl_traverse_depth_first( root, free_dir_node_avl, NULL, k_postfix, 0 );
}

/* Opens an xiso, validates it and loads its whole file tree into memory. */
static int xc_open_image( const char *path, xiso_image *im ) {
	int err = 0;
	int32_t root_sect = 0, root_size = 0;
	char tag[ XISO_OPTIMIZED_TAG_LENGTH + 16 ];
	off64_t end;

	memset( im, 0, sizeof( *im ) );
	im->fd = -1;

	if ( ( im->fd = open( path, READFLAGS, 0 ) ) == -1 ) { open_err( path ); return err; }

	if ( ( end = lseek( im->fd, 0, SEEK_END ) ) == -1 ) { seek_err(); return err; }
	im->size = (uint64_t) end;

	/* "optimized" tag decides whether the linked-list compatibility hack is needed */
	memset( tag, 0, sizeof( tag ) );
	if ( lseek( im->fd, (xoff_t) XISO_OPTIMIZED_TAG_OFFSET, SEEK_SET ) != -1
		 && read( im->fd, tag, XISO_OPTIMIZED_TAG_LENGTH ) == XISO_OPTIMIZED_TAG_LENGTH ) {
		tag[ XISO_OPTIMIZED_TAG_LENGTH ] = 0;
		if ( ! strncmp( tag, XISO_OPTIMIZED_TAG, XISO_OPTIMIZED_TAG_LENGTH_MIN ) ) im->optimized = true;
	}

	err = verify_xiso( im->fd, &root_sect, &root_size, (char *) path );
	if ( err == err_iso_no_files ) { im->empty = true; return 0; }
	if ( err ) return err;

	if ( root_sect && root_size ) {
		xoff_t dir_start = (xoff_t) root_sect * XISO_SECTOR_SIZE + s_xbox_disc_lseek;

		if ( lseek( im->fd, dir_start, SEEK_SET ) == -1 ) { seek_err(); return err; }
		err = traverse_xiso( im->fd, NULL, dir_start, (char *) "", k_generate_avl, &im->root, ! im->optimized );
	} else {
		im->empty = true;
	}

	if ( ! err && ! xc_is_real( im->root ) ) im->empty = true;
	return err;
}

/* Finds an entry by its path inside the iso ("dir/sub/file.xbe", case insensitive). */
static dir_node_avl *xc_find( dir_node_avl *root, const char *path ) {
	char comp[ XISO_FILENAME_MAX_CHARS + 2 ];
	dir_node_avl *cur = root, *n;
	const char *p = path;

	for ( ;; ) {
		size_t len;

		while ( *p == '/' ) ++p;
		if ( ! *p ) return NULL;

		len = strcspn( p, "/" );
		if ( len > XISO_FILENAME_MAX_CHARS ) return NULL;
		memcpy( comp, p, len );
		comp[ len ] = 0;
		p += len;

		if ( ! xc_is_real( cur ) ) return NULL;
		if ( ( n = avl_fetch( cur, comp ) ) == NULL ) return NULL;

		while ( *p == '/' ) ++p;
		if ( ! *p ) return n;

		cur = n->subdirectory;
	}
}

static int xc_is_systemupdate( dir_node_avl *n ) {
	return n->subdirectory && strstr( n->filename, s_systemupdate ) != NULL;
}

/* ------------------------------------------------------------------------ */
/* LIST                                                                      */
/* ------------------------------------------------------------------------ */

static void xc_list_rec( dir_node_avl *n, char *path, size_t plen, uint64_t *files, uint64_t *bytes ) {
	size_t nl;

	if ( ! xc_is_real( n ) ) return;
	xc_list_rec( n->left, path, plen, files, bytes );

	nl = strlen( n->filename );
	if ( plen + nl + 2 < XC_PATH_MAX ) {
		memcpy( path + plen, n->filename, nl );
		path[ plen + nl ] = 0;
		if ( n->subdirectory ) {
			xc_emit( "T\tD\t%s", path );
			path[ plen + nl ] = '/';
			path[ plen + nl + 1 ] = 0;
			xc_list_rec( n->subdirectory, path, plen + nl + 1, files, bytes );
		} else {
			xc_emit( "T\tF\t%u\t%s", (unsigned) n->file_size, path );
			++*files;
			*bytes += n->file_size;
		}
	}

	xc_list_rec( n->right, path, plen, files, bytes );
}

static int xc_do_list( const char *iso ) {
	xiso_image im;
	char path[ XC_PATH_MAX ];
	uint64_t files = 0, bytes = 0;
	int err = xc_open_image( iso, &im );

	if ( ! err && ! im.empty ) {
		path[ 0 ] = 0;
		xc_list_rec( im.root, path, 0, &files, &bytes );
	}
	if ( ! err ) {
		xc_emit( "R\tfiles\t%llu", (unsigned long long) files );
		xc_emit( "R\tbytes\t%llu", (unsigned long long) bytes );
		xc_emit( "R\tisosize\t%llu", (unsigned long long) im.size );
		xc_emit( "R\toptimized\t%d", im.optimized ? 1 : 0 );
	}

	xc_free_tree( im.root );
	xc_close_image( &im );
	return err ? 1 : 0;
}

/* ------------------------------------------------------------------------ */
/* EXTRACT                                                                   */
/* ------------------------------------------------------------------------ */

typedef struct xc_ex {
	int		iso_fd;
	char   *buf;
	size_t	bufsz;
	int		skip_su;
	int		warnings;
} xc_ex;

static int xc_extract_node( xc_ex *cx, dir_node_avl *n, const char *dir_out );

static int xc_extract_file( xc_ex *cx, dir_node_avl *n, const char *outpath ) {
	int err = 0, out;
	uint32_t left = n->file_size;

	if ( ( out = open( outpath, WRITEFLAGS, 0644 ) ) == -1 ) { open_err( outpath ); return err; }

	if ( left ) {
		if ( lseek( cx->iso_fd, (xoff_t) n->old_start_sector * XISO_SECTOR_SIZE + s_xbox_disc_lseek, SEEK_SET ) == -1 ) seek_err();

		while ( ! err && left ) {
			size_t want = left < cx->bufsz ? left : cx->bufsz;
			ssize_t r = read( cx->iso_fd, cx->buf, want ), off = 0;

			if ( r < 0 ) {
				if ( errno == EINTR ) continue;
				read_err();
				break;
			}
			if ( r == 0 ) {
				xc_warn( "%s is truncated in the iso (%u bytes missing)", n->filename, (unsigned) left );
				++cx->warnings;
				break;
			}
			while ( off < r ) {
				ssize_t w = write( out, cx->buf + off, (size_t) ( r - off ) );
				if ( w < 0 ) {
					if ( errno == EINTR ) continue;
					write_err();
					break;
				}
				off += w;
			}
			left -= (uint32_t) r;
		}
	}

	if ( close( out ) != 0 && ! err ) write_err();
	return err;
}

static int xc_extract_tree( xc_ex *cx, dir_node_avl *n, const char *dir_out ) {
	int err = 0;

	if ( ! xc_is_real( n ) ) return 0;
	err = xc_extract_tree( cx, n->left, dir_out );
	if ( ! err ) err = xc_extract_node( cx, n, dir_out );
	if ( ! err ) err = xc_extract_tree( cx, n->right, dir_out );
	return err;
}

static int xc_extract_node( xc_ex *cx, dir_node_avl *n, const char *dir_out ) {
	char out[ XC_PATH_MAX ];
	int err = 0;

	if ( snprintf( out, sizeof( out ), "%s/%s", dir_out, n->filename ) >= (int) sizeof( out ) )
		return xc_fail( "path too long: %s/%s", dir_out, n->filename );

	if ( n->subdirectory ) {
		if ( cx->skip_su && xc_is_systemupdate( n ) ) return 0;
		if ( xc_mkdirs( out ) != 0 ) { mkdir_err( out ); return err; }
		return xc_extract_tree( cx, n->subdirectory, out );
	}
	return xc_extract_file( cx, n, out );
}

static uint64_t xc_sum_bytes( dir_node_avl *n, int skip_su ) {
	uint64_t sum = 0;

	if ( ! xc_is_real( n ) ) return 0;
	sum += xc_sum_bytes( n->left, skip_su ) + xc_sum_bytes( n->right, skip_su );
	if ( n->subdirectory ) {
		if ( ! ( skip_su && xc_is_systemupdate( n ) ) ) sum += xc_sum_bytes( n->subdirectory, skip_su );
	} else sum += n->file_size;
	return sum;
}

static int xc_do_extract( const char *iso, const char *dest, int skip_su, const char *selfile ) {
	xiso_image im;
	xc_ex cx;
	int err = xc_open_image( iso, &im );

	memset( &cx, 0, sizeof( cx ) );
	cx.iso_fd = im.fd;
	cx.skip_su = skip_su;
	cx.bufsz = READWRITE_BUFFER_SIZE;

	if ( ! err && ( cx.buf = (char *) malloc( cx.bufsz ) ) == NULL ) mem_err();
	if ( ! err && xc_mkdirs( dest ) != 0 ) { mkdir_err( dest ); }

	if ( ! err && ! im.empty ) {
		if ( selfile && *selfile ) {
			/* selected entries only: they keep their path relative to the iso root */
			FILE *f = fopen( selfile, "r" );
			char *line = NULL;
			size_t cap = 0;
			ssize_t len;
			char **paths = NULL;
			int n = 0, i, ncap = 0;

			if ( ! f ) { err = xc_fail( "cannot read selection file" ); }
			while ( ! err && ( len = getline( &line, &cap, f ) ) != -1 ) {
				while ( len > 0 && ( line[ len - 1 ] == '\n' || line[ len - 1 ] == '\r' ) ) line[ --len ] = 0;
				if ( ! len ) continue;
				if ( n == ncap ) {
					ncap = ncap ? ncap * 2 : 16;
					paths = (char **) realloc( paths, (size_t) ncap * sizeof( char * ) );
					if ( ! paths ) { mem_err(); break; }
				}
				paths[ n++ ] = strdup( line );
			}
			if ( f ) fclose( f );
			free( line );

			for ( i = 0; ! err && i < n; ++i ) {
				dir_node_avl *node = xc_find( im.root, paths[ i ] );
				if ( node ) g_total += node->subdirectory ? xc_sum_bytes( node->subdirectory, skip_su ) : node->file_size;
			}
			g_count_reads = 1;
			xc_progress( 1 );

			for ( i = 0; ! err && i < n; ++i ) {
				dir_node_avl *node = xc_find( im.root, paths[ i ] );
				char parent[ XC_PATH_MAX ], *slash;

				if ( ! node ) { xc_warn( "not found in iso: %s", paths[ i ] ); continue; }
				if ( snprintf( parent, sizeof( parent ), "%s/%s", dest, paths[ i ] ) >= (int) sizeof( parent ) ) { err = xc_fail( "path too long" ); break; }
				if ( ( slash = strrchr( parent, '/' ) ) != NULL && slash > parent + strlen( dest ) ) {
					*slash = 0;
					if ( xc_mkdirs( parent ) != 0 ) { mkdir_err( parent ); break; }
				} else {
					snprintf( parent, sizeof( parent ), "%s", dest );
				}
				err = xc_extract_node( &cx, node, parent );
			}
			for ( i = 0; i < n; ++i ) free( paths[ i ] );
			free( paths );
		} else {
			g_total = xc_sum_bytes( im.root, skip_su );
			g_count_reads = 1;
			xc_progress( 1 );
			err = xc_extract_tree( &cx, im.root, dest );
		}
	}

	if ( ! err ) {
		g_done = g_total;
		xc_progress( 1 );
		xc_emit( "R\twarnings\t%d", cx.warnings );
	}

	free( cx.buf );
	xc_free_tree( im.root );
	xc_close_image( &im );
	return err ? 1 : 0;
}

/* ------------------------------------------------------------------------ */
/* CREATE                                                                    */
/* ------------------------------------------------------------------------ */

static void xc_progress_cb( xoff_t cur, xoff_t final_bytes ) {
	(void) cur;
	if ( ! g_count_reads ) {			/* first call: right before data is written */
		g_total = (uint64_t) final_bytes;
		g_done = 0;
		g_count_reads = 1;
	}
	xc_progress( 0 );
}

static int xc_do_create( const char *folder, const char *outdir, const char *outname, int media ) {
	int err = 0;
	char *root = strdup( folder ), *od = strdup( outdir ), *nm = strdup( outname ), *outpath = NULL;

	if ( ! root || ! od || ! nm ) { mem_err(); }
	s_media_enable = media ? true : false;

	if ( ! err ) err = boyer_moore_init( XISO_MEDIA_ENABLE, XISO_MEDIA_ENABLE_LENGTH, k_default_alphabet_size );
	if ( ! err ) err = create_xiso( root, od, NULL, -1, &outpath, nm, xc_progress_cb );

	if ( ! err ) {
		g_done = g_total;
		xc_progress( 1 );
		xc_emit( "R\tfiles\t%u", (unsigned) s_total_files );
		xc_emit( "R\tbytes\t%llu", (unsigned long long) s_total_bytes );
		if ( outpath ) xc_emit( "R\tout\t%s", outpath );
	}

	boyer_moore_done();
	free( outpath );
	free( root );
	free( od );
	free( nm );
	return err ? 1 : 0;
}

/* ------------------------------------------------------------------------ */
/* REBUILD (rewrite / optimise / apply edits)                                */
/* ------------------------------------------------------------------------ */

typedef struct xc_edit {
	char		op;			/* 'D' delete, 'R' replace, 'A' add */
	char	   *path;
	char	   *src;
	char	   *base;		/* last path component */
	size_t		dirlen;		/* length of the parent directory part (no trailing slash) */
	uint64_t	size;
	int			used;
} xc_edit;

static xc_edit *g_ed = NULL;
static int		g_ed_n = 0;

static void xc_free_edits( void ) {
	int i;
	for ( i = 0; i < g_ed_n; ++i ) { free( g_ed[ i ].path ); free( g_ed[ i ].src ); }
	free( g_ed );
	g_ed = NULL;
	g_ed_n = 0;
}

static uint64_t xc_align_up( uint64_t v ) {
	return ( v + XISO_SECTOR_SIZE - 1 ) / XISO_SECTOR_SIZE * XISO_SECTOR_SIZE;
}

/* Registers a file whose bytes will be read through the overlay; returns the
 * "old start sector" to store in the AVL node, or 0 with *err = 1. */
static uint32_t xc_overlay_add( const char *src, uint64_t size, int *err ) {
	ov_range *r;
	uint64_t start, sector;

	if ( g_ov_n == g_ov_cap ) {
		g_ov_cap = g_ov_cap ? g_ov_cap * 2 : 16;
		g_ov = (ov_range *) realloc( g_ov, (size_t) g_ov_cap * sizeof( ov_range ) );
		if ( ! g_ov ) { *err = xc_fail( "out of memory" ); return 0; }
	}
	start = g_ov_next;
	sector = ( start - (uint64_t) s_xbox_disc_lseek ) / XISO_SECTOR_SIZE;
	if ( sector + xc_align_up( size ) / XISO_SECTOR_SIZE >= 0xFFFFFFFFull ) { *err = xc_fail( "too much data for one xiso" ); return 0; }

	r = &g_ov[ g_ov_n++ ];
	r->start = start;
	r->len = size;
	r->path = strdup( src );
	r->fd = -1;
	g_ov_next = start + xc_align_up( size );
	return (uint32_t) sector;
}

static int xc_parse_edits( const char *file ) {
	FILE *f = fopen( file, "r" );
	char *line = NULL;
	size_t cap = 0;
	ssize_t len;
	int err = 0, ecap = 0;

	if ( ! f ) return xc_fail( "cannot read edits file" );

	while ( ! err && ( len = getline( &line, &cap, f ) ) != -1 ) {
		char op, *p1, *p2 = NULL, *tab, *slash;
		xc_edit *e;

		while ( len > 0 && ( line[ len - 1 ] == '\n' || line[ len - 1 ] == '\r' ) ) line[ --len ] = 0;
		if ( len < 3 || line[ 1 ] != '\t' ) continue;

		op = line[ 0 ];
		p1 = line + 2;
		if ( ( tab = strchr( p1, '\t' ) ) != NULL ) { *tab = 0; p2 = tab + 1; }
		while ( *p1 == '/' ) ++p1;
		while ( *p1 && p1[ strlen( p1 ) - 1 ] == '/' ) p1[ strlen( p1 ) - 1 ] = 0;
		if ( ! *p1 ) { err = xc_fail( "empty path in edits" ); break; }
		if ( op != 'D' && op != 'R' && op != 'A' ) continue;
		if ( op != 'D' && ( ! p2 || ! *p2 ) ) { err = xc_fail( "missing source file for %s", p1 ); break; }

		if ( g_ed_n == ecap ) {
			ecap = ecap ? ecap * 2 : 16;
			g_ed = (xc_edit *) realloc( g_ed, (size_t) ecap * sizeof( xc_edit ) );
			if ( ! g_ed ) { err = xc_fail( "out of memory" ); break; }
		}
		e = &g_ed[ g_ed_n++ ];
		memset( e, 0, sizeof( *e ) );
		e->op = op;
		e->path = strdup( p1 );
		e->src = p2 ? strdup( p2 ) : NULL;
		slash = strrchr( e->path, '/' );
		e->base = slash ? slash + 1 : e->path;
		e->dirlen = slash ? (size_t) ( slash - e->path ) : 0;

		if ( op != 'D' ) {
			int fd = open( e->src, O_RDONLY | O_LARGEFILE );
			off64_t end;

			if ( fd < 0 ) { err = xc_fail( "cannot open %s: %s", e->src, strerror( errno ) ); break; }
			end = lseek( fd, 0, SEEK_END );
			close( fd );
			if ( end < 0 ) { err = xc_fail( "cannot size %s", e->src ); break; }
			if ( (uint64_t) end > 0xFFFFFFFFull ) { err = xc_fail( "%s is larger than 4 GiB, which an xiso cannot hold", e->src ); break; }
			e->size = (uint64_t) end;
			if ( strlen( e->base ) > XISO_FILENAME_MAX_CHARS || ! *e->base ) { err = xc_fail( "invalid file name: %s", e->base ); break; }
		}
	}
	free( line );
	fclose( f );
	return err;
}

static xc_edit *xc_find_edit( const char *full, int want_delete ) {
	int i;
	xc_edit *found = NULL;

	for ( i = 0; i < g_ed_n; ++i ) {
		if ( strcasecmp( g_ed[ i ].path, full ) != 0 ) continue;
		if ( want_delete ) { if ( g_ed[ i ].op == 'D' ) return &g_ed[ i ]; }
		else if ( g_ed[ i ].op != 'D' ) found = &g_ed[ i ];
	}
	return found;
}

static int xc_collect( dir_node_avl *n, dir_node_avl ***arr, int *cnt, int *cap ) {
	if ( ! xc_is_real( n ) ) return 0;
	if ( xc_collect( n->left, arr, cnt, cap ) ) return 1;
	if ( *cnt == *cap ) {
		*cap = *cap ? *cap * 2 : 32;
		*arr = (dir_node_avl **) realloc( *arr, (size_t) *cap * sizeof( dir_node_avl * ) );
		if ( ! *arr ) return 1;
	}
	( *arr )[ ( *cnt )++ ] = n;
	return xc_collect( n->right, arr, cnt, cap );
}

/* Applies the edits to one directory level (and below). prefix is "" or "a/b/". */
static int xc_apply_level( dir_node_avl **proot, const char *prefix ) {
	dir_node_avl **arr = NULL, *newroot = NULL;
	int cnt = 0, cap = 0, i, err = 0, kept = 0;
	size_t plen = strlen( prefix );

	if ( xc_collect( *proot, &arr, &cnt, &cap ) ) return xc_fail( "out of memory" );

	/* detach every node from the old tree so it can be rebuilt */
	for ( i = 0; i < cnt; ++i ) { arr[ i ]->left = arr[ i ]->right = NULL; arr[ i ]->skew = k_no_skew; }

	for ( i = 0; ! err && i < cnt; ++i ) {
		dir_node_avl *n = arr[ i ];
		char full[ XC_PATH_MAX ];
		xc_edit *e;

		if ( snprintf( full, sizeof( full ), "%s%s", prefix, n->filename ) >= (int) sizeof( full ) ) { err = xc_fail( "path too long" ); break; }

		if ( ( e = xc_find_edit( full, 1 ) ) != NULL ) {
			e->used = 1;
			free_dir_node_avl( n, NULL, 0 );
			arr[ i ] = NULL;
			continue;
		}
		if ( ( e = xc_find_edit( full, 0 ) ) != NULL ) {
			if ( n->subdirectory ) { err = xc_fail( "%s is a folder, cannot replace it with a file", full ); break; }
			e->used = 1;
			n->file_size = (uint32_t) e->size;
			n->old_start_sector = xc_overlay_add( e->src, e->size, &err );
		}
		if ( ! err && n->subdirectory ) {
			char sub[ XC_PATH_MAX ];
			snprintf( sub, sizeof( sub ), "%s/", full );
			err = xc_apply_level( &n->subdirectory, sub );
		}
	}

	/* new files that belong into this directory */
	for ( i = 0; ! err && i < g_ed_n; ++i ) {
		xc_edit *e = &g_ed[ i ];
		dir_node_avl *nn;
		int j, exists = 0;

		if ( e->op != 'A' || e->used ) continue;
		if ( plen == 0 ? e->dirlen != 0 : ( e->dirlen + 1 != plen || strncasecmp( prefix, e->path, e->dirlen ) != 0 ) ) continue;

		for ( j = 0; j < cnt; ++j ) if ( arr[ j ] && strcasecmp( arr[ j ]->filename, e->base ) == 0 ) exists = 1;
		if ( exists ) continue;			/* handled above as replace */

		if ( ( nn = (dir_node_avl *) calloc( 1, sizeof( dir_node_avl ) ) ) == NULL || ( nn->filename = strdup( e->base ) ) == NULL ) { err = xc_fail( "out of memory" ); break; }
		nn->file_size = (uint32_t) e->size;
		nn->old_start_sector = xc_overlay_add( e->src, e->size, &err );
		e->used = 1;

		arr = (dir_node_avl **) realloc( arr, (size_t) ( cnt + 1 ) * sizeof( dir_node_avl * ) );
		if ( ! arr ) { err = xc_fail( "out of memory" ); break; }
		arr[ cnt++ ] = nn;
	}

	/* rebuild the AVL tree of this level */
	for ( i = 0; i < cnt; ++i ) {
		if ( ! arr[ i ] ) continue;
		if ( ! err ) {
			if ( avl_insert( &newroot, arr[ i ] ) == k_avl_error ) { err = xc_fail( "duplicate name in %s: %s", *prefix ? prefix : "/", arr[ i ]->filename ); }
			else ++kept;
		}
		if ( err ) { /* keep ownership simple: leak on error, the process ends anyway */ }
	}
	free( arr );

	*proot = kept ? newroot : EMPTY_SUBDIRECTORY;
	return err;
}

static int xc_do_rebuild( const char *iso, const char *outdir, const char *outname, const char *editsfile, int media ) {
	xiso_image im;
	int err = xc_open_image( iso, &im ), tree_owned_by_writer = 0, i;
	char *od = NULL, *nm1 = NULL, *nm2 = NULL, *outpath = NULL;

	if ( ! err && im.empty ) err = xc_fail( "this iso contains no files" );

	if ( ! err && editsfile && *editsfile ) {
		err = xc_parse_edits( editsfile );
		if ( ! err ) {
			g_ov_next = xc_align_up( im.size );
			err = xc_apply_level( &im.root, "" );
		}
		for ( i = 0; ! err && i < g_ed_n; ++i ) {
			if ( ! g_ed[ i ].used ) err = xc_fail( g_ed[ i ].op == 'A' ? "folder not found for new file: %s" : "not found in iso: %s", g_ed[ i ].path );
		}
		if ( ! err && ! xc_is_real( im.root ) ) err = xc_fail( "an xiso cannot be empty - nothing would be left after these changes" );
	}

	if ( ! err ) {
		s_media_enable = media ? true : false;
		od = strdup( outdir );
		nm1 = strdup( outname );
		nm2 = strdup( outname );
		if ( ! od || ! nm1 || ! nm2 ) mem_err();
	}
	if ( ! err ) err = boyer_moore_init( XISO_MEDIA_ENABLE, XISO_MEDIA_ENABLE_LENGTH, k_default_alphabet_size );

	if ( ! err ) {
		/* from now on reads of the source iso go through the overlay */
		g_src_fd = im.fd;
		g_src_size = im.size;
		g_vpos = 0;
		tree_owned_by_writer = 1;		/* create_xiso frees the tree itself */
		err = create_xiso( nm1, od, im.root, im.fd, &outpath, nm2, xc_progress_cb );
	}

	if ( ! err ) {
		g_done = g_total;
		xc_progress( 1 );
		xc_emit( "R\tfiles\t%u", (unsigned) s_total_files );
		xc_emit( "R\tbytes\t%llu", (unsigned long long) s_total_bytes );
		if ( outpath ) xc_emit( "R\tout\t%s", outpath );
	}

	boyer_moore_done();
	if ( ! tree_owned_by_writer ) xc_free_tree( im.root );
	for ( i = 0; i < g_ov_n; ++i ) { if ( g_ov[ i ].fd >= 0 ) close( g_ov[ i ].fd ); free( g_ov[ i ].path ); }
	free( g_ov );
	g_ov = NULL;
	g_ov_n = 0;
	xc_free_edits();
	xc_close_image( &im );
	free( od ); free( nm1 ); free( nm2 ); free( outpath );
	return err ? 1 : 0;
}

/* ------------------------------------------------------------------------ */
/* CLI (the original command line, run through upstream's own main())        */
/* ------------------------------------------------------------------------ */

static int xc_opt_takes_arg( char c ) { return c == 'c' || c == 'd' || c == 'p'; }

/* Android's getopt may not permute arguments like glibc does, so put all
 * options first (keeping "-c dir [name]" together) and the file names last. */
static int xc_do_cli( int argc, char **argv ) {
	char **nargv, **pos;
	int i, n = 0, np = 0, rc;

	if ( argc < 1 ) return 2;
	if ( chdir( argv[ 0 ] ) != 0 ) {
		fprintf( stderr, "cannot enter directory %s: %s\n", argv[ 0 ], strerror( errno ) );
		return 1;
	}

	nargv = (char **) calloc( (size_t) argc + 2, sizeof( char * ) );
	pos = (char **) calloc( (size_t) argc + 2, sizeof( char * ) );
	if ( ! nargv || ! pos ) return 1;
	nargv[ n++ ] = (char *) "extract-xiso";

	for ( i = 1; i < argc; ++i ) {
		char *a = argv[ i ];

		if ( a[ 0 ] == '-' && a[ 1 ] && ! ( a[ 1 ] == '-' && ! a[ 2 ] ) ) {
			int j, takes = 0, is_c = 0;
			nargv[ n++ ] = a;
			for ( j = 1; a[ j ]; ++j ) {
				if ( xc_opt_takes_arg( a[ j ] ) ) { takes = ( a[ j + 1 ] == 0 ); is_c = ( a[ j ] == 'c' ); break; }
			}
			if ( takes && i + 1 < argc ) {
				nargv[ n++ ] = argv[ ++i ];
				if ( is_c && i + 1 < argc && argv[ i + 1 ][ 0 ] != '-' ) nargv[ n++ ] = argv[ ++i ];
			}
		} else {
			pos[ np++ ] = a;
		}
	}
	for ( i = 0; i < np; ++i ) nargv[ n++ ] = pos[ i ];
	nargv[ n ] = NULL;

	setvbuf( stdout, NULL, _IONBF, 0 );
	setvbuf( stderr, NULL, _IONBF, 0 );
	s_quiet = false;
	rc = exiso_upstream_main( n, nargv );
	fflush( NULL );
	return rc ? 1 : 0;
}

/* ------------------------------------------------------------------------ */
/* Dispatcher                                                                */
/* ------------------------------------------------------------------------ */

int xiso_run( int op, int argc, char **argv ) {
	int rc;

	xc_reset_state();

	switch ( op ) {
		case XISO_OP_LIST:
			if ( argc < 1 ) return 2;
			rc = xc_do_list( argv[ 0 ] );
			break;
		case XISO_OP_EXTRACT:
			if ( argc < 3 ) return 2;
			rc = xc_do_extract( argv[ 0 ], argv[ 1 ], atoi( argv[ 2 ] ), argc > 3 ? argv[ 3 ] : NULL );
			break;
		case XISO_OP_CREATE:
			if ( argc < 4 ) return 2;
			rc = xc_do_create( argv[ 0 ], argv[ 1 ], argv[ 2 ], atoi( argv[ 3 ] ) );
			break;
		case XISO_OP_REBUILD:
			if ( argc < 5 ) return 2;
			rc = xc_do_rebuild( argv[ 0 ], argv[ 1 ], argv[ 2 ], argv[ 3 ], atoi( argv[ 4 ] ) );
			break;
		case XISO_OP_CLI:
			s_quiet = false;
			rc = xc_do_cli( argc, argv );
			break;
		default:
			return 2;
	}
	return rc;
}
