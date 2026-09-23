# CBACT04C Migration Specification: COBOL Batch to Java + Spring Batch

| | |
|---|---|
| Source program | `app/cbl/CBACT04C.cbl` (CardDemo v2.0-25-gdb72e6b-235) |
| Source JCL | `app/jcl/INTCALC.jcl`, step `STEP15 EXEC PGM=CBACT04C,PARM='2022071800'` |
| Copybooks | `app/cpy/CVTRA01Y.cpy`, `CVACT03Y.cpy`, `CVTRA02Y.cpy`, `CVACT01Y.cpy`, `CVTRA05Y.cpy` |
| Scheduler | `app/scheduler/CardDemo.controlm`, folder `MONTHLY-InterestCalculation`: `INTCALC` → `COMBTRAN` |
| Related spec | `docs/specs/CBTRN02C-migration-spec.md` (shared table design, §4.9 there) |
| Target | Java 17+, Spring Boot 3.x, Spring Batch 5.x, JDBC/JPA repository over an RDBMS |
| Status | Draft for review |

This document is derived directly from the COBOL source, copybooks and JCL listed above. It is
intended to be sufficient for an implementation team to build and verify the Java replacement
without re-reading the COBOL. Line references (`L123`) point into `CBACT04C.cbl` unless another
file is named.

---

## 1. Overview & Purpose

`CBACT04C` is the CardDemo **monthly interest calculator**, run by job `INTCALC` (Control-M
folder `MONTHLY-InterestCalculation`, followed by `COMBTRAN`). It reads the transaction category
balance file (`TCATBALF`) **sequentially in key order** (account id, transaction type, category)
and, for every category balance record:

1. When the account id changes, finishes the previous account (adds the accumulated interest to
   `ACCT-CURR-BAL`, zeroes the current-cycle credit/debit totals, `REWRITE`s the account), then
   loads the new account from `ACCTFILE` and the account's card number from `XREFFILE` via the
   alternate index on account id.
2. Looks up the annual interest rate for `(ACCT-GROUP-ID, TRANCAT-TYPE-CD, TRANCAT-CD)` in the
   disclosure group file `DISCGRP`; if that key is missing, retries with group `DEFAULT`.
3. If the rate is non-zero, computes the monthly interest
   `TRAN-CAT-BAL × rate / 1200` (truncated to cents), adds it to the account's running total and
   writes a 350-byte **interest transaction** (`TRAN-TYPE-CD='01'`, `TRAN-CAT-CD=0005`,
   source `System`) to the sequential output `TRANSACT` (`AWS.M2.CARDDEMO.SYSTRAN(+1)`).
4. `1400-COMPUTE-FEES` is an empty stub ("To be implemented").

Transaction ids are `PARM-DATE (10 chars) + 6-digit running suffix`. Any I/O error other than
"record not found" on `DISCGRP` abends the program (user abend U0999). The program never sets
`RETURN-CODE`; a normal run ends with `RC=0`.

Two behaviours are essential to understand before reading further (details in §5.6 / §9):

* **The last account in the file is never updated.** The `ELSE PERFORM 1050-UPDATE-ACCOUNT`
  branch at L219-221 is unreachable, so the final account's interest transactions are written
  but its balance is not changed and its cycle totals are not reset.
* In the shipped sample data every account's `ACCT-GROUP-ID` is blank, so **every** rate lookup
  falls through to the `DEFAULT` group.

The Java replacement must produce interest transactions and account updates that are
byte-for-byte reproducible against a COBOL golden run, with the last-account defect handled as
a documented, switchable deviation.

---

## 2. Current-State (COBOL) Analysis

### 2.1 Control flow

```
PROCEDURE DIVISION USING EXTERNAL-PARMS                        (L180-232)
  DISPLAY 'START OF EXECUTION OF PROGRAM CBACT04C'
  PERFORM 0000-TCATBALF-OPEN   (OPEN INPUT  TCATBAL-FILE)      sequential KSDS browse
  PERFORM 0100-XREFFILE-OPEN   (OPEN INPUT  XREF-FILE)         random, alt key on acct id
  PERFORM 0200-DISCGRP-OPEN    (OPEN INPUT  DISCGRP-FILE)      random
  PERFORM 0300-ACCTFILE-OPEN   (OPEN I-O    ACCOUNT-FILE)      random, rewritten
  PERFORM 0400-TRANFILE-OPEN   (OPEN OUTPUT TRANSACT-FILE)     sequential PS, new GDG gen

  PERFORM UNTIL END-OF-FILE = 'Y'                              (L188-222)
      IF END-OF-FILE = 'N'                                     <-- always true inside the loop
          PERFORM 1000-TCATBALF-GET-NEXT
          IF END-OF-FILE = 'N'
              ADD 1 TO WS-RECORD-COUNT
              DISPLAY TRAN-CAT-BAL-RECORD                      (50-byte record echoed to SYSOUT)
              IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM         (account break)
                  IF WS-FIRST-TIME NOT = 'Y'
                      PERFORM 1050-UPDATE-ACCOUNT              (finish PREVIOUS account)
                  ELSE
                      MOVE 'N' TO WS-FIRST-TIME
                  END-IF
                  MOVE 0 TO WS-TOTAL-INT
                  MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM
                  MOVE TRANCAT-ACCT-ID TO FD-ACCT-ID
                  PERFORM 1100-GET-ACCT-DATA                   (READ ACCTFILE by key; not found = abend)
                  MOVE TRANCAT-ACCT-ID TO FD-XREF-ACCT-ID
                  PERFORM 1110-GET-XREF-DATA                   (READ XREFFILE by alt key; not found = abend)
              END-IF
              MOVE ACCT-GROUP-ID    TO FD-DIS-ACCT-GROUP-ID
              MOVE TRANCAT-CD       TO FD-DIS-TRAN-CAT-CD
              MOVE TRANCAT-TYPE-CD  TO FD-DIS-TRAN-TYPE-CD
              PERFORM 1200-GET-INTEREST-RATE                   ('23' → retry with 'DEFAULT')
              IF DIS-INT-RATE NOT = 0
                  PERFORM 1300-COMPUTE-INTEREST                (COMPUTE + 1300-B-WRITE-TX)
                  PERFORM 1400-COMPUTE-FEES                    (empty stub)
              END-IF
          END-IF
      ELSE
          PERFORM 1050-UPDATE-ACCOUNT                          <-- DEAD CODE (see §5.6)
      END-IF
  END-PERFORM

  PERFORM 9000..9400 CLOSE all five files
  DISPLAY 'END OF EXECUTION OF PROGRAM CBACT04C'
  GOBACK
```

Processing is strictly **one category-balance record at a time in VSAM key order** with a
control break on account id. No commit boundaries exist; the `ACCTFILE` `REWRITE` for account
*n* happens when the first record of account *n+1* is read.

### 2.2 Paragraph reference

| Paragraph | Lines | Responsibility | Java equivalent (see §6) |
|---|---|---|---|
| `0000-TCATBALF-OPEN` | L234-250 | `OPEN INPUT TCATBAL-FILE`; non-`'00'` → `ERROR OPENING TRANSACTION CATEGORY BALANCE`, `9910`, `9999`. | Reader `open()`. |
| `0100-XREFFILE-OPEN` | L252-268 | `OPEN INPUT XREF-FILE`; error text `ERROR OPENING CROSS REF FILE` **followed by the raw 2-byte status** (only open paragraph that appends it). | Datasource check. |
| `0200-DISCGRP-OPEN` | L270-286 | `OPEN INPUT DISCGRP-FILE`; error text is `ERROR OPENING DALY REJECTS FILE` — copy/paste defect, message is wrong; not to be replicated. | Datasource check. |
| `0300-ACCTFILE-OPEN` | L289-305 | `OPEN I-O ACCOUNT-FILE`; error text `ERROR OPENING ACCOUNT MASTER FILE`. | Datasource check. |
| `0400-TRANFILE-OPEN` | L307-323 | `OPEN OUTPUT TRANSACT-FILE` (sequential, new dataset); error text `ERROR OPENING TRANSACTION FILE`. | Writer `open()`. |
| `1000-TCATBALF-GET-NEXT` | L325-348 | `READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD`. `'00'` record, `'10'` EOF → `END-OF-FILE='Y'`, else `ERROR READING TRANSACTION CATEGORY FILE` + abend. | `ItemReader.read()`; `null` at EOF. |
| `1050-UPDATE-ACCOUNT` | L350-370 | `ADD WS-TOTAL-INT TO ACCT-CURR-BAL`; `MOVE 0 TO ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT`; `REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD`; non-`'00'` → `ERROR RE-WRITING ACCOUNT FILE` + abend. Operates on whatever `ACCOUNT-RECORD` currently holds (the *previous* account at a control break). | `AccountRepository.applyInterestAndResetCycle`. |
| `1100-GET-ACCT-DATA` | L372-391 | `READ ACCOUNT-FILE INTO ACCOUNT-RECORD` by `FD-ACCT-ID`. `INVALID KEY` displays `ACCOUNT NOT FOUND: <id>` **and then** the status (`'23'`) fails the `= '00'` test → `ERROR READING ACCOUNT FILE` + abend. Missing account is fatal. | `AccountRepository.findByIdForUpdate` → throw if empty. |
| `1110-GET-XREF-DATA` | L393-413 | `READ XREF-FILE INTO CARD-XREF-RECORD KEY IS FD-XREF-ACCT-ID` (alternate index). `INVALID KEY` displays `ACCOUNT NOT FOUND: <id>` then abends the same way (`ERROR READING XREF FILE`). Any status other than `'00'` (including `'02'` duplicate-alt-key notification) is fatal — see §9 R4. | `CardXrefRepository.findFirstByAcctId` → throw if empty. |
| `1200-GET-INTEREST-RATE` | L415-440 | `READ DISCGRP-FILE INTO DIS-GROUP-RECORD` by `(group, type, cat)`. `'00'` or `'23'` accepted; `'23'` displays `DISCLOSURE GROUP RECORD MISSING` / `TRY WITH DEFAULT GROUP CODE`, sets group `'DEFAULT'`, performs `1200-A`. Other statuses → `ERROR READING DISCLOSURE GROUP FILE` + abend. | `DisclosureGroupRepository.findRate` with fallback. |
| `1200-A-GET-DEFAULT-INT-RATE` | L443-460 | Second `READ DISCGRP-FILE` with group `DEFAULT`; only `'00'` accepted, anything else (including not found) → `ERROR READING DEFAULT DISCLOSURE GROUP` + abend. | fallback lookup; missing `DEFAULT` row is fatal. |
| `1300-COMPUTE-INTEREST` | L462-470 | `COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200` (no `ROUNDED`, no `ON SIZE ERROR`); `ADD WS-MONTHLY-INT TO WS-TOTAL-INT`; `PERFORM 1300-B-WRITE-TX`. | `InterestCalculator.monthlyInterest`. |
| `1300-B-WRITE-TX` | L473-515 | Increment `WS-TRANID-SUFFIX`; build `TRAN-RECORD` (§5.4); `Z-GET-DB2-FORMAT-TIMESTAMP`; `WRITE FD-TRANFILE-REC FROM TRAN-RECORD`; non-`'00'` → `ERROR WRITING TRANSACTION RECORD` + abend. | `InterestTransactionFactory` + writer. |
| `1400-COMPUTE-FEES` | L518-520 | Empty (`* To be implemented`). | Not implemented; extension point only. |
| `9000`-`9400-*-CLOSE` | L522-611 | Close each file; non-`'00'` → abend. (`9000` message says `TRANSACTION BALANCE FILE`.) | Resource cleanup. |
| `Z-GET-DB2-FORMAT-TIMESTAMP` | L613-626 | `FUNCTION CURRENT-DATE` → `YYYY-MM-DD-HH.MM.SS.hh0000` (26 chars). Identical to CBTRN02C. | `Db2TimestampFormatter.now()` (shared). |
| `9999-ABEND-PROGRAM` | L628-632 | `DISPLAY 'ABENDING PROGRAM'`, `CALL 'CEE3ABD' USING 999, 0` → U0999. | Fatal exception → job `FAILED`. |
| `9910-DISPLAY-IO-STATUS` | L635-648 | Same status formatter as CBTRN02C (`FILE STATUS IS: NNNN`). | Log in exception handler. |

### 2.3 File SELECT / FILE STATUS handling

Each file has a two-byte `FILE STATUS` (`TCATBALF-STATUS`, `XREFFILE-STATUS`, `DISCGRP-STATUS`,
`ACCTFILE-STATUS`, `TRANFILE-STATUS`, L98-120). The program uses the same `APPL-RESULT` (0 / 12 /
16) + `88`-level pattern as CBTRN02C. Statuses treated as non-fatal:

| File | Operation | Status treated as normal |
|---|---|---|
| `TCATBALF` | `READ` (sequential) | `'10'` = end of file |
| `DISCGRP` | first `READ` (`1200`) | `'23'` = not found → retry with `DEFAULT` group |

**Everything else is fatal**, notably:

* `ACCTFILE` `READ` not found (`'23'`) — abend (a category balance for an unknown account is a
  data integrity error).
* `XREFFILE` `READ` by alternate key not found — abend (account with no card).
* `DISCGRP` second `READ` (`DEFAULT`) not found — abend.
* Any non-`'00'` on `REWRITE ACCTFILE` or `WRITE TRANSACT`.

`SELECT` particulars (L28-56):

* `TCATBAL-FILE`: `ORGANIZATION INDEXED`, `ACCESS SEQUENTIAL`, key `FD-TRAN-CAT-KEY` — a
  full sequential browse of the KSDS in ascending key order.
* `XREF-FILE`: `ACCESS RANDOM`, `RECORD KEY FD-XREF-CARD-NUM`, `ALTERNATE RECORD KEY
  FD-XREF-ACCT-ID` (no `WITH DUPLICATES` clause). The alternate index is defined
  `NONUNIQUEKEY`, `KEYS(11,25)`, `UPGRADE` in `app/jcl/XREFFILE.jcl` L72-76 and supplied on DD
  `XREFFIL1` (`INTCALC.jcl` L31-32, the COBOL naming convention for the first alternate path).
* `TRANSACT-FILE`: `ORGANIZATION SEQUENTIAL` — this program writes a flat file, **not** the
  transaction master KSDS. `COMBTRAN` later sorts it together with the master backup and
  `REPRO`s the result into `AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS` (`app/jcl/COMBTRAN.jcl`
  L22-51).

### 2.4 Program parameter

`PROCEDURE DIVISION USING EXTERNAL-PARMS` (L175-180): `PARM-LENGTH S9(4) COMP` followed by
`PARM-DATE X(10)`. `INTCALC.jcl` L22 passes `PARM='2022071800'` (10 characters, apparently
`YYYYMMDD` + `00`). `PARM-LENGTH` is never checked; if the JCL passes fewer than 10 characters,
the tail of `PARM-DATE` is whatever follows the parm in memory (undefined). The value is used
**only** as the 10-character prefix of every generated `TRAN-ID` (§5.4).

---

## 3. Files / Datasets

DD-to-dataset wiring is from `INTCALC.jcl` L27-41.

| DD name | Dataset (JCL) | COBOL file name | Organization | Access | Open mode | Record key (FD) | Copybook (WS layout) | LRECL |
|---|---|---|---|---|---|---|---|---|
| `TCATBALF` | `AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS` (`KEYS(17 0)`, `TCATBALF.jcl` L40) | `TCATBAL-FILE` | Indexed (KSDS) | Sequential | `INPUT` | `FD-TRAN-CAT-KEY` = `9(11)`+`X(2)`+`9(4)` (17 bytes) @1 | `CVTRA01Y` (`TRAN-CAT-BAL-RECORD`) | 50 |
| `XREFFILE` | `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` | `XREF-FILE` | Indexed (KSDS) | Random, by **alternate key** | `INPUT` | primary `FD-XREF-CARD-NUM X(16)` @1; alternate `FD-XREF-ACCT-ID 9(11)` @26 | `CVACT03Y` (`CARD-XREF-RECORD`) | 50 |
| `XREFFIL1` | `AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH` | (alternate index path for `XREF-FILE`) | AIX path, `NONUNIQUEKEY` | – | – | acct id, offset 25 length 11 | – | – |
| `ACCTFILE` | `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` | `ACCOUNT-FILE` | Indexed (KSDS) | Random | `I-O` | `FD-ACCT-ID 9(11)` @1 | `CVACT01Y` (`ACCOUNT-RECORD`) | 300 |
| `DISCGRP` | `AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS` (`KEYS(16 0)`, `DISCGRP.jcl` L40) | `DISCGRP-FILE` | Indexed (KSDS) | Random | `INPUT` | `FD-DISCGRP-KEY` = `X(10)`+`X(2)`+`9(4)` (16 bytes) @1 | `CVTRA02Y` (`DIS-GROUP-RECORD`) | 50 |
| `TRANSACT` | `AWS.M2.CARDDEMO.SYSTRAN(+1)` (new GDG generation, `RECFM=F,LRECL=350`) | `TRANSACT-FILE` | Sequential (PS) | Sequential | `OUTPUT` | – | `CVTRA05Y` (`TRAN-RECORD`) | 350 |

Notes:

* `TCATBALF`, `XREFFILE`, `ACCTFILE` are the same datasets CBTRN02C uses; their layouts and
  target tables are specified in the CBTRN02C spec §4.3-4.5/§4.9 and are **not** redefined here.
* `DISCGRP` is new to this spec (§4.2). It is loaded weekly from `AWS.M2.CARDDEMO.DISCGRP.PS`
  by job `DISCGRP` (Control-M folder `WEEKLY-DisclosureGroupsRefresh`).
* `TRANSACT` here is a *flat* file consumed by `COMBTRAN` (`SYSTRAN(0)` on `COMBTRAN.jcl` L26),
  which merges it with `TRANSACT.BKUP(0)` sorted by `TRAN-ID` and reloads the master KSDS. In
  the RDBMS target the natural equivalent is a direct insert into `transaction` (§6.5, §9 Q3).
* Sample ASCII fixtures: `app/data/ASCII/tcatbal.txt` (50 × 50 bytes), `discgrp.txt`
  (51 × 50 bytes), `acctdata.txt` (50 × 300), `cardxref.txt` (50 × 50).

---

## 4. Data Model & Field Mappings

Type-mapping conventions (`X(n)` → fixed-width `String`, `9(n)` → `long`/`int`, `S9(n)V99` →
`BigDecimal` scale 2 with sign overpunch on the last byte, `X(26)` timestamps kept as `String`)
are exactly those in CBTRN02C spec §4.1 and §4.7 and apply unchanged. No `COMP-3` fields exist in
any copybook used by this program; the only binary items are the `COMP`/`BINARY` working
fields (`APPL-RESULT`, `ABCODE`, `TIMING`, `TWO-BYTES-BINARY`, `PARM-LENGTH`), none of which are
persisted.

### 4.1 Shared records (defined in the CBTRN02C spec)

| Copybook | Record | Used here as | Spec reference |
|---|---|---|---|
| `CVTRA01Y` | `TRAN-CAT-BAL-RECORD` (50) | driver input, read-only | CBTRN02C §4.5, table `tran_cat_bal` |
| `CVACT03Y` | `CARD-XREF-RECORD` (50) | lookup by `XREF-ACCT-ID`, supplies `XREF-CARD-NUM` | CBTRN02C §4.3, table `card_xref` (index `ix_card_xref_acct` is the AIX equivalent) |
| `CVACT01Y` | `ACCOUNT-RECORD` (300) | read + rewrite (`ACCT-CURR-BAL`, `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT`); `ACCT-GROUP-ID` read | CBTRN02C §4.4, table `account` |
| `CVTRA05Y` | `TRAN-RECORD` (350) | output record | CBTRN02C §4.6 / Appendix A, table `transaction` |

Fields of `ACCOUNT-RECORD` touched by this program: `ACCT-ID` (offset 1, used in `TRAN-DESC`),
`ACCT-CURR-BAL` (13-24, updated), `ACCT-CURR-CYC-CREDIT` (79-90, set to 0),
`ACCT-CURR-CYC-DEBIT` (91-102, set to 0), `ACCT-GROUP-ID` (113-122, read). All other columns
must be left untouched by the Java update.

### 4.2 `CVTRA02Y` — Disclosure group (`DIS-GROUP-RECORD`, 50 bytes, KSDS key = 16-byte composite) — NEW

| # | COBOL field | PIC | Offset (1-based) | Len | Java field | Java type |
|---|---|---|---|---|---|---|
| 1 | `DIS-ACCT-GROUP-ID` | `X(10)` | 1 | 10 | `acctGroupId` (PK part 1) | `String` |
| 2 | `DIS-TRAN-TYPE-CD` | `X(02)` | 11 | 2 | `tranTypeCd` (PK part 2) | `String` |
| 3 | `DIS-TRAN-CAT-CD` | `9(04)` | 13 | 4 | `tranCatCd` (PK part 3) | `int` |
| 4 | `DIS-INT-RATE` | `S9(04)V99` | 17 | 6 | `intRate` | `BigDecimal(scale 2)` — **annual percentage**, e.g. `15.00` |
| 5 | `FILLER` | `X(28)` | 23 | 28 | – | not mapped |

Cross-check against `app/data/ASCII/discgrp.txt` (51 records, all 50 bytes):

```
A00000000001000100150{0000000000000000000000000000
│         ││  ││  ││    └ FILLER (28)
│         ││  ││  │└──── DIS-INT-RATE "00150{" = digits 001500, sign + → 15.00
│         ││  │└─└────── DIS-TRAN-CAT-CD "0001"
│         │└─└────────── DIS-TRAN-TYPE-CD "01"
└─────────└───────────── DIS-ACCT-GROUP-ID "A000000000"
```

Distinct groups in the sample: `A000000000` (17 rows), `DEFAULT` (17 rows), `ZEROAPR`
(17 rows, all rates `0.00`). Distinct rates: `0.00` (30 rows), `15.00` (15), `25.00` (6). Type/
category pairs present per group: `01/0001-0004`, `02/0001-0003`, `03/0001-0003`, `04/0001-0003`,
`05/0001`, `06/0001-0002`, `07/0001`.

### 4.3 Sample-data observation: `ACCT-GROUP-ID` is blank

In `app/data/ASCII/acctdata.txt` (50 × 300 bytes) bytes 103-112 (`ACCT-ADDR-ZIP`) contain
`A000000000` in every record and bytes 113-122 (`ACCT-GROUP-ID`) are **spaces** in every record:

```
00000000001Y00000001940{00000020200{00000010200{2014-11-202025-05-202025-05-2000000000000{00000000000{A000000000<10 spaces>...
                                                                                                       ^103      ^113
```

The group id appears to have been generated one field too early. Consequence for the program:
the first `DISCGRP` read uses key `'          01' + cat` → status `'23'` → `DEFAULT` fallback
for every record, and the SYSOUT contains `DISCLOSURE GROUP RECORD MISSING` /
`TRY WITH DEFAULT GROUP CODE` 50 times. The Java implementation must reproduce the fallback
faithfully; whether the data should be corrected is an open question (§9 Q5). Do not "fix" it
by reading the zip column.

### 4.4 Working-storage items that shape the output

| Item | PIC | Lines | Role |
|---|---|---|---|
| `WS-LAST-ACCT-NUM` | `X(11)` `VALUE SPACES` | L167 | Control-break key; compared alphanumerically against `TRANCAT-ACCT-ID 9(11)`. |
| `WS-MONTHLY-INT` | `S9(09)V99` | L168 | Interest for one category record; truncating receiver of the `COMPUTE`. |
| `WS-TOTAL-INT` | `S9(09)V99` | L169 | Sum of `WS-MONTHLY-INT` for the current account; reset to 0 at each break. |
| `WS-FIRST-TIME` | `X(01)` `VALUE 'Y'` | L170 | Suppresses `1050` on the first break. |
| `WS-RECORD-COUNT` | `9(09)` `VALUE 0` | L172 | Records read; **never displayed**. |
| `WS-TRANID-SUFFIX` | `9(06)` `VALUE 0` | L173 | Incremented before each write; wraps silently after 999999. |
| `PARM-DATE` | `X(10)` | L178 | `TRAN-ID` prefix. |
| `TRAN-RECORD` (`CVTRA05Y`) | 350 bytes, **no `VALUE`, never `INITIALIZE`d** | L117 | Output buffer; fields not explicitly set keep their initial working-storage content (§5.4, §9 R3). |

### 4.5 Relational design — additions to CBTRN02C §4.9

Only one new table is required; all other tables are reused as defined there.

```sql
CREATE TABLE disclosure_group (
  acct_group_id CHAR(10)     NOT NULL,
  tran_type_cd  CHAR(2)      NOT NULL,
  tran_cat_cd   NUMERIC(4)   NOT NULL,
  int_rate      DECIMAL(6,2) NOT NULL,          -- annual %, S9(4)V99
  PRIMARY KEY (acct_group_id, tran_type_cd, tran_cat_cd)
);
```

Reused without change: `tran_cat_bal` (driver; PK order `(acct_id, tran_type_cd, tran_cat_cd)`
gives the VSAM browse order), `card_xref` with `ix_card_xref_acct` (AIX equivalent), `account`,
`transaction`. No new columns are needed on any existing table. The `DEFAULT` fallback is a
second lookup on the same table with `acct_group_id = 'DEFAULT   '` (blank-padded to 10 — or
trimmed, consistently with the engine's `CHAR` semantics).

---

## 5. Business Rules / Validation

### 5.1 Account control break (L194-206)

* Break condition: `TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM`. Because `TCATBALF` is browsed in
  key order, all category rows for one account are contiguous; the Java reader must preserve
  `ORDER BY acct_id, tran_type_cd, tran_cat_cd`.
* On a break, **before** loading the new account:
  * if this is not the first account, `1050-UPDATE-ACCOUNT` finalises the previous account
    (§5.5);
  * `WS-TOTAL-INT := 0`.
* Then the new account is read (`1100`; missing → fatal) and its card is read via the account-id
  alternate index (`1110`; missing → fatal). The card number is captured once per account and
  stamped on every interest transaction of that account.

### 5.2 Rate lookup with `DEFAULT` fallback (L210-213, L415-460)

```
key1 = (ACCT-GROUP-ID, TRANCAT-TYPE-CD, TRANCAT-CD)
row  = disclosure_group[key1]
if row missing:                                   -- FILE STATUS '23'
    log 'DISCLOSURE GROUP RECORD MISSING'
    log 'TRY WITH DEFAULT GROUP CODE'
    key2 = ('DEFAULT', TRANCAT-TYPE-CD, TRANCAT-CD)
    row  = disclosure_group[key2]
    if row missing: FATAL ('ERROR READING DEFAULT DISCLOSURE GROUP', status 0023)
rate = row.DIS-INT-RATE
```

* The fallback is on **group only**; the type/category codes come from the category balance
  record and are never defaulted.
* The lookup happens for **every** category record (not cached per account in COBOL). Caching in
  Java is permitted because `DISCGRP` is opened `INPUT` and cannot change during the run.
* A zero rate (`DIS-INT-RATE = 0`, e.g. group `ZEROAPR`) means: **no interest transaction, no
  addition to `WS-TOTAL-INT`, no suffix increment** for that record (L214-217). The account is
  still finalised at the next break (with whatever total it has, possibly 0).

### 5.3 Interest formula (L464-465)

```
WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE ) / 1200
```

* `TRAN-CAT-BAL` is `S9(09)V99`, `DIS-INT-RATE` is `S9(04)V99` (an annual percentage), so
  `/ 1200` = `/ 100` (percent → fraction) `/ 12` (annual → monthly).
* **No `ROUNDED`**: the result is truncated toward zero to 2 decimals. In IBM Enterprise COBOL the
  intermediate product is held exactly (11 × 6 digits, well below the 30-digit limit) and the
  quotient carries at least the receiving field's decimals before final truncation; since
  truncation at ≥2 decimals followed by truncation at 2 equals a single truncation at 2, the
  exact rule is:

  ```java
  BigDecimal monthly = bal.multiply(rate)                        // exact, scale 4
                         .divide(new BigDecimal("1200"), 2, RoundingMode.DOWN);
  ```

  `RoundingMode.DOWN` (toward zero) — **not** `FLOOR` — so negative balances truncate toward zero
  symmetrically with positive ones.
* **No `ON SIZE ERROR`**: a result whose integer part exceeds 9 digits would be silently
  high-order truncated into `WS-MONTHLY-INT`. Not reachable with realistic data
  (|bal| < 10^9, rate ≤ 9999.99 ⇒ |monthly| < 8.4 × 10^9 only in absurd cases); Java should
  assert and fail (§9 R2).
* Worked examples (rate 15.00 unless stated):

  | `TRAN-CAT-BAL` | rate | exact | `WS-MONTHLY-INT` |
  |---|---|---|---|
  | `1234.56` | 15.00 | 15.432 | `15.43` |
  | `-1234.56` | 15.00 | −15.432 | `-15.43` |
  | `999.99` | 25.00 | 20.833125 | `20.83` |
  | `0.01` | 15.00 | 0.000125 | `0.00` (transaction **is** still written) |
  | `0.00` | 15.00 | 0 | `0.00` (transaction **is** still written) |
  | `100.00` | 0.00 | – | no transaction (rate zero) |
  | `123.45` | 15.00 | 1.543125 | `1.54` |
  | `-0.07` | 25.00 | −0.001458 | `0.00` (IBM COBOL gives a zero arithmetic result a positive sign, so the overpunched output is `0000000000{`; confirm from the golden run — AC-C5) |

* `ADD WS-MONTHLY-INT TO WS-TOTAL-INT` (L467) — plain addition, scale 2, no rounding.

### 5.4 Interest transaction construction (`1300-B-WRITE-TX`, L473-498)

| `TRAN-RECORD` field | Offset/Len | Value | Notes |
|---|---|---|---|
| `TRAN-ID` | 1-16 | `PARM-DATE (10) ∥ WS-TRANID-SUFFIX (6, zero-padded)` via `STRING ... DELIMITED BY SIZE` | Exactly fills 16 bytes. e.g. `2022071800000001`. Suffix is global across the run (not per account), starts at `000001`, increments only when a transaction is written. |
| `TRAN-TYPE-CD` | 17-18 | `'01'` | `trantype.txt`: `01 = Purchase`. |
| `TRAN-CAT-CD` | 19-22 | `0005` | `MOVE '05'` (alphanumeric literal) to `9(04)` → `0005`. `trancatg.txt`: `010005 = Interest Amount`. |
| `TRAN-SOURCE` | 23-32 | `'System    '` | space-padded to 10. |
| `TRAN-DESC` | 33-132 | bytes 33-56 = `'Int. for a/c '` (13) ∥ `ACCT-ID` (11 digits, zero-padded) → 24 chars; bytes 57-132 **not written by the `STRING`** | `STRING` does not space-fill the remainder. Content of bytes 57-132 = initial working-storage content of `TRAN-DESC` (§9 R3; treat as spaces). `ACCT-ID` is the account just read (`ACCOUNT-RECORD`), which equals `TRANCAT-ACCT-ID`. |
| `TRAN-AMT` | 133-143 | `WS-MONTHLY-INT` | signed overpunch, e.g. `0000000154C` = `+15.43`, `0000000154L` = `−15.43`. |
| `TRAN-MERCHANT-ID` | 144-152 | `000000000` | |
| `TRAN-MERCHANT-NAME` | 153-202 | spaces | |
| `TRAN-MERCHANT-CITY` | 203-252 | spaces | |
| `TRAN-MERCHANT-ZIP` | 253-262 | spaces | |
| `TRAN-CARD-NUM` | 263-278 | `XREF-CARD-NUM` | the card found via the account-id alternate index (§5.1); for accounts with several cards see §9 R4. |
| `TRAN-ORIG-TS` | 279-304 | `DB2-FORMAT-TS` | `YYYY-MM-DD-HH.MM.SS.hh0000`, generated per transaction. **Same value** as `PROC-TS`. |
| `TRAN-PROC-TS` | 305-330 | `DB2-FORMAT-TS` | identical to `ORIG-TS` (single call to `Z-GET-DB2-FORMAT-TIMESTAMP` at L496). |
| `FILLER` | 331-350 | not set | initial working-storage content; treat as spaces (§9 R3). |

Note that unlike CBTRN02C's `ORIG-TS` (ISO style with space and colons), the interest
transaction's `ORIG-TS` is in **DB2 format** (dashes and dots). Downstream date logic that
parses `ORIG-TS(1:10)` still works (`YYYY-MM-DD`), but any consumer parsing the time portion
must accept both formats.

### 5.5 Account finalisation (`1050-UPDATE-ACCOUNT`, L350-370)

Executed at each account break for the *previous* account (and — by design intent, but not in
fact — for the last account at EOF):

```
ACCT-CURR-BAL        := ACCT-CURR-BAL + WS-TOTAL-INT
ACCT-CURR-CYC-CREDIT := 0.00
ACCT-CURR-CYC-DEBIT  := 0.00
REWRITE ACCOUNT-RECORD              (all other fields unchanged)
```

* `WS-TOTAL-INT` may be `0.00` (all rates zero or all balances rounding to zero); the `REWRITE`
  still happens and still resets the cycle totals. Accounts with **no** `TCATBALF` rows are never
  touched and keep their cycle totals — a consequence of driving from the balance file (§9 Q6).
* `ADD` without `ON SIZE ERROR` into `S9(10)V99` — overflow impossible with realistic values.

### 5.6 Last-account defect (L188-222) — replicate or deviate

`PERFORM UNTIL END-OF-FILE = 'Y'` evaluates its condition *before* each iteration, so inside the
loop `END-OF-FILE` is always `'N'` and the `IF END-OF-FILE = 'N'` at L189 is always true. The
`ELSE PERFORM 1050-UPDATE-ACCOUNT` at L219-221 — clearly intended to finalise the last account
when EOF is reached — is **never executed**. Effects on every COBOL run:

1. The last account in `TCATBALF` key order has its interest transactions written to `TRANSACT`
   but its `ACCT-CURR-BAL` is **not** increased and its cycle totals are **not** reset.
2. If the file contains exactly one account, no account is updated at all.
3. Ledger inconsistency: `Σ interest transactions` ≠ `Σ account balance changes` by the last
   account's `WS-TOTAL-INT`.

**Decision**: the Java job finalises every account, including the last one (correct intent).
For golden-file parity a job parameter `intcalc.replicateLastAccountSkip=true` (default `false`)
reproduces the COBOL behaviour so the parity test can compare against a COBOL golden run; the
deviation is recorded in the parity test as an explicit exclusion (§8.3). Product owner
sign-off required (§9 Q1).

### 5.7 Other latent behaviours (documented, not replicated)

| Behaviour | Lines | Decision |
|---|---|---|
| `0200-DISCGRP-OPEN` error text says `DALY REJECTS FILE`. | L281 | Java logs `ERROR OPENING DISCLOSURE GROUP FILE`. |
| `0100-XREFFILE-OPEN` appends the raw status to the message, other opens do not. | L263 | Normalise: all open failures log `ERROR OPENING <file>` + `FILE STATUS IS: NNNN`. |
| `WS-RECORD-COUNT` is maintained but never displayed. | L172, L192 | Java exposes `readCount` on the step and logs `RECORDS PROCESSED :nnnnnnnnn` at end (additive; no COBOL parity impact). |
| `WS-TRANID-SUFFIX 9(06)` wraps to `000000` after 999,999 writes, producing duplicate `TRAN-ID`s. | L173, L474 | Java fails the job if the suffix would exceed 999,999 (§9 R5). |
| `PARM-LENGTH` never validated. | L177 | Java requires the `runDate`/`tranIdPrefix` parameter to be exactly 10 characters, else fail before opening any resource. |
| Rate lookup for every record (no per-account caching). | L210-213 | Java may cache; results are identical because the file is read-only for the run. |
| `1400-COMPUTE-FEES` stub. | L518-520 | Not implemented; leave a clearly named no-op extension point (`FeeCalculator`) so the design mirrors the intent. |

### 5.8 Derived / generated values

| Value | Rule |
|---|---|
| `TRAN-ID` | `PARM-DATE ∥ %06d(suffix)`; suffix starts at 1 and increments per written transaction. |
| `TRAN-ORIG-TS` = `TRAN-PROC-TS` | DB2-format local timestamp at write time, centisecond precision + `0000`. |
| `WS-TOTAL-INT` | Σ `WS-MONTHLY-INT` over the account's non-zero-rate categories. |
| `RETURN-CODE` | Never set → `0` on normal completion; abend → U0999. |

---

## 6. Target Spring Batch Design

### 6.1 Module and package layout (proposed)

```
carddemo-batch-intcalc/
  src/main/java/com/carddemo/batch/intcalc/
    IntCalcJobConfig.java                 // Job + Step beans
    model/DisclosureGroup.java            // CVTRA02Y
    model/AccountInterestUnit.java        // one account + its List<TranCategoryBalance>
    model/InterestResult.java             // List<Transaction> + mutated Account (nullable)
    io/AccountGroupingItemReader.java     // control-break reader (§6.3)
    io/InterestTransactionLineAggregator.java  // 350-byte fixed-width (optional file output)
    calc/InterestCalculator.java          // §5.3
    calc/InterestTransactionFactory.java  // §5.4
    calc/FeeCalculator.java               // no-op (1400-COMPUTE-FEES)
    calc/IntCalcItemProcessor.java
    io/InterestResultWriter.java          // CompositeItemWriter
    repo/DisclosureGroupRepository.java
    listener/IntCalcJobListener.java
  (shared from posttran module) model/Account, TranCategoryBalance, CardXref, Transaction,
                                io/ZonedDecimal, io/Db2TimestampFormatter,
                                repo/AccountRepository, CardXrefRepository, TransactionRepository
```

### 6.2 Job structure

```
Job: intCalcJob
  parameters: tranIdPrefix   (required, identifying, exactly 10 chars — the COBOL PARM, e.g. 2022071800)
              systranFile    (optional; if set, also write the 350-byte SYSTRAN flat file)
              chunkSize      (optional, default 50 accounts)
              replicateLastAccountSkip (optional, default false; §5.6)
  Step 1: validateParametersStep (tasklet)      -- length check on tranIdPrefix, DEFAULT group presence check
  Step 2: calculateInterestStep  (chunk-oriented, item = one account)
      reader    : accountGroupingItemReader   (ItemReader<AccountInterestUnit>)
      processor : intCalcItemProcessor        (ItemProcessor<AccountInterestUnit, InterestResult>)
      writer    : interestResultWriter        (CompositeItemWriter<InterestResult>)
      listeners : IntCalcJobListener (exit code, summary log)
      fault tolerance: NONE
```

The **item is an account**, not a category row. This maps the COBOL control break onto Spring
Batch's chunk model cleanly: all writes for one account (n transactions + 1 account update) are
produced by one `process()` call and committed with the chunk. Single-threaded, no partitioning:
the global `TRAN-ID` suffix is a sequential counter and must be assigned in key order.

### 6.3 Reader — `AccountGroupingItemReader`

* Delegate: `JdbcCursorItemReader<TranCategoryBalance>` (or `JpaCursorItemReader`) with
  `SELECT acct_id, tran_type_cd, tran_cat_cd, tran_cat_bal FROM tran_cat_bal ORDER BY acct_id,
  tran_type_cd, tran_cat_cd` — the VSAM browse order. `saveState=true`.
* Wrapped in `SingleItemPeekableItemReader`; `read()` collects consecutive rows with the same
  `acct_id` into one `AccountInterestUnit(acctId, List<TranCategoryBalance>)`, peeking ahead to
  detect the break. Returns `null` at EOF.
* For file-based parity runs, a `FlatFileItemReader` over `tcatbal.txt` (50-byte records,
  ranges `1-11, 12-13, 14-17, 18-28, 29-50`) can replace the JDBC delegate; the grouping wrapper
  is unchanged. The file must already be in key order (VSAM guarantees this; the fixture is).

### 6.4 Processor — `IntCalcItemProcessor`

```java
public InterestResult process(AccountInterestUnit unit) {
    Account acct = accountRepo.findByIdForUpdate(unit.acctId())
        .orElseThrow(() -> new FatalBatchException("ACCOUNT NOT FOUND: " + pad11(unit.acctId())));
    CardXref xref = cardXrefRepo.findFirstByAcctId(unit.acctId())          // §9 R4 ordering
        .orElseThrow(() -> new FatalBatchException("ACCOUNT NOT FOUND: " + pad11(unit.acctId())));

    BigDecimal totalInt = BigDecimal.ZERO.setScale(2);
    List<Transaction> txns = new ArrayList<>();
    for (TranCategoryBalance cat : unit.categories()) {                     // key order
        log.info(formatTcatbalRecord(cat));                                 // DISPLAY TRAN-CAT-BAL-RECORD
        BigDecimal rate = rateLookup.rateFor(acct.groupId(), cat.tranTypeCd(), cat.tranCatCd()); // §5.2
        if (rate.signum() != 0) {
            BigDecimal monthly = interestCalculator.monthlyInterest(cat.tranCatBal(), rate);   // §5.3
            totalInt = totalInt.add(monthly);
            txns.add(txFactory.create(tranIdSequence.next(), acct.acctId(), xref.cardNum(), monthly)); // §5.4
            feeCalculator.apply(acct, cat);                                 // no-op
        }
    }
    boolean finalise = !(replicateLastAccountSkip && unit.isLast());
    Account updated = finalise ? acct.withInterestApplied(totalInt) : null; // §5.5
    return new InterestResult(txns, updated);
}
```

* `tranIdSequence` is a step-scoped, `ExecutionContext`-persisted counter (`intcalc.suffix`) so
  a restart continues numbering; it starts at 1 and throws past 999,999.
* `rateLookup` wraps `DisclosureGroupRepository` with the `DEFAULT` fallback and emits the two
  COBOL log lines on fallback. It may memoise `(group,type,cat) → rate` for the step's lifetime.
* `unit.isLast()` is set by the reader when the peek returns `null` (needed only for the
  replicate flag).

### 6.5 Writers — `CompositeItemWriter<InterestResult>`

| Order | Writer | Target |
|---|---|---|
| 1 | `TransactionItemWriter` | `INSERT INTO transaction` for each generated `Transaction` (`DuplicateKeyException` is fatal — the COBOL equivalent is `COMBTRAN`/`REPRO` failing later, so failing early is a documented improvement, §9 Q3). |
| 2 | `SystranFileItemWriter` (optional, `systranFile` set) | `FlatFileItemWriter<Transaction>` with the 350-byte `InterestTransactionLineAggregator`; `shouldDeleteIfEmpty=false`; transaction-aware. Produces the `SYSTRAN(+1)` equivalent for parity and for any downstream still consuming the flat file. |
| 3 | `AccountItemWriter` | `UPDATE account SET curr_bal = ?, curr_cyc_credit = 0, curr_cyc_debit = 0 WHERE acct_id = ?` when `updated != null`; must affect exactly 1 row. |

COBOL order is transactions first (as each category is processed), then the account rewrite at
the next break; inside a DB transaction the order is immaterial but is kept for log comparability.

### 6.6 Repository / DAO interfaces

```java
public interface DisclosureGroupRepository {
    Optional<DisclosureGroup> findByKey(String acctGroupId, String tranTypeCd, int tranCatCd); // DISCGRP random READ
}

// Additions to the CBTRN02C interfaces
public interface CardXrefRepository {
    Optional<CardXref> findByCardNum(String cardNum);
    Optional<CardXref> findFirstByAcctId(long acctId);        // XREFFILE READ KEY IS FD-XREF-ACCT-ID (AIX);
                                                              // ORDER BY card_num LIMIT 1 — see §9 R4
}

public interface AccountRepository {
    Optional<Account> findByIdForUpdate(long acctId);
    int applyInterestAndResetCycle(long acctId, BigDecimal newCurrBal);  // REWRITE in 1050: sets cyc credit/debit = 0
}

public interface TranCategoryBalanceRepository {
    Stream<TranCategoryBalance> streamAllInKeyOrder();        // TCATBALF sequential browse (used by the cursor reader)
}
```

### 6.7 Transactions, restart and fault tolerance

* One DB transaction per chunk of accounts. A failure rolls back the chunk's transactions, file
  lines and account updates together — the COBOL leaves partial state (transactions written for
  an account whose balance was not yet updated).
* **No skip/retry.** Missing account, missing card, missing `DEFAULT` rate, duplicate `tran_id`,
  numeric overflow → step `FAILED`.
* Restart resumes at the first uncommitted account with the persisted `TRAN-ID` suffix; output is
  identical to an uninterrupted run except `ORIG-TS`/`PROC-TS` values.
* `tranIdPrefix` is an identifying parameter: rerunning the same prefix to completion twice would
  generate duplicate `tran_id`s and is blocked by Spring Batch's `JobInstanceAlreadyCompleteException`
  unless a `run.id` incrementer is supplied deliberately (as with COBOL, where a rerun would produce
  a second `SYSTRAN` generation with clashing ids for `COMBTRAN` to choke on).

### 6.8 Observability

Log lines mirroring the COBOL `DISPLAY`s (normal path, exact wording):

```
START OF EXECUTION OF PROGRAM CBACT04C
000000000010100010000000000{0000000000000000000000        (one 50-byte line per category record)
DISCLOSURE GROUP RECORD MISSING                           (per fallback)
TRY WITH DEFAULT GROUP CODE
END OF EXECUTION OF PROGRAM CBACT04C
```

Additional (non-parity) lines: `RECORDS PROCESSED :nnnnnnnnn`, `ACCOUNTS UPDATED :nnnnnnnnn`,
`INTEREST TRANSACTIONS WRITTEN :nnnnnnnnn`, `TOTAL INTEREST :±nnnnnnnnn.nn`. Counts also on the
`StepExecution` (`readCount` = accounts, `intcalc.categoryCount`, `intcalc.txnCount`) and as
Micrometer counters.

---

## 7. Output & Exit Behavior

### 7.1 Interest transaction file (`TRANSACT` → `SYSTRAN(+1)`) — 350 bytes per record

Layout is CBTRN02C Appendix A. Byte-level content of a generated record for account
`00000000001`, card `0500024453765740`, interest `15.43`, prefix `2022071800`, first transaction,
timestamp `2022-07-18-02.30.15.120000`:

```
 1-16   2022071800000001                    TRAN-ID
17-18   01                                  TRAN-TYPE-CD
19-22   0005                                TRAN-CAT-CD
23-32   System····                          TRAN-SOURCE (· = space)
33-56   Int. for a/c 00000000001            TRAN-DESC bytes 1-24
57-132  <76 bytes: initial WS content, expected spaces>
133-143 0000000154C                         TRAN-AMT +15.43 (overpunch C = +3)
144-152 000000000                           TRAN-MERCHANT-ID
153-202 <50 spaces>                         TRAN-MERCHANT-NAME
203-252 <50 spaces>                         TRAN-MERCHANT-CITY
253-262 <10 spaces>                         TRAN-MERCHANT-ZIP
263-278 0500024453765740                    TRAN-CARD-NUM
279-304 2022-07-18-02.30.15.120000          TRAN-ORIG-TS (DB2 format)
305-330 2022-07-18-02.30.15.120000          TRAN-PROC-TS (same value)
331-350 <20 bytes: initial WS content, expected spaces>
```

Records appear in `TCATBALF` key order; one record per category row with a non-zero rate. The
file is created new every run (`DISP=(NEW,CATLG,DELETE)`) and exists (possibly empty) even if no
interest was generated. No header/trailer.

### 7.2 Updated `account` rows

Every account that has at least one `tran_cat_bal` row — **except the last in key order when
`replicateLastAccountSkip=true`** — gets `curr_bal += totalInt`, `curr_cyc_credit = 0`,
`curr_cyc_debit = 0`. Nothing else changes. `tran_cat_bal` is **not** modified by this program.

### 7.3 Console output

| Message | When |
|---|---|
| `START OF EXECUTION OF PROGRAM CBACT04C` | job start |
| `<50-byte TRAN-CAT-BAL-RECORD>` | each category record read |
| `DISCLOSURE GROUP RECORD MISSING` / `TRY WITH DEFAULT GROUP CODE` | each fallback to `DEFAULT` |
| `ACCOUNT NOT FOUND: <11 digits>` then `ERROR READING ACCOUNT FILE` / `ERROR READING XREF FILE`, `FILE STATUS IS: 0023`, `ABENDING PROGRAM` | missing account / card (fatal) |
| `ERROR READING DEFAULT DISCLOSURE GROUP`, `FILE STATUS IS: 0023`, `ABENDING PROGRAM` | missing `DEFAULT` rate (fatal) |
| `ERROR <OPENING|READING|RE-WRITING|WRITING|CLOSING> <file>` + `FILE STATUS IS: NNNN` + `ABENDING PROGRAM` | other fatal I/O |
| `END OF EXECUTION OF PROGRAM CBACT04C` | end (not printed on abend) |

No counts are printed by the COBOL.

### 7.4 Exit status mapping

| COBOL outcome | COBOL `RETURN-CODE` / abend | Spring Batch `ExitStatus` | Process exit code |
|---|---|---|---|
| Normal completion (with or without interest generated) | `RC=0` | `COMPLETED` | `0` |
| Any fatal I/O / data error | `U0999` abend | `FAILED` | `8` (same convention as CBTRN02C §7.5, pending §9 Q4 there) |
| Parameter validation failure (Java only) | n/a (COBOL would run with garbage prefix) | `FAILED` | `8` |

Control-M conditions `MONTHLY-InterestCalculation-INTCALC` (+) → `COMBTRAN` on success; the Java
launcher must return `0` for the downstream to release.

---

## 8. Acceptance Criteria & Test Plan

### 8.1 Test fixtures

* Sample data: `tcatbal.txt` (50 rows, accounts 1-50, all `01/0001`, all balances `0.00`),
  `discgrp.txt`, `acctdata.txt` (all `ACCT-GROUP-ID` blank), `cardxref.txt` (50 cards, 50
  distinct accounts). With these inputs the COBOL produces **50 transactions of `0.00`**
  (`2022071800000001`-`2022071800000050`), 49 account rewrites that change nothing byte-wise
  (cycle totals are already zero) and no rewrite of account 50. This exercises the fallback path
  and id generation but **not** the arithmetic, so hand-crafted fixtures are mandatory.
* Golden output: run `INTCALC` on the mainframe/emulator against the sample data **and** against
  the crafted fixture set below; capture `SYSTRAN(+1)`, the post-run `ACCTDATA` and SYSOUT under
  `test/resources/golden/intcalc/`.
* Crafted fixture `intcalc-crafted`: 4 accounts —
  * acct 1: group `A000000000`, categories `01/0001` bal `1234.56`, `01/0002` bal `-1234.56`,
    `02/0001` bal `999.99`;
  * acct 2: group `ZEROAPR`, one category → no transactions, still finalised with `+0.00`;
  * acct 3: group blank, category `01/0003` bal `123.45` → `DEFAULT` fallback;
  * acct 4 (last): group `A000000000`, `01/0001` bal `100.00` → exercises §5.6;
  * acct 3 has two cards in `card_xref` (R4).
* Fixed `Clock` (`2026-01-02T03:04:05.06`) so timestamps are deterministic.

### 8.2 Unit-level acceptance criteria

| ID | Criterion | Expected |
|---|---|---|
| AC-C1 | `monthlyInterest(1234.56, 15.00)` | `15.43` |
| AC-C2 | `monthlyInterest(-1234.56, 15.00)` | `-15.43` (toward zero) |
| AC-C3 | `monthlyInterest(999.99, 25.00)` | `20.83` |
| AC-C4 | `monthlyInterest(0.01, 15.00)` | `0.00`, scale 2 |
| AC-C5 | `monthlyInterest(-0.07, 25.00)` | `0.00` and serialises as `0000000000{` (no negative zero) |
| AC-C6 | result magnitude ≥ 10^9 | exception, job `FAILED` (no silent truncation) |
| AC-R1 | `DisclosureGroup` parsed from `A00000000001000100150{…` | group `A000000000`, type `01`, cat `1`, rate `15.00` |
| AC-R2 | rate lookup, key present | returns row rate, no fallback log lines |
| AC-R3 | rate lookup, key absent, `DEFAULT` present | returns `DEFAULT` rate; logs `DISCLOSURE GROUP RECORD MISSING` then `TRY WITH DEFAULT GROUP CODE` |
| AC-R4 | rate lookup, key absent, `DEFAULT` absent for that type/cat | `FatalBatchException`, step `FAILED`, exit `8` |
| AC-R5 | rate `0.00` | no transaction, suffix not incremented, `totalInt` unchanged |
| AC-T1 | first generated `TRAN-ID` with prefix `2022071800` | `2022071800000001`; 1000th → `2022071800001000` |
| AC-T2 | suffix would reach `1000000` | job `FAILED` before writing |
| AC-T3 | `tranIdPrefix` of 9 or 11 chars | job `FAILED` in `validateParametersStep`, no resources opened |
| AC-T4 | generated `Transaction` for acct `1`, card `0500024453765740`, `15.43` | fields exactly as §7.1; `origTs == procTs`; `tranCatCd == 5`; `merchantId == 0`; desc = `"Int. for a/c 00000000001"` padded to 100 |
| AC-A1 | account `currBal=194.00, cycCredit=50.00, cycDebit=-20.00`, `totalInt=15.43` | `currBal=209.43, cycCredit=0.00, cycDebit=0.00`; other columns unchanged |
| AC-A2 | account with only zero-rate categories | row updated: `currBal` unchanged, cycle totals zeroed |
| AC-A3 | account with no `tran_cat_bal` rows | row untouched |
| AC-A4 | `replicateLastAccountSkip=false` (default) | last account finalised |
| AC-A5 | `replicateLastAccountSkip=true` | last account **not** updated; its transactions still written |
| AC-A6 | account id present in `tran_cat_bal` but absent in `account` | log `ACCOUNT NOT FOUND: <id>`, `FAILED`, chunk rolled back |
| AC-A7 | account with no `card_xref` row | `FAILED`, same message |
| AC-A8 | account with two cards | card chosen = lowest `card_num`; documented (R4) |
| AC-G1 | reader over `tcatbal.txt` | 50 `AccountInterestUnit`s each with 1 category; order 1..50 |
| AC-G2 | reader over rows `1/01/0001, 1/01/0002, 2/01/0001` | units `[1: 2 cats], [2: 1 cat]`; `isLast` true only on the second |
| AC-O1 | run over sample data with `systranFile` set | file has 50 × 350-byte records, amounts all `0000000000{`, ids `…000001`-`…000050`, cards match `cardxref.txt` for accounts 1-50 |
| AC-O2 | run producing no transactions (all `ZEROAPR`) | file exists and is empty; exit `0` |
| AC-X1 | failure injected in chunk N | chunks 1..N−1 committed; restart resumes with the correct next suffix; final output identical to uninterrupted run except timestamps |

### 8.3 End-to-end parity criterion (must pass before cut-over)

For both fixture sets (sample and crafted), run `intCalcJob` with `replicateLastAccountSkip=true`,
`tranIdPrefix=2022071800`, `systranFile` set, then:

1. The produced `SYSTRAN` file equals the golden file for every byte **except** columns 279-330
   (`ORIG-TS`, `PROC-TS`), which must match `\d{4}-\d{2}-\d{2}-\d{2}\.\d{2}\.\d{2}\.\d{2}0000`
   and be ≥ job start, and **except** columns 57-132 / 331-350 if the golden run shows non-space
   initial storage there (R3) — in which case the parity test asserts the golden bytes and the
   writer is configured to emit them.
2. The exported `account` file equals the golden post-run `ACCTDATA` byte-for-byte.
3. `tran_cat_bal`, `card_xref`, `disclosure_group` are unchanged.
4. SYSOUT normal-path lines (record echoes, fallback messages, start/end) match in content and
   order.
5. Exit code `0`.

Then run again with `replicateLastAccountSkip=false` and assert the only difference is the last
account's row (`curr_bal + totalInt`, cycle totals zero) — this is the recorded deviation.

### 8.4 Non-functional criteria

* Throughput ≥ 2,000 category rows/s with `chunkSize=50` accounts.
* Memory bounded (cursor reader, one account's categories in memory at a time).
* Restartable without manual data restore (§6.7).

---

## 9. Assumptions, Risks & Open Questions

### 9.1 Assumptions

| # | Assumption |
|---|---|
| A1 | The CBTRN02C table design (§4.9 there) is adopted; `disclosure_group` is added per §4.5. `DISCGRP` is loaded into `disclosure_group` by the weekly refresh job's replacement. |
| A2 | `tran_cat_bal` PK ordering `(acct_id, tran_type_cd, tran_cat_cd)` with `tran_type_cd` collating as `CHAR` reproduces the VSAM key order (EBCDIC and ASCII collate digits identically; codes are all digits). |
| A3 | The `PARM` value is a 10-character run identifier supplied by the scheduler (`YYYYMMDD00` today) and is passed to the Java job as `tranIdPrefix`. |
| A4 | `DIS-INT-RATE` is an annual percentage with two decimals; `/1200` semantics are intentional. |
| A5 | Timestamps use the LPAR-equivalent zone via an explicit clock property (CBTRN02C A5/Q2). |

### 9.2 Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | **Last-account defect (§5.6)**: fixing it changes account balances relative to the legacy system; keeping it perpetuates a ledger inconsistency. | Switchable flag; default is the fix; parity test runs both; product decision Q1. |
| R2 | **Silent truncation** into `WS-MONTHLY-INT`/`WS-TOTAL-INT` (`S9(9)V99`) for absurd inputs. | Java asserts `|value| < 10^9` and fails; documented deviation. |
| R3 | **Uninitialised bytes** in `TRAN-DESC` 57-132 and `FILLER` 331-350: `TRAN-RECORD` has no `VALUE` and is never `INITIALIZE`d; content depends on compiler/runtime storage initialisation (Enterprise COBOL without `WSCLEAR` leaves it undefined; in practice spaces or `LOW-VALUES`). Downstream `CBSTM03A`/`CBTRN03C` print `TRAN-DESC`, so `LOW-VALUES` would be visible. | Default to spaces; confirm from the golden `SYSTRAN` file and make the pad byte configurable (`intcalc.output.padByte`). |
| R4 | **Multiple cards per account**: the AIX is `NONUNIQUEKEY` and the COBOL `SELECT` does not declare `WITH DUPLICATES`. I believe (not verified against the compiler docs) that a random `READ` on a non-unique alternate key returns the first entry in the AIX pointer chain (insertion order) and may return status `'02'` when duplicates exist — which this program would treat as fatal. The sample data has exactly one card per account, so the behaviour is untested. | Java picks `ORDER BY card_num LIMIT 1` deterministically and logs when >1 card exists; confirm on the mainframe with a two-card fixture before cut-over (Q2). |
| R5 | **`TRAN-ID` uniqueness** relies on the prefix being unique per run and the 6-digit suffix; > 999,999 interest lines per run or a rerun with the same prefix create duplicates that only surface in `COMBTRAN`/the master load. | Java fails fast on both (identifying parameter + suffix guard). |
| R6 | **`ORIG-TS` format inconsistency** (DB2 style here vs ISO style from CBTRN02C) may break consumers that parse the time part. | Preserve for parity; raise with statement/report owners. |
| R7 | **Blank `ACCT-GROUP-ID` in the data (§4.3)** means the production behaviour is "everyone gets `DEFAULT` rates"; correcting the data would change interest amounts. | Reproduce faithfully; decision Q5. |
| R8 | **Zero-amount interest transactions** (`0.00`, §5.3) inflate the transaction master and statements. | Preserve for parity; consider a filter flag later (Q7). |
| R9 | **Cycle-total reset scope**: only accounts present in `TCATBALF` get `CURR-CYC-*` reset; others never do. | Preserve; document for the statement cycle owners (Q6). |
| R10 | **Error-message defects** (`DALY REJECTS FILE`, appended status) make log-scraping parity approximate. | Match normal-path messages exactly; error path best-effort. |

### 9.3 Open questions

| # | Question | Options / recommendation |
|---|---|---|
| Q1 | Approve fixing the last-account defect (§5.6) as the default behaviour? | Recommend yes, with `replicateLastAccountSkip` retained for parity only. |
| Q2 | Which card should be stamped on interest transactions for multi-card accounts? | Recommend lowest `card_num` (deterministic); alternatively the primary card if a flag exists in a future model. Verify COBOL behaviour on a two-card fixture. |
| Q3 | Should the Java job insert directly into `transaction` (making `COMBTRAN` redundant) or keep producing the `SYSTRAN` flat file for a `COMBTRAN` replacement? | Recommend direct insert **plus** optional flat file until all monthly downstream jobs (`COMBTRAN`, `CREASTMT`) are migrated. |
| Q4 | Is `PARM` always `YYYYMMDD00`? Should the Java job derive it from the business date instead of a free-text parameter? | Recommend accepting the 10-char parameter verbatim (parity) and validating format `\d{10}` as a warning only. |
| Q5 | Is the blank `ACCT-GROUP-ID` (§4.3) a data-generation bug to be corrected during migration, or is `DEFAULT`-for-all the intended production state? | Needs business decision; affects every interest amount. |
| Q6 | Should cycle totals be reset for accounts with no category balances? | Out of scope for like-for-like; log as enhancement. |
| Q7 | Suppress `0.00` interest transactions? | Out of scope for parity; enhancement flag. |
| Q8 | Should `1400-COMPUTE-FEES` be given requirements now (so the `FeeCalculator` hook has a real design) or remain a no-op? | Remain no-op; separate spec if fees are wanted. |
| Q9 | `disclosure_group.acct_group_id` — trimmed or blank-padded `CHAR(10)`? Must be consistent with how `account.group_id` is stored so the join/lookup matches. | Store both as `CHAR(10)`; compare with the engine's `CHAR` semantics or explicit `RTRIM` on both sides. |

---

## Appendix A — Disclosure group record quick reference (50 bytes)

```
 1-10   DIS-ACCT-GROUP-ID    X(10)      e.g. A000000000 | DEFAULT    | ZEROAPR
11-12   DIS-TRAN-TYPE-CD     X(02)
13-16   DIS-TRAN-CAT-CD      9(04)
17-22   DIS-INT-RATE         S9(04)V99  annual %, sign overpunch on byte 22 (00150{ = +15.00)
23-50   FILLER               X(28)
```

## Appendix B — Interest transaction quick reference

```
TRAN-ID        = PARM-DATE(10) ∥ suffix(6)        e.g. 2022071800000001
TRAN-TYPE-CD   = '01'
TRAN-CAT-CD    = 0005                             ("Interest Amount")
TRAN-SOURCE    = 'System'
TRAN-DESC      = 'Int. for a/c ' ∥ ACCT-ID(11)    (24 chars; rest = initial storage)
TRAN-AMT       = trunc2( TRAN-CAT-BAL × DIS-INT-RATE / 1200 )
TRAN-MERCHANT-* = 0 / spaces
TRAN-CARD-NUM  = card found by account-id alternate index
TRAN-ORIG-TS   = TRAN-PROC-TS = DB2 timestamp at write
```
