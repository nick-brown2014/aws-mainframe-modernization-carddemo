# CBSTM03A / CBSTM03B Migration Specification: COBOL Batch to Java + Spring Batch

| | |
|---|---|
| Source programs | `app/cbl/CBSTM03A.CBL` (main, 924 lines) and `app/cbl/CBSTM03B.CBL` (called file-I/O subprogram, 230 lines) |
| Source JCL | `app/jcl/CREASTMT.JCL`, step `STEP040 EXEC PGM=CBSTM03A` (preceded by IDCAMS `DELETE/DEFINE`, `SORT` + `OUTREC`, IDCAMS `REPRO`, `IEFBR14` cleanup) |
| Copybooks | `app/cpy/COSTM01.CPY` (statement transaction record), `CUSTREC.cpy` (customer), `CVACT01Y.cpy` (account), `CVACT03Y.cpy` (card xref) |
| Shared-table reference | `docs/specs/CBTRN02C-migration-spec.md` §4.3 (`card_xref`), §4.4/§4.9 (`account`), §4.6/§4.9 (`transaction`) — **not redefined here** |
| Target | Java 17+, Spring Boot 3.x, Spring Batch 5.x; one chunk step over the card cross-reference producing two fixed-width files (80-byte text statements, 100-byte HTML statement lines) |
| Status | Draft for review |

This document is derived directly from the COBOL source, copybooks and JCL listed above and is
intended to let an implementation team build and verify the Java replacement without re-reading
the COBOL. Line references `A:L123` point into `CBSTM03A.CBL`, `B:L45` into `CBSTM03B.CBL`,
`JCL L53` into `CREASTMT.JCL`, `CUSTREC L7` etc. into the copybooks.

---

## 1. Overview & Purpose

`CBSTM03A` is the CardDemo **account statement generator**, run by job `CREASTMT`. For every
record in the card cross-reference file (`XREFFILE`, read sequentially in card-number order) it:

1. Reads the customer (`CUSTFILE`, keyed by `XREF-CUST-ID`) and the account (`ACCTFILE`, keyed by
   `XREF-ACCT-ID`).
2. Finds the card's transactions in an **in-memory table** that was loaded once at start-up from
   `TRNXFILE` — a KSDS built by the JCL from the transaction master, re-keyed as
   *card number + transaction id* so that a card's transactions are contiguous and ordered.
3. Writes one **plain-text statement** (80-byte records) to `STMTFILE` and one complete **HTML
   document** (100-byte records, one tag/line per record) to `HTMLFILE`: customer name/address,
   account id, current balance, FICO score, one row per transaction (id, description, amount) and
   a net total.

All file I/O for the four input files is delegated to `CBSTM03B` through a single 1,037-byte
`CALL … USING` area (§4.6); `CBSTM03B` performs the `OPEN`/`READ`/`READ` by key/`CLOSE` and hands
back the two-byte `FILE STATUS`. The two output files are written directly by `CBSTM03A`.

Any status other than `'00'`/`'04'` (and `'10'` at end of the sequential files) abends the job via
`CEE3ABD`. No `RETURN-CODE` other than 0 is ever set.

The Java replacement must reproduce the text file **byte for byte** and the HTML file **tag for
tag, record for record** (same 100-byte records in the same order) for the same input, subject
to the explicitly documented deviations (§5.8), and must expose the same success/failure exit
semantics.

### 1.1 Job context (`CREASTMT.JCL`)

| Step | Program | Purpose | Lines |
|---|---|---|---|
| `DELDEF01` | `IDCAMS` | `DELETE AWS.M2.CARDDEMO.TRXFL.SEQ`, `DELETE … TRXFL.VSAM.KSDS CLUSTER`, `SET MAXCC=0`, `DEFINE CLUSTER … KEYS(32 0) RECORDSIZE(350 350) INDEXED` — the working transaction KSDS keyed on **card number (16) + transaction id (16)** | L22-40 |
| `STEP010` | `SORT` | `SORTIN = AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS` (350-byte `CVTRA05Y` records). `SORT FIELDS=(263,16,CH,A,1,16,CH,A)` (card number, then transaction id). `OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)` → card number first, then original bytes 1-262, then original bytes 279-328 (see §4.2 and §9.2 R2). Output `TRXFL.SEQ`, `LRECL=350` | L44-55 |
| `STEP020` | `IDCAMS REPRO` | Loads `TRXFL.SEQ` into `TRXFL.VSAM.KSDS` | L56-62 |
| `STEP030` | `IEFBR14` | Deletes the previous `STATEMNT.HTML` and `STATEMNT.PS` (`DISP=(MOD,DELETE,DELETE)`) | L66-75 |
| `STEP040` | `CBSTM03A` | The programs specified here. DDs: `TRNXFILE`, `XREFFILE`, `ACCTFILE`, `CUSTFILE`, `STMTFILE` (`LRECL=80`), `HTMLFILE` (`LRECL=100`) | L79-96 |

Downstream: `app/jcl/TXT2PDF1.JCL` converts `AWS.M2.CARDDEMO.STATEMNT.PS` to PDF, so the 80-byte
text layout is a contractual interface, not just a human-readable artifact.

---

## 2. Current-State (COBOL) Analysis

### 2.1 Control flow — `CBSTM03A`

The main program is written as a **state machine driven by `ALTER`/`GO TO`** (A:L296-314,
A:L726-728): `WS-FL-DD` names the next file to open; `0000-START` `ALTER`s the target of
`8100-FILE-OPEN` and jumps to it; each open paragraph sets the next `WS-FL-DD` and jumps back to
`0000-START`. The equivalent structured flow is:

```
PROCEDURE DIVISION                                                (A:L262)
  -- z/OS control-block walk: PSA -> TCB -> TIOT, DISPLAY job/step name and every DD name
     with "valid UCB"/"null UCB"                                  (A:L266-291)   [non-portable, §5.8 D12]
  OPEN OUTPUT STMT-FILE HTML-FILE                                 (A:L293)       [no FILE STATUS; runtime abend on failure]
  INITIALIZE WS-TRNX-TABLE WS-TRN-TBL-CNTR                        (A:L294)

  -- WS-FL-DD = 'TRNXFILE' (initial VALUE)
  8100-TRNXFILE-OPEN:   CALL 03B (TRNXFILE, 'O');  RC not 00/04 -> abend          (A:L730-742)
                        CALL 03B (TRNXFILE, 'R');  RC not 00/04 -> abend  <-- empty file (RC 10) abends, §5.8 D2
                        TRNX-RECORD <- FLDT; WS-SAVE-CARD <- card; CR-CNT=1; TR-CNT=0
                        WS-FL-DD='READTRNX'                                       (A:L756-761)
  8500-READTRNX-READ:   load every transaction into WS-CARD-TBL (§5.2)            (A:L818-847)
  8599-EXIT:            WS-TRCT(CR-CNT) <- TR-CNT; WS-FL-DD='XREFFILE'            (A:L849-852)
  8200-XREFFILE-OPEN:   CALL 03B (XREFFILE,'O'); RC not 00/04 -> abend; WS-FL-DD='CUSTFILE'   (A:L765-780)
  8300-CUSTFILE-OPEN:   CALL 03B (CUSTFILE,'O'); ...; WS-FL-DD='ACCTFILE'         (A:L783-798)
  8400-ACCTFILE-OPEN:   CALL 03B (ACCTFILE,'O'); ...; GO TO 1000-MAINLINE         (A:L801-815)

1000-MAINLINE                                                     (A:L316-342)
  PERFORM UNTIL END-OF-FILE = 'Y'
     PERFORM 1000-XREFFILE-GET-NEXT        CALL 03B (XREFFILE,'R'): 00 ok, 10 -> EOF, other -> abend
     IF END-OF-FILE = 'N'
        PERFORM 2000-CUSTFILE-GET          CALL 03B (CUSTFILE,'K', key=XREF-CUST-ID, len 9): 00 ok, other -> abend
        PERFORM 3000-ACCTFILE-GET          CALL 03B (ACCTFILE,'K', key=XREF-ACCT-ID, len 11): 00 ok, other -> abend
        PERFORM 5000-CREATE-STATEMENT      text header lines + HTML header/customer/basic-details blocks
        MOVE 1 TO CR-JMP; MOVE ZERO TO WS-TOTAL-AMT
        PERFORM 4000-TRNXFILE-GET          emit the card's transactions from the table + totals + HTML footer
     END-IF
  END-PERFORM
  PERFORM 9100/9200/9300/9400-*-CLOSE     CALL 03B (dd,'C') ×4; RC not 00/04 -> abend
  CLOSE STMT-FILE HTML-FILE
9999-GOBACK. GOBACK.
```

Note that the program **never calls `CBSTM03B` with `'W'` or `'Z'`** although both are declared
(A:L78-79, B:L107-108) — and `CBSTM03B` does not implement them (§2.4).

### 2.2 Paragraph reference — `CBSTM03A`

| Paragraph | Lines | Responsibility |
|---|---|---|
| (unnamed prologue) | A:L266-294 | PSA/TCB/TIOT walk with `DISPLAY`s; `OPEN OUTPUT` both statement files; `INITIALIZE` the transaction table and counters. |
| `0000-START` | A:L296-314 | `EVALUATE WS-FL-DD` dispatcher (`TRNXFILE`/`XREFFILE`/`CUSTFILE`/`ACCTFILE` → `ALTER`+`GO TO 8100-FILE-OPEN`; `READTRNX` → `GO TO 8500`; other → `GO TO 9999-GOBACK`). |
| `1000-MAINLINE` | A:L316-342 | Per-xref loop (above), closes, `GOBACK`. |
| `1000-XREFFILE-GET-NEXT` | A:L345-366 | Sequential xref read via 03B; `'10'` → `END-OF-FILE='Y'`; other non-`00` → `ERROR READING XREFFILE`, `RETURN CODE: nn`, abend. Moves the 1000-byte `FLDT` into `CARD-XREF-RECORD` (50 bytes; even after EOF — the record area then holds spaces, harmless). |
| `2000-CUSTFILE-GET` | A:L368-390 | Keyed customer read: `WS-M03B-KEY <- XREF-CUST-ID` (9 digits, left-justified in X(25)), `KEY-LN = LENGTH OF XREF-CUST-ID` (=9); non-`00` → `ERROR READING CUSTFILE`, abend. `CUSTOMER-RECORD <- FLDT`. |
| `3000-ACCTFILE-GET` | A:L392-414 | Keyed account read, key `XREF-ACCT-ID`, `KEY-LN` = 11; non-`00` → `ERROR READING ACCTFILE`, abend. `ACCOUNT-RECORD <- FLDT`. |
| `4000-TRNXFILE-GET` | A:L416-456 | Scan table for `XREF-CARD-NUM` (§5.3); for each matching transaction `PERFORM 6000-WRITE-TRANS` and `ADD TRNX-AMT TO WS-TOTAL-AMT`; then write `ST-LINE12`, `ST-LINE14A` (total), `ST-LINE15`; write HTML footer (8 records). |
| `5000-CREATE-STATEMENT` | A:L458-504 | `INITIALIZE STATEMENT-LINES`; write `ST-LINE0`; HTML header (5100); build `ST-NAME`, `ST-ADD1..3`, `ST-ACCT-ID`, `ST-CURR-BAL`, `ST-FICO-SCORE`; HTML customer/basic blocks (5200); write `ST-LINE1..ST-LINE13` in the fixed order of §7.1. |
| `5100-WRITE-HTML-HEADER` | A:L506-555 | 22 HTML records (§7.2 rows 1-22). |
| `5200-WRITE-HTML-NMADBS` | A:L558-672 | 34 HTML records: name/address paragraphs via `STRING`, "Basic Details" and "Transaction Summary" section rows, column header cells (§7.2 rows 23-56). |
| `6000-WRITE-TRANS` | A:L675-723 | One text detail line (`ST-LINE14`) + 11 HTML records per transaction. |
| `8100-FILE-OPEN` | A:L726-728 | `GO TO` stub whose target is rewritten by `ALTER`. |
| `8100-TRNXFILE-OPEN` … `8400-ACCTFILE-OPEN` | A:L730-816 | Open (and, for `TRNXFILE`, first read) via 03B; chain to next state. |
| `8500-READTRNX-READ` / `8599-EXIT` | A:L818-853 | Table load loop (§5.2); subsequent reads accept **only** `'00'` (not `'04'`), `'10'` ends the load. |
| `9100`–`9400-*-CLOSE` | A:L856-919 | Close via 03B; RC not `00`/`04` → `ERROR CLOSING <dd>`, abend. |
| `9999-ABEND-PROGRAM` | A:L921-923 | `DISPLAY 'ABENDING PROGRAM'`, `CALL 'CEE3ABD'` **with no arguments** (§5.8 D13). |

### 2.3 Control flow — `CBSTM03B` (called once per I/O operation)

```
PROCEDURE DIVISION USING LK-M03B-AREA                             (B:L114)
0000-START. EVALUATE LK-M03B-DD                                    (B:L118-128)
   'TRNXFILE' -> 1000-TRNXFILE-PROC THRU 1999-EXIT
   'XREFFILE' -> 2000-XREFFILE-PROC THRU 2999-EXIT
   'CUSTFILE' -> 3000-CUSTFILE-PROC THRU 3999-EXIT
   'ACCTFILE' -> 4000-ACCTFILE-PROC THRU 4999-EXIT
   OTHER      -> GOBACK (LK-M03B-RC untouched)                     [§5.8 D10]
9999-GOBACK. GOBACK.

n000-<file>-PROC:                                                  (B:L133-229)
   IF M03B-OPEN    OPEN INPUT <file>;                         GO TO n900-EXIT
   IF M03B-READ    READ <file> INTO LK-M03B-FLDT;             GO TO n900-EXIT     (TRNX, XREF only)
   IF M03B-READ-K  MOVE LK-M03B-KEY(1:LK-M03B-KEY-LN) TO <record key>;
                   READ <file> INTO LK-M03B-FLDT;             GO TO n900-EXIT     (CUST, ACCT only)
   IF M03B-CLOSE   CLOSE <file>;                              GO TO n900-EXIT
   (fall through for any other operation code)
n900-EXIT.  MOVE <file>-STATUS TO LK-M03B-RC                      [stale status if nothing was executed, §5.8 D10]
n999-EXIT.  EXIT.
```

`CBSTM03B` keeps its file state in its own `WORKING-STORAGE`/`FILE SECTION` between calls
(standard static subprogram semantics; no `INITIAL`/`CANCEL`), which is what makes the
open-once/read-many protocol work.

### 2.4 Operation-code matrix (what each file actually supports)

| `LK-M03B-OPER` | 88-name | `TRNXFILE` | `XREFFILE` | `CUSTFILE` | `ACCTFILE` |
|---|---|---|---|---|---|
| `O` | `M03B-OPEN` | `OPEN INPUT` (B:L136) | `OPEN INPUT` (B:L160) | `OPEN INPUT` (B:L184) | `OPEN INPUT` (B:L209) |
| `R` | `M03B-READ` | sequential `READ` (B:L141) | sequential `READ` (B:L165) | **not implemented** | **not implemented** |
| `K` | `M03B-READ-K` | **not implemented** | **not implemented** | keyed `READ` (B:L189-190) | keyed `READ` (B:L214-215) |
| `C` | `M03B-CLOSE` | `CLOSE` (B:L147) | `CLOSE` (B:L171) | `CLOSE` (B:L196) | `CLOSE` (B:L221) |
| `W` | `M03B-WRITE` | not implemented | not implemented | not implemented | not implemented |
| `Z` | `M03B-REWRITE` | not implemented | not implemented | not implemented | not implemented |

"Not implemented" = no `IF` branch matches; control falls to `n900-EXIT` and the **previous**
`FILE STATUS` of that file is returned unchanged.

### 2.5 File SELECT / FILE STATUS handling

`CBSTM03A` has only the two output files; `CBSTM03B` has the four inputs.

| Program | COBOL file | DD | Organization / access | Record key | FD record | FILE STATUS |
|---|---|---|---|---|---|---|
| 03A (A:L33-36) | `STMT-FILE` | `STMTFILE` | sequential (default) | — | `FD-STMTFILE-REC PIC X(80)` | **none declared** — any OPEN/WRITE/CLOSE error is a runtime abend |
| 03A (A:L37-40) | `HTML-FILE` | `HTMLFILE` | sequential | — | `FD-HTMLFILE-REC PIC X(100)` | none declared |
| 03B (B:L31-35) | `TRNX-FILE` | `TRNXFILE` | `INDEXED`, `SEQUENTIAL` | `FD-TRNXS-ID` = card(16)+id(16) @1 | 350 = 32 + 318 (B:L59-63) | `TRNXFILE-STATUS` → returned in `LK-M03B-RC` |
| 03B (B:L37-41) | `XREF-FILE` | `XREFFILE` | `INDEXED`, `SEQUENTIAL` | `FD-XREF-CARD-NUM` X(16) @1 | 50 = 16 + 34 (B:L66-68) | `XREFFILE-STATUS` |
| 03B (B:L43-47) | `CUST-FILE` | `CUSTFILE` | `INDEXED`, `RANDOM` | `FD-CUST-ID` X(09) @1 | 500 = 9 + 491 (B:L71-73) | `CUSTFILE-STATUS` |
| 03B (B:L49-53) | `ACCT-FILE` | `ACCTFILE` | `INDEXED`, `RANDOM` | `FD-ACCT-ID` 9(11) @1 | 300 = 11 + 289 (B:L76-78) | `ACCTFILE-STATUS` |

Status interpretation by the **caller** (`CBSTM03A`):

| Operation | Accepted | EOF | Fatal (`DISPLAY 'ERROR <op> <dd>'`, `'RETURN CODE: '` + RC, abend) |
|---|---|---|---|
| `O` (all four) | `00`, `04` | — | anything else (`35` missing file, `39` attribute mismatch, `9x`) |
| `R` `TRNXFILE` first read (A:L748) | `00`, `04` | **`10` is fatal** (empty file) | others |
| `R` `TRNXFILE` subsequent (A:L837) | `00` | `10` → end of load | others **including `04`** |
| `R` `XREFFILE` (A:L353) | `00` | `10` → `END-OF-FILE='Y'` | others including `04` |
| `K` `CUSTFILE`/`ACCTFILE` (A:L379, L403) | `00` | — | anything else (`23` not found is the normal failure) |
| `C` (all four) | `00`, `04` | — | others |

Since `READ … INTO LK-M03B-FLDT` moves a 350/50/500/300-byte record into a 1000-byte area, the
caller always receives the record left-justified and space-padded to 1000 bytes; on EOF the area
still holds the caller's `MOVE SPACES` (A:L350 etc.).

---

## 3. Files / Datasets

| DD | Dataset (from `CREASTMT.JCL`) | JCL lines | Direction | Organization | Access | Key (offset, len) | Copybook / layout | LRECL |
|---|---|---|---|---|---|---|---|---|
| `TRNXFILE` | `AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS` (rebuilt every run from `TRANSACT.VSAM.KSDS` by `DELDEF01`/`STEP010`/`STEP020`) | L83, L29-39, L44-62 | in | VSAM KSDS | sequential (key order = card number, transaction id) | (0, 32) | `COSTM01` `TRNX-RECORD` | 350 |
| `XREFFILE` | `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` | L84 | in | VSAM KSDS | sequential (card-number order) | (0, 16) | `CVACT03Y` `CARD-XREF-RECORD` | 50 |
| `ACCTFILE` | `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` | L85 | in | VSAM KSDS | random by key | (0, 11) | `CVACT01Y` `ACCOUNT-RECORD` | 300 |
| `CUSTFILE` | `AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS` | L86 | in | VSAM KSDS | random by key | (0, 9) | `CUSTREC` `CUSTOMER-RECORD` | 500 |
| `STMTFILE` | `AWS.M2.CARDDEMO.STATEMNT.PS`, `DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)` | L87-91 | out | PS FB | sequential | — | `STATEMENT-LINES` (A:L85-146), 80-byte records | 80 |
| `HTMLFILE` | `AWS.M2.CARDDEMO.STATEMNT.HTML`, `DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)` | L92-96 | out | PS FB | sequential | — | `HTML-LINES` (A:L148-223), 100-byte records | 100 |
| `SYSOUT`/`SYSPRINT` | `SYSOUT=*` | L81-82 | out | — | — | — | `DISPLAY` output (§7.3) | — |

Sample data for offset cross-checks (`app/data/ASCII/`): `cardxref.txt` (50 × 50 bytes, sorted by
card), `custdata.txt` (50 × 500), `acctdata.txt` (50 × 300), `dailytran.txt` (300 × 350 in
`CVTRA05Y` layout — i.e. **before** the `OUTREC` re-keying; 50 distinct cards × 6 transactions
each, which fits the 51 × 10 table of §4.7). There is no sample of `TRXFL`; it is derived
deterministically from `dailytran.txt` by the mapping in §4.2.

---

## 4. Data Model & Field Mappings

### 4.1 Type-mapping conventions

As CBTRN02C §4.1: `X(n)` → `String` fixed width; `9(n)` identifiers → zero-padded `String` (or
`long` with `%0nd` rendering); `S9(n)V99` display → `BigDecimal` scale 2 with zoned sign overpunch
(CBTRN02C §4.7); `COMP` `S9(4)` → `short`/`int` (2-byte big-endian binary, working storage only);
`COMP-3 S9(9)V99` → `BigDecimal` scale 2 (6-byte packed, working storage only). Offsets are
1-based byte positions.

### 4.2 `COSTM01` — Statement transaction record (`TRNX-RECORD`, 350 bytes, KSDS key = 32 bytes) — NEW layout over shared data

This is the **same data** as the transaction master (`CVTRA05Y`, CBTRN02C §4.6) re-ordered by the
JCL `OUTREC` (JCL L54). No new table is needed: it is a *view* of `transaction` ordered by
`(card_num, tran_id)`.

| Field | PIC | Offset | Len | From `CVTRA05Y` byte range (via `OUTREC`) | Java type | Used by 03A |
|---|---|---|---|---|---|---|
| `TRNX-KEY.TRNX-CARD-NUM` | X(16) | 1 | 16 | 263-278 (`TRAN-CARD-NUM`) | `String` | grouping key |
| `TRNX-KEY.TRNX-ID` | X(16) | 17 | 16 | 1-16 (`TRAN-ID`) | `String` | detail col 1-16 |
| `TRNX-REST.TRNX-TYPE-CD` | X(02) | 33 | 2 | 17-18 | `String` | no |
| `TRNX-REST.TRNX-CAT-CD` | 9(04) | 35 | 4 | 19-22 | `int` | no |
| `TRNX-REST.TRNX-SOURCE` | X(10) | 39 | 10 | 23-32 | `String` | no |
| `TRNX-REST.TRNX-DESC` | X(100) | 49 | 100 | 33-132 | `String` | detail col 18-66 (**first 49 chars**) |
| `TRNX-REST.TRNX-AMT` | S9(09)V99 | 149 | 11 | 133-143 | `BigDecimal(2)` | detail amount; total |
| `TRNX-REST.TRNX-MERCHANT-ID` | 9(09) | 160 | 9 | 144-152 | `long` | no |
| `TRNX-REST.TRNX-MERCHANT-NAME` | X(50) | 169 | 50 | 153-202 | `String` | no |
| `TRNX-REST.TRNX-MERCHANT-CITY` | X(50) | 219 | 50 | 203-252 | `String` | no |
| `TRNX-REST.TRNX-MERCHANT-ZIP` | X(10) | 269 | 10 | 253-262 | `String` | no |
| `TRNX-REST.TRNX-ORIG-TS` | X(26) | 279 | 26 | 279-304 | `String` | no |
| `TRNX-REST.TRNX-PROC-TS` | X(26) | 305 | 26 | **305-328 only** (24 bytes; `OUTREC` copies 50 bytes from 279) — last 2 bytes and the 20-byte filler are not populated (§9.2 R2) | `String` | no |
| `TRNX-REST.FILLER` | X(20) | 331 | 20 | — | — | no |

`TRNX-REST` (offset 33, 318 bytes) is what `CBSTM03A` stores per table slot (`WS-TRAN-REST`,
A:L230). Only **card number, transaction id, description and amount** influence the output.

### 4.3 `CUSTREC` — Customer master (`CUSTOMER-RECORD`, 500 bytes, KSDS key = customer id) — NEW

| Field | PIC | Offset | Len | Java type / column | Used by 03A |
|---|---|---|---|---|---|
| `CUST-ID` | 9(09) | 1 | 9 | `String(9)` / `cust_id NUMERIC(9) PK` | key |
| `CUST-FIRST-NAME` | X(25) | 10 | 25 | `String` / `first_name CHAR(25)` | name line (first word) |
| `CUST-MIDDLE-NAME` | X(25) | 35 | 25 | `String` / `middle_name CHAR(25)` | name line (first word) |
| `CUST-LAST-NAME` | X(25) | 60 | 25 | `String` / `last_name CHAR(25)` | name line (first word) |
| `CUST-ADDR-LINE-1` | X(50) | 85 | 50 | `String` / `addr_line_1 CHAR(50)` | address line 1 (whole field) |
| `CUST-ADDR-LINE-2` | X(50) | 135 | 50 | `String` / `addr_line_2 CHAR(50)` | address line 2 (whole field) |
| `CUST-ADDR-LINE-3` | X(50) | 185 | 50 | `String` / `addr_line_3 CHAR(50)` | address line 3 (first word) |
| `CUST-ADDR-STATE-CD` | X(02) | 235 | 2 | `String` / `addr_state_cd CHAR(2)` | address line 3 |
| `CUST-ADDR-COUNTRY-CD` | X(03) | 237 | 3 | `String` / `addr_country_cd CHAR(3)` | address line 3 |
| `CUST-ADDR-ZIP` | X(10) | 240 | 10 | `String` / `addr_zip CHAR(10)` | address line 3 (first word) |
| `CUST-PHONE-NUM-1` | X(15) | 250 | 15 | `String` / `phone_num_1 CHAR(15)` | no |
| `CUST-PHONE-NUM-2` | X(15) | 265 | 15 | `String` / `phone_num_2 CHAR(15)` | no |
| `CUST-SSN` | 9(09) | 280 | 9 | `String(9)` / `ssn CHAR(9)` (sensitive — encrypt/mask at rest) | no |
| `CUST-GOVT-ISSUED-ID` | X(20) | 289 | 20 | `String` / `govt_issued_id CHAR(20)` | no |
| `CUST-DOB-YYYYMMDD` | X(10) | 309 | 10 | `String` / `dob CHAR(10)` (sample data is `YYYY-MM-DD`, e.g. `1961-06-08`, despite the name) | no |
| `CUST-EFT-ACCOUNT-ID` | X(10) | 319 | 10 | `String` / `eft_account_id CHAR(10)` | no |
| `CUST-PRI-CARD-HOLDER-IND` | X(01) | 329 | 1 | `String` / `pri_card_holder_ind CHAR(1)` | no |
| `CUST-FICO-CREDIT-SCORE` | 9(03) | 330 | 3 | `int` (rendered `%03d`) / `fico_credit_score NUMERIC(3)` | "FICO Score" line |
| `FILLER` | X(168) | 333 | 168 | — | no |

Sample check (`custdata.txt` record 1): `000000001|Immanuel|Madeline|Kessler|618 Deshaun Route|Apt. 802|Altenwerthshire|NC|USA|12546|(908)119-8310|(373)693-8684|020973888|000000000000493684371961-06-08…`
— the field boundaries above reproduce these values.

### 4.4 `CVACT01Y` — Account master (`ACCOUNT-RECORD`, 300 bytes)

Shared with CBTRN02C (§4.4, table `account` §4.9) — **reused, not redefined**. Fields read by 03A:
`ACCT-ID` 9(11) @1 (→ "Account ID", 11 digits left-justified in a 20-byte field), `ACCT-CURR-BAL`
S9(10)V99 @13 (→ "Current Balance" via `9(9).99-`, see §4.9). The copybook's `ACCT-EXPIRAION-DATE`
misspelling is preserved in the COBOL; the Java field is `expirationDate` (already decided in
CBTRN02C).

### 4.5 `CVACT03Y` — Card cross-reference (`CARD-XREF-RECORD`, 50 bytes)

Shared (CBTRN02C §4.3, table `card_xref`). All three fields are used: `XREF-CARD-NUM` @1 (16) is
the statement grouping key, `XREF-CUST-ID` @17 (9) and `XREF-ACCT-ID` @26 (11) are the lookup
keys. The **statement driver is this file**: one statement per xref record, in card-number order.

### 4.6 `CBSTM03A` ↔ `CBSTM03B` CALL interface (`WS-M03B-AREA` A:L71-83 = `LK-M03B-AREA` B:L100-112)

Passed `BY REFERENCE` (default) as a single group; 1,037 bytes.

| Field | PIC | Offset | Len | Direction | Semantics |
|---|---|---|---|---|---|
| `M03B-DD` | X(08) | 1 | 8 | in | DD name selecting the file: `TRNXFILE`, `XREFFILE`, `CUSTFILE`, `ACCTFILE` (anything else → no-op) |
| `M03B-OPER` | X(01) | 9 | 1 | in | `O` open, `C` close, `R` sequential read, `K` keyed read, `W` write (declared, unimplemented), `Z` rewrite (declared, unimplemented) |
| `M03B-RC` | X(02) | 10 | 2 | out | two-byte COBOL `FILE STATUS` of the operation (caller pre-sets `'00'` via `MOVE ZERO`, A:L349 etc.) |
| `M03B-KEY` | X(25) | 12 | 25 | in (`K` only) | key value, left-justified, space-padded (numeric `MOVE` of `XREF-CUST-ID`/`XREF-ACCT-ID` yields the digits) |
| `M03B-KEY-LN` | S9(4) display | 37 | 4 | in (`K` only) | number of leading key bytes to use: 9 for `CUSTFILE`, 11 for `ACCTFILE` (`COMPUTE … = LENGTH OF …`, A:L374/L398). Used as `LK-M03B-KEY(1:LK-M03B-KEY-LN)` (B:L189/L214). A length of 0 or > 25 is a reference-modification error (not guarded). |
| `M03B-FLDT` | X(1000) | 41 | 1000 | out (`R`/`K`) | record image, left-justified, space-padded (caller pre-fills with spaces) |

Protocol as exercised by 03A: `O` once per file → many `R` (TRNX, XREF) or `K` (CUST, ACCT) → `C`
once per file. `K` on `CUST` moves `LK-M03B-KEY(1:9)` to `FD-CUST-ID PIC X(09)`; `K` on `ACCT`
moves `LK-M03B-KEY(1:11)` (alphanumeric) to `FD-ACCT-ID PIC 9(11)` — an alphanumeric-to-numeric
move that is well-defined because the source holds 11 digits.

### 4.7 Working-storage tables and numerics (`CBSTM03A`)

| Item | PIC / shape | Storage | Java equivalent | Notes |
|---|---|---|---|---|
| `WS-TRNX-TABLE.WS-CARD-TBL OCCURS 51` (A:L226) | 51 × (16 + 10 × 334) = 51 × 3,356 = 171,156 bytes | | none — replaced by a per-card query (§6) | **hard limit: 51 distinct cards** |
| `WS-CARD-NUM` (A:L227) | X(16) | | `String cardNum` | card of slot `i` |
| `WS-TRAN-TBL OCCURS 10` (A:L228) | 10 × 334 | | `List<StatementTransaction>` | **hard limit: 10 transactions per card** |
| `WS-TRAN-NUM` / `WS-TRAN-REST` (A:L229-230) | X(16) / X(318) | | `tranId`, remaining fields | `TRNX-REST` image |
| `WS-TRN-TBL-CTR.WS-TRCT OCCURS 51` (A:L231-233) | S9(4) COMP each | 2-byte binary | list size | transactions loaded for slot `i` |
| `CR-CNT`, `TR-CNT`, `CR-JMP`, `TR-JMP` (A:L59-63) | S9(4) COMP | 2-byte binary | loop indices | max 9,999 — irrelevant given the 51/10 limits |
| `WS-TOTAL-AMT` (A:L64-65) | S9(9)V99 COMP-3 | 6-byte packed | `BigDecimal` scale 2 | per-statement net total; `ADD` without `SIZE ERROR` |
| `WS-TRN-AMT` (A:L68) | S9(9)V99 display | 11 bytes zoned | — | intermediate copy of the total before editing (`MOVE WS-TOTAL-AMT TO WS-TRN-AMT`, A:L433) |
| `WS-SAVE-CARD` (A:L69) | X(16) | | `String` | previous card during table load |
| `WS-FL-DD` (A:L67) | X(8) VALUE `'TRNXFILE'` | | state enum | state-machine variable |
| `END-OF-FILE` (A:L70) | X VALUE `'N'` | | `boolean` | xref EOF |

Memory layout consequence (needed to reason about the overflow defect D1): `WS-TRAN-TBL(c, 11)`
addresses the same bytes as `WS-CARD-NUM(c+1)` followed by `WS-TRAN-TBL(c+1, 1..)`; and
`WS-CARD-TBL(52)` starts at the first byte after the table, i.e. `WS-TRN-TBL-CNTR` (A:L231) and
then `PSAPTR`/`BUMP-TIOT` (A:L235-237). Neither subscript is range-checked (no `SSRANGE`).

### 4.8 Plain-text statement layouts (`STATEMENT-LINES`, A:L85-146; every line is exactly 80 bytes)

| Line | Bytes | Composition (col ranges 1-based) |
|---|---|---|
| `ST-LINE0` | 31+18+31 | 1-31 `*`×31, 32-49 `START OF STATEMENT`, 50-80 `*`×31 |
| `ST-LINE1` | 75+5 | 1-75 `ST-NAME`, 76-80 spaces |
| `ST-LINE2` | 50+30 | 1-50 `ST-ADD1` (= `CUST-ADDR-LINE-1`), 51-80 spaces |
| `ST-LINE3` | 50+30 | 1-50 `ST-ADD2` (= `CUST-ADDR-LINE-2`), 51-80 spaces |
| `ST-LINE4` | 80 | 1-80 `ST-ADD3` |
| `ST-LINE5` | 80 | `-`×80 |
| `ST-LINE6` | 33+14+33 | 1-33 spaces, 34-47 `Basic Details ` (13 chars + 1 pad space), 48-80 spaces |
| `ST-LINE7` | 20+20+40 | 1-20 `Account ID         :`, 21-40 `ST-ACCT-ID`, 41-80 spaces |
| `ST-LINE8` | 20+13+7+40 | 1-20 `Current Balance    :`, 21-33 `ST-CURR-BAL` (`9(9).99-`), 34-80 spaces |
| `ST-LINE9` | 20+20+40 | 1-20 `FICO Score         :`, 21-40 `ST-FICO-SCORE`, 41-80 spaces |
| `ST-LINE10` | 80 | `-`×80 |
| `ST-LINE11` | 30+20+30 | 1-30 spaces, 31-50 `TRANSACTION SUMMARY ` (19 + 1 pad space), 51-80 spaces |
| `ST-LINE12` | 80 | `-`×80 |
| `ST-LINE13` | 16+51+13 | 1-16 `Tran ID         `, 17-67 `Tran Details    ` padded to 51, 68-80 `  Tran Amount` |
| `ST-LINE14` | 16+1+49+1+13 | 1-16 `ST-TRANID`, 17 space, 18-66 `ST-TRANDT` (desc, 49 chars), 67 `$`, 68-80 `ST-TRANAMT` (`Z(9).99-`) |
| `ST-LINE14A` | 10+56+1+13 | 1-10 `Total EXP:`, 11-66 spaces, 67 `$`, 68-80 `ST-TOTAL-TRAMT` (`Z(9).99-`) |
| `ST-LINE15` | 32+16+32 | 1-32 `*`×32, 33-48 `END OF STATEMENT`, 49-80 `*`×32 |

`INITIALIZE STATEMENT-LINES` (A:L459) sets the named alphanumerics to spaces and the three edited
numerics to their zero rendering, and leaves every `FILLER VALUE` intact.

### 4.9 Numeric-edited pictures used on the statement

| PIC | Width | Rule | Examples (`_` = space) |
|---|---|---|---|
| `9(9).99-` (`ST-CURR-BAL`, A:L112) | 13 | **No zero suppression**: 9 integer digits, `.`, 2 decimals, trailing `-` if negative else space. Source `ACCT-CURR-BAL` is `S9(10)V99`: a balance ≥ 1,000,000,000.00 loses its high-order digit silently (D7). | `492.00` → `000000492.00_`; `-15.5` → `000000015.50-` |
| `Z(9).99-` (`ST-TRANAMT`, `ST-TOTAL-TRAMT`, A:L137/L142) | 13 | Integer digits zero-suppressed (no commas), decimals always printed (`.99`), trailing `-` if negative else space. Zero → `_________.00_`. | `183.88` → `______183.88_`; `-47.88` → `_______47.88-`; `1453.87` → `_____1453.87_` |

### 4.10 HTML constants (`HTML-LINES`, A:L148-223)

`HTML-FIXED-LN PIC X(100)` with 88-level `VALUE`s; `SET HTML-Lnn TO TRUE` moves the literal
left-justified and space-pads to 100. Exact literals (lengths in bytes; continuation lines
concatenated exactly as the compiler does — note the **two spaces** in `<table  align` and the
absence of a space in `5px;background-color` for L10/L15/L22-35/L30-42):

| Name | Len | Literal |
|---|---|---|
| `HTML-L01` | 15 | `<!DOCTYPE html>` |
| `HTML-L02` | 16 | `<html lang="en">` |
| `HTML-L03` | 6 | `<head>` |
| `HTML-L04` | 22 | `<meta charset="utf-8">` |
| `HTML-L05` | 32 | `<title>HTML Table Layout</title>` |
| `HTML-L06` | 7 | `</head>` |
| `HTML-L07` | 26 | `<body style="margin:0px;">` |
| `HTML-L08` | 85 | `<table  align="center" frame="box" style="width:70%; font:12px Segoe UI,sans-serif;">` |
| `HTML-LTRS` / `LTRE` | 4 / 5 | `<tr>` / `</tr>` |
| `HTML-LTDS` / `LTDE` | 4 / 5 | `<td>` (never used) / `</td>` |
| `HTML-L10` | 68 | `<td colspan="3" style="padding:0px 5px;background-color:#1d1d96b3;">` |
| `HTML-L15` | 66 | `<td colspan="3" style="padding:0px 5px;background-color:#FFAF33;">` |
| `HTML-L16` | 41 | `<p style="font-size:16px">Bank of XYZ</p>` |
| `HTML-L17` | 22 | `<p>410 Terry Ave N</p>` |
| `HTML-L18` | 23 | `<p>Seattle WA 99999</p>` |
| `HTML-L22-35` | 66 | `<td colspan="3" style="padding:0px 5px;background-color:#f2f2f2;">` |
| `HTML-L30-42` | 85 | `<td colspan="3" style="padding:0px 5px;background-color:#33FFD1; text-align:center;">` |
| `HTML-L31` | 43 | `<p style="font-size:16px">Basic Details</p>` |
| `HTML-L43` | 49 | `<p style="font-size:16px">Transaction Summary</p>` |
| `HTML-L47` | 83 | `<td style="width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;">` |
| `HTML-L48` | 37 | `<p style="font-size:16px">Tran ID</p>` |
| `HTML-L50` | 83 | `<td style="width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;">` |
| `HTML-L51` | 42 | `<p style="font-size:16px">Tran Details</p>` |
| `HTML-L53` | 84 | `<td style="width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;">` |
| `HTML-L54` | 36 | `<p style="font-size:16px">Amount</p>` |
| `HTML-L58` | 83 | `<td style="width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;">` |
| `HTML-L61` | 83 | `<td style="width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;">` |
| `HTML-L64` | 84 | `<td style="width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;">` |
| `HTML-L75` | 25 | `<h3>End of Statement</h3>` |
| `HTML-L78` / `L79` / `L80` | 8 / 7 / 7 | `</table>` / `</body>` / `</html>` |

Dynamic HTML records (built with `STRING`, all ≤ 100 bytes so `ON OVERFLOW` never triggers):

| Record | Composition | Max len |
|---|---|---|
| `HTML-L11` (A:L212-216) | `<h3>Statement for Account Number: ` (34) + `L11-ACCT` X(20) ← `ACCT-ID` (11 digits + 9 spaces) + `</h3>` (5) | 59 |
| name (A:L560-568) | `<p style="font-size:16px">` + `L23-NAME` **delimited by two spaces** (`L23-NAME` = first 50 bytes of `ST-NAME`) + `  ` + `</p>` | 82 |
| address 1/2 (A:L569-584) | `<p>` + `ST-ADD1`/`ST-ADD2` delimited by two spaces + `  ` + `</p>` | 59 |
| address 3 (A:L585-592) | `<p>` + `ST-ADD3` (80) delimited by two spaces + `  ` + `</p>` | 89 |
| account (A:L613-619) | `<p>Account ID         : ` (24) + `ST-ACCT-ID` (20, whole field incl. trailing spaces) + `</p>` | 48 |
| balance (A:L620-626) | `<p>Current Balance    : ` + `ST-CURR-BAL` (13) + `</p>` | 41 |
| FICO (A:L627-633) | `<p>FICO Score         : ` + `ST-FICO-SCORE` (20) + `</p>` | 48 |
| tran id (A:L686-692) | `<p>` + `ST-TRANID` (16) + `</p>` | 23 |
| tran desc (A:L698-704) | `<p>` + `ST-TRANDT` (49, whole field) + `</p>` | 56 |
| tran amount (A:L710-716) | `<p>` + `ST-TRANAMT` (13) + `</p>` | 20 |

`DELIMITED BY '*'` on the literals and on `ST-ACCT-ID`/`ST-CURR-BAL`/`ST-FICO-SCORE`/`ST-TRANID`/
`ST-TRANDT`/`ST-TRANAMT` is effectively `BY SIZE` (no `*` can occur) — so these fields are copied
**with their trailing spaces**. Only the name and the three address lines are trimmed, and only at
the first occurrence of **two consecutive spaces** (§5.5).

### 4.11 Relational design (additions only)

`card_xref`, `account`, `transaction` per CBTRN02C §4.9. New:

```sql
CREATE TABLE customer (
  cust_id             NUMERIC(9)  NOT NULL PRIMARY KEY,
  first_name          CHAR(25)    NOT NULL,
  middle_name         CHAR(25)    NOT NULL,
  last_name           CHAR(25)    NOT NULL,
  addr_line_1         CHAR(50)    NOT NULL,
  addr_line_2         CHAR(50)    NOT NULL,
  addr_line_3         CHAR(50)    NOT NULL,
  addr_state_cd       CHAR(2)     NOT NULL,
  addr_country_cd     CHAR(3)     NOT NULL,
  addr_zip            CHAR(10)    NOT NULL,
  phone_num_1         CHAR(15)    NOT NULL,
  phone_num_2         CHAR(15)    NOT NULL,
  ssn                 CHAR(9)     NOT NULL,   -- PII: column-level encryption / masking policy required
  govt_issued_id      CHAR(20)    NOT NULL,   -- PII
  dob                 CHAR(10)    NOT NULL,
  eft_account_id      CHAR(10)    NOT NULL,
  pri_card_holder_ind CHAR(1)     NOT NULL,
  fico_credit_score   NUMERIC(3)  NOT NULL
);

-- The TRXFL working KSDS is replaced by an index that gives the same access path:
CREATE INDEX ix_transaction_card_tran ON transaction(card_num, tran_id);
```

The statement files are generated artifacts and are not persisted relationally. Optionally a
`statement_run` audit table (run id, card, account, transaction count, total) may be added — not
required for parity.

---

## 5. Business Rules / Validation

### 5.1 Statement driver and ordering

* One statement per **card cross-reference record**, in `XREFFILE` key order (ascending 16-byte
  card number as text). An account with two cards yields two statements with identical account
  header data (D4). A card with no transactions still yields a full statement with zero rows and
  `Total EXP: … $         .00 ` (D3).
* Transactions that belong to a card **absent from `XREFFILE`** are loaded into the table but
  never printed (D19).

### 5.2 Transaction table load (`8100-TRNXFILE-OPEN` + `8500-READTRNX-READ`, A:L744-853)

```
first record R0 (fatal if none):   saveCard = R0.card; CR-CNT = 1; TR-CNT = 0
for each record R (starting with R0):
    if R.card == saveCard: TR-CNT += 1
    else:                  WS-TRCT(CR-CNT) = TR-CNT; CR-CNT += 1; TR-CNT = 1
    WS-CARD-NUM(CR-CNT) = R.card
    WS-TRAN-NUM(CR-CNT, TR-CNT) = R.id;  WS-TRAN-REST(CR-CNT, TR-CNT) = R.rest
    saveCard = R.card
    read next:  00 -> loop;  10 -> exit;  other -> 'ERROR READING TRNXFILE', abend
WS-TRCT(CR-CNT) = TR-CNT                                  (last group)
```

* Relies on the input being **grouped by card** (guaranteed by the KSDS key). A card that appears
  in two non-adjacent runs would occupy two slots; only the first slot ≤ the xref card would be
  matched by the scan in §5.3 (the scan stops at the first slot whose card is greater).
* **Bounds**: `TR-CNT` > 10 or `CR-CNT` > 51 silently overlays adjacent storage (§4.7). With 11
  transactions for card *c*, the 11th overwrites `WS-CARD-NUM(c+1)` and the first 318 bytes of
  slot *c+1*'s first transaction; the *next* card's group then starts by re-writing
  `WS-CARD-NUM(c+1)` (so the card name is repaired) but its first transaction has already been
  clobbered... The precise corruption pattern is not worth reproducing — see D1.

### 5.3 Transaction selection for a statement (`4000-TRNXFILE-GET`, A:L417-432)

```
for CR-JMP = 1 .. CR-CNT while WS-CARD-NUM(CR-JMP) <= XREF-CARD-NUM:
    if WS-CARD-NUM(CR-JMP) == XREF-CARD-NUM:
        for TR-JMP = 1 .. WS-TRCT(CR-JMP):
            write transaction (TR-JMP)   -- text line + 11 HTML records
            WS-TOTAL-AMT += TRNX-AMT
```

* Both the table and the xref are in ascending card order, so the scan is a linear merge that
  restarts from slot 1 for every xref record (O(cards²), irrelevant at 51).
* The `UNTIL` clause evaluates `WS-CARD-NUM(CR-JMP)` with `CR-JMP = CR-CNT + 1` before the
  `CR-JMP > CR-CNT` test can short-circuit (COBOL does not guarantee short-circuit evaluation);
  with 51 cards this reads slot 52 = `WS-TRN-TBL-CNTR` bytes (D11). Harmless unless those bytes
  compare equal to a card number.
* Java equivalent: `transactionRepository.findByCardNumOrderByTranId(card)`.

### 5.4 Lookups and failure semantics

| # | Lookup | Key | Failure (COBOL) | Java |
|---|---|---|---|---|
| L1 | customer | `XREF-CUST-ID` (9 digits) | `ERROR READING CUSTFILE` / `RETURN CODE: 23` / `ABENDING PROGRAM` / abend | `ReferenceDataMissingException` → step `FAILED`, same log lines |
| L2 | account | `XREF-ACCT-ID` (11 digits) | `ERROR READING ACCTFILE` / `RETURN CODE: 23` / abend | same |

Both are executed for every xref record, before anything for that statement is written; a failure
therefore leaves the output files ending exactly after the previous complete statement.

### 5.5 Text assembly rules (`STRING`, A:L462-481, L560-592)

| Target | Rule | Effect |
|---|---|---|
| `ST-NAME` X(75) | `STRING first DELIMITED BY ' ' ' ' middle DELIMITED BY ' ' ' ' last DELIMITED BY ' ' ' '` | Each name component contributes **only its first space-delimited word**; components are separated by one space and a trailing space is appended. Blank middle name → two consecutive spaces (`Aniya  Von `). Target was pre-blanked by `INITIALIZE`; `STRING` does not pad. Overflow (>75) impossible: max 25+1+25+1+25+1 = 78 > 75 — **possible** only if all three names are 25 characters without spaces; then the last 3 bytes are dropped (`ON OVERFLOW` absent). |
| `ST-ADD1`, `ST-ADD2` X(50) | plain `MOVE` | whole 50-byte field incl. embedded spaces |
| `ST-ADD3` X(80) | `STRING line3 DELIMITED BY ' ' ' ' state DELIMITED BY ' ' ' ' country DELIMITED BY ' ' ' ' zip DELIMITED BY ' ' ' '` | **First word only** of address line 3 (city `New Aricchester` → `New`), then state, country, zip (first word), each followed by one space (D5). |
| `ST-ACCT-ID` X(20) ← `ACCT-ID` 9(11) | numeric→alphanumeric `MOVE` | 11 digits left-justified + 9 spaces |
| `ST-CURR-BAL` | `MOVE ACCT-CURR-BAL` | §4.9 |
| `ST-FICO-SCORE` X(20) ← 9(03) | `MOVE` | 3 digits + 17 spaces |
| `ST-TRANDT` X(49) ← `TRNX-DESC` X(100) | `MOVE` | left 49 chars |
| HTML name (`L23-NAME` X(50) ← `ST-NAME` X(75)) | `DELIMITED BY '  '` (two spaces) | text up to (excluding) the first double space, then a literal two spaces, then `</p>`. With a blank middle name the HTML shows **only the first name** (`Aniya  </p>`) while the text line shows `Aniya  Von` (D6). |
| HTML address 1/2/3 | `DELIMITED BY '  '` | same trimming rule; an all-blank line yields `<p>  </p>` |

### 5.6 Arithmetic

* `WS-TOTAL-AMT` is zeroed per statement (A:L325) and accumulates every printed `TRNX-AMT`
  (A:L429) — credits (negative amounts) reduce it; the label is nevertheless `Total EXP:`.
* `MOVE WS-TOTAL-AMT TO WS-TRN-AMT` (COMP-3 → display, same picture, lossless) then to
  `ST-TOTAL-TRAMT` (`Z(9).99-`).
* Overflow beyond ±999,999,999.99 truncates silently (D8).

### 5.7 Error handling summary (both programs)

| Condition | COBOL behaviour | Java |
|---|---|---|
| Output file cannot be opened/written | Runtime abend (no `FILE STATUS`) | `FAILED`, exit 8 |
| Any input open RC not `00`/`04` | `ERROR OPENING <dd>` + `RETURN CODE: nn` + `ABENDING PROGRAM` + `CEE3ABD` | `FAILED`, exit 8, same three log lines |
| `TRNXFILE` empty | `ERROR READING TRNXFILE` / `RETURN CODE: 10` / abend | see D2 |
| `TRNXFILE`/`XREFFILE` read RC other than `00`/`10` | `ERROR READING <dd>` / abend | `FAILED` |
| Customer/account not found (`23`) or any other RC | `ERROR READING CUSTFILE|ACCTFILE` / abend | `FAILED` |
| Close RC not `00`/`04` | `ERROR CLOSING <dd>` / abend | `FAILED` |
| `CBSTM03B` called with unknown DD | silent no-op, RC unchanged (`'00'`) | n/a (no gateway) or `IllegalArgumentException` |
| `CBSTM03B` called with unimplemented op (`W`, `Z`, `R` on keyed files, `K` on sequential files) | returns previous status of that file | n/a or `UnsupportedOperationException` |

### 5.8 Latent defects / surprising behaviour and the replicate-vs-deviate decision

| ID | Behaviour | Where | Decision | Rationale |
|---|---|---|---|---|
| **D1** | Hard limits of **51 cards** and **10 transactions per card**; exceeding either silently corrupts adjacent storage (§4.7, §5.2). The shipped sample (50 cards × 6) is one card away from the limit. | A:L226-233, L818-830 | **Deviate**: no limits; transactions fetched per card from the database. Emit a `WARN` when a card has > 10 transactions or the run has > 51 cards, so parity tests know they are outside the COBOL-safe envelope. | Reproducing memory corruption is not a requirement; the COBOL output in that regime is undefined. |
| **D2** | Empty `TRNXFILE` abends (`RETURN CODE: 10` on the first read). | A:L744-754 | **Replicate as a controlled failure**: job `FAILED` with message `TRNXFILE is empty` (plus the COBOL lines). Configurable `carddemo.stmt.allow-empty-transactions=false`. | An empty transaction set almost certainly means the upstream extract failed; producing 50 empty statements would be worse. |
| **D3** | Cards with zero transactions still get a full statement with `$         .00 ` total. | A:L416-437 | **Replicate**. | Parity; business may want it. |
| **D4** | Statement per **card**, not per account; multi-card accounts get duplicate statements. | driver = xref | **Replicate**; note for product owner (Q2). | Parity. |
| **D5** | `DELIMITED BY ' '` truncates each name component, address line 3 and zip to their first word (`New Aricchester OR USA 04257` → `New OR USA 04257`). | A:L462-481 | **Replicate**, behind `carddemo.stmt.legacy-string-semantics=true`. | Parity is the cut-over gate; the fix is trivial once approved (Q3). |
| **D6** | HTML name/address trimmed at the first double space; blank middle name makes the HTML name just the first name. | A:L562-591 | **Replicate** (same switch as D5). | Parity. |
| **D7** | `9(9).99-` drops the high-order digit of balances ≥ 10^9. | A:L112, L484 | **Deviate**: fail the job on a balance that does not fit (cannot occur with realistic data). | Wrong balances on a customer statement are unacceptable. |
| **D8** | Total overflow beyond 9 integer digits truncates silently. | A:L429 | **Deviate**: fail. | Same. |
| **D9** | `TRNX-DESC` truncated to 49 characters on both outputs. | A:L677 | **Replicate**. | Layout contract (80-byte line). |
| **D10** | `CBSTM03B` returns a stale status for unimplemented operations and leaves RC untouched for unknown DDs → caller sees success. | B:L127-128, L133-229 | **Deviate** (n/a): the Java design has no generic gateway; if one is kept for coexistence it must throw. | Fail-fast. |
| **D11** | Out-of-bounds read of slot 52 in the scan's `UNTIL` when 51 cards are loaded. | A:L417-419 | n/a with D1. | |
| **D12** | PSA/TCB/TIOT walk and `DISPLAY` of DD names with UCB validity; `PSAPTR` is a null pointer deliberately dereferenced as address 0 (the z/OS PSA). | A:L235-291 | **Deviate**: replace with an `INFO` log of the resolved input/output resources. | Non-portable diagnostics only. |
| **D13** | `CALL 'CEE3ABD'` without the required `(abend code, timing)` arguments. Termination is abnormal but the abend code is undefined (LE may raise a protection exception rather than `U0999`). | A:L923 | **Deviate**: deterministic `FAILED`/exit 8. | |
| **D14** | Read status `'04'` accepted on the first `TRNXFILE` read but fatal on subsequent reads and on all `XREFFILE` reads. | A:L748 vs L837, L353 | n/a (no record-length mismatches in the RDBMS design). | |
| **D15** | `HTMLFILE` is **N complete HTML documents concatenated** (each with its own `<!DOCTYPE html>` … `</html>`); browsers render this leniently. | A:L506-555, L439-454 | **Replicate** in the parity artifact; additionally offer `carddemo.stmt.html.split-per-account=true` writing `<acct>.html` files (Q4). | Parity + usability. |
| **D16** | The header `<tr><td colspan="3" …#f2f2f2>` opened at the end of `5100` is closed only after the address paragraphs in `5200`; `<p>` elements sit directly inside `<td>`s; `frame`/`align` are obsolete HTML attributes. | A:L551-597 | **Replicate** tag-for-tag. | Contract is the record sequence. |
| **D17** | `OUTREC` copies only 50 of the 52 trailing bytes (`PROC-TS` loses its last 2 bytes) and yields 328-byte records for a 350-byte `LRECL`/`RECORDSIZE`. | JCL L54, L50, L32 | n/a — unused fields; note for the JCL owners (R2). | |
| **D18** | `CREASTMT.JCL` L90 contains stray text (`SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS`) — a JCL syntax error as committed; `STEP030` deletes `STATEMNT.HTML` with `LRECL=80` while `STEP040` creates it with `LRECL=100` (harmless for a delete). | JCL L87-91, L67-71 | Document; the golden capture must record the JCL actually executed (R1). | |
| **D19** | Transactions whose card is not in `XREFFILE` are silently dropped. | §5.3 | **Replicate**, log the count at `WARN`. | Parity + observability. |
| **D20** | Negative amounts print with a trailing `-` after the decimals (`$       47.88-`) and reduce `Total EXP`. | `Z(9).99-` | **Replicate**. | Layout contract. |

---

## 6. Target Spring Batch Design

### 6.1 Module and package layout (proposed)

```
carddemo-batch-creastmt/
  com.carddemo.batch.creastmt
    CreaStmtJobConfig
    CardXrefReader                     // JdbcCursorItemReader<CardXref> ORDER BY card_num
    StatementAssembler                 // ItemProcessor<CardXref, Statement>: customer + account + transactions
    StatementWriter                    // CompositeItemWriter -> text + html FlatFileItemWriters
    render/
      TextStatementRenderer            // Statement -> List<String> (80-byte lines, §7.1)
      HtmlStatementRenderer            // Statement -> List<String> (≤100-byte records, §7.2)
      CobolEdit                        // 9(9).99-  and  Z(9).99-
      LegacyString                     // firstWord(), delimitedByDoubleSpace()
    model/ Statement, StatementTransaction, Customer
    repo/  CustomerRepository (new), AccountRepository (shared), CardXrefRepository (shared), TransactionRepository (shared, +findByCardNumOrderByTranId)
  optional coexistence adapter:
    legacy/ Cbstm03bFileGateway        // implements the §4.6 contract over exported flat files (see §6.6)
```

### 6.2 Job structure

```
Job creaStmtJob
  Step 1  validateInputs      (Tasklet)  fail fast: transaction set non-empty (D2), output paths writable, delete previous outputs (mirrors STEP030)
  Step 2  generateStatements  (chunk)    reader: card_xref ORDER BY card_num
                                         processor: StatementAssembler
                                         writer: StatementWriter (text + html)
```

The mainframe steps `DELDEF01`/`STEP010`/`STEP020` (re-keying `TRANSACT` into `TRXFL`) have **no
Java counterpart**: `ORDER BY card_num, tran_id` over `transaction` with index
`ix_transaction_card_tran` is the same access path (§4.11).

Job parameters: `stmtFile`, `htmlFile` (paths), `runId` (identifying), `legacyStringSemantics`
(default `true`), `htmlSplitPerAccount` (default `false`), `allowEmptyTransactions` (default `false`).

### 6.3 Reader — `JdbcCursorItemReader<CardXref>`

`SELECT card_num, cust_id, acct_id FROM card_xref ORDER BY card_num` — the byte-wise ascending
order of the VSAM key (`CHAR(16)` collation must be binary/`C`; assert with a test on
mixed-content keys). Coexistence alternative: `FlatFileItemReader` over an exported 50-byte
`CARDXREF` file (already sorted).

### 6.4 Processor — `StatementAssembler`

```
process(xref):
  customer = customerRepo.findById(xref.custId)  orElseThrow ReferenceDataMissing("ERROR READING CUSTFILE", "23")
  account  = accountRepo.findById(xref.acctId)   orElseThrow ReferenceDataMissing("ERROR READING ACCTFILE", "23")
  trans    = transactionRepo.findByCardNumOrderByTranId(xref.cardNum)      // replaces the 51x10 table (D1)
  if trans.size() > 10 || ...: log WARN "outside COBOL-safe envelope"
  total    = Σ trans.amt  (BigDecimal, scale 2; fail if |total| >= 1e9, D8)
  if |account.currBal| >= 1e9: fail (D7)
  return Statement(xref.cardNum, account, customer, trans, total)
```

Pure function of its inputs; no cross-item state → chunk size and restart are free.

### 6.5 Writer — `StatementWriter`

`CompositeItemWriter<Statement>` over two `FlatFileItemWriter<String>` instances fed by the two
renderers (each `Statement` expands to `19 + n` text lines and `64 + 11·n` HTML records):

| Writer | Record width | Padding | Separator |
|---|---|---|---|
| text (`STMTFILE`) | 80 | right-pad with spaces; renderer guarantees exactly 80 | property `carddemo.io.line-separator` (`""` for parity with `RECFM=FB`, `\n` for text consumers such as the PDF converter replacement) |
| html (`HTMLFILE`) | 100 | right-pad with spaces to 100 (COBOL `WRITE … FROM` of shorter items pads) | same property; when `htmlSplitPerAccount=true`, per-account files are written **without** padding and with `\n` (usability deliverable, not the parity artifact) |

Rendering approach: **hand-coded line emitters** mirroring the COBOL 88-levels and `STRING`s, not a
template engine. Reasons: (a) the contract is a *record sequence* (one tag per 100-byte record,
padded), which no HTML template engine emits naturally; (b) the `DELIMITED BY '  '` trimming and
`Z(9).99-` editing are COBOL semantics best isolated in `LegacyString`/`CobolEdit` with unit tests;
(c) the 33 constant records are literal strings — a template would only obscure them. A Thymeleaf
template is an appropriate **second** deliverable for a modern statement, driven by the same
`Statement` model, once parity is signed off.

### 6.6 CALL interface mapping (03A → 03B) to Java

The interface is an in-process I/O gateway; in the target it dissolves into repositories:

| COBOL call (`DD`, `OPER`) | Java |
|---|---|
| `TRNXFILE O` / `R` … / `C` | `TransactionRepository.findByCardNumOrderByTranId(card)` per statement (no whole-file preload) |
| `XREFFILE O` / `R` … `10` / `C` | `JdbcCursorItemReader<CardXref>` (Spring Batch owns open/next/close; `10` = reader returns `null`) |
| `CUSTFILE O` / `K key(1:9)` / `C` | `CustomerRepository.findById(custId)`; `Optional.empty()` ≙ RC `23` |
| `ACCTFILE O` / `K key(1:11)` / `C` | `AccountRepository.findById(acctId)`; `Optional.empty()` ≙ RC `23` |
| RC `00` | normal return |
| RC `04`/`10`/`23`/other | `Optional.empty()` / end-of-cursor / `ReferenceDataMissingException` / `DataAccessException` |
| `W`, `Z` | not implemented in COBOL → not provided |

If a coexistence phase requires reading the four VSAM extracts as flat files, implement
`Cbstm03bFileGateway` with the *same* contract (`open(dd)`, `readNext(dd)`, `readByKey(dd, key, len)`,
`close(dd)`, returning `FileStatus`) and unit-test it against §2.4/§2.5 — including throwing on
`W`/`Z`/unknown DD instead of returning stale status (D10).

### 6.7 Repository interfaces

```java
public interface CustomerRepository { Optional<Customer> findById(String custId9); }
public interface AccountRepository  { Optional<Account>  findById(String acctId11); }      // shared
public interface CardXrefRepository { Stream<CardXref>  streamAllOrderByCardNum(); }       // shared (+ this method)
public interface TransactionRepository {                                                    // shared (+ this method)
    List<Transaction> findByCardNumOrderByTranId(String cardNum16);
}
```

Transactions / restart: read-only database access; outputs are files. On `FAILED`, delete the
partial files (or write `.partial` and rename). Restart: because the writers are `ItemStream`s and
the processor is stateless, standard `FlatFileItemWriter` restart (truncate to last committed
offset) works; the simpler policy — always regenerate (`allowStartIfComplete`) — is acceptable
(output is idempotent, ≤ 10^5 statements). Single-threaded: statement order is contractual.

### 6.8 Observability

`INFO`: run start/end, number of xref records, statements written, transactions printed, dropped
transactions (D19), cards > 10 transactions (D1). `ERROR`: the COBOL strings
`ERROR READING CUSTFILE` / `RETURN CODE: 23` / `ABENDING PROGRAM` verbatim. `DEBUG`: the resolved
resource list replacing the TIOT dump (D12).

---

## 7. Output & Exit Behavior

### 7.1 `STMTFILE` — record sequence per statement (80 bytes each, `19 + n` records)

| # | Source | Content |
|---|---|---|
| 1 | `ST-LINE0` | `*******************************START OF STATEMENT*******************************` |
| 2 | `ST-LINE1` | name (§5.5) |
| 3 | `ST-LINE2` | address line 1 |
| 4 | `ST-LINE3` | address line 2 |
| 5 | `ST-LINE4` | `city-first-word state country zip ` |
| 6 | `ST-LINE5` | 80 × `-` |
| 7 | `ST-LINE6` | 33 spaces + `Basic Details` + 34 spaces |
| 8 | `ST-LINE5` | 80 × `-` |
| 9 | `ST-LINE7` | `Account ID         :` + 11 digits + 49 spaces |
| 10 | `ST-LINE8` | `Current Balance    :` + `9(9).99-` + 47 spaces |
| 11 | `ST-LINE9` | `FICO Score         :` + 3 digits + 57 spaces |
| 12 | `ST-LINE10` | 80 × `-` |
| 13 | `ST-LINE11` | 30 spaces + `TRANSACTION SUMMARY` + 31 spaces |
| 14 | `ST-LINE12` | 80 × `-` |
| 15 | `ST-LINE13` | `Tran ID         Tran Details` + 37 spaces + `  Tran Amount` |
| 16 | `ST-LINE12` | 80 × `-` |
| 17 … 16+n | `ST-LINE14` | `<id 16> <desc 49>$<Z(9).99->` per transaction, in `tran_id` order |
| 17+n | `ST-LINE12` | 80 × `-` |
| 18+n | `ST-LINE14A` | `Total EXP:` + 56 spaces + `$` + `Z(9).99-` |
| 19+n | `ST-LINE15` | `********************************END OF STATEMENT********************************` |

Worked example generated from the sample data (`cardxref.txt` record 1 → customer `000000050`,
account `00000000050`; transactions for card `0500024453765740` from `dailytran.txt` sorted by
transaction id; `|` marks the record boundary):

```
|*******************************START OF STATEMENT*******************************|
|Aniya Alba Von                                                                  |
|1588 Nienow Cape                                                                |
|Suite 187                                                                       |
|New OR USA 04257                                                                |
|--------------------------------------------------------------------------------|
|                                 Basic Details                                  |
|--------------------------------------------------------------------------------|
|Account ID         :00000000050                                                 |
|Current Balance    :000000492.00                                                |
|FICO Score         :623                                                         |
|--------------------------------------------------------------------------------|
|                              TRANSACTION SUMMARY                               |
|--------------------------------------------------------------------------------|
|Tran ID         Tran Details                                         Tran Amount|
|--------------------------------------------------------------------------------|
|0000000058866561 Purchase at Blick-Rippin                         $      183.88 |
|0000000329724245 Purchase at Reichel Group                        $       14.00 |
|0000000475746885 Purchase at Adams-Watsica                        $      967.44 |
|0000000577826814 Return item at DuBuque, Wuckert and Mraz         $       47.88-|
|0000000685488982 Purchase at Williamson Group                     $       94.77 |
|0000000838587312 Purchase at Abbott-Gerlach                       $      241.66 |
|--------------------------------------------------------------------------------|
|Total EXP:                                                        $     1453.87 |
|********************************END OF STATEMENT********************************|
```

(`New Aricchester` became `New` — D5; `dailytran.txt` amounts carry overpunched signs, e.g.
`0000000004788}` → `-47.88`.)

### 7.2 `HTMLFILE` — record sequence per statement (100 bytes each, `64 + 11·n` records)

Each record is the literal below, left-justified, space-padded to 100. Dynamic parts in `«»`.

```
 1  <!DOCTYPE html>                                    L01      (5100-WRITE-HTML-HEADER)
 2  <html lang="en">                                   L02
 3  <head>                                             L03
 4  <meta charset="utf-8">                             L04
 5  <title>HTML Table Layout</title>                   L05
 6  </head>                                            L06
 7  <body style="margin:0px;">                         L07
 8  <table  align="center" frame="box" style="width:70%; font:12px Segoe UI,sans-serif;">   L08
 9  <tr>                                               LTRS
10  <td colspan="3" style="padding:0px 5px;background-color:#1d1d96b3;">                    L10
11  <h3>Statement for Account Number: «acct 11 digits + 9 spaces»</h3>                      L11
12  </td>                                              LTDE
13  </tr>                                              LTRE
14  <tr>                                               LTRS
15  <td colspan="3" style="padding:0px 5px;background-color:#FFAF33;">                      L15
16  <p style="font-size:16px">Bank of XYZ</p>          L16
17  <p>410 Terry Ave N</p>                             L17
18  <p>Seattle WA 99999</p>                            L18
19  </td>                                              LTDE
20  </tr>                                              LTRE
21  <tr>                                               LTRS
22  <td colspan="3" style="padding:0px 5px;background-color:#f2f2f2;">                      L22-35
23  <p style="font-size:16px">«name ≤50, cut at first double space»  </p>                   (5200-WRITE-HTML-NMADBS)
24  <p>«addr1 cut at first double space»  </p>
25  <p>«addr2 …»  </p>
26  <p>«addr3 …»  </p>
27  </td>
28  </tr>
29  <tr>
30  <td colspan="3" style="padding:0px 5px;background-color:#33FFD1; text-align:center;">   L30-42
31  <p style="font-size:16px">Basic Details</p>        L31
32  </td>
33  </tr>
34  <tr>
35  <td colspan="3" style="padding:0px 5px;background-color:#f2f2f2;">                      L22-35
36  <p>Account ID         : «acct 20»</p>
37  <p>Current Balance    : «9(9).99- 13»</p>
38  <p>FICO Score         : «fico 3 + 17 spaces»</p>
39  </td>
40  </tr>
41  <tr>
42  <td colspan="3" style="padding:0px 5px;background-color:#33FFD1; text-align:center;">   L30-42
43  <p style="font-size:16px">Transaction Summary</p>  L43
44  </td>
45  </tr>
46  <tr>
47  <td style="width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;">     L47
48  <p style="font-size:16px">Tran ID</p>              L48
49  </td>
50  <td style="width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;">     L50
51  <p style="font-size:16px">Tran Details</p>         L51
52  </td>
53  <td style="width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;">    L53
54  <p style="font-size:16px">Amount</p>               L54
55  </td>
56  </tr>
--- per transaction (6000-WRITE-TRANS), 11 records:
    <tr>
    <td style="width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;">     L58
    <p>«tran id 16»</p>
    </td>
    <td style="width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;">     L61
    <p>«desc 49 incl. trailing spaces»</p>
    </td>
    <td style="width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;">    L64
    <p>«Z(9).99- 13»</p>
    </td>
    </tr>
--- footer (4000-TRNXFILE-GET), 8 records:
    <tr>                                               LTRS
    <td colspan="3" style="padding:0px 5px;background-color:#1d1d96b3;">                    L10
    <h3>End of Statement</h3>                          L75
    </td>                                              LTDE
    </tr>                                              LTRE
    </table>                                           L78
    </body>                                            L79
    </html>                                            L80
```

Dynamic records for the worked example of §7.1:

```
<h3>Statement for Account Number: 00000000050         </h3>
<p style="font-size:16px">Aniya Alba Von  </p>
<p>1588 Nienow Cape  </p>
<p>Suite 187  </p>
<p>New OR USA 04257  </p>
<p>Account ID         : 00000000050         </p>
<p>Current Balance    : 000000492.00 </p>
<p>FICO Score         : 623                 </p>
<p>0000000058866561</p>
<p>Purchase at Blick-Rippin                         </p>
<p>      183.88 </p>
```

Note there is **no total row in the HTML** — the `Total EXP` line exists only in the text
statement; the HTML footer goes straight to `End of Statement`.

### 7.3 Console output

| Message | When |
|---|---|
| `Running JCL : <job> Step <step>` then `DD Names from TIOT: ` and one `: <dd> -- valid UCB` / `: <dd> --  null UCB` per TIOT entry (plus a final duplicate for the last entry) | prologue (D12; not reproduced) |
| `ERROR OPENING <dd>` · `ERROR READING <dd>` · `ERROR CLOSING <dd>` (`<dd>` ∈ `TRNXFILE`, `XREFFILE`, `CUSTFILE`, `ACCTFILE`) | fatal I/O |
| `RETURN CODE: nn` | immediately after the above (two-byte status) |
| `ABENDING PROGRAM` | immediately before `CEE3ABD` |

There is **no** start/end-of-execution banner and no counts in this program.

### 7.4 Exit status mapping

| COBOL outcome | COBOL `RETURN-CODE` / abend | Spring Batch `ExitStatus` | Process exit code |
|---|---|---|---|
| All statements written | `RC=0` | `COMPLETED` | `0` |
| Any I/O error, missing customer/account, empty transaction file | abend via `CEE3ABD` (code undefined, D13) or LE runtime abend on the unprotected output files | `FAILED` | `8` |
| Java-only: balance/total overflow (D7/D8), invalid configuration | n/a | `FAILED` | `8` |

The program never returns `4`. `CREASTMT` is scheduled standalone in `CardDemo.ca7` (no
downstream conditions other than `TXT2PDF1`, which is manual).

---

## 8. Acceptance Criteria & Test Plan

### 8.1 Test fixtures

* `app/data/ASCII/` files loaded into `card_xref` (50), `customer` (50), `account` (50),
  `transaction` (300 from `dailytran.txt`, all with `proc_ts` blank — irrelevant here).
* **Golden output**: `STATEMNT.PS` and `STATEMNT.HTML` captured once from a mainframe/emulator
  run of `CREASTMT` against these inputs (after fixing the JCL syntax error, D18), stored under
  `test/resources/golden/creastmt/`, plus SYSOUT.
* Micro-fixtures: customers with blank middle name, multi-word city, 25-char names without
  spaces, negative balance, card with 0 / 1 / 10 / 11 transactions, account with two cards,
  transaction with a 100-char description, xref card with no transactions, transactions with
  no xref.

### 8.2 Unit-level acceptance criteria

| ID | Criterion | Expected |
|---|---|---|
| AC-01 | CALL-area marshalling (only if `Cbstm03bFileGateway` is built): offsets 1/9/10/12/37/41, lengths 8/1/2/25/4/1000 | byte layout per §4.6; `K` with len 9 uses key bytes 1-9, len 11 uses 1-11 |
| AC-02 | Gateway op matrix (§2.4) | `R` on CUST/ACCT, `K` on TRNX/XREF, `W`, `Z`, unknown DD → exception (D10 deviation) |
| AC-03 | Statement order | one statement per `card_xref` row, ascending `card_num` (binary collation) |
| AC-04 | Customer lookup by `cust_id` | name/address/FICO from that row |
| AC-05 | Customer missing | `FAILED`, exit 8; log `ERROR READING CUSTFILE`, `RETURN CODE: 23`, `ABENDING PROGRAM`; outputs end after the previous complete statement (or are removed) |
| AC-06 | Account lookup / missing | as AC-04/05 with `ERROR READING ACCTFILE` |
| AC-07 | Transaction grouping | exactly the transactions with `card_num = xref.card_num`, ordered by `tran_id` ascending (text order) |
| AC-08 | Card with 0 transactions | 19 text records, 64 HTML records; total `$         .00 ` |
| AC-09 | Card with 11 transactions | all 11 printed (D1 deviation), `WARN` logged |
| AC-10 | Account with two cards | two statements with identical header data |
| AC-11 | Transactions whose card has no xref | not printed; count logged |
| AC-12 | Name assembly, legacy semantics | `Aniya`/`Alba`/`Von` → `Aniya Alba Von ` + pad to 75; blank middle → `Aniya  Von `; `Mary Ann`/`X`/`Smith-Jones` → `Mary X Smith-Jones ` |
| AC-13 | Address 3 assembly | `New Aricchester`/`OR`/`USA`/`04257` → `New OR USA 04257 ` + pad to 80 |
| AC-14 | HTML name/address trimming | `Aniya Alba Von` + spaces → `<p style="font-size:16px">Aniya Alba Von  </p>`; blank middle → `<p style="font-size:16px">Aniya  </p>`; blank address 2 → `<p>  </p>` |
| AC-15 | `9(9).99-` | `492.00` → `000000492.00 `; `-15.5` → `000000015.50-`; `1000000000.00` → `FAILED` (D7) |
| AC-16 | `Z(9).99-` | `183.88` → `      183.88 `; `-47.88` → `       47.88-`; `0` → `         .00 `; `1453.87` → `     1453.87 ` |
| AC-17 | Total | Σ amounts incl. negatives; `Total EXP:` line cols 1-10/67/68-80 |
| AC-18 | Text line widths | every record exactly 80 bytes; all constant lines byte-equal to §4.8 |
| AC-19 | HTML record count and order | `64 + 11n` records in the §7.2 order; every record 100 bytes (parity mode); constants byte-equal to §4.10 (including `<table  align` double space and `5px;background` no-space) |
| AC-20 | HTML dynamic records | `<h3>Statement for Account Number: 00000000050         </h3>` (59 bytes + pad); basic-detail lines keep trailing spaces of the 20/13/20-byte fields |
| AC-21 | Description truncation | 100-char description → first 49 chars on both outputs |
| AC-22 | Empty transaction set | `FAILED` with `TRNXFILE is empty` unless `allowEmptyTransactions=true` (then 50 zero-row statements) |
| AC-23 | Output not writable | `FAILED`, exit 8 |
| AC-24 | Zoned sign decoding on `tran_amt`/`curr_bal` (`{`…`I`, `}`…`R`) | per CBTRN02C §4.7 |
| AC-25 | Restart after failure mid-run | output identical to a clean run (or regenerated) |

### 8.3 End-to-end parity criteria (must pass before cut-over)

1. **Text**: `cmp` of the Java `STMTFILE` (parity settings: `legacyStringSemantics=true`,
   `lineSeparator=""`, golden charset) against the golden `STATEMNT.PS` → zero differing bytes,
   equal length (`= 80 × Σ(19 + n_i)`).
2. **HTML**: `cmp` of the Java `HTMLFILE` against the golden `STATEMNT.HTML` → zero differing
   bytes (`= 100 × Σ(64 + 11·n_i)`). Additionally a structural check: split both files into
   100-byte records, trim, and assert the tag sequence is identical per statement (this is the
   "tag-for-tag" criterion and localises any diff).
3. Any difference must be attributable to a documented deviation actually exercised by the fixture
   (D1 with > 10 transactions, D7/D8 overflow, D2 empty input) — none of which the sample data
   triggers — otherwise the implementation is wrong.

### 8.4 Non-functional criteria

* 10^5 xref records / 10^6 transactions in < 10 minutes single-threaded; memory O(1) in
  statements (no whole-file preload — the COBOL's 171 KB table is not carried over).
* Deterministic output independent of chunk size (assert for 1, 7, 100).

---

## 9. Assumptions, Risks & Open Questions

### 9.1 Assumptions

* A1 — `card_xref`, `account`, `transaction` follow CBTRN02C §4.9; `customer` is loaded from
  `CUSTDATA` by the migration loaders with the §4.3 layout.
* A2 — `CHAR` columns preserve trailing blanks, or the renderers re-pad to copybook width before
  applying the `STRING`/`MOVE` rules (the rules depend on the padding).
* A3 — The transaction population for statements is the **entire** transaction master (the JCL
  applies no date filter), i.e. every statement lists all-time transactions for the card.
* A4 — The 80-byte text file remains the input to the PDF conversion (`TXT2PDF1`); the HTML file
  has no automated consumer.
* A5 — Card numbers and ids are all-digit strings, so text ordering equals numeric ordering.

### 9.2 Risks

| ID | Risk | Mitigation |
|---|---|---|
| R1 | `CREASTMT.JCL` as committed has a syntax error (D18) — the golden run must use a corrected copy; record it with the golden output. | Store the exact JCL used. |
| R2 | `OUTREC` produces 328-byte records for a 350-byte `LRECL`/`RECORDSIZE(350 350)` (D17); DFSORT/REPRO behaviour (pad vs. reject) determines whether the job even runs. Unused bytes, but it affects golden capture. | Confirm with the mainframe team; Java is unaffected. |
| R3 | Collation: `ORDER BY card_num` must be binary; a locale-aware collation could reorder statements. | Set column collation / use `COLLATE "C"`; test with mixed keys. |
| R4 | EBCDIC→ASCII: the HTML contains `<`, `>`, `"`, `#`, `%`, `;`, `:`, `,`, `-`, `!` — all present in cp037 with 1:1 mappings; `$` (statement lines) is also 1:1. Non-ASCII customer names (none in the sample) would need cp037 handling in the loader. | Parity compare in one charset; loader validates charset. |
| R5 | PII (`ssn`, `govt_issued_id`, `dob`) in the new `customer` table. | Column encryption/masking policy; not needed by this job — the repository projection should exclude them. |
| R6 | The `DELIMITED BY ' '` truncation (D5) is a visible data-quality defect on customer statements; replicating it may be challenged by the business. | Switch provided; needs Q3 answer before cut-over. |
| R7 | Restart with `FlatFileItemWriter` truncation requires both writers to commit in lock-step; a mismatch would desynchronise text and HTML. | Prefer regenerate-from-scratch policy. |

### 9.3 Open questions (decisions required before implementation is complete)

| # | Question | Default if unanswered |
|---|---|---|
| Q1 | Is the whole transaction master the intended statement population (A3), or should a statement period filter (like `TRANREPT`'s `DATEPARM`) be introduced? | Whole master (parity). |
| Q2 | Statement per card vs per account (D4): accept duplicates for multi-card accounts? | Per card (parity). |
| Q3 | Approve fixing the first-word truncation of names/cities (D5/D6) at cut-over or later? | Ship with `legacyStringSemantics=true`; raise a COBOL defect. |
| Q4 | Should the HTML output be one concatenated file (D15) or one file per account, and should records be padded to 100 bytes? | Parity file as specified; per-account files as an additional, optional output. |
| Q5 | Should the Java job also replace `TXT2PDF1` (PDF rendering) or keep feeding the 80-byte text file to it? | Keep the text file contract. |
| Q6 | Exit code for failure (`8` vs `12`) — align with the CBTRN02C decision. | `8`. |
| Q7 | Is a per-run audit table (`statement_run`) wanted for reconciliation (statement count, total per account)? | Log counts only. |

---

## Appendix A — Field offset quick reference

**`CUSTOMER-RECORD` (500)**: id 1-9 · first 10-34 · middle 35-59 · last 60-84 · addr1 85-134 ·
addr2 135-184 · addr3 185-234 · state 235-236 · country 237-239 · zip 240-249 · phone1 250-264 ·
phone2 265-279 · ssn 280-288 · govt id 289-308 · dob 309-318 · eft 319-328 · pri-card 329 ·
fico 330-332 · filler 333-500.

**`TRNX-RECORD` (350, `TRXFL` layout)**: card 1-16 · tran id 17-32 · type 33-34 · cat 35-38 ·
source 39-48 · desc 49-148 · amt 149-159 · merchant id 160-168 · merchant name 169-218 ·
merchant city 219-268 · merchant zip 269-278 · orig ts 279-304 · proc ts 305-330 · filler 331-350.
(`CVTRA05Y` → `TRXFL`: bytes 263-278 move to 1-16; bytes 1-262 move to 17-278; bytes 279-328 stay.)

**`ACCOUNT-RECORD` (300)** and **`CARD-XREF-RECORD` (50)**: see CBTRN02C Appendix / §4.3-4.4.

**CALL area (1,037)**: dd 1-8 · oper 9 · rc 10-11 · key 12-36 · key-len 37-40 · data 41-1040.

## Appendix B — Statement text column quick reference (80 bytes)

```
      12345678901234567890123456789012345678901234567890123456789012345678901234567890
LINE7 |Account ID         :AAAAAAAAAAA                                                 |
LINE8 |Current Balance    :999999999.99-                                               |
LINE9 |FICO Score         :FFF                                                         |
LINE13|Tran ID         Tran Details                                         Tran Amount|
LINE14|TTTTTTTTTTTTTTTT DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD$ZZZZZZZZZ.99-|
LINE14A|Total EXP:                                                        $ZZZZZZZZZ.99-|
```
