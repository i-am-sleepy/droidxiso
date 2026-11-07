#!/usr/bin/env bash
# Native regression tests. Runs the Android core on the host and verifies every
# result with the ORIGINAL upstream extract-xiso binary.
#   usage: run_tests.sh [path-to-repo-root]
set -u
ROOT="$(cd "${1:-$(dirname "$0")/../..}" && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
CPP="$HERE/../app/src/main/cpp"
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
FAIL=0
ok()   { echo "  PASS: $1"; }
bad()  { echo "  FAIL: $1"; FAIL=1; }

echo "== build"
gcc -std=gnu11 -O1 -g -w -D__LINUX__ -o "$W/orig" "$ROOT/extract-xiso.c" || { echo "cannot build upstream"; exit 1; }
SAN=""
[ "${NO_SAN:-0}" = "1" ] || SAN="-fsanitize=address,undefined"
gcc -std=gnu11 -O1 -g -w $SAN -I"$CPP" -I"$ROOT" "$CPP/xiso_core.c" "$HERE/xiso_host.c" -o "$W/host" || { echo "cannot build core"; exit 1; }
export ASAN_OPTIONS=detect_leaks=0
H() { "$W/host" "$@"; }
LIST=1; EXTRACT=2; CREATE=3; REBUILD=4; CLI=5

echo "== make test tree"
T="$W/Game"; mkdir -p "$T/Media/Sub" "$T/Empty" "$T/\$SystemUpdate" "$T/many"
python3 - "$T" <<'PY'
import os, sys, random
t = sys.argv[1]; random.seed(7)
def w(p, n, pat=None):
    d = os.path.dirname(os.path.join(t, p)); os.makedirs(d, exist_ok=True)
    b = bytearray(random.getrandbits(8) for _ in range(min(n, 4096)))
    b = (bytes(b) * (n // len(b) + 1))[:n] if n else b""
    if pat: b = b[:100] + pat + b[100 + len(pat):]
    open(os.path.join(t, p), "wb").write(b)
w("default.xbe", 300000, bytes.fromhex("e8cafdffff85c07d"))
w("Media/big.bin", 5_000_000)
w("Media/Sub/a.txt", 10)
w("Media/Sub/zero.bin", 0)
w("readme.TXT", 2048)
w("exact_sector.bin", 4096)
w("unicode_\u00e9\u00e8 name.dat", 777)
w("$SystemUpdate/upd.xbe", 123456)
for i in range(300): w("many/file_%03d.dat" % i, random.randint(1, 5000))
PY
mkdir -p "$T/Empty"

echo "== create"
H $CREATE "$T" "$W" "Game.iso" 1 > "$W/create.log" 2>&1 && ok "create exit code" || { bad "create exit code"; cat "$W/create.log"; }
grep -q "^R.out" "$W/create.log" && ok "create reports output" || bad "create reports output"
grep -q "^P" "$W/create.log" && ok "create reports progress" || bad "create reports progress"
[ -s "$W/Game.iso" ] || { bad "iso exists"; exit 1; }

echo "== upstream can read what we created"
mkdir "$W/x1"; ( cd "$W/x1" && "$W/orig" -q -x "$W/Game.iso" ) && ok "upstream extracts our iso" || bad "upstream extracts our iso"
# media patch applied?  compare default.xbe: byte 100+7 should be 0xeb
python3 - "$W/x1/Game/default.xbe" "$T/default.xbe" <<'PY' && ok "media-enable patch applied" || bad "media-enable patch applied"
import sys
a=open(sys.argv[1],'rb').read(); b=open(sys.argv[2],'rb').read()
assert len(a)==len(b) and a[107]==0xeb and b[107]==0x7d and a[:107]==b[:107] and a[108:]==b[108:]
PY
diff -r -x default.xbe "$T" "$W/x1/Game" >/dev/null && ok "contents identical (except patched xbe)" || bad "contents identical"
[ -d "$W/x1/Game/Empty" ] && ok "empty directory preserved" || bad "empty directory preserved"

echo "== create without media patch"
H $CREATE "$T" "$W" "Game_nomedia.iso" 0 >/dev/null 2>&1 && ok "create (no patch)" || bad "create (no patch)"
mkdir "$W/x2"; ( cd "$W/x2" && "$W/orig" -q -x "$W/Game_nomedia.iso" )
diff -r "$T" "$W/x2/Game_nomedia" >/dev/null && ok "unpatched xbe identical" || bad "unpatched xbe identical"

echo "== list"
H $LIST "$W/Game.iso" > "$W/list.log" 2>&1 && ok "list exit code" || bad "list exit code"
NF=$(grep -c $'^T\tF\t' "$W/list.log"); EXPECT=$(find "$T" -type f | wc -l)
[ "$NF" = "$EXPECT" ] && ok "list file count ($NF)" || bad "list file count $NF != $EXPECT"
grep -q $'^T\tD\tMedia/Sub$' "$W/list.log" && ok "list nested dir" || bad "list nested dir"
grep -q $'^T\tF\t5000000\tMedia/big.bin$' "$W/list.log" && ok "list size+path" || bad "list size+path"

echo "== extract all / skip SystemUpdate"
H $EXTRACT "$W/Game.iso" "$W/e1" 0 >/dev/null 2>&1 && ok "extract all" || bad "extract all"
diff -r "$W/x1/Game" "$W/e1" >/dev/null && ok "extract identical to upstream extraction" || bad "extract identical"
H $EXTRACT "$W/Game.iso" "$W/e2" 1 >/dev/null 2>&1
[ ! -e "$W/e2/\$SystemUpdate/upd.xbe" ] && [ -f "$W/e2/default.xbe" ] && ok "skip \$SystemUpdate" || bad "skip \$SystemUpdate"

echo "== extract selection"
printf 'Media/big.bin\nMedia/Sub\ndefault.xbe\nnope/missing.bin\n' > "$W/sel.txt"
H $EXTRACT "$W/Game.iso" "$W/e3" 0 "$W/sel.txt" > "$W/sel.log" 2>&1 && ok "extract selection" || bad "extract selection"
cmp "$W/e3/Media/big.bin" "$T/Media/big.bin" && cmp "$W/e3/Media/Sub/a.txt" "$T/Media/Sub/a.txt" && [ -f "$W/e3/default.xbe" ] && [ ! -e "$W/e3/readme.TXT" ] && ok "selection content + layout" || bad "selection content + layout"
grep -q "^W" "$W/sel.log" && ok "missing entry gives warning" || bad "missing entry gives warning"

echo "== rebuild without edits (optimize)"
H $REBUILD "$W/Game.iso" "$W" "Game_opt.iso" "" 1 >/dev/null 2>&1 && ok "rebuild no edits" || bad "rebuild no edits"
mkdir "$W/x3"; ( cd "$W/x3" && "$W/orig" -q -x "$W/Game_opt.iso" ) && diff -r "$W/x1/Game" "$W/x3/Game_opt" >/dev/null && ok "optimized iso identical" || bad "optimized iso identical"

echo "== rebuild with edits (delete / replace / add)"
head -c 1234567 /dev/urandom > "$W/new_big.bin"
printf 'hello replaced' > "$W/new_small.txt"
head -c 50000 /dev/urandom > "$W/added.dat"
head -c 200000 /dev/urandom > "$W/new_default.xbe"
python3 - "$W/new_default.xbe" <<'PY'
import sys; p=sys.argv[1]; b=bytearray(open(p,'rb').read()); b[500:508]=bytes.fromhex("e8cafdffff85c07d"); open(p,'wb').write(b)
PY
{
  printf 'D\t$SystemUpdate\n'
  printf 'D\tmany/file_010.dat\n'
  printf 'D\tMedia/Sub\n'
  printf 'R\tMedia/big.bin\t%s\n' "$W/new_big.bin"
  printf 'R\treadme.txt\t%s\n' "$W/new_small.txt"
  printf 'A\tmany/added.dat\t%s\n' "$W/added.dat"
  printf 'A\tEmpty/in_empty.dat\t%s\n' "$W/added.dat"
  printf 'A\ttoplevel_added.dat\t%s\n' "$W/added.dat"
  printf 'R\tdefault.xbe\t%s\n' "$W/new_default.xbe"
} > "$W/edits.txt"
H $REBUILD "$W/Game.iso" "$W" "Game_edit.iso" "$W/edits.txt" 1 > "$W/edit.log" 2>&1 && ok "rebuild with edits" || { bad "rebuild with edits"; cat "$W/edit.log"; }
mkdir "$W/x4"; ( cd "$W/x4" && "$W/orig" -q -x "$W/Game_edit.iso" ) && ok "upstream reads edited iso" || bad "upstream reads edited iso"
D="$W/x4/Game_edit"
[ ! -e "$D/\$SystemUpdate" ] && ok "delete folder" || bad "delete folder"
[ ! -e "$D/many/file_010.dat" ] && [ -f "$D/many/file_011.dat" ] && ok "delete file" || bad "delete file"
[ ! -e "$D/Media/Sub" ] && ok "delete nested folder" || bad "delete nested folder"
cmp "$D/Media/big.bin" "$W/new_big.bin" && ok "replace big file" || bad "replace big file"
[ "$(cat "$D/readme.TXT")" = "hello replaced" ] && ok "replace (case-insensitive path, shrinks)" || bad "replace shrink"
cmp "$D/many/added.dat" "$W/added.dat" && cmp "$D/Empty/in_empty.dat" "$W/added.dat" && cmp "$D/toplevel_added.dat" "$W/added.dat" && ok "add files (incl. into empty dir)" || bad "add files"
python3 - "$D/default.xbe" "$W/new_default.xbe" <<'PY' && ok "replaced xbe got media patch" || bad "replaced xbe got media patch"
import sys
a=open(sys.argv[1],'rb').read(); b=open(sys.argv[2],'rb').read()
assert len(a)==len(b) and a[507]==0xeb and b[507]==0x7d
PY
for f in default.xbe; do :; done
cmp "$D/exact_sector.bin" "$T/exact_sector.bin" && cmp "$D/many/file_099.dat" "$T/many/file_099.dat" && ok "untouched files intact" || bad "untouched files intact"
H $LIST "$W/Game_edit.iso" | grep -q $'^T\tF\t14\treadme.TXT$' && ok "list after edit" || bad "list after edit"

echo "== edit errors"
printf 'D\tdoes/not/exist.bin\n' > "$W/bad1.txt"
H $REBUILD "$W/Game.iso" "$W" "Game_bad.iso" "$W/bad1.txt" 1 >/dev/null 2>&1 && bad "missing target must fail" || ok "missing target fails"
[ ! -e "$W/Game_bad.iso" ] && ok "no output on failure" || bad "no output on failure"
printf 'R\tMedia\t%s\n' "$W/added.dat" > "$W/bad2.txt"
H $REBUILD "$W/Game.iso" "$W" "Game_bad.iso" "$W/bad2.txt" 1 >/dev/null 2>&1 && bad "replace folder must fail" || ok "replace folder fails"
H $LIST "$W/added.dat" >/dev/null 2>&1 && bad "non-iso must fail" || ok "non-iso fails"

echo "== redump style iso (video partition offset)"
for OFF in 0x0FD90000 0x02080000 0x18300000; do
  python3 - "$W/Game.iso" "$W/redump.iso" $OFF <<'PY'
import sys
src, dst, off = sys.argv[1], sys.argv[2], int(sys.argv[3], 16)
with open(src,'rb') as f, open(dst,'wb') as g:
    g.truncate(off); g.seek(off); g.write(f.read())
    g.truncate(max(g.tell(), 0x18400000))   # real redumps are GBs; upstream probes up to ~0x18310000
PY
  H $LIST "$W/redump.iso" > "$W/rl.log" 2>&1
  [ "$(grep -c $'^T\tF\t' "$W/rl.log")" = "$EXPECT" ] && ok "list with offset $OFF" || bad "list with offset $OFF"
  H $EXTRACT "$W/redump.iso" "$W/re_$OFF" 0 >/dev/null 2>&1; diff -r "$W/x1/Game" "$W/re_$OFF" >/dev/null && ok "extract with offset $OFF" || bad "extract with offset $OFF"
  printf 'D\tMedia/Sub\nR\treadme.TXT\t%s\n' "$W/new_small.txt" > "$W/e_re.txt"
  H $REBUILD "$W/redump.iso" "$W" "re_out.iso" "$W/e_re.txt" 1 >/dev/null 2>&1 && ok "rebuild with offset $OFF" || bad "rebuild with offset $OFF"
  rm -rf "$W/xr"; mkdir "$W/xr"; ( cd "$W/xr" && "$W/orig" -q -x "$W/re_out.iso" ) && [ "$(cat "$W/xr/re_out/readme.TXT")" = "hello replaced" ] && [ ! -e "$W/xr/re_out/Media/Sub" ] && ok "offset iso rebuilt correctly $OFF" || bad "offset iso rebuilt $OFF"
  rm -f "$W/redump.iso" "$W/re_out.iso"
done

echo "== CLI passthrough (original command line)"
H $CLI "$W" -l "$W/Game.iso" > "$W/cli.log" 2>&1; grep -q "many/file_001.dat" "$W/cli.log" && ok "cli -l" || bad "cli -l"
mkdir "$W/cwd"; H $CLI "$W/cwd" "$W/Game.iso" -d "$W/cli_out" >/dev/null 2>&1; diff -r "$W/x1/Game" "$W/cli_out" >/dev/null && ok "cli extract (options after file name)" || bad "cli extract"
H $CLI "$W" -v 2>&1 | grep -q "extract-xiso v" && ok "cli -v" || bad "cli -v"
H $CLI "$W" >/dev/null 2>&1; [ $? -ne 0 ] && ok "cli usage exits non-zero" || bad "cli usage exit code"

echo
[ "$FAIL" = "0" ] && echo "ALL TESTS PASSED" || { echo "SOME TESTS FAILED"; exit 1; }
