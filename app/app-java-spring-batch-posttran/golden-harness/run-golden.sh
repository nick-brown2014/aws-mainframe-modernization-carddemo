#!/usr/bin/env bash
# Builds CBTRN02C with GnuCOBOL (EBCDIC sign overpunch) and produces POSTTRAN golden outputs.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
APP=${APP:-$(cd "$HERE/../.." && pwd)}
OUT=${1:-$HERE/../src/test/resources/golden/posttran}
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
cobc -x -fsign=EBCDIC -I "$APP/cpy" -o "$W/cbtrn02c" "$APP/cbl/CBTRN02C.cbl"
cobc -x -fsign=EBCDIC -o "$W/loadidx" "$HERE/LOADIDX.cbl"
D=$APP/data/ASCII
cd "$W"
awk '{printf "%-50s\n", $0}' "$D/cardxref.txt" > xref.txt
tr -d '\r' < "$D/acctdata.txt" > acct.txt
tr -d '\r' < "$D/tcatbal.txt" > tcat.txt
tr -d '\r\n' < "$D/dailytran.txt" > dalytran.dat
export DD_XREFSEQ=xref.txt DD_ACCTSEQ=acct.txt DD_TCATSEQ=tcat.txt DD_XREFFILE=xref.idx \
       DD_ACCTFILE=acct.idx DD_TCATBALF=tcat.idx DD_TRANFILE=tran.idx \
       DD_DALYTRAN=dalytran.dat DD_DALYREJS=dalyrejs.dat
./loadidx LOAD
set +e; ./cbtrn02c > sysout.txt; rc=$?; set -e
export DD_ACCTSEQ=acct.out DD_TCATSEQ=tcat.out DD_TRANSEQ=tran.out
./loadidx DUMP
mkdir -p "$OUT"
fold -w430 dalyrejs.dat | awk '{print}' > "$OUT/dalyrejs.txt"
awk '{printf "%-350s\n",$0}' tran.out > "$OUT/transact.txt"
awk '{printf "%-300s\n",$0}' acct.out > "$OUT/acctdata.txt"
awk '{printf "%-50s\n",$0}' tcat.out > "$OUT/tcatbal.txt"
cp sysout.txt "$OUT/sysout.txt"
echo "$rc" > "$OUT/returncode.txt"
echo "CBTRN02C RC=$rc"
