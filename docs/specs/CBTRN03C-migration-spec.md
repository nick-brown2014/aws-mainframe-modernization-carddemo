# CBTRN03C Migration Specification: COBOL Batch to Java + Spring Batch

| | |
|---|---|
| Source program | `app/cbl/CBTRN03C.cbl` (CardDemo v2.0-25-gdb72e6b-235, 649 lines) |
| Source JCL | `app/jcl/TRANREPT.jcl`, step `STEP10R EXEC PGM=CBTRN03C` (preceded by `REPROC` backup and a `SORT` pre-filter) |
| Copybooks | `app/cpy/CVTRA05Y.cpy` (transaction master), `CVACT03Y.cpy` (card xref), `CVTRA03Y.cpy` (transaction type), `CVTRA04Y.cpy` (transaction category), `CVTRA07Y.cpy` (report layouts) |
| Shared-table reference | `docs/specs/CBTRN02C-migration-spec.md` §4.3 (`card_xref`), §4.6/§4.9 (`transaction`) — **not redefined here** |
| Target | Java 17+, Spring Boot 3.x, Spring Batch 5.x, `FlatFileItemWriter` with per-record-type fixed-width line aggregators |
| Status | Draft for review |

This document is derived directly from the COBOL source, copybooks and JCL listed above. It is
intended to be sufficient for an implementation team to build and verify the Java replacement
without re-reading the COBOL. Line references (`L123`) point into `CBTRN03C.cbl` unless
prefixed (`CVTRA07Y L5`, `JCL L46`).

---

## 1. Overview & Purpose

`CBTRN03C` is the CardDemo **daily transaction detail report** program, run by job `TRANREPT`.
It reads a date-filtered, card-number-sorted extract of the transaction master (`TRANFILE`), and
for every transaction whose processing date falls inside the range supplied in `DATEPARM` it:

1. Looks up the **account id** for the card (`CARDXREF`, keyed by card number) — once per change
   of card number.
2. Looks up the **transaction type description** (`TRANTYPE`, keyed by 2-byte type code).
3. Looks up the **transaction category description** (`TRANCATG`, keyed by type code + 4-digit
   category code).
4. Writes a 133-byte fixed-width detail line to `TRANREPT`, maintaining a running **page total**,
   **account total** (per card number) and **grand total**, and emitting a
   "page" header block every time the running line counter is a multiple of 20.

The report has **no carriage-control byte** (`RECFM=FB, LRECL=133`); a "page break" is purely the
emission of a page-total block followed by a fresh header block. Any I/O error, or any failed
key lookup, abends the program with user abend `U0999`. The program never sets a non-zero
`RETURN-CODE`.

The Java replacement must produce a **byte-for-byte identical** 133-byte report for the same
input and reference data (subject to the explicitly documented deviations in §5.6 and §9), and
expose the same success/failure exit semantics to the scheduler.

### 1.1 Job context (`TRANREPT.jcl`)

| Step | Program | Purpose | Lines |
|---|---|---|---|
| `STEP05R` (proc `REPROC`) | `IDCAMS REPRO` | Copies `AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS` to a flat GDG backup `AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)` (350-byte FB) | JCL L23-33 |
| `STEP05R` (**duplicate step name** — see §9.2) | `SORT` | `INCLUDE COND=(305,10 GE C'2022-01-01' AND LE C'2022-07-06')`, `SORT FIELDS=(263,16,ZD,A)` → `AWS.M2.CARDDEMO.TRANSACT.DALY(+1)` | JCL L37-55 |
| `STEP10R` | `CBTRN03C` | The report program specified here | JCL L59-80 |

Two things follow from the JCL that the Java design must preserve:

* The program **relies on its input being sorted by card number** (account grouping is a
  control-break on `TRAN-CARD-NUM`, L181). Unsorted input silently produces multiple
  "Account Total" blocks for the same account.
* The date range is applied **twice**: hard-coded in the SORT `SYMNAMES` (JCL L43-44) and again
  in the program from `DATEPARM`. Only the `DATEPARM` values appear in the report header. In
  production these must be kept equal by hand (§9.3, Q1).

---

## 2. Current-State (COBOL) Analysis

### 2.1 Control flow

```
PROCEDURE DIVISION                                        (L159-217)
  DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN03C'
  PERFORM 0000-TRANFILE-OPEN     (OPEN INPUT  TRANSACT-FILE)     L376
  PERFORM 0100-REPTFILE-OPEN     (OPEN OUTPUT REPORT-FILE)       L394
  PERFORM 0200-CARDXREF-OPEN     (OPEN INPUT  XREF-FILE)         L412
  PERFORM 0300-TRANTYPE-OPEN     (OPEN INPUT  TRANTYPE-FILE)     L430
  PERFORM 0400-TRANCATG-OPEN     (OPEN INPUT  TRANCATG-FILE)     L448
  PERFORM 0500-DATEPARM-OPEN     (OPEN INPUT  DATE-PARMS-FILE)   L466
  PERFORM 0550-DATEPARM-READ     (one record -> WS-START-DATE / WS-END-DATE;
                                  EOF on DATEPARM sets END-OF-FILE='Y')   L220

  PERFORM UNTIL END-OF-FILE = 'Y'                                 L170
    IF END-OF-FILE = 'N'
       PERFORM 1000-TRANFILE-GET-NEXT                             L172
       IF TRAN-PROC-TS(1:10) >= WS-START-DATE
          AND TRAN-PROC-TS(1:10) <= WS-END-DATE                   L173-174
          CONTINUE
       ELSE
          NEXT SENTENCE            <-- !! leaves the PERFORM, see §5.6 D1
       END-IF
       IF END-OF-FILE = 'N'                                       L179
          DISPLAY TRAN-RECORD                                     L180 (350-byte debug echo)
          IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM                  L181  (control break)
             IF WS-FIRST-TIME = 'N'
                PERFORM 1120-WRITE-ACCOUNT-TOTALS                 L183
             END-IF
             MOVE TRAN-CARD-NUM TO WS-CURR-CARD-NUM, FD-XREF-CARD-NUM
             PERFORM 1500-A-LOOKUP-XREF                           L187
          END-IF
          MOVE TRAN-TYPE-CD TO FD-TRAN-TYPE
          PERFORM 1500-B-LOOKUP-TRANTYPE                          L190
          MOVE TRAN-TYPE-CD, TRAN-CAT-CD TO FD-TRAN-CAT-KEY
          PERFORM 1500-C-LOOKUP-TRANCATG                          L195
          PERFORM 1100-WRITE-TRANSACTION-REPORT                   L196
       ELSE                                                       L197  (EOF just detected)
          DISPLAY 'TRAN-AMT ' TRAN-AMT
          DISPLAY 'WS-PAGE-TOTAL' WS-PAGE-TOTAL
          ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL          L200  <-- !! stale record, see §5.6 D2
          PERFORM 1110-WRITE-PAGE-TOTALS                          L202
          PERFORM 1110-WRITE-GRAND-TOTALS                         L203
       END-IF
    END-IF
  END-PERFORM.

  PERFORM 9000..9500-*-CLOSE  (six CLOSEs, each checked)          L208-213
  DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN03C'
  GOBACK.                                                         L217  (RETURN-CODE stays 0)
```

Report-line production (`1100-WRITE-TRANSACTION-REPORT`, L274-290):

```
IF WS-FIRST-TIME = 'Y'                       (first in-range record only)
   MOVE 'N' TO WS-FIRST-TIME
   MOVE WS-START-DATE/WS-END-DATE TO REPT-START-DATE/REPT-END-DATE
   PERFORM 1120-WRITE-HEADERS                 (4 lines, counter += 4)
END-IF
IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0      (WS-PAGE-SIZE = 20)
   PERFORM 1110-WRITE-PAGE-TOTALS             (2 lines, counter += 2, page -> grand, page = 0)
   PERFORM 1120-WRITE-HEADERS                 (4 lines, counter += 4)
END-IF
ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL
PERFORM 1120-WRITE-DETAIL                     (1 line, counter += 1)
```

### 2.2 Paragraph reference

| Paragraph | Lines | Responsibility |
|---|---|---|
| (main) | L159-217 | Open files, read date parms, drive the read/filter/lookup/write loop, EOF totals, close files. |
| `0550-DATEPARM-READ` | L220-243 | `READ DATE-PARMS-FILE INTO WS-DATEPARM-RECORD`. `'00'` → display `Reporting from <start> to <end>`; `'10'` → `END-OF-FILE='Y'` (report loop skipped entirely); other → `ERROR READING DATEPARM FILE`, status display, abend. |
| `1000-TRANFILE-GET-NEXT` | L248-272 | `READ TRANSACT-FILE INTO TRAN-RECORD`. `'00'` ok; `'10'` → `END-OF-FILE='Y'`; other → `ERROR READING TRANSACTION FILE`, abend. |
| `1100-WRITE-TRANSACTION-REPORT` | L274-290 | First-time header block; page-break check `MOD(counter,20)=0`; accumulate page & account totals; write detail. |
| `1110-WRITE-PAGE-TOTALS` | L293-304 | Write `REPORT-PAGE-TOTALS` line, add page total to grand total, zero page total, write `TRANSACTION-HEADER-2` (dashes). Counter += 2. |
| `1120-WRITE-ACCOUNT-TOTALS` | L306-316 | Write `REPORT-ACCOUNT-TOTALS` line, zero account total, write dashes line. Counter += 2. Called **only** on a card-number change after the first card (never at EOF). |
| `1110-WRITE-GRAND-TOTALS` | L318-322 | Write `REPORT-GRAND-TOTALS` line. Counter **not** incremented (irrelevant — nothing follows). |
| `1120-WRITE-HEADERS` | L324-341 | Write `REPORT-NAME-HEADER`, blank line, `TRANSACTION-HEADER-1`, `TRANSACTION-HEADER-2`. Counter += 4. |
| `1111-WRITE-REPORT-REC` | L343-359 | `WRITE FD-REPTFILE-REC`; any status other than `'00'` → `ERROR WRITING REPTFILE`, abend. |
| `1120-WRITE-DETAIL` | L361-374 | `INITIALIZE TRANSACTION-DETAIL-REPORT`; move 8 fields; write; counter += 1. |
| `0000`/`0100`/`0200`/`0300`/`0400`/`0500-*-OPEN` | L376-482 | One OPEN each; status ≠ `'00'` → `ERROR OPENING <name>`, status display, abend. |
| `1500-A-LOOKUP-XREF` | L484-492 | `READ XREF-FILE INTO CARD-XREF-RECORD` by `FD-XREF-CARD-NUM`; `INVALID KEY` → `INVALID CARD NUMBER : <16>`, `FILE STATUS IS: NNNN0023`, abend. |
| `1500-B-LOOKUP-TRANTYPE` | L494-502 | `READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD`; `INVALID KEY` → `INVALID TRANSACTION TYPE : <2>`, abend. |
| `1500-C-LOOKUP-TRANCATG` | L504-512 | `READ TRANCATG-FILE INTO TRAN-CAT-RECORD`; `INVALID KEY` → `INVALID TRAN CATG KEY : <6>`, abend. |
| `9000`..`9500-*-CLOSE` | L514-621 | One CLOSE each; status ≠ `'00'` → `ERROR CLOSING <name>`, abend. Note the `TRANFILE` close message says `POSTED TRANSACTION FILE`. |
| `9999-ABEND-PROGRAM` | L626-630 | `DISPLAY 'ABENDING PROGRAM'`, `CALL 'CEE3ABD' USING ABCODE(999), TIMING(0)` → user abend `U0999`. |
| `9910-DISPLAY-IO-STATUS` | L633-646 | Formats the 2-byte status as `FILE STATUS IS: NNNN` + 4 digits (see §7.3). |

Paragraphs that are declared but unused: none. Working-storage that is declared but unused:
`WS-BLANK-LINE` is used (blank header line); `IO-STATUS-04`, `TWO-BYTES-*` are used by
`9910`; nothing else is dead.

### 2.3 File SELECT / FILE STATUS handling

| COBOL file | DD (`ASSIGN`) | Organization / access | Record key | FD length | FILE STATUS | Where checked |
|---|---|---|---|---|---|---|
| `TRANSACT-FILE` (L29-31) | `TRANFILE` | `SEQUENTIAL` | — | 350 (`304 + 26 + 20`, L62-65) | `TRANFILE-STATUS` | open `'00'` only; read `'00'`/`'10'`/other; close `'00'` |
| `XREF-FILE` (L33-37) | `CARDXREF` | `INDEXED`, `RANDOM` | `FD-XREF-CARD-NUM` X(16) | 50 | `CARDXREF-STATUS` | open/close `'00'`; read via `INVALID KEY` only |
| `TRANTYPE-FILE` (L39-43) | `TRANTYPE` | `INDEXED`, `RANDOM` | `FD-TRAN-TYPE` X(02) | 60 | `TRANTYPE-STATUS` | open/close `'00'`; read via `INVALID KEY` only |
| `TRANCATG-FILE` (L45-49) | `TRANCATG` | `INDEXED`, `RANDOM` | `FD-TRAN-CAT-KEY` = X(02)+9(04) | 60 | `TRANCATG-STATUS` | open/close `'00'`; read via `INVALID KEY` only |
| `REPORT-FILE` (L51-53) | `TRANREPT` | `SEQUENTIAL` | — | 133 | `TRANREPT-STATUS` | open/write/close `'00'` only |
| `DATE-PARMS-FILE` (L55-57) | `DATEPARM` | `SEQUENTIAL` | — | 80 | `DATEPARM-STATUS` | open `'00'`; read `'00'`/`'10'`/other; close `'00'` |

Observations that matter for the migration:

* The three keyed lookups only trap `INVALID KEY` (status `2x`). Any other non-zero status on a
  random `READ` (e.g. `'9x'` logic errors) is **not** examined; the program would continue with a
  stale `CARD-XREF-RECORD` / `TRAN-TYPE-RECORD` / `TRAN-CAT-RECORD`. Java must treat every lookup
  failure as fatal (§5.4).
* `OPEN` status `'04'`/`'05'`/`'97'` are treated as errors here (unlike `CBSTM03A`, which tolerates
  `'04'`). An optional/empty `DATEPARM` therefore does **not** open-fail; it read-fails with `'10'`
  and silently produces an empty report (§5.6 D4).
* `APPL-RESULT` (L150-152) is an internal ok/EOF/error tri-state (`0`/`16`/`12`); it never
  reaches `RETURN-CODE`.

---

## 3. Files / Datasets

| DD | Dataset (from `TRANREPT.jcl`) | JCL lines | Direction | Organization | Access | Key (offset, len) | Copybook / layout | LRECL |
|---|---|---|---|---|---|---|---|---|
| `TRANFILE` | `AWS.M2.CARDDEMO.TRANSACT.DALY(+1)` — output of the SORT pre-step; date-filtered, sorted by card number | L65-66 | in | PS, `RECFM=FB` | sequential | — (sorted on 263/16) | `CVTRA05Y` `TRAN-RECORD` | 350 |
| `CARDXREF` | `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` | L67-68 | in | VSAM KSDS | random by key | card number (0, 16) | `CVACT03Y` `CARD-XREF-RECORD` | 50 |
| `TRANTYPE` | `AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS` | L69-70 | in | VSAM KSDS | random by key | type code (0, 2) | `CVTRA03Y` `TRAN-TYPE-RECORD` | 60 |
| `TRANCATG` | `AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS` | L71-72 | in | VSAM KSDS | random by key | type code + category (0, 6) | `CVTRA04Y` `TRAN-CAT-RECORD` | 60 |
| `DATEPARM` | `AWS.M2.CARDDEMO.DATEPARM` | L73-74 | in | PS | sequential (first record only) | — | inline `WS-DATEPARM-RECORD` (L122-125), see §4.5 | 80 |
| `TRANREPT` | `AWS.M2.CARDDEMO.TRANREPT(+1)` (GDG, `LIMIT(10)`, defined by `REPTFILE.jcl`) | L76-80 | out | PS, `RECFM=FB`, `DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)` | sequential | — | `CVTRA07Y` (5 record layouts) | 133 |
| `SYSOUT` / `SYSPRINT` | `SYSOUT=*` | L62-63 | out | — | — | — | `DISPLAY` output (§7.2) | — |

Sample data available for offset cross-checks (`app/data/ASCII/`): `dailytran.txt` (300 × 350
bytes, same layout as `CVTRA05Y` bar the 26-byte `PROC-TS` which is **blank** in the sample —
it is populated by `CBTRN02C` posting), `cardxref.txt` (50 × 50), `trantype.txt` (7 × 60),
`trancatg.txt` (18 × 60). There is no sample `DATEPARM` file (§9.3, Q2).

---

## 4. Data Model & Field Mappings

### 4.1 Type-mapping conventions

Identical to CBTRN02C spec §4.1: `PIC X(n)` → `String` (fixed width, right-padded with spaces, never
trimmed on the storage side); `PIC 9(n)` → `long`/`int` or zero-padded `String` where the value is
an identifier; `PIC S9(n)V99` → `BigDecimal` scale 2 with zoned-decimal sign overpunch (CBTRN02C
§4.7); `PIC 9(n) COMP-3` → `int`/`long` (packed decimal, present in this program only in
working storage, never on a file). Offsets below are **1-based byte positions** within the record.

### 4.2 `CVTRA05Y` — Transaction master (`TRAN-RECORD`, 350 bytes, input)

Shared with CBTRN02C (§4.6) — relational table `transaction` (§4.9) is **reused, not redefined**.
Full offset list for the report's benefit (the program reads the 350-byte record into this
copybook, L249, and uses the starred fields):

| Field | PIC | Offset | Len | Java type | Used by CBTRN03C |
|---|---|---|---|---|---|
| `TRAN-ID` * | X(16) | 1 | 16 | `String` | detail col 1-16 |
| `TRAN-TYPE-CD` * | X(02) | 17 | 2 | `String` | lookup key; detail col 30-31 |
| `TRAN-CAT-CD` * | 9(04) | 19 | 4 | `int` (rendered `%04d`) | lookup key; detail col 49-52 |
| `TRAN-SOURCE` * | X(10) | 23 | 10 | `String` | detail col 84-93 |
| `TRAN-DESC` | X(100) | 33 | 100 | `String` | echoed by `DISPLAY TRAN-RECORD` only |
| `TRAN-AMT` * | S9(09)V99 | 133 | 11 | `BigDecimal(2)` | totals; detail col 98-112 |
| `TRAN-MERCHANT-ID` | 9(09) | 144 | 9 | `long` | no |
| `TRAN-MERCHANT-NAME` | X(50) | 153 | 50 | `String` | no |
| `TRAN-MERCHANT-CITY` | X(50) | 203 | 50 | `String` | no |
| `TRAN-MERCHANT-ZIP` | X(10) | 253 | 10 | `String` | no |
| `TRAN-CARD-NUM` * | X(16) | 263 | 16 | `String` | control break; xref key (JCL sort key 263/16) |
| `TRAN-ORIG-TS` | X(26) | 279 | 26 | `String` | no |
| `TRAN-PROC-TS` * | X(26) | 305 | 26 | `String` | date filter on bytes 305-314 (`(1:10)`) |
| `FILLER` | X(20) | 331 | 20 | — | no |

The FD (`FD-TRANFILE-REC`, L62-65) splits the same 350 bytes as `304 + 26 + 20`; the
`FD-TRAN-PROC-TS` at 305-330 matches `TRAN-PROC-TS` and the JCL `TRAN-PROC-DT,305,10,CH`
symbol (JCL L42). `PROC-TS` format is DB2 `YYYY-MM-DD-HH.MM.SS.hh0000` (CBTRN02C §4.8); only the
first 10 characters `YYYY-MM-DD` are compared.

### 4.3 `CVACT03Y` — Card cross-reference (`CARD-XREF-RECORD`, 50 bytes)

Shared with CBTRN02C (§4.3) — table `card_xref` reused. Fields: `XREF-CARD-NUM` X(16) @1,
`XREF-CUST-ID` 9(09) @17, `XREF-ACCT-ID` 9(11) @26, `FILLER` X(14) @37. CBTRN03C uses only
`XREF-ACCT-ID` (L364) — rendered as 11 zero-padded digits into an `X(11)` report field.
Sample check: `cardxref.txt` record 1 = `0500024453765740|000000005|00000000050`.

### 4.4 `CVTRA03Y` — Transaction type (`TRAN-TYPE-RECORD`, 60 bytes, KSDS key = type code) — NEW

| Field | PIC | Offset | Len | Java type / column | Notes |
|---|---|---|---|---|---|
| `TRAN-TYPE` | X(02) | 1 | 2 | `String` / `tran_type_cd CHAR(2) PK` | FD key `FD-TRAN-TYPE` (L74) |
| `TRAN-TYPE-DESC` | X(50) | 3 | 50 | `String` / `tran_type_desc CHAR(50)` | truncated to **15** chars on the report (L366) |
| `FILLER` | X(08) | 53 | 8 | — | sample data holds `00000000`, not spaces; not persisted |

Sample (`trantype.txt`): `01Purchase…`, `02Payment…`, `03Credit…` (7 rows).

### 4.5 `CVTRA04Y` — Transaction category (`TRAN-CAT-RECORD`, 60 bytes, KSDS key = 6-byte composite) — NEW

| Field | PIC | Offset | Len | Java type / column | Notes |
|---|---|---|---|---|---|
| `TRAN-CAT-KEY.TRAN-TYPE-CD` | X(02) | 1 | 2 | `String` / `tran_type_cd CHAR(2)` | |
| `TRAN-CAT-KEY.TRAN-CAT-CD` | 9(04) | 3 | 4 | `int` / `tran_cat_cd NUMERIC(4)` | zero-padded in key |
| `TRAN-CAT-TYPE-DESC` | X(50) | 7 | 50 | `String` / `tran_cat_desc CHAR(50)` | truncated to **29** chars on the report (L368) |
| `FILLER` | X(04) | 57 | 4 | — | sample data holds `0000` |

Sample (`trancatg.txt`): `010001Regular Sales Draft…`, `010002Regular Cash Advance…` (18 rows).

### 4.6 `DATEPARM` record (inline `WS-DATEPARM-RECORD`, L122-125) — NEW

The FD record is 80 bytes (`FD-DATEPARM-REC PIC X(80)`, L88); `READ … INTO` copies the first 21
bytes into working storage and discards 22-80.

| Field | PIC | Offset | Len | Java type | Notes |
|---|---|---|---|---|---|
| `WS-START-DATE` | X(10) | 1 | 10 | `String` (kept as text; also parsed to `LocalDate` for validation only) | expected `YYYY-MM-DD` |
| `FILLER` | X(01) | 11 | 1 | — | separator, any byte |
| `WS-END-DATE` | X(10) | 12 | 10 | `String` | expected `YYYY-MM-DD` |
| (unused) | — | 22 | 59 | — | ignored |

The program performs **no format validation** on either date; comparison is plain
character comparison against `PROC-TS(1:10)` (§5.1). Java should validate the format and fail
fast (deviation D5, §5.6) but compare as strings to retain identical inclusion semantics.

### 4.7 `CVTRA07Y` — Report record layouts (133-byte output; five `01` levels)

All five layouts are shorter than 133 bytes; `MOVE <layout> TO FD-REPTFILE-REC` (an `X(133)`
group move) right-pads with spaces. Byte positions below are 1-based columns of the 133-byte
record.

**4.7.1 `REPORT-NAME-HEADER` (CVTRA07Y L4-13) — 115 significant bytes**

| Col | Len | Field | Content |
|---|---|---|---|
| 1-38 | 38 | `REPT-SHORT-NAME` | `DALYREPT` + 30 spaces |
| 39-79 | 41 | `REPT-LONG-NAME` | `Daily Transaction Report` + 17 spaces |
| 80-91 | 12 | `REPT-DATE-HEADER` | `Date Range: ` (trailing space is byte 91) |
| 92-101 | 10 | `REPT-START-DATE` | `WS-START-DATE` verbatim (L277) |
| 102-105 | 4 | `FILLER` | ` to ` |
| 106-115 | 10 | `REPT-END-DATE` | `WS-END-DATE` verbatim (L278) |
| 116-133 | 18 | (pad) | spaces |

**4.7.2 blank line** — `WS-BLANK-LINE PIC X(133) VALUE SPACES` (L133): 133 spaces.

**4.7.3 `TRANSACTION-HEADER-1` (CVTRA07Y L33-46) — 114 significant bytes**

| Col | Len | Content |
|---|---|---|
| 1-17 | 17 | `Transaction ID` + 3 spaces |
| 18-29 | 12 | `Account ID` + 2 spaces |
| 30-48 | 19 | `Transaction Type` + 3 spaces |
| 49-83 | 35 | `Tran Category` + 22 spaces |
| 84-97 | 14 | `Tran Source` + 3 spaces |
| 98 | 1 | space |
| 99-114 | 16 | `        Amount` (8 spaces, `Amount` at 107-112, 2 trailing spaces) |
| 115-133 | 19 | spaces |

**4.7.4 `TRANSACTION-HEADER-2` (CVTRA07Y L48)** — 133 × `-`.

**4.7.5 `TRANSACTION-DETAIL-REPORT` (CVTRA07Y L15-31) — 114 significant bytes**

| Col | Len | Field | PIC | Source (L363-370) | Notes |
|---|---|---|---|---|---|
| 1-16 | 16 | `TRAN-REPORT-TRANS-ID` | X(16) | `TRAN-ID` | |
| 17 | 1 | FILLER | X | space | |
| 18-28 | 11 | `TRAN-REPORT-ACCOUNT-ID` | X(11) | `XREF-ACCT-ID` 9(11) | 11 digits, zero-padded (numeric→alnum move keeps leading zeros) |
| 29 | 1 | FILLER | X | space | |
| 30-31 | 2 | `TRAN-REPORT-TYPE-CD` | X(02) | `TRAN-TYPE-CD` | |
| 32 | 1 | FILLER | X | `-` | |
| 33-47 | 15 | `TRAN-REPORT-TYPE-DESC` | X(15) | `TRAN-TYPE-DESC` X(50) | **left 15 chars only** |
| 48 | 1 | FILLER | X | space | |
| 49-52 | 4 | `TRAN-REPORT-CAT-CD` | 9(04) | `TRAN-CAT-CD` | 4 digits zero-padded |
| 53 | 1 | FILLER | X | `-` | |
| 54-82 | 29 | `TRAN-REPORT-CAT-DESC` | X(29) | `TRAN-CAT-TYPE-DESC` X(50) | **left 29 chars only** |
| 83 | 1 | FILLER | X | space | |
| 84-93 | 10 | `TRAN-REPORT-SOURCE` | X(10) | `TRAN-SOURCE` | |
| 94-97 | 4 | FILLER | X(04) | spaces | |
| 98-112 | 15 | `TRAN-REPORT-AMT` | `-ZZZ,ZZZ,ZZZ.ZZ` | `TRAN-AMT` | see §4.8 |
| 113-114 | 2 | FILLER | X(02) | spaces | |
| 115-133 | 19 | (pad) | | spaces | |

`INITIALIZE TRANSACTION-DETAIL-REPORT` (L362) resets the named fields (alphanumerics to spaces,
the edited amount to zero → all spaces per §4.8) and leaves the `FILLER` `VALUE`s untouched.

**4.7.6 Total lines (CVTRA07Y L50-66) — 112 significant bytes each**

| Layout | Col 1-n label | Dots | Col 98-112 |
|---|---|---|---|
| `REPORT-PAGE-TOTALS` | 1-11 `Page Total` + 1 space | 12-97: 86 × `.` | `REPT-PAGE-TOTAL` `+ZZZ,ZZZ,ZZZ.ZZ` |
| `REPORT-ACCOUNT-TOTALS` | 1-13 `Account Total` | 14-97: 84 × `.` | `REPT-ACCOUNT-TOTAL` `+ZZZ,ZZZ,ZZZ.ZZ` |
| `REPORT-GRAND-TOTALS` | 1-11 `Grand Total` | 12-97: 86 × `.` | `REPT-GRAND-TOTAL` `+ZZZ,ZZZ,ZZZ.ZZ` |

All three end at column 112 and are padded with 21 spaces; the amount column (98-112) is
vertically aligned with the detail amount and with `Amount` in header 1.

### 4.8 Numeric-edited pictures used on the report

| PIC | Width | Behaviour (IBM Enterprise COBOL editing rules) | Examples (`_` = space) |
|---|---|---|---|
| `-ZZZ,ZZZ,ZZZ.ZZ` (detail) | 15 | Fixed leading sign position: `-` when negative, space otherwise. Zero suppression of integer digits **and** of the commas to their left. Decimal digits are also `Z`, so a value of exactly **0.00 prints as 15 spaces** (all-`Z` field with zero value → all spaces). | `504.77` → `_________504.77`; `-123.40` → `-________123.40`; `1234567.89` → `___1,234,567.89`; `0` → `_______________` |
| `+ZZZ,ZZZ,ZZZ.ZZ` (totals) | 15 | Fixed leading sign: `+` for positive, `-` for negative. Same suppression. Zero total: digits/commas/point are suppressed to spaces; the fixed sign position **must be verified against the golden file** (expected `+` followed by 14 spaces — see §9.3 Q4). | `2500.00` → `+______2,500.00`; `-75.25` → `-_________75.25` |

Both edited fields hold at most 9 integer digits, which matches `TRAN-AMT S9(09)V99` and the
three `S9(09)V99` accumulators (L134-136) — an accumulator overflow (`|total| ≥ 10^9`) truncates
high-order digits silently in COBOL (`ADD` without `ON SIZE ERROR`); Java must detect and fail
(§5.6 D6).

### 4.9 Working-storage numerics

| Field | PIC | Storage | Java | Notes |
|---|---|---|---|---|
| `WS-LINE-COUNTER` (L129) | 9(09) COMP-3 | 5-byte packed | `long lineCounter` | never reset; drives page breaks via `MOD 20` |
| `WS-PAGE-SIZE` (L131) | 9(03) COMP-3 VALUE 20 | 2-byte packed | `static final int PAGE_SIZE = 20` (configurable, default 20) | |
| `WS-PAGE-TOTAL`, `WS-ACCOUNT-TOTAL`, `WS-GRAND-TOTAL` (L134-136) | S9(09)V99 | zoned, 11 bytes | `BigDecimal` scale 2 | |
| `WS-CURR-CARD-NUM` (L137) | X(16) VALUE SPACES | | `String currentCard = " " * 16` | control-break memory |
| `WS-FIRST-TIME` (L128) | X VALUE 'Y' | | `boolean headersWritten` | |
| `ABCODE`/`TIMING` (L155-156) | S9(9) BINARY | | — | `CEE3ABD` args: 999 / 0 |

### 4.10 Relational design (additions only)

`card_xref` and `transaction` are defined in CBTRN02C §4.9 and reused as-is. New reference tables:

```sql
CREATE TABLE tran_type (
  tran_type_cd   CHAR(2)   NOT NULL PRIMARY KEY,
  tran_type_desc CHAR(50)  NOT NULL
);

CREATE TABLE tran_category (
  tran_type_cd   CHAR(2)    NOT NULL,
  tran_cat_cd    NUMERIC(4) NOT NULL,
  tran_cat_desc  CHAR(50)   NOT NULL,
  PRIMARY KEY (tran_type_cd, tran_cat_cd)
);

-- Optional: replaces the DATEPARM flat file if the team prefers parameters in the DB.
-- Otherwise DATEPARM stays a 21/80-byte text file or becomes two job parameters (§6.2).
```

The report itself is **not** persisted relationally; it is a generated fixed-width artifact.
If the extract step (§6.2, step 1) reads from the `transaction` table instead of the `DALY` flat
file, the query must be
`WHERE SUBSTR(proc_ts,1,10) BETWEEN :start AND :end ORDER BY card_num, <stable tiebreak>` —
see §9.2 R3 for why the tiebreak must reproduce DFSORT's default ordering.

---

## 5. Business Rules / Validation

### 5.1 Date-range inclusion (L173-174)

```
include  ⇔  PROC-TS[0..10) >= START-DATE  AND  PROC-TS[0..10) <= END-DATE
```

* Pure **character** comparison (both operands `PIC X(10)`); `YYYY-MM-DD` compares correctly as
  text. Both bounds are **inclusive**.
* A blank `PROC-TS` (as in the unposted `dailytran.txt` sample) is `"          "`, which is lower
  than any digit string → excluded.
* Java: `String s = procTs.substring(0,10); s.compareTo(start) >= 0 && s.compareTo(end) <= 0`.
  Do **not** parse to `LocalDate` for the comparison (a malformed timestamp must behave as
  COBOL does, i.e. be compared textually).
* What COBOL does with an **excluded** record is a latent defect (D1 in §5.6); Java skips it.

### 5.2 Account (card) control break (L181-188)

* Group key is the **16-byte card number**, not the account id. The xref lookup happens once per
  group and its `XREF-ACCT-ID` is reused for every detail in the group.
* On a key change **after the first group** (`WS-FIRST-TIME = 'N'`), `1120-WRITE-ACCOUNT-TOTALS`
  writes `Account Total` + dashes and zeros the account total, **then** the new group's xref lookup
  runs. If the very first in-range record triggers the break (it always does, since
  `WS-CURR-CARD-NUM` starts as spaces) no account total is written.
* **The last group's Account Total is never written** (nothing calls `1120-WRITE-ACCOUNT-TOTALS`
  at EOF) — defect D2b.
* Two cards mapping to the same account get separate groups; an unsorted file gets a group per
  run of equal card numbers. Java must replicate (no re-sorting inside the report step; sorting is
  the extract step's job).

### 5.3 Lookups (L187-195, L484-512)

| # | Lookup | Key | Result used | Not-found behaviour (COBOL) | Java |
|---|---|---|---|---|---|
| L1 | `CARDXREF` | `TRAN-CARD-NUM` (16) | `XREF-ACCT-ID` | `INVALID CARD NUMBER : <card>` / `FILE STATUS IS: NNNN0023` / `ABENDING PROGRAM` / U0999 | throw `ReferenceDataMissingException` → step FAILED, same messages |
| L2 | `TRANTYPE` | `TRAN-TYPE-CD` (2) | `TRAN-TYPE-DESC` (first 15) | `INVALID TRANSACTION TYPE : <cd>` / `…0023` / abend | same |
| L3 | `TRANCATG` | `TRAN-TYPE-CD` + `TRAN-CAT-CD` (`%04d`) | `TRAN-CAT-TYPE-DESC` (first 29) | `INVALID TRAN CATG KEY : <cd><cccc>` / `…0023` / abend | same |

L2 and L3 are executed for **every** in-range record (no caching in COBOL). Java may cache
(7 and 18 rows) — observable behaviour is identical because the reference files are opened
`INPUT` and are static during the run.

Order of evaluation per record: L1 (only on break) → L2 → L3 → write. A record with an unknown
type therefore aborts the job before its category is checked; the report file at that point
contains everything written so far (VSAM/QSAM output is not rolled back). Java: the partially
written report file must be deleted or clearly marked on failure (§6.7).

### 5.4 Line counter and page-break rule (L282-285, L293-341)

```
counter = 0
on first in-range record: writeHeaders()             // counter += 4
per in-range record:
    if counter % 20 == 0: writePageTotals()           // 2 lines, counter += 2
                          writeHeaders()              // 4 lines, counter += 4
    pageTotal += amt; accountTotal += amt
    writeDetail()                                     // counter += 1
on card change (not first): writeAccountTotals()      // 2 lines, counter += 2  (happens BEFORE the page check of the new record)
```

Consequences (must be reproduced exactly):

* The check is on the **running total of lines written**, not on lines-per-page. Since every
  non-detail block adds an even number of lines (2, 2, 4) and details add 1, the break point
  drifts: the first block yields 16 details (counter 4→20), then each block yields 14 details
  (26→40, 46→60 …) **when no account totals intervene**; each account-total pair pushes the next
  break 2 details earlier in the next block.
* A page total is written **only** when the modulus hits 0 immediately before a detail, or at EOF.
  Hence the page-total block can appear directly after an account-total block (both dashes lines
  adjacent).
* `1110-WRITE-GRAND-TOTALS` does not increment the counter (irrelevant).
* There is no ASA control character; consumers paginate by recognising the `DALYREPT` header.

### 5.5 Totals arithmetic

| Accumulator | Added when | Reset when | Emitted |
|---|---|---|---|
| `WS-PAGE-TOTAL` | every in-range detail (L287) **+ once more at EOF with the stale last amount (L200, defect D2a)** | after each page-total write (L298) | `Page Total` line |
| `WS-ACCOUNT-TOTAL` | same as page total (incl. the stale EOF add) | after each account-total write (L310) | `Account Total` line (never for the last group) |
| `WS-GRAND-TOTAL` | `+= WS-PAGE-TOTAL` at every page-total write (L297) — **not** per detail | never | `Grand Total` line, written once at EOF after the final page total |

Because the grand total is fed from page totals, it inherits the EOF double-count: in COBOL,
`Grand Total = Σ(in-range amounts) + amount(last record read)`.

### 5.6 Latent defects / surprising behaviour and the replicate-vs-deviate decision

| ID | Behaviour in COBOL | Root cause | Decision | Rationale |
|---|---|---|---|---|
| **D1** | An **out-of-range** record does not get skipped; `NEXT SENTENCE` (L177) transfers control to the statement after the next separator period, which is `PERFORM 9000-TRANFILE-CLOSE` (L208). The report loop terminates: no account/page/grand totals are written, files are closed, `END OF EXECUTION` is displayed, RC=0. | `NEXT SENTENCE` inside an inline `PERFORM … END-PERFORM` whose only period is after `END-PERFORM` | **Deviate**: Java *skips* the record (`ItemProcessor` returns `null`) and continues. | In production the SORT step already removes out-of-range records, so COBOL output never exercises this path; replicating a silent truncation would be indefensible. Behaviour is unit-tested in Java (AC-05) and the deviation is documented in the job's README. |
| **D2a** | At EOF the last record's `TRAN-AMT` is added a **second time** to page and account totals (L200-201), so the final `Page Total` and the `Grand Total` are overstated by the last amount. (`READ … INTO` does not modify `TRAN-RECORD` on status `'10'`.) | Copy/paste of the accumulate statement into the EOF branch | **Replicate by default, behind a switch**: `carddemo.tranrept.replicate-eof-double-count=true` (default `true` for cut-over parity). | Golden-file parity (§8.3) is the cut-over gate; business sign-off required to flip the switch (§9.3 Q3). |
| **D2b** | The last card group never gets an `Account Total` line. | No account-total call at EOF | **Replicate** (same switch as D2a governs adding it). | Same. |
| **D3** | Every in-range 350-byte record is `DISPLAY`ed (L180), plus `TRAN-AMT ` / `WS-PAGE-TOTAL` debug displays at EOF (L198-199). | Debug statements left in | **Deviate**: emit at `DEBUG` log level only. | SYSOUT is not a contractual output; 350-byte echoes are noise. |
| **D4** | Empty `DATEPARM` (status `'10'`) → `END-OF-FILE='Y'` before the loop → **empty report, RC=0**, no header, no `Reporting from` message. | L225-226, L235-236 | **Deviate**: missing/empty date parameters fail the job (`FAILED`, exit 8) with a clear message. | Silent empty report is a data-loss risk for downstream consumers. |
| **D5** | No validation of the `DATEPARM` date format; `start > end` yields an empty (or, via D1, truncated) report. | — | **Deviate**: validate `YYYY-MM-DD` and `start <= end`; fail fast. Comparison itself stays textual (§5.1). | |
| **D6** | Accumulator overflow beyond 9 integer digits truncates silently. | `ADD` without `SIZE ERROR` | **Deviate**: fail the job on overflow. | Practically unreachable; cheap to guard. |
| **D7** | Zero-record report (loop reaches EOF on first read): `TRAN-AMT` is uninitialised working storage (copybook has no `VALUE`), so the EOF `ADD` (L200) operates on spaces → in Enterprise COBOL this is an invalid decimal (`S0C7`) or zero depending on `NUMPROC`; then a page total + dashes + grand total are written with **no headers**. | Same EOF branch | **Deviate**: with zero in-range records Java writes headers + `Page Total` (0) + dashes + `Grand Total` (0) — i.e. the same three trailer lines preceded by a header block — and logs a warning. | Deterministic, no crash; header presence is the only difference and is documented. |
| **D8** | Lookup `READ` statuses other than `2x` are ignored (stale data reused). | `INVALID KEY` only | **Deviate**: any lookup failure is fatal. | Cannot occur in the RDBMS design anyway. |
| **D9** | The SORT step orders by `ZD` (zoned decimal) on the 16-digit card number; the program compares as text. Equivalent for all-digit card numbers. | — | Replicate ordering as `ORDER BY card_num` (`CHAR(16)`). | Document only. |

### 5.7 Console messages (exact text)

| Message (as displayed) | When |
|---|---|
| `START OF EXECUTION OF PROGRAM CBTRN03C` | start |
| `Reporting from <start> to <end>` | after successful `DATEPARM` read (L232-233) |
| `<350-byte TRAN-RECORD>` | each in-range record (D3) |
| `INVALID CARD NUMBER : <16>` / `INVALID TRANSACTION TYPE : <2>` / `INVALID TRAN CATG KEY : <6>` | lookup miss, followed by `FILE STATUS IS: NNNN0023` and `ABENDING PROGRAM` |
| `ERROR OPENING TRANFILE` · `ERROR OPENING REPTFILE` · `ERROR OPENING CROSS REF FILE` · `ERROR OPENING TRANSACTION TYPE FILE` · `ERROR OPENING TRANSACTION CATG FILE` · `ERROR OPENING DATE PARM FILE` | open failure |
| `ERROR READING DATEPARM FILE` · `ERROR READING TRANSACTION FILE` · `ERROR WRITING REPTFILE` | I/O failure |
| `ERROR CLOSING POSTED TRANSACTION FILE` · `ERROR CLOSING REPORT FILE` · `ERROR CLOSING CROSS REF FILE` · `ERROR CLOSING TRANSACTION TYPE FILE` · `ERROR CLOSING TRANSACTION CATG FILE` · `ERROR CLOSING DATE PARM FILE` | close failure |
| `TRAN-AMT <11 bytes zoned>` and `WS-PAGE-TOTAL<11 bytes zoned>` (no space after the label) | EOF (D3) |
| `FILE STATUS IS: NNNN<4 digits>` | after every error message (§7.3) |
| `ABENDING PROGRAM` | immediately before `CEE3ABD` |
| `END OF EXECUTION OF PROGRAM CBTRN03C` | normal end only |

---

## 6. Target Spring Batch Design

### 6.1 Module and package layout (proposed)

```
carddemo-batch-tranrept/
  com.carddemo.batch.tranrept
    TranReptJobConfig                 // job + steps
    DateParmReader / DateRange        // step 1
    ReportTransactionReader           // step 2 reader (flat file or JDBC)
    ReportEnrichmentProcessor         // date filter + 3 lookups -> ReportDetail
    TransactionReportWriter           // stateful ItemWriter: grouping, totals, page breaks
    layout/
      ReportLine (sealed)             // NameHeader, BlankLine, ColumnHeader, DashLine, Detail, PageTotal, AccountTotal, GrandTotal
      ReportLineAggregator            // LineAggregator<ReportLine>, 133-byte fixed width
      CobolEdit                       // -ZZZ,ZZZ,ZZZ.ZZ and +ZZZ,ZZZ,ZZZ.ZZ formatters
    repo/
      CardXrefRepository (shared), TranTypeRepository, TranCategoryRepository
```

Reuse from the CBTRN02C module: `ZonedDecimal` codec, `FixedWidthRecord` helpers, `CardXref`
entity/repository, `Transaction` entity.

### 6.2 Job structure

```
Job tranReptJob
  Step 1  readDateParms        (Tasklet)      DATEPARM file (or job params startDate/endDate) -> JobExecutionContext
  Step 2  extractTransactions  (chunk, optional) transaction table -> sorted 350-byte DALY file
                                              [only if the mainframe SORT step is being replaced; otherwise the
                                               job consumes the DALY file produced upstream]
  Step 3  writeTransactionReport (chunk)      DALY file -> TRANREPT (133-byte FB)
```

Job parameters: `tranFile` (path), `reportFile` (path), `dateParmFile` (path) **or**
`startDate`/`endDate` (`YYYY-MM-DD`), `runId` (identifying, for restart uniqueness),
`replicateEofDoubleCount` (default `true`, §5.6 D2). Step 1 fails the job if neither a readable
21+-byte `DATEPARM` record nor both date parameters are present (D4/D5).

### 6.3 Reader — step 3

Option A (default, mirrors the JCL): `FlatFileItemReader<Transaction>` over the 350-byte `DALY`
extract with a `FixedLengthTokenizer` using the offsets of §4.2 and the shared zoned-decimal
`FieldSetMapper`. `RecordSeparatorPolicy`: fixed 350-byte records with no line terminator when
the file is a transferred FB dataset; `\n`-terminated when produced by the Java extract step —
choose via `carddemo.io.record-terminator`.

Option B: `JdbcCursorItemReader<Transaction>` with
`SELECT … FROM transaction WHERE SUBSTR(proc_ts,1,10) BETWEEN ? AND ? ORDER BY card_num, tran_id`
(see §9.2 R3 about the tiebreak). Chunk size is irrelevant to output; use 100.

### 6.4 Processor — `ReportEnrichmentProcessor implements ItemProcessor<Transaction, ReportDetail>`

```
process(t):
  if not inRange(t.procTs.substring(0,10), start, end): return null       // D1 deviation: skip
  acctId  = xrefCache.computeIfAbsent(t.cardNum, cardXrefRepo::findById)   // L1; miss -> ReferenceDataMissingException("INVALID CARD NUMBER : " + cardNum)
  typeDsc = tranTypeRepo.findById(t.typeCd)                                // L2; miss -> "INVALID TRANSACTION TYPE : " + typeCd
  catDsc  = tranCatRepo.findById(t.typeCd, t.catCd)                        // L3; miss -> "INVALID TRAN CATG KEY : " + typeCd + %04d(catCd)
  return new ReportDetail(t.tranId, acctId, t.typeCd, left(typeDsc,15), t.catCd, left(catDsc,29), t.source, t.amt, t.cardNum)
```

The COBOL only performs the xref lookup on a card change; caching by card number gives the same
observable result and also protects against the "unsorted input → repeated lookups" case.
Lookup exceptions are **not** skippable (`faultTolerant()` is not used).

### 6.5 Writer — `TransactionReportWriter implements ItemStreamWriter<ReportDetail>`

The writer is **stateful** (current card, three totals, line counter, headers-written flag) and
delegates physical output to a `FlatFileItemWriter<ReportLine>` configured with
`ReportLineAggregator`. Per item:

```
write(chunk):
  for d in chunk:
    if !headersWritten: emit(NameHeader(start,end)); emit(Blank); emit(ColumnHeader); emit(Dash); counter+=4; headersWritten=true
    if !d.cardNum.equals(currentCard):
        if currentCardSeen: emit(AccountTotal(accountTotal)); emit(Dash); counter+=2; accountTotal=0
        currentCard=d.cardNum; currentCardSeen=true
    if counter % 20 == 0: emitPageTotals(); emit(NameHeader..Dash ×4); counter+=4
    pageTotal+=d.amt; accountTotal+=d.amt; lastAmt=d.amt
    emit(Detail(d)); counter+=1

emitPageTotals(): emit(PageTotal(pageTotal)); grandTotal+=pageTotal; pageTotal=0; emit(Dash); counter+=2

close()  (StepExecutionListener.afterStep, on success):
    if replicateEofDoubleCount && lastAmt != null: pageTotal+=lastAmt; accountTotal+=lastAmt     // D2a
    else if !replicateEofDoubleCount && currentCardSeen: emit(AccountTotal(accountTotal)); emit(Dash); counter+=2   // D2b fix
    if !headersWritten: emit header block (D7 deviation)
    emitPageTotals(); emit(GrandTotal(grandTotal))
```

Ordering subtlety preserved from L181-196: the account-total block for the previous card is
written **before** the page-break check for the new record (so the account-total lines count
toward the modulus), exactly as in COBOL.

### 6.6 Why `FlatFileItemWriter` + line aggregators rather than a template engine

| Concern | Line aggregators (chosen) | Template engine (Thymeleaf/FreeMarker/JasperReports) |
|---|---|---|
| Byte-exact 133-byte records, no terminator option | Native: `FormatterLineAggregator`/custom pad-to-133, `lineSeparator("")` supported | Templates are text-oriented; trailing-space preservation and exact padding require post-processing |
| COBOL edited pictures (`-ZZZ,ZZZ,ZZZ.ZZ`, zero → all spaces) | One reusable `CobolEdit` formatter, unit-testable in isolation | `DecimalFormat` in templates cannot express the all-spaces-on-zero rule without custom helpers |
| Stateful pagination that depends on a running counter, not on page height | Trivial inside an `ItemWriter` | Report engines paginate by page height, which would *fix* the drift in §5.4 and break parity |
| Restart / streaming | `ItemStream` integration, no in-memory document | Most template engines render whole documents |
| Multiple record layouts in one file | `ClassifierCompositeItemWriter` or a sealed `ReportLine` hierarchy with one aggregator per type | Possible but adds no value for 5 fixed layouts |

Templating is the right tool for `CBSTM03A`'s HTML statements (see that spec); here it would fight
the fixed-width contract. Decision: **`FlatFileItemWriter<ReportLine>` with a
`ReportLineAggregator` that dispatches on the sealed `ReportLine` subtype and always returns
exactly 133 characters.**

### 6.7 Repository / DAO interfaces

```java
public interface CardXrefRepository {               // shared, CBTRN02C §6.6
    Optional<CardXref> findByCardNum(String cardNum16);
}
public interface TranTypeRepository {
    Optional<TranType> findByCode(String typeCd2);           // TranType(code, desc50)
    List<TranType> findAll();                                // for preload/caching
}
public interface TranCategoryRepository {
    Optional<TranCategory> findByKey(String typeCd2, int catCd);   // TranCategory(typeCd, catCd, desc50)
    List<TranCategory> findAll();
}
```

Transactions and fault tolerance:

* Reading is non-transactional; the report file is the only output. Spring Batch's
  `FlatFileItemWriter` truncates its buffer on rollback but the safest contract is:
  on `FAILED`, delete the partial report (`StepExecutionListener.afterStep`) or write to
  `<name>.partial` and rename on success. The mainframe leaves a partial GDG generation; the Java
  job deliberately does not (documented deviation, no functional consumer of partial reports).
* Restart: the writer's state (counter, totals, current card, headers flag, lastAmt) is stored in
  the `ExecutionContext` on `update()` and restored in `open()`; `FlatFileItemWriter` restarts by
  truncating to the saved position. Simpler acceptable alternative: mark the step
  `allowStartIfComplete(true)` and always regenerate from scratch (report generation is
  idempotent and cheap: ≤ 10^5 lines).
* Single-threaded only (ordering is semantic).

### 6.8 Observability

`INFO`: the six start/end/date-range messages from §5.7 verbatim; counts of records read,
in-range, skipped, groups, pages. `DEBUG`: per-record echo (D3). `ERROR`: the exact COBOL error
strings followed by `FILE STATUS IS: NNNN00xx` equivalents (map: not found → `0023`, I/O →
`0030`, see §7.3), so operators searching SYSOUT-era runbooks still find them.

---

## 7. Output & Exit Behavior

### 7.1 Report record sequence

```
[header block]         REPORT-NAME-HEADER, blank, TRANSACTION-HEADER-1, TRANSACTION-HEADER-2   (4 lines)
repeat per in-range transaction (input order):
   [account block]     Account Total, dashes            (only when card changes, not before first group)
   [page block]        Page Total, dashes, header block (only when counter % 20 == 0 before a detail)
   detail line
at EOF:
   Page Total, dashes                                    (page total includes D2a double-count when replicated)
   Grand Total
```

Worked example (`PAGE_SIZE=20`): header block → counter 4; details 1-16 → counter 20; before
detail 17 the modulus is 0 → `Page Total`, dashes, header block → counter 26; details 17-30 →
counter 40; page block → 46; details 31-44 → 60; and so on (16 details on the first "page", 14
thereafter). If the card changes after detail 10 (counter 14), the account block makes the
counter 16 and only **4** more details fit before the next page block (counter 20). Implementers
must drive the modulus rule, not a page height; the golden fixtures (§8.1) pin this down.

Line layouts to the byte: §4.7. All records are exactly 133 bytes; no record separator on the
mainframe (`RECFM=FB`); the Java writer's `lineSeparator` is a property defaulting to `""` for the
parity run and `\n` for downstream text consumers. Character set: EBCDIC cp037 on z/OS; the Java
job emits the configured charset (parity comparison converts both sides to the same encoding).

Sample detail line (values from `dailytran.txt` record 1 joined with `cardxref`/`trantype`/
`trancatg` samples; columns per §4.7.5):

```
0000000000683580 00000000050 01-Purchase        0001-Regular Sales Draft           POS TERM               504.77
```
(114 significant bytes: `Purchase` padded to 15, `Regular Sales Draft` padded to 29, four spaces
after the 10-byte source, amount right-aligned in 98-112, two filler spaces, then padded with
spaces to column 133.)

### 7.2 Console output

See §5.7 for exact strings. Non-abend runs print exactly: `START…`, `Reporting from…`, N × record
echo, `TRAN-AMT …`, `WS-PAGE-TOTAL…`, `END…`.

### 7.3 `FILE STATUS IS: NNNN` formatting (L633-646)

* Status numeric and first digit ≠ `9`: `'00'` + the two status bytes → e.g. `NNNN0023`, `NNNN0035`.
* First digit `9` (or non-numeric): first byte, then the **binary value** of the second byte
  rendered as three digits: for VSAM extended statuses `'9x'` the second byte is a binary
  return code → `NNNN9` + `%03d`.
* Java: `String.format("FILE STATUS IS: NNNN%04d", code)` using a small status enum
  (`0023` not found, `0030` permanent I/O error, `0035` file not found on open, `0010` EOF).

### 7.4 Exit status mapping

| COBOL outcome | COBOL `RETURN-CODE` / abend | Spring Batch `ExitStatus` | Process exit code |
|---|---|---|---|
| Report produced (including empty/truncated reports, D1/D4) | `RC=0` | `COMPLETED` | `0` |
| Any open/read/write/close error, any lookup miss | `U0999` abend (`CEE3ABD` 999) | `FAILED` | `8` (non-zero; must be distinct from `4`, which CardDemo uses as "warning" in `CBTRN02C`) |
| Java-only: bad/missing date parameters (D4/D5), accumulator overflow (D6) | n/a | `FAILED` | `8` |

The program never returns `4`. Scheduler (`CardDemo.controlm`/`.ca7`) conditions downstream
steps on `RC=0`.

---

## 8. Acceptance Criteria & Test Plan

### 8.1 Test fixtures

* Reference data from `app/data/ASCII/`: `cardxref.txt` (50), `trantype.txt` (7),
  `trancatg.txt` (18) loaded into `card_xref`, `tran_type`, `tran_category`.
* Transaction input: `dailytran.txt` (300 records, 50 cards × 6) **after** posting through
  `CBTRN02C` so that `proc_ts` is populated, then run through the `TRANREPT` SORT filter
  (`2022-01-01`–`2022-07-06`, or whatever range matches the posting date used) — or a synthetic
  350-byte fixture with hand-set `PROC-TS` values.
* **Golden output**: `TRANREPT(+1)` and SYSOUT captured once from the mainframe/emulator run of
  `TRANREPT` against the same inputs, stored under `test/resources/golden/tranrept/`, converted
  to the comparison charset with fixed 133-byte records.
* Micro-fixtures (1-40 records) for each criterion below; `DATEPARM` fixture
  `2022-01-01 2022-07-06` + 59 trailing spaces.

### 8.2 Unit-level acceptance criteria

| ID | Criterion | Expected |
|---|---|---|
| AC-01 | `DATEPARM` parse: 80-byte record `2022-01-01 2022-07-06…` | start=`2022-01-01`, end=`2022-07-06`; header cols 92-101 / 106-115 show them verbatim; log `Reporting from 2022-01-01 to 2022-07-06` |
| AC-02 | Date boundary: `PROC-TS` = `2022-01-01-00.00.00.000000` and `2022-07-06-23.59.59.990000` | both included |
| AC-03 | Date boundary: `2021-12-31-…` and `2022-07-07-…` | both excluded (skipped, D1 deviation) |
| AC-04 | Blank `PROC-TS` (26 spaces) | excluded |
| AC-05 | Excluded record in the middle of the file | later in-range records still reported; totals correct (documents D1 deviation) |
| AC-06 | Xref lookup L1 hit | account column 18-28 = 11-digit `XREF-ACCT-ID` (leading zeros kept, e.g. `00000000050`) |
| AC-07 | Xref miss | job FAILED, exit 8; log `INVALID CARD NUMBER : <16>`, `FILE STATUS IS: NNNN0023`, `ABENDING PROGRAM`; partial report removed |
| AC-08 | Type lookup L2: desc `Purchase` | cols 30-47 = `01-Purchase       ` (15-char field); a 50-char description is cut to its first 15 chars |
| AC-09 | Type miss | FAILED; `INVALID TRANSACTION TYPE : <2>` |
| AC-10 | Category lookup L3: type `01`, cat `1` | key `010001`; cols 49-82 = `0001-Regular Sales Draft          `; 50-char description cut to 29 |
| AC-11 | Category miss | FAILED; `INVALID TRAN CATG KEY : 019999` (type + `%04d`) |
| AC-12 | Detail amount editing | `504.77` → `         504.77`; `-123.40` → `-        123.40`; `0.00` → 15 spaces; `123456789.00` → ` 123,456,789.00` |
| AC-13 | Total amount editing | `2500.00` → `+      2,500.00`; `-75.25` → `-         75.25`; zero → per golden (Q4) |
| AC-14 | Header block bytes | line 1 = §4.7.1 exactly (`DALYREPT` @1, `Daily Transaction Report` @39, `Date Range: ` @80…); line 2 = 133 spaces; line 3 = §4.7.3; line 4 = 133 dashes |
| AC-15 | Every record is exactly 133 bytes; total-line labels/dots at the documented columns; amounts at 98-112 | byte compare |
| AC-16 | Control break: cards A,A,B | after the 2 A details: `Account Total` (ΣA) + dashes, then B detail |
| AC-17 | Same card, non-adjacent (A,B,A) | three groups, two `Account Total` lines (replicates no re-sort) |
| AC-18 | Page break drift: 40 in-range records, one card | details 1-16, page block, details 17-30, page block, details 31-40, EOF trailer |
| AC-19 | Page break with account change: card change after detail 10 | account block written before the page check; next page block occurs when counter%20==0 immediately before a detail |
| AC-20 | EOF trailer, `replicateEofDoubleCount=true` | final `Page Total` = Σ(page amounts) + lastAmt; `Grand Total` = Σ(all page totals incl. the extra lastAmt); **no** final `Account Total` |
| AC-21 | EOF trailer, `replicateEofDoubleCount=false` | final `Account Total` + dashes emitted; `Page Total`/`Grand Total` = true sums |
| AC-22 | Grand total = Σ page totals (not recomputed from details) | assert internal equality for both switch positions |
| AC-23 | Zero in-range records | headers + `Page Total` (zero edit) + dashes + `Grand Total`; warning logged (D7) |
| AC-24 | Missing or empty `DATEPARM`, malformed date, start > end | FAILED, exit 8 (D4/D5) |
| AC-25 | Input open failure / output not writable | FAILED, exit 8; `ERROR OPENING TRANFILE` / `ERROR OPENING REPTFILE` + `FILE STATUS IS: NNNN0035` |
| AC-26 | Accumulator > 999,999,999.99 | FAILED (D6) |
| AC-27 | Zoned-decimal negative amount (`}`/`J`-`R` overpunch) decoded and edited with `-` | sign handling per CBTRN02C §4.7 |
| AC-28 | Console/log messages | `START OF EXECUTION OF PROGRAM CBTRN03C` first, `END OF EXECUTION OF PROGRAM CBTRN03C` last on success, absent on failure |

### 8.3 End-to-end parity criterion (must pass before cut-over)

Run the Java job with the golden inputs and `replicateEofDoubleCount=true`,
`lineSeparator=""`, output charset = golden charset. `cmp` of the produced report against the
golden `TRANREPT` must report **zero differing bytes and equal length**. Additionally, the
`INFO` log must contain the same `Reporting from … to …` line. Any difference must be explained
by exactly one of the documented deviations (D1, D3-D8) — none of which are exercised by the
production golden input — otherwise the implementation is wrong.

### 8.4 Non-functional criteria

* 10^6 input records processed in < 5 minutes single-threaded on the reference environment.
* Memory: O(1) in records (streaming), O(reference rows) for caches.
* Deterministic output independent of chunk size (assert equal output for chunk sizes 1, 7, 100).

---

## 9. Assumptions, Risks & Open Questions

### 9.1 Assumptions

* A1 — The Java job consumes the same **pre-filtered, card-sorted** extract the JCL SORT
  produces (or reproduces it with `ORDER BY card_num`); it does not sort internally.
* A2 — Reference tables `tran_type`/`tran_category` are loaded from `TRANTYPE.PS`/`TRANCATG.PS`
  by the migration loaders and are read-only during the run.
* A3 — `card_xref` and `transaction` follow the CBTRN02C §4.9 design; `CHAR` semantics preserve
  trailing blanks (or the writer re-pads).
* A4 — `PROC-TS` is populated for every posted transaction in `YYYY-MM-DD-…` form; unposted
  records (blank `PROC-TS`) are never in scope.
* A5 — The 133-byte output has no ASA/machine control character; no downstream consumer relies
  on form feeds.

### 9.2 Risks

| ID | Risk | Mitigation |
|---|---|---|
| R1 | `TRANREPT.jcl` declares two steps named `STEP05R` (L23, L37); on z/OS a duplicate step name is allowed but makes `COND`/restart references ambiguous and suggests the JCL was never run as-is; the golden capture must record which JCL actually ran. | Capture the exact JCL with the golden output. |
| R2 | The hard-coded SORT dates (JCL L43-44) and `DATEPARM` can diverge; the report header then lies about the range actually applied upstream. | Java job takes a single date range and applies it in both the extract (if owned) and the report; if the extract stays on the mainframe, add a check that `DATEPARM` ⊇ the extract range (Q1). |
| R3 | Within one card number the COBOL output order is DFSORT's order for equal keys — DFSORT without `EQUALS` does **not** guarantee input order. A JDBC extract with `ORDER BY card_num` alone is likewise non-deterministic; the golden file fixes one specific order. | Reproduce the extract from the golden `DALY(+1)` file for parity; for the JDBC path add a documented tiebreak (`tran_id`) and accept that parity is then only guaranteed when the mainframe SORT is run with `OPTION EQUALS` or the input was already ordered by `tran_id` within card. |
| R4 | `+ZZZ,ZZZ,ZZZ.ZZ` with a zero value — whether the fixed `+` prints or is blanked is compiler-rule detail (Q4). | Include a zero-total case in the golden capture or a one-line COBOL probe. |
| R5 | Numeric-edited `MOVE` of a value with more than 9 integer digits cannot occur (source is `S9(9)V99`), but the accumulators can overflow (D6). | Guard in Java. |
| R6 | EBCDIC→ASCII conversion of the report: the only non-alphanumerics are `-`, `.`, `,`, `+`, `:`; all map 1:1. | Parity compare in a single charset. |
| R7 | `DISPLAY TRAN-RECORD` (D3) may be relied upon by someone reading SYSOUT to reconcile counts. | Provide `INFO` counts instead; confirm with operations. |

### 9.3 Open questions (decisions required before implementation is complete)

| # | Question | Default if unanswered |
|---|---|---|
| Q1 | Does the Java job own the extract (replace the `REPROC` + `SORT` steps) or consume the mainframe `DALY(+1)` file during coexistence? | Support both readers (§6.3 A/B); production default = consume the file. |
| Q2 | What is the real content/format of `AWS.M2.CARDDEMO.DATEPARM` (there is no sample in the repo)? Is it ever multi-record (only the first record is read)? | Assume `YYYY-MM-DD YYYY-MM-DD` at columns 1-21, single record. |
| Q3 | Business sign-off to correct the EOF double-count and missing final `Account Total` (D2a/D2b) after cut-over? | Ship with `replicateEofDoubleCount=true`; raise a defect ticket against the COBOL. |
| Q4 | Exact rendering of a zero page/grand total under `+ZZZ,ZZZ,ZZZ.ZZ` (`+` + 14 spaces vs 15 spaces). | Implement as `+` + 14 spaces; verify against golden/probe and adjust `CobolEdit`. |
| Q5 | Should the Java report be produced in EBCDIC (for a mainframe consumer) or the platform charset? Record terminator? | Platform charset, no terminator for the parity run; `\n` thereafter. |
| Q6 | Is the 350-byte per-record `DISPLAY` (D3) needed by anyone? | No; `DEBUG` only. |
| Q7 | Should `PAGE_SIZE` remain 20 lines with the drifting rule, or should the "page" become a true 20-detail page once parity is proven? | Keep the COBOL rule (parity); expose `carddemo.tranrept.page-size`. |

---

## Appendix A — Column quick reference (133-byte report record)

Generated from the `CVTRA07Y` pictures; `|` marks columns 0 and 134 (i.e. the record boundary).

```
      1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123
NAME |DALYREPT                              Daily Transaction Report                 Date Range: YYYY-MM-DD to YYYY-MM-DD                  |
BLNK |                                                                                                                                     |
HDR1 |Transaction ID   Account ID  Transaction Type   Tran Category                      Tran Source            Amount                     |
HDR2 |-------------------------------------------------------------------------------------------------------------------------------------|
DETL |TTTTTTTTTTTTTTTT AAAAAAAAAAA TT-DDDDDDDDDDDDDDD CCCC-DDDDDDDDDDDDDDDDDDDDDDDDDDDDD SSSSSSSSSS    -ZZZ,ZZZ,ZZZ.ZZ                     |
PGTO |Page Total ......................................................................................+ZZZ,ZZZ,ZZZ.ZZ                     |
ACTO |Account Total....................................................................................+ZZZ,ZZZ,ZZZ.ZZ                     |
GRTO |Grand Total......................................................................................+ZZZ,ZZZ,ZZZ.ZZ                     |
```

Field starts: tran id 1, account 18, type 30, type desc 33, category 49, category desc 54,
source 84, amount 98-112; totals label 1, dots to 97, amount 98-112; everything after the last
significant byte is space-filled to 133.
