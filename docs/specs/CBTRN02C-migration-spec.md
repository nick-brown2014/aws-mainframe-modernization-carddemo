# CBTRN02C Migration Specification: COBOL Batch to Java + Spring Batch

| | |
|---|---|
| Source program | `app/cbl/CBTRN02C.cbl` (CardDemo v2.0-25-gdb72e6b-235) |
| Source JCL | `app/jcl/POSTTRAN.jcl`, step `STEP15 EXEC PGM=CBTRN02C` |
| Copybooks | `app/cpy/CVTRA06Y.cpy`, `CVACT03Y.cpy`, `CVACT01Y.cpy`, `CVTRA01Y.cpy`, `CVTRA05Y.cpy` |
| Target | Java 17+, Spring Boot 3.x, Spring Batch 5.x, JDBC/JPA repository over an RDBMS |
| Status | Draft for review |

This document is derived directly from the COBOL source and copybooks listed above. It is
intended to be sufficient for an implementation team to build and verify the Java replacement
without re-reading the COBOL. Line references (`L123`) point into `CBTRN02C.cbl`.

---

## 1. Overview & Purpose

`CBTRN02C` is the CardDemo **daily transaction posting** batch program, run by job `POSTTRAN`.
For every record in the sequential daily transaction file (`DALYTRAN`) it:

1. Validates the transaction against the card cross-reference file (`XREFFILE`) and the account
   master (`ACCTFILE`).
2. If valid, **posts** it:
   * adds the amount to the account/type/category balance record in `TCATBALF`
     (creating the record if absent),
   * updates the account's current balance and current-cycle credit or debit total in `ACCTFILE`,
   * writes a 350-byte transaction master record (with a fresh DB2-format processing timestamp)
     to `TRANFILE`.
3. If invalid, writes the original 350-byte record plus an 80-byte validation trailer
   (reason code + description) to the rejects file `DALYREJS`.

At end of job it prints processed/rejected counts and sets `RETURN-CODE 4` if any record was
rejected. Any I/O error other than "not found"/EOF abends the program (user abend 999).

The Java replacement must produce **byte-for-byte equivalent** posted transactions, balance
updates and reject records for the same input and starting state, and must expose the same
success/warning/failure exit semantics to the scheduler.

---

## 2. Current-State (COBOL) Analysis

### 2.1 Control flow

```
PROCEDURE DIVISION                                   (L193-234)
  DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN02C'
  PERFORM 0000-DALYTRAN-OPEN   (OPEN INPUT  DALYTRAN)
  PERFORM 0100-TRANFILE-OPEN   (OPEN OUTPUT TRANFILE)    <-- note: OUTPUT, not I-O
  PERFORM 0200-XREFFILE-OPEN   (OPEN INPUT  XREFFILE)
  PERFORM 0300-DALYREJS-OPEN   (OPEN OUTPUT DALYREJS)
  PERFORM 0400-ACCTFILE-OPEN   (OPEN I-O    ACCTFILE)
  PERFORM 0500-TCATBALF-OPEN   (OPEN I-O    TCATBALF)

  PERFORM UNTIL END-OF-FILE = 'Y'
      PERFORM 1000-DALYTRAN-GET-NEXT
      IF END-OF-FILE = 'N'
          ADD 1 TO WS-TRANSACTION-COUNT
          MOVE 0      TO WS-VALIDATION-FAIL-REASON
          MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC
          PERFORM 1500-VALIDATE-TRAN
          IF WS-VALIDATION-FAIL-REASON = 0
              PERFORM 2000-POST-TRANSACTION
          ELSE
              ADD 1 TO WS-REJECT-COUNT
              PERFORM 2500-WRITE-REJECT-REC
          END-IF
      END-IF
  END-PERFORM

  PERFORM 9000..9500 CLOSE all six files
  DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT
  DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT
  IF WS-REJECT-COUNT > 0  MOVE 4 TO RETURN-CODE
  DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN02C'
  GOBACK
```

Processing is strictly **one record at a time, in input order, with no commit boundaries**
(VSAM updates are applied immediately as each record is posted).

### 2.2 Paragraph reference

| Paragraph | Lines | Responsibility | Java equivalent (see §6) |
|---|---|---|---|
| `0000`-`0500-*-OPEN` | L236-343 | Open each file; on any non-`'00'` status display an error, print file status via `9910`, abend via `9999`. | Reader/writer `open()`, datasource health check; failure = job `FAILED`. |
| `1000-DALYTRAN-GET-NEXT` | L345-369 | `READ DALYTRAN-FILE INTO DALYTRAN-RECORD`. Status `'00'` = record; `'10'` = EOF (`END-OF-FILE='Y'`); anything else = abend. | `FlatFileItemReader.read()` returning `null` at EOF. |
| `1500-VALIDATE-TRAN` | L370-378 | Runs `1500-A-LOOKUP-XREF`; only if that passed runs `1500-B-LOOKUP-ACCT`. Comment `* ADD MORE VALIDATIONS HERE` marks the extension point. | `TransactionValidator` in the `ItemProcessor`. |
| `1500-A-LOOKUP-XREF` | L380-392 | Random `READ XREF-FILE` by `DALYTRAN-CARD-NUM`. `INVALID KEY` → reason **100**. | `CardXrefRepository.findByCardNum`. |
| `1500-B-LOOKUP-ACCT` | L393-422 | Random `READ ACCOUNT-FILE` by `XREF-ACCT-ID`. `INVALID KEY` → reason **101**. Otherwise evaluate over-limit (**102**) then expiration (**103**); both are evaluated, later one overwrites the earlier reason (see §5.2). | `AccountRepository.findById` + rule evaluation. |
| `2000-POST-TRANSACTION` | L424-444 | Copy the 12 business fields from `DALYTRAN-RECORD` to `TRAN-RECORD`, set `TRAN-PROC-TS` from `Z-GET-DB2-FORMAT-TIMESTAMP`, then perform `2700`, `2800`, `2900` in that order. | `PostingService.post()` producing a `PostingResult`. |
| `2700-UPDATE-TCATBAL` | L467-501 | Build key `(XREF-ACCT-ID, DALYTRAN-TYPE-CD, DALYTRAN-CAT-CD)`; random `READ TCATBAL-FILE`. Status `'00'` → update path; `'23'` (not found) → create path (a `DISPLAY 'TCATBAL record not found for key : ' ... '.. Creating.'` is emitted); any other status → abend. | `TranCategoryBalanceRepository` upsert. |
| `2700-A-CREATE-TCATBAL-REC` | L503-524 | `INITIALIZE` record, set key, `ADD DALYTRAN-AMT TO TRAN-CAT-BAL` (i.e. balance = amount), `WRITE`. Non-`'00'` → abend. | insert. |
| `2700-B-UPDATE-TCATBAL-REC` | L526-542 | `ADD DALYTRAN-AMT TO TRAN-CAT-BAL`, `REWRITE`. Non-`'00'` → abend. | update. |
| `2800-UPDATE-ACCOUNT-REC` | L545-560 | `ADD DALYTRAN-AMT TO ACCT-CURR-BAL`; if amount `>= 0` add to `ACCT-CURR-CYC-CREDIT` else add to `ACCT-CURR-CYC-DEBIT`; `REWRITE`. `INVALID KEY` sets reason **109** / `'ACCOUNT RECORD NOT FOUND'` **but processing continues** (see §5.4 and §9). | `AccountRepository.save`. |
| `2900-WRITE-TRANSACTION-FILE` | L562-579 | `WRITE FD-TRANFILE-REC FROM TRAN-RECORD`. Non-`'00'` → abend (this includes `'22'` duplicate key). | `TransactionRepository.insert`. |
| `2500-WRITE-REJECT-REC` | L446-465 | Build 430-byte reject record = original `DALYTRAN-RECORD` (350) + `WS-VALIDATION-TRAILER` (80); `WRITE`. Non-`'00'` → abend. | `FlatFileItemWriter` for rejects. |
| `9000`-`9500-*-CLOSE` | L582-690 | Close each file; non-`'00'` → abend. (Note: `9300-DALYREJS-CLOSE` displays `XREFFILE-STATUS` instead of `DALYREJS-STATUS` on error – a source defect, not to be replicated.) | Resource cleanup. |
| `Z-GET-DB2-FORMAT-TIMESTAMP` | L692-705 | `FUNCTION CURRENT-DATE` → `YYYY-MM-DD-HH.MM.SS.hh0000` (26 chars; centisecond precision zero-padded to 6 fraction digits). | `Db2TimestampFormatter.now()`. |
| `9910-DISPLAY-IO-STATUS` | L714-727 | Formats the 2-byte file status as `NNNN` (`'0000'`+status, or for `'9x'`/non-numeric statuses the binary value of the second byte) and displays `FILE STATUS IS: NNNN`. | Log message in exception handler. |
| `9999-ABEND-PROGRAM` | L707-711 | `DISPLAY 'ABENDING PROGRAM'`, `CALL 'CEE3ABD' USING 999, 0` → user abend U0999. | Throw fatal exception → job `FAILED`, non-zero exit code. |

### 2.3 File SELECT / FILE STATUS handling

Every file has its own two-byte `FILE STATUS` field (`DALYTRAN-STATUS`, `TRANFILE-STATUS`,
`XREFFILE-STATUS`, `DALYREJS-STATUS`, `ACCTFILE-STATUS`, `TCATBALF-STATUS`). The program
follows a uniform pattern: set `APPL-RESULT` to 8, perform the I/O, map status to
`APPL-RESULT` (`0` ok, `16` EOF, `12` error), then branch on the `88`-levels `APPL-AOK` /
`APPL-EOF`. Statuses interpreted as *non-fatal*:

| File | Operation | Status treated as normal |
|---|---|---|
| `DALYTRAN` | `READ` | `'10'` = end of file |
| `XREFFILE` | `READ` | `INVALID KEY` (`'23'`) = card not found → reject 100 |
| `ACCTFILE` | `READ` | `INVALID KEY` (`'23'`) = account not found → reject 101 |
| `ACCTFILE` | `REWRITE` | `INVALID KEY` → sets reason 109 (not fatal, see §5.4) |
| `TCATBALF` | `READ` | `'23'` = not found → create path |

All other non-`'00'` statuses on any operation (open, read, write, rewrite, close) cause an
immediate abend after printing `ERROR <verb>ING <file description>` and `FILE STATUS IS: NNNN`.

---

## 3. Files / Datasets

DD-to-dataset wiring is from `POSTTRAN.jcl` (L23-42).

| DD name | Dataset (JCL) | COBOL file name | Organization | Access | Open mode | Record key (FD) | Copybook (WS layout) | LRECL |
|---|---|---|---|---|---|---|---|---|
| `DALYTRAN` | `AWS.M2.CARDDEMO.DALYTRAN.PS` | `DALYTRAN-FILE` | Sequential (PS) | Sequential | `INPUT` | – | `CVTRA06Y` (`DALYTRAN-RECORD`) | 350 |
| `XREFFILE` | `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` | `XREF-FILE` | Indexed (KSDS) | Random | `INPUT` | `FD-XREF-CARD-NUM` `X(16)` @1 | `CVACT03Y` (`CARD-XREF-RECORD`) | 50 |
| `ACCTFILE` | `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` | `ACCOUNT-FILE` | Indexed (KSDS) | Random | `I-O` | `FD-ACCT-ID` `9(11)` @1 | `CVACT01Y` (`ACCOUNT-RECORD`) | 300 |
| `TCATBALF` | `AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS` | `TCATBAL-FILE` | Indexed (KSDS) | Random | `I-O` | `FD-TRAN-CAT-KEY` = `9(11)`+`X(2)`+`9(4)` (17 bytes) @1 | `CVTRA01Y` (`TRAN-CAT-BAL-RECORD`) | 50 |
| `TRANFILE` | `AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS` | `TRANSACT-FILE` | Indexed (KSDS) | Random | `OUTPUT` | `FD-TRANS-ID` `X(16)` @1 | `CVTRA05Y` (`TRAN-RECORD`) | 350 |
| `DALYREJS` | `AWS.M2.CARDDEMO.DALYREJS(+1)` (new GDG generation, `RECFM=F,LRECL=430`) | `DALYREJS-FILE` | Sequential (PS) | Sequential | `OUTPUT` | – | `REJECT-RECORD` (WS, L176) | 430 |

Notes:

* `TRANFILE` is opened `OUTPUT`, not `I-O`. On a VSAM KSDS this means the program expects to
  (re)load the file rather than append to it; if the cluster is defined `REUSE` the existing
  contents are discarded at open, and if not `REUSE` the open fails on a non-empty file. The
  surrounding job stream (`TRANBKP` / `TRANFILE` IDCAMS refresh jobs in the README) refreshes the
  transaction master before `POSTTRAN`. See §9 for the decision this forces on the RDBMS design.
* `DALYREJS` is a new generation each run; the file exists (possibly empty) even when there are
  no rejects.
* Sample ASCII data for all files exists under `app/data/ASCII/` (`dailytran.txt` = 300
  records × 350 bytes, `cardxref.txt`, `acctdata.txt`, `tcatbal.txt`) and EBCDIC equivalents
  under `app/data/EBCDIC/`. These are the primary fixtures for §8.

---

## 4. Data Model & Field Mappings

### 4.1 Type-mapping conventions

| COBOL picture | Storage | Java type | Notes |
|---|---|---|---|
| `PIC X(n)` | n bytes, space-padded | `String` (fixed width n) | Preserve trailing spaces when re-serialising to fixed-width output; trim only for comparisons where the COBOL compares whole fields (COBOL alphanumeric comparison pads the shorter operand with spaces, so `"AB"` equals `"AB  "`). |
| `PIC 9(n)` (unsigned, display) | n bytes zoned, zero-padded | `long` / `Long` (n ≤ 18) or `String` when used purely as an identifier | Identifiers (`ACCT-ID`, `CUST-ID`, `MERCHANT-ID`, `CAT-CD`) are recommended as `long` in the domain with zero-padded fixed-width formatting on output. |
| `PIC S9(n)V99` (signed, display, implied decimal) | n+2 bytes zoned; sign is an **overpunch on the last digit** | **`java.math.BigDecimal` with scale 2** | Never `double`/`float`. Arithmetic must use `RoundingMode.UNNECESSARY`; all operations in this program are add/subtract so no rounding ever occurs. See §4.7 for sign-overpunch decoding. |
| `PIC X(26)` timestamps | 26 chars | `String` (26) in the record model; convert to `java.time` only where compared | Two different textual formats are present (see §4.8); keep the raw string to guarantee round-trip fidelity. |
| `PIC X(10)` dates | `YYYY-MM-DD` | `String` (10), optionally `LocalDate` for validation | The program compares dates as strings (§5.2 rule 103). |

### 4.2 `CVTRA06Y` — Daily transaction record (`DALYTRAN-RECORD`, 350 bytes, input)

| # | COBOL field | PIC | Offset (1-based) | Len | Java field | Java type |
|---|---|---|---|---|---|---|
| 1 | `DALYTRAN-ID` | `X(16)` | 1 | 16 | `tranId` | `String` |
| 2 | `DALYTRAN-TYPE-CD` | `X(02)` | 17 | 2 | `tranTypeCd` | `String` |
| 3 | `DALYTRAN-CAT-CD` | `9(04)` | 19 | 4 | `tranCatCd` | `int` (format `%04d`) |
| 4 | `DALYTRAN-SOURCE` | `X(10)` | 23 | 10 | `tranSource` | `String` |
| 5 | `DALYTRAN-DESC` | `X(100)` | 33 | 100 | `tranDesc` | `String` |
| 6 | `DALYTRAN-AMT` | `S9(09)V99` | 133 | 11 | `tranAmt` | `BigDecimal(scale 2)` |
| 7 | `DALYTRAN-MERCHANT-ID` | `9(09)` | 144 | 9 | `merchantId` | `long` (format `%09d`) |
| 8 | `DALYTRAN-MERCHANT-NAME` | `X(50)` | 153 | 50 | `merchantName` | `String` |
| 9 | `DALYTRAN-MERCHANT-CITY` | `X(50)` | 203 | 50 | `merchantCity` | `String` |
| 10 | `DALYTRAN-MERCHANT-ZIP` | `X(10)` | 253 | 10 | `merchantZip` | `String` |
| 11 | `DALYTRAN-CARD-NUM` | `X(16)` | 263 | 16 | `cardNum` | `String` |
| 12 | `DALYTRAN-ORIG-TS` | `X(26)` | 279 | 26 | `origTs` | `String` |
| 13 | `DALYTRAN-PROC-TS` | `X(26)` | 305 | 26 | `procTs` | `String` (input value is ignored by the program; carried into reject records verbatim) |
| 14 | `FILLER` | `X(20)` | 331 | 20 | `filler` | `String` (retain for reject-record fidelity) |

The FD (`FD-TRAN-RECORD`, L67-69) only splits the record into `FD-TRAN-ID X(16)` +
`FD-CUST-DATA X(334)`; the copybook layout above is what the program actually uses.

### 4.3 `CVACT03Y` — Card cross-reference (`CARD-XREF-RECORD`, 50 bytes, KSDS key = card number)

| COBOL field | PIC | Offset | Len | Java field | Java type |
|---|---|---|---|---|---|
| `XREF-CARD-NUM` | `X(16)` | 1 | 16 | `cardNum` (PK) | `String` |
| `XREF-CUST-ID` | `9(09)` | 17 | 9 | `custId` | `long` |
| `XREF-ACCT-ID` | `9(11)` | 26 | 11 | `acctId` | `long` |
| `FILLER` | `X(14)` | 37 | 14 | – | not mapped |

### 4.4 `CVACT01Y` — Account master (`ACCOUNT-RECORD`, 300 bytes, KSDS key = account id)

| COBOL field | PIC | Offset | Len | Java field | Java type |
|---|---|---|---|---|---|
| `ACCT-ID` | `9(11)` | 1 | 11 | `acctId` (PK) | `long` |
| `ACCT-ACTIVE-STATUS` | `X(01)` | 12 | 1 | `activeStatus` | `String`/`char` (not used by CBTRN02C) |
| `ACCT-CURR-BAL` | `S9(10)V99` | 13 | 12 | `currBal` | `BigDecimal(2)` |
| `ACCT-CREDIT-LIMIT` | `S9(10)V99` | 25 | 12 | `creditLimit` | `BigDecimal(2)` |
| `ACCT-CASH-CREDIT-LIMIT` | `S9(10)V99` | 37 | 12 | `cashCreditLimit` | `BigDecimal(2)` (not used) |
| `ACCT-OPEN-DATE` | `X(10)` | 49 | 10 | `openDate` | `String` (not used) |
| `ACCT-EXPIRAION-DATE` *(sic)* | `X(10)` | 59 | 10 | `expirationDate` | `String` `YYYY-MM-DD` |
| `ACCT-REISSUE-DATE` | `X(10)` | 69 | 10 | `reissueDate` | `String` (not used) |
| `ACCT-CURR-CYC-CREDIT` | `S9(10)V99` | 79 | 12 | `currCycCredit` | `BigDecimal(2)` |
| `ACCT-CURR-CYC-DEBIT` | `S9(10)V99` | 91 | 12 | `currCycDebit` | `BigDecimal(2)` |
| `ACCT-ADDR-ZIP` | `X(10)` | 103 | 10 | `addrZip` | `String` (not used) |
| `ACCT-GROUP-ID` | `X(10)` | 113 | 10 | `groupId` | `String` (not used) |
| `FILLER` | `X(178)` | 123 | 178 | – | not mapped |

Fields marked "not used" are read and rewritten unchanged by the COBOL `REWRITE`; the Java
implementation must update **only** `currBal`, `currCycCredit`, `currCycDebit` and leave every
other column untouched.

### 4.5 `CVTRA01Y` — Transaction category balance (`TRAN-CAT-BAL-RECORD`, 50 bytes, KSDS key = 17-byte composite)

| COBOL field | PIC | Offset | Len | Java field | Java type |
|---|---|---|---|---|---|
| `TRANCAT-ACCT-ID` | `9(11)` | 1 | 11 | `acctId` (PK part 1) | `long` |
| `TRANCAT-TYPE-CD` | `X(02)` | 12 | 2 | `tranTypeCd` (PK part 2) | `String` |
| `TRANCAT-CD` | `9(04)` | 14 | 4 | `tranCatCd` (PK part 3) | `int` |
| `TRAN-CAT-BAL` | `S9(09)V99` | 18 | 11 | `tranCatBal` | `BigDecimal(2)` |
| `FILLER` | `X(22)` | 29 | 22 | – | not mapped |

Note `TRAN-CAT-BAL` is `S9(09)V99` (max ±999,999,999.99) whereas account amounts are
`S9(10)V99`. The Java column must be at least `DECIMAL(11,2)`; overflow beyond 9 integer digits
would silently truncate in COBOL (no `ON SIZE ERROR`) — the Java implementation should instead
fail loudly (see §9).

### 4.6 `CVTRA05Y` — Transaction master (`TRAN-RECORD`, 350 bytes, KSDS key = transaction id)

Layout is field-for-field identical to `CVTRA06Y` with the `TRAN-` prefix instead of
`DALYTRAN-`: `TRAN-ID X(16)` (PK), `TRAN-TYPE-CD X(02)`, `TRAN-CAT-CD 9(04)`, `TRAN-SOURCE X(10)`,
`TRAN-DESC X(100)`, `TRAN-AMT S9(09)V99`, `TRAN-MERCHANT-ID 9(09)`, `TRAN-MERCHANT-NAME X(50)`,
`TRAN-MERCHANT-CITY X(50)`, `TRAN-MERCHANT-ZIP X(10)`, `TRAN-CARD-NUM X(16)`,
`TRAN-ORIG-TS X(26)`, `TRAN-PROC-TS X(26)`, `FILLER X(20)`. Offsets are those in §4.2.

`2000-POST-TRANSACTION` copies fields 1-12 verbatim from the daily record and sets field 13
(`TRAN-PROC-TS`) to the generated DB2 timestamp. The `FILLER` is **not** moved (it retains
whatever `TRAN-RECORD` working storage held — spaces/low-values from program start, since no
`INITIALIZE` is done — treat as 20 spaces).

### 4.7 Zoned-decimal sign overpunch (`S9(n)V99` fields)

Signed display numerics carry the sign in the zone nibble of the **last** byte. The reader must
decode, and the writers must encode, both encodings:

| Sign | Last-digit values 0-9 (EBCDIC) | Last-digit values 0-9 (ASCII sample data in `app/data/ASCII`) |
|---|---|---|
| Positive | `{ A B C D E F G H I` (`x'C0'-x'C9'`) | `{ A B C D E F G H I` |
| Negative | `} J K L M N O P Q R` (`x'D0'-x'D9'`) | `} J K L M N O P Q R` |
| Unsigned/plain digit | `0`-`9` (`x'F0'-x'F9'`) treated as positive | `0`-`9` |

Example from `dailytran.txt`: `0000005047G` → digits `00000050477`, sign `+`, value
`+504.77`. `0000001234}` → `-12340` → `-123.40`. All 300 sample records use overpunched signs.

### 4.8 Timestamp formats

| Field | Format observed | Example |
|---|---|---|
| `DALYTRAN-ORIG-TS` / `TRAN-ORIG-TS` | ISO-like, `YYYY-MM-DD HH:MM:SS.ffffff` (space, colons) | `2022-06-10 19:27:53.000000` |
| `TRAN-PROC-TS` (generated) | DB2, `YYYY-MM-DD-HH.MM.SS.hh0000` (dashes, dots; centiseconds + `0000`) | `2026-09-23-18.15.42.370000` |

Only characters 1-10 (`YYYY-MM-DD`) of `ORIG-TS` are ever interpreted (rule 103). Both are 26
bytes and must be stored as `CHAR(26)` to round-trip.

### 4.9 Proposed relational design (VSAM → RDBMS)

One table per KSDS, key columns mirror the VSAM key; column widths mirror the copybook.

```sql
CREATE TABLE card_xref (
  card_num   CHAR(16)      NOT NULL PRIMARY KEY,
  cust_id    NUMERIC(9)    NOT NULL,
  acct_id    NUMERIC(11)   NOT NULL
);
CREATE INDEX ix_card_xref_acct ON card_xref(acct_id);

CREATE TABLE account (
  acct_id            NUMERIC(11)   NOT NULL PRIMARY KEY,
  active_status      CHAR(1)       NOT NULL,
  curr_bal           DECIMAL(12,2) NOT NULL,
  credit_limit       DECIMAL(12,2) NOT NULL,
  cash_credit_limit  DECIMAL(12,2) NOT NULL,
  open_date          CHAR(10)      NOT NULL,
  expiration_date    CHAR(10)      NOT NULL,
  reissue_date       CHAR(10)      NOT NULL,
  curr_cyc_credit    DECIMAL(12,2) NOT NULL,
  curr_cyc_debit     DECIMAL(12,2) NOT NULL,
  addr_zip           CHAR(10)      NOT NULL,
  group_id           CHAR(10)      NOT NULL
);

CREATE TABLE tran_cat_bal (
  acct_id      NUMERIC(11)   NOT NULL,
  tran_type_cd CHAR(2)       NOT NULL,
  tran_cat_cd  NUMERIC(4)    NOT NULL,
  tran_cat_bal DECIMAL(11,2) NOT NULL,
  PRIMARY KEY (acct_id, tran_type_cd, tran_cat_cd)
);

CREATE TABLE transaction (
  tran_id        CHAR(16)      NOT NULL PRIMARY KEY,
  tran_type_cd   CHAR(2)       NOT NULL,
  tran_cat_cd    NUMERIC(4)    NOT NULL,
  tran_source    CHAR(10)      NOT NULL,
  tran_desc      CHAR(100)     NOT NULL,
  tran_amt       DECIMAL(11,2) NOT NULL,
  merchant_id    NUMERIC(9)    NOT NULL,
  merchant_name  CHAR(50)      NOT NULL,
  merchant_city  CHAR(50)      NOT NULL,
  merchant_zip   CHAR(10)      NOT NULL,
  card_num       CHAR(16)      NOT NULL,
  orig_ts        CHAR(26)      NOT NULL,
  proc_ts        CHAR(26)      NOT NULL
);
CREATE INDEX ix_transaction_card ON transaction(card_num);
```

Design notes:

* `CHAR` (not `VARCHAR`) is deliberate so that fixed-width padding survives round-trips and the
  legacy extracts/reports (`CBTRN03C`, `CBSTM03A`) can be regenerated identically. If the target
  RDBMS strips trailing blanks on `CHAR` (e.g. PostgreSQL semantics), the fixed-width writers must
  right-pad on output.
* `FILLER` bytes are not persisted; they are regenerated as spaces when producing fixed-width
  files.
* The `TRANFILE` `OPEN OUTPUT` semantics (§3) suggest the `transaction` table is truncated/reloaded
  by an upstream step. The Java job should **not** truncate; the job stream (or an explicit
  preceding tasklet controlled by a job parameter) owns that decision. See §9.
* Numeric identifiers are `NUMERIC(n)` to allow range enforcement; a `CHAR(n)` alternative is
  acceptable if the team prefers zero-padded string identity everywhere.

---

## 5. Business Rules / Validation

### 5.1 Validation rules (in evaluation order)

`WS-VALIDATION-FAIL-REASON` is `PIC 9(04)` (rendered as 4 digits, e.g. `0100`) and
`WS-VALIDATION-FAIL-REASON-DESC` is `PIC X(76)` (left-justified, space padded).

| Rule | Reason code | Description text (exact) | Condition (COBOL) | Condition (Java) | Where |
|---|---|---|---|---|---|
| V1 | `0100` | `INVALID CARD NUMBER FOUND` | `READ XREF-FILE` with key `DALYTRAN-CARD-NUM` returns `INVALID KEY` | `cardXrefRepo.findByCardNum(tran.cardNum).isEmpty()` | `1500-A-LOOKUP-XREF` L380 |
| V2 | `0101` | `ACCOUNT RECORD NOT FOUND` | `READ ACCOUNT-FILE` with key `XREF-ACCT-ID` returns `INVALID KEY` (only evaluated when V1 passed) | `accountRepo.findById(xref.acctId).isEmpty()` | `1500-B-LOOKUP-ACCT` L393 |
| V3 | `0102` | `OVERLIMIT TRANSACTION` | `WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT`; fail when `ACCT-CREDIT-LIMIT < WS-TEMP-BAL` (pass when `ACCT-CREDIT-LIMIT >= WS-TEMP-BAL`) | `acct.currCycCredit.subtract(acct.currCycDebit).add(tran.tranAmt).compareTo(acct.creditLimit) > 0` | L403-413 |
| V4 | `0103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | fail when `ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)` (pass when `>=`); a **character** comparison of two 10-byte `YYYY-MM-DD` strings | `acct.expirationDate.compareTo(tran.origTs.substring(0,10)) < 0` | L414-420 |

Notes on V3:

* `WS-TEMP-BAL` is `PIC S9(09)V99` while the operands are `S9(10)V99`. COBOL `COMPUTE` without
  `ON SIZE ERROR` truncates high-order digits if the result exceeds ±999,999,999.99. The Java
  implementation must compute with full `BigDecimal` precision; a difference would only arise
  for balances ≥ 1 billion, which are outside the sample data. Flag as an accepted deviation (§9).
* The comparison uses cycle credit/debit, **not** `ACCT-CURR-BAL`. This is the legacy rule and
  must be preserved even though it looks unusual (debit is accumulated as negative amounts, see
  §5.3, so `credit - debit` is effectively `credit + |debit|`).
* `ACCT-ACTIVE-STATUS` is *not* checked by this program.

### 5.2 Rule precedence / short-circuiting

* V1 failing → V2-V4 are **not** evaluated; reason = 100.
* V2 failing → V3, V4 are not evaluated (they are inside `NOT INVALID KEY`); reason = 101.
* V3 and V4 are **both** evaluated, each unconditionally overwriting the reason. Therefore if a
  transaction is both over-limit and after expiration, the reject carries **103**, not 102.
  Java must reproduce this "last failing rule wins" behaviour (evaluate V3, then V4, assign
  sequentially) rather than stopping at the first failure.
* Exactly one reject record is written per rejected transaction regardless of how many rules
  fail.

### 5.3 Posting math (only executed when reason = 0)

Let `amt = DALYTRAN-AMT` (signed, scale 2).

**Category balance (`2700-UPDATE-TCATBAL`)** — key `(XREF-ACCT-ID, DALYTRAN-TYPE-CD, DALYTRAN-CAT-CD)`:

```
if record exists:   TRAN-CAT-BAL := TRAN-CAT-BAL + amt          (REWRITE)
else:               TRAN-CAT-BAL := 0 + amt  (= amt), FILLER := spaces   (WRITE)
```

Note the account id comes from the **cross-reference** (`XREF-ACCT-ID`), never from the daily
record, and the type/category codes come from the daily record.

**Account (`2800-UPDATE-ACCOUNT-REC`)**:

```
ACCT-CURR-BAL := ACCT-CURR-BAL + amt
if amt >= 0:  ACCT-CURR-CYC-CREDIT := ACCT-CURR-CYC-CREDIT + amt
else:         ACCT-CURR-CYC-DEBIT  := ACCT-CURR-CYC-DEBIT  + amt     (amt is negative; debit total decreases)
REWRITE ACCOUNT-RECORD
```

`amt = 0.00` goes to the credit bucket. The debit total is therefore a *negative* running sum
(or unchanged if all amounts are positive). Do not "fix" this by adding `|amt|`.

**Transaction master (`2900-WRITE-TRANSACTION-FILE`)** — insert `TRAN-RECORD` as described in
§4.6 with `TRAN-PROC-TS` = current DB2-format timestamp. A duplicate `TRAN-ID` yields file status
`'22'` which the COBOL treats as fatal (abend).

**Order of side effects**: category balance → account → transaction master. Because VSAM has no
transaction, an abend mid-sequence leaves partially applied state; the Java design (§6) wraps
the three writes in one database transaction per chunk so this cannot happen.

### 5.4 Reason code 109 (latent defect to be handled explicitly)

`2800-UPDATE-ACCOUNT-REC` sets reason `109` / `ACCOUNT RECORD NOT FOUND` if the `REWRITE` hits
`INVALID KEY`. In practice this cannot occur (the record was just read by key), and even if it did
the COBOL would still write the transaction master record and would **not** count or write a
reject (the reject branch was already decided). Java should treat a failed account update as a
fatal error (job fails, chunk rolls back) rather than emulate this path. Reason code 109 is
reserved and documented so that downstream consumers of the reject file are not surprised if
another program emits it.

### 5.5 Derived / generated values

| Value | Rule |
|---|---|
| `TRAN-PROC-TS` | `Z-GET-DB2-FORMAT-TIMESTAMP`: local system time at posting, formatted `YYYY-MM-DD-HH.MM.SS.hh0000`. Generated **per transaction** (called inside `2000-POST-TRANSACTION`), so consecutive records may carry different timestamps. |
| `WS-TRANSACTION-COUNT` | Incremented for every record successfully read (posted + rejected). `PIC 9(09)`. |
| `WS-REJECT-COUNT` | Incremented once per rejected record. `PIC 9(09)`. |
| `RETURN-CODE` | `4` if `WS-REJECT-COUNT > 0`, else `0`. Abend → U0999. |

---

## 6. Target Spring Batch Design

### 6.1 Module and package layout (proposed)

```
carddemo-batch-posttran/
  src/main/java/com/carddemo/batch/posttran/
    PostTranJobConfig.java          // Job + Step beans
    model/DailyTransaction.java      // CVTRA06Y
    model/CardXref.java              // CVACT03Y
    model/Account.java               // CVACT01Y
    model/TranCategoryBalance.java   // CVTRA01Y
    model/Transaction.java           // CVTRA05Y
    model/RejectRecord.java          // 350 + 80 byte reject
    model/PostingResult.java         // sealed: Posted | Rejected
    io/DailyTransactionFieldSetMapper.java
    io/ZonedDecimal.java             // sign-overpunch codec (§4.7)
    io/Db2TimestampFormatter.java    // §4.8
    io/RejectRecordLineAggregator.java
    validation/TransactionValidator.java
    validation/ValidationFailure.java // (code, description)
    posting/PostingService.java
    posting/PostTranItemProcessor.java
    posting/PostingResultWriter.java  // ClassifierCompositeItemWriter
    repo/CardXrefRepository.java
    repo/AccountRepository.java
    repo/TranCategoryBalanceRepository.java
    repo/TransactionRepository.java
    listener/PostTranJobListener.java // counts, exit code
```

### 6.2 Job structure

```
Job: postTranJob
  parameters: dailyTranFile (required, file path/URI)
              rejectsFile   (required, file path/URI)
              runDate       (optional, identifying; defaults to today)
              chunkSize     (optional, default 100)
  Step 1: postTransactionsStep   (chunk-oriented)
      reader    : dailyTransactionReader   (FlatFileItemReader<DailyTransaction>)
      processor : postTranItemProcessor    (ItemProcessor<DailyTransaction, PostingResult>)
      writer    : postingResultWriter      (ClassifierCompositeItemWriter<PostingResult>)
      listeners : PostTranStepListener (counts), PostTranJobListener (exit code)
      fault tolerance: NONE by default (see §6.7)
```

A single step mirrors the single COBOL loop. No partitioning: the program's semantics depend on
strict input order for records touching the same account/category (balance arithmetic is
commutative, but duplicate-key detection and reject ordering are not), and the volumes
(hundreds to low millions of records/day) do not require parallelism.

### 6.3 Reader — `FlatFileItemReader<DailyTransaction>`

* Resource: `dailyTranFile` job parameter.
* Encoding: configurable (`posttran.input.charset`); default `UTF-8`/`US-ASCII` for the
  ASCII fixtures; `IBM037`/`IBM1047` for EBCDIC transfers (see §9). Records are fixed length
  350 bytes with **no** record delimiter in the mainframe form; when the file has been
  transferred as text with line terminators, use a `DefaultRecordSeparatorPolicy` line reader.
  For true `RECFM=F` byte streams use a custom `BufferedReaderFactory` that yields 350-byte
  slices.
* Tokenizer: `FixedLengthTokenizer` with the 14 ranges in §4.2 (`1-16, 17-18, 19-22, 23-32,
  33-132, 133-143, 144-152, 153-202, 203-252, 253-262, 263-278, 279-304, 305-330, 331-350`);
  `strict=true` (a short record is an error, matching a COBOL read error → abend).
* `FieldSetMapper`: `DailyTransactionFieldSetMapper` — raw 350-byte line is also retained on the
  item (`rawRecord`) so reject records can echo the input verbatim.
* Amount parsing via `ZonedDecimal.parse(String, scale=2)`; numeric identifiers via `Long.parseLong`
  after validating all-digits (non-numeric → treat as read error, i.e. job fails, consistent with
  COBOL behaviour for a corrupt file — the COBOL would either abend on a `9(n)` compare or
  silently misbehave; failing fast is the chosen equivalent, see §9).
* EOF → `null` → step completes.

### 6.4 Processor — `PostTranItemProcessor`

```java
public PostingResult process(DailyTransaction tran) {
    Optional<CardXref> xref = cardXrefRepo.findByCardNum(tran.cardNum());
    if (xref.isEmpty()) return Rejected.of(tran, 100, "INVALID CARD NUMBER FOUND");

    Optional<Account> acct = accountRepo.findByIdForUpdate(xref.get().acctId());
    if (acct.isEmpty())  return Rejected.of(tran, 101, "ACCOUNT RECORD NOT FOUND");

    ValidationFailure failure = null;
    BigDecimal tempBal = acct.currCycCredit().subtract(acct.currCycDebit()).add(tran.tranAmt());
    if (acct.creditLimit().compareTo(tempBal) < 0)
        failure = new ValidationFailure(102, "OVERLIMIT TRANSACTION");
    if (acct.expirationDate().compareTo(tran.origTs().substring(0, 10)) < 0)
        failure = new ValidationFailure(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
    if (failure != null) return Rejected.of(tran, failure);

    return postingService.post(tran, xref.get(), acct.get());   // returns Posted
}
```

`PostingService.post` computes (does **not** persist) the mutated `Account`, the upserted
`TranCategoryBalance` (with `isNew` flag) and the new `Transaction`
(`procTs = Db2TimestampFormatter.now(clock)`). Persisting is left to the writer so that all
writes for a chunk share one transaction. `Clock` is injected for deterministic tests.

Because posting reads and then updates `account` and `tran_cat_bal`, and two records in the same
chunk may hit the same account, the processor must operate on the **latest in-chunk state**.
Recommended approach: `PostingService` keeps a per-chunk write-behind cache
(`Map<Long, Account>`, `Map<TranCatKey, TranCategoryBalance>`) that is consulted before the
repository and cleared in a `ChunkListener.afterChunk/afterChunkError`. Alternative: `chunkSize=1`
(simplest, exact COBOL semantics, slower). The choice is an implementation decision; both satisfy
§8.

### 6.5 Writers — `ClassifierCompositeItemWriter<PostingResult>`

| Route | Item | Writer | Target |
|---|---|---|---|
| `Posted` | `Transaction` | `TransactionItemWriter` (`JdbcBatchItemWriter` or repository) | `INSERT INTO transaction` |
| `Posted` | `Account` (mutated) | `AccountItemWriter` | `UPDATE account SET curr_bal=?, curr_cyc_credit=?, curr_cyc_debit=? WHERE acct_id=?` (must affect exactly 1 row or throw) |
| `Posted` | `TranCategoryBalance` | `TranCategoryBalanceItemWriter` | `INSERT` when `isNew`, else `UPDATE ... SET tran_cat_bal=? WHERE (acct_id,tran_type_cd,tran_cat_cd)=?` |
| `Rejected` | `RejectRecord` | `FlatFileItemWriter<RejectRecord>` with `RejectRecordLineAggregator` | `rejectsFile`, fixed 430-byte records |

Implementation shape: a `CompositeItemWriter` for the three `Posted` writers, and the classifier
selects between it and the rejects writer. Order within the posted composite mirrors COBOL
(`tran_cat_bal`, `account`, `transaction`) — not semantically required inside a DB transaction,
but keeps logs comparable.

The rejects `FlatFileItemWriter`:

* `shouldDeleteIfEmpty=false` — the file must exist even with zero rejects (JCL always
  allocates `DALYREJS(+1)`).
* `lineSeparator` configurable: `""` for true fixed-block output, `"\n"` for text fixtures.
* `encoding` configurable, same as the reader.
* Must be registered as a step-scoped stream so restart appends correctly.

### 6.6 Repository / DAO interfaces

```java
public interface CardXrefRepository {
    Optional<CardXref> findByCardNum(String cardNum);           // XREFFILE random READ
}

public interface AccountRepository {
    Optional<Account> findById(long acctId);                     // ACCTFILE random READ
    Optional<Account> findByIdForUpdate(long acctId);            // SELECT ... FOR UPDATE (I-O semantics)
    int updateBalances(long acctId, BigDecimal currBal,
                       BigDecimal currCycCredit, BigDecimal currCycDebit);   // REWRITE; returns rows affected
}

public interface TranCategoryBalanceRepository {
    Optional<TranCategoryBalance> findByKey(long acctId, String tranTypeCd, int tranCatCd);
    void insert(TranCategoryBalance bal);                        // WRITE
    int updateBalance(long acctId, String tranTypeCd, int tranCatCd, BigDecimal newBal);  // REWRITE
}

public interface TransactionRepository {
    void insert(Transaction tran);                               // WRITE; DuplicateKeyException = fatal
}
```

Reads inside the processor must see writes made earlier in the same chunk (same connection /
same `@Transactional` scope, or the in-chunk cache of §6.4).

### 6.7 Transactions, restart and fault tolerance

* One database transaction per chunk (Spring Batch default). Rejects file writes are part of the
  same chunk via the `ItemStream` transaction-aware buffer, so a rollback also discards the
  rejects written in that chunk.
* **No skip / retry** is configured by default: the COBOL abends on any unexpected I/O error and
  the operational expectation is "fix and rerun". `DuplicateKeyException` on `transaction`,
  `DataAccessException`, `FlatFileParseException` all fail the step.
* Restartability: the reader saves its line position in the execution context; a restart
  continues after the last committed chunk. This is strictly better than the COBOL (which needs a
  manual restore of VSAM files before rerun). `runDate` is an identifying parameter so the same
  day's job can be restarted but not accidentally re-run to completion twice; a re-run needs a
  new `JobInstance` (e.g. `run.id` incrementer) and a prior restore of the data, as today.

### 6.8 Observability

Log lines mirroring the COBOL `DISPLAY`s (same wording, so existing log scrapers keep working):

```
START OF EXECUTION OF PROGRAM CBTRN02C
TCATBAL record not found for key : 00000000001010001.. Creating.      (per created balance record)
TRANSACTIONS PROCESSED :000000300
TRANSACTIONS REJECTED  :000000011
END OF EXECUTION OF PROGRAM CBTRN02C
```

Counts are additionally exposed on the `StepExecution` (`readCount` = processed,
`writeCount` split by classifier via custom `ExecutionContext` keys `posted.count`,
`rejected.count`) and as Micrometer counters.

---

## 7. Output & Exit Behavior

### 7.1 Rejects file (`DALYREJS`) layout — 430 bytes per record

| Offset | Len | Content | Source |
|---|---|---|---|
| 1-350 | 350 | Original daily transaction record, byte-for-byte (including its `PROC-TS` and `FILLER` as read) | `MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA` (L447) |
| 351-354 | 4 | `WS-VALIDATION-FAIL-REASON` — zero-padded 4-digit reason code (`0100`, `0101`, `0102`, `0103`) | L181 |
| 355-430 | 76 | `WS-VALIDATION-FAIL-REASON-DESC` — description text, left-justified, space-padded to 76 | L182 |

Example (line-wrapped for display):

```
<350 bytes of input record>0100INVALID CARD NUMBER FOUND<51 spaces>
```

The file is created new on every run (`DISP=(NEW,CATLG,DELETE)` GDG `+1`). Records appear in
input order. No header or trailer records.

### 7.2 Transaction master (`TRANFILE`) output

One 350-byte record per posted transaction (layout §4.6). In the RDBMS design this is one row
inserted into `transaction`; a fixed-width extract writer with the same layout should be provided
for parity testing and for downstream jobs that still expect the VSAM/flat form.

### 7.3 Updated masters

`account` rows for every account that received at least one posted transaction have new
`curr_bal`, `curr_cyc_credit`, `curr_cyc_debit`. `tran_cat_bal` rows are inserted or updated.
Nothing else changes.

### 7.4 Console output / end-of-job counts

| Message | When |
|---|---|
| `START OF EXECUTION OF PROGRAM CBTRN02C` | job start |
| `TCATBAL record not found for key : <17-byte key>.. Creating.` | each time a category balance row is created |
| `ERROR <OPENING|READING|WRITING|REWRITING|CLOSING> <file>` + `FILE STATUS IS: NNNN` + `ABENDING PROGRAM` | fatal I/O error |
| `TRANSACTIONS PROCESSED :nnnnnnnnn` | end; count of records read (posted + rejected), 9 digits zero-padded |
| `TRANSACTIONS REJECTED  :nnnnnnnnn` | end; 9 digits zero-padded |
| `END OF EXECUTION OF PROGRAM CBTRN02C` | end (not printed on abend) |

### 7.5 Exit status mapping

| COBOL outcome | COBOL `RETURN-CODE` / abend | Spring Batch `ExitStatus` | Process exit code (via `ExitCodeGenerator`) |
|---|---|---|---|
| All records posted, no rejects | `RC=0` | `COMPLETED` | `0` |
| At least one reject, no I/O error | `RC=4` | `COMPLETED WITH REJECTS` (custom exit code set in `JobExecutionListener.afterJob` when `rejected.count > 0`) | `4` |
| Any fatal I/O / data error | `U0999` abend | `FAILED` | `8` (or `12`; must be non-zero and distinct from 4 — decision in §9) |

The scheduler (Control-M today; see `app/scheduler/CardDemo.controlm`) conditions downstream
jobs on `RC<=4`; the Java launcher must preserve `0` = clean, `4` = warning, `>4` = failure.

---

## 8. Acceptance Criteria & Test Plan

### 8.1 Test fixtures

* `app/data/ASCII/dailytran.txt` (300 records), `cardxref.txt`, `acctdata.txt`, `tcatbal.txt`
  loaded into the RDBMS via the migration loaders.
* A **golden output set** produced by running the COBOL `POSTTRAN` job against these same inputs
  on the mainframe / emulator (`samples/m2/...` runtimes): the resulting `TRANSACT` records,
  `ACCTDATA`, `TCATBALF` and `DALYREJS(+1)` files, plus SYSOUT. This must be captured **once**
  before implementation begins and stored under `test/resources/golden/posttran/`.
* Hand-crafted micro-fixtures (one or two records each) for each rule below, with a fixed
  `Clock` so `proc_ts` is deterministic (`2026-01-02-03.04.05.060000`).

### 8.2 Unit-level acceptance criteria

| ID | Criterion | Expected |
|---|---|---|
| AC-R1 | `ZonedDecimal.parse("0000005047G")` | `504.77` (scale 2) |
| AC-R2 | `ZonedDecimal.parse("0000001234}")` | `-123.40` |
| AC-R3 | `ZonedDecimal.format(new BigDecimal("-123.40"), 11)` | `"0000001234}"`; `format(504.77)` → `"0000005047G"`; round-trip for all 300 sample amounts |
| AC-R4 | Reader tokenises the first sample record | `tranId="0000000000683580"`, `tranTypeCd="01"`, `tranCatCd=1`, `tranSource="POS TERM  "`, `origTs="2022-06-10 19:27:53.000000"` |
| AC-R5 | Reader given a 349-byte record | `FlatFileParseException`, step `FAILED` |
| AC-T1 | `Db2TimestampFormatter.format(2026-09-23T18:15:42.376)` | `"2026-09-23-18.15.42.370000"` (26 chars, centiseconds truncated not rounded) |
| AC-V100 | card number absent from `card_xref` | `Rejected(100, "INVALID CARD NUMBER FOUND")`; no DB writes |
| AC-V101 | xref present, account id absent from `account` | `Rejected(101, "ACCOUNT RECORD NOT FOUND")`; no DB writes |
| AC-V102a | `creditLimit=1000.00, cycCredit=900.00, cycDebit=0.00, amt=100.00` | posted (`1000.00 >= 1000.00`) |
| AC-V102b | same but `amt=100.01` | `Rejected(102, "OVERLIMIT TRANSACTION")` |
| AC-V102c | `creditLimit=1000.00, cycCredit=900.00, cycDebit=-200.00, amt=250.00` | temp = 900 − (−200) + 250 = 1350 > 1000 → `Rejected(102)` (verifies debit sign handling) |
| AC-V103a | `expirationDate="2025-12-31"`, `origTs="2025-12-31 23:59:59.999999"` | posted (equal dates pass) |
| AC-V103b | `expirationDate="2025-12-31"`, `origTs="2026-01-01 00:00:00.000000"` | `Rejected(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION")` |
| AC-V-PREC | record fails both V3 and V4 | `Rejected(103)` (last rule wins), exactly one reject record |
| AC-V-ORDER | record fails V1 and would fail V3/V4 | `Rejected(100)`; account repository never called |
| AC-P1 | posted `amt=50.00` on account `currBal=100.00, cycCredit=10.00, cycDebit=-5.00` | `currBal=150.00, cycCredit=60.00, cycDebit=-5.00` |
| AC-P2 | posted `amt=-30.00` on the same account | `currBal=70.00, cycCredit=10.00, cycDebit=-35.00` |
| AC-P3 | posted `amt=0.00` | `cycCredit` unchanged (+0), `cycDebit` unchanged; row still updated (idempotent) |
| AC-P4 | no `tran_cat_bal` row for `(acct, type, cat)` | row inserted with `tran_cat_bal = amt`; log line `TCATBAL record not found for key : <acct11><type2><cat4>.. Creating.` |
| AC-P5 | existing `tran_cat_bal=100.00`, `amt=-40.00` | row updated to `60.00`, no insert |
| AC-P6 | category key uses `xref.acctId`, not any account id embedded in the daily record | verified by fixture where they differ |
| AC-P7 | posted transaction row | all 12 business fields equal the input, `proc_ts` = formatter output for injected clock, `tran_id` PK |
| AC-P8 | two transactions for the same account in one chunk | second sees first's updated balances (V3 evaluated against updated cycle totals); final balances equal sequential application |
| AC-P9 | duplicate `tran_id` in input | `DuplicateKeyException`, step `FAILED`, chunk rolled back, exit code ≠ 0 and ≠ 4 |
| AC-O1 | reject record for AC-V100 | length 430; bytes 1-350 identical to input line; bytes 351-354 `"0100"`; bytes 355-430 description padded with spaces to 76 |
| AC-O2 | run with zero rejects | rejects file exists and is empty; exit code `0` |
| AC-O3 | run with ≥1 reject | exit code `4`; `TRANSACTIONS REJECTED  :` count matches number of reject records |
| AC-O4 | `TRANSACTIONS PROCESSED :` | equals total input records = posted + rejected |
| AC-X1 | database unavailable at start | job `FAILED`, exit code `8`, no partial output |
| AC-X2 | failure injected in chunk N | chunks 1..N−1 committed, chunk N fully rolled back including its rejects-file lines; restart of same `JobInstance` resumes at chunk N and final outputs equal an uninterrupted run |

### 8.3 End-to-end parity criterion (must pass before cut-over)

Given the 300-record `dailytran.txt` and the seeded `card_xref` / `account` / `tran_cat_bal`
fixtures, running `postTranJob` and then exporting `transaction`, `account`, `tran_cat_bal` to
fixed-width files with the copybook layouts:

1. The exported `transaction` file equals the golden `TRANSACT` file for every byte **except**
   columns 305-330 (`proc_ts`), which must instead match the pattern
   `\d{4}-\d{2}-\d{2}-\d{2}\.\d{2}\.\d{2}\.\d{2}0000` and be ≥ job start time.
2. The exported `account` file equals the golden `ACCTDATA` file byte-for-byte.
3. The exported `tran_cat_bal` file equals the golden `TCATBALF` file byte-for-byte (sorted by
   key, since VSAM returns key order).
4. The rejects file equals the golden `DALYREJS` file byte-for-byte, same record count and order.
5. Console counts equal the golden SYSOUT counts; process exit code is `4` if the golden run had
   rejects, else `0`.

This comparison is automated (`PostTranParityIT`) and runs in CI against an embedded/containerised
database. Any intentional deviation from the golden output (see §9, e.g. the 109 path) must be
recorded in the test as an explicit, reviewed exclusion.

### 8.4 Non-functional criteria

* Throughput ≥ 1,000 records/s on the reference environment with `chunkSize=100` (COBOL volume
  is far below this; the target is to make the job cost irrelevant).
* Memory bounded regardless of file size (streaming reader; in-chunk cache only).
* Job is re-runnable/restartable as per §6.7 without manual data restores.

---

## 9. Assumptions, Risks & Open Questions

### 9.1 Assumptions

| # | Assumption |
|---|---|
| A1 | The four VSAM KSDS files are migrated to relational tables (§4.9) and all other CardDemo programs that share them (`CBACT04C`, `CBTRN03C`, `CBSTM03A`, CICS `COTRN*`/`COACT*`) are migrated to the same tables or fed by fixed-width extracts. |
| A2 | Daily transaction files arrive as fixed-length 350-byte records; they may be ASCII (already converted in transit) or EBCDIC. Encoding is a job parameter, not auto-detected. |
| A3 | Amount fields in the file use zoned-decimal sign overpunch (§4.7). If an upstream producer switches to explicit `+`/`-` or packed decimal, the codec — not the business logic — changes. |
| A4 | The upstream refresh of the transaction master (`TRANFILE` `OPEN OUTPUT` semantics) is handled by the job stream, not by this job. |
| A5 | The generated `proc_ts` uses the batch server's local time zone configured to match the mainframe LPAR (CDT/CST in the sample data) unless the team decides otherwise (Q2). |

### 9.2 Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | **Silent COBOL truncation** in `WS-TEMP-BAL` (`S9(9)V99` receiving `S9(10)V99` arithmetic) and in `TRAN-CAT-BAL` (`S9(9)V99` accumulator) could make COBOL and Java diverge for values ≥ 1,000,000,000.00. | Java uses full precision and additionally asserts `|value| < 10^9` on write, failing the job rather than truncating. Documented deviation; confirm no production data approaches the limit. |
| R2 | **Trailing-blank / `CHAR` semantics** differ between DB engines; a `CHAR(100)` in PostgreSQL compares equal ignoring trailing blanks but returns them; MySQL strips them on retrieval. | Fixed-width writers always right-pad to the copybook width; parity tests compare padded output. |
| R3 | **EBCDIC ↔ ASCII conversion** of `X(n)` fields containing non-ASCII bytes (e.g. `LOW-VALUES` in `FILLER`, `¬`, `¢`) is lossy or table-dependent. | Use `IBM1047`/`IBM037` per the source LPAR; treat `FILLER` as opaque bytes echoed into rejects. Parity test runs on both ASCII and EBCDIC fixtures. |
| R4 | **Intra-chunk consistency**: multiple transactions for one account in the same chunk must see each other's updates; a naïve stateless processor would compute V3 against stale limits and lose updates. | In-chunk write-behind cache or `chunkSize=1` (§6.4); AC-P8 guards this. |
| R5 | **Concurrency with online (CICS-equivalent) updates** to `account` during the batch window. VSAM `DISP=SHR` plus CICS file closure (`CLOSEFIL` job) serialises today. | `SELECT ... FOR UPDATE` on `account` rows; schedule remains "online closed during posting" until an optimistic-locking design is agreed. |
| R6 | **Timestamp precision** — COBOL `CURRENT-DATE` gives centiseconds, zero-filled to microseconds; a Java `Instant` has nanoseconds. Emitting real microseconds would break byte parity of `proc_ts` and any downstream sort/compare that assumes `hh0000`. | Formatter truncates to centiseconds and appends `0000` (AC-T1). Revisit only with downstream owners (Q1). |
| R7 | **Reason 109 path** (§5.4) cannot be reproduced faithfully in a transactional system and is almost certainly dead code. | Treat as fatal; document as deviation. |
| R8 | The `9300-DALYREJS-CLOSE` defect (displays wrong status field) and other message inconsistencies mean log-scraping parity is approximate. | Match the *normal-path* messages exactly; error-path messages are best-effort. |
| R9 | **Duplicate `TRAN-ID`** in the daily file abends the COBOL mid-run with partial updates. The Java job fails the chunk cleanly, which is different (better) behaviour but changes what operators see. | Document in runbook; AC-P9. |
| R10 | Non-numeric data in `9(n)` fields (e.g. blank `MERCHANT-ID`) is tolerated by COBOL `MOVE` but would throw in `Long.parseLong`. | Decide per Q6; default is to fail fast, with an option to map blanks to `0` for identifier fields that are pass-through only (`merchantId`). |

### 9.3 Open questions (decisions required before implementation is complete)

| # | Question | Options / recommendation |
|---|---|---|
| Q1 | Should `proc_ts` keep the DB2 string format `YYYY-MM-DD-HH.MM.SS.hh0000` in a `CHAR(26)`, or become a native `TIMESTAMP(6)` with the string rendered only on extract? | Recommend `CHAR(26)` for phase 1 (parity); convert in a later phase once all consumers are migrated. |
| Q2 | Time zone for `proc_ts` generation. | Recommend an explicit `posttran.clock.zone` property defaulting to the LPAR zone; never rely on JVM default. |
| Q3 | Target RDBMS (PostgreSQL / Aurora PostgreSQL / Oracle / DB2 LUW). Affects `CHAR` semantics (R2), `FOR UPDATE` syntax, and `NUMERIC` vs `DECIMAL`. | Must be decided by the platform team; the spec is engine-neutral. |
| Q4 | Who truncates/reloads `transaction` before the daily run (A4)? Should `postTranJob` gain an optional first tasklet `truncateTransactionsStep` guarded by a job parameter? | Recommend a separate job/step owned by the scheduler to mirror the JCL stream. |
| Q5 | Exact non-zero exit code for fatal failure (`8` vs `12`, the COBOL `APPL-RESULT` conventions) and how Control-M / its replacement interprets it. | Recommend `8`; confirm with operations. |
| Q6 | Handling of malformed numeric fields in input (R10): fail the job, or reject the record with a new reason code (e.g. `0104 INVALID NUMERIC FIELD`)? | The COBOL comment `* ADD MORE VALIDATIONS HERE` suggests new reasons are anticipated; recommend a new reason code, but this changes reject-file parity and must be agreed. |
| Q7 | Should the rejects file remain a fixed-width 430-byte file, or additionally/instead be persisted to a `daily_transaction_reject` table? | Recommend both: file for parity/downstream, table for operability. |
| Q8 | Input delivery: raw `RECFM=F` byte stream vs newline-delimited text; EBCDIC vs ASCII (A2/R3). | Support both via configuration; parity suite runs both. |
| Q9 | Should `ACCT-ACTIVE-STATUS` be validated (it is not today)? | Out of scope for a like-for-like migration; log as a business enhancement. |
| Q10 | Does any consumer depend on the *negative* `curr_cyc_debit` convention (§5.3)? Changing it to a positive magnitude would be cleaner but breaks V3 and parity. | Keep as-is for migration; raise with the interest/statement (`CBACT04C`, `CBSTM03A`) owners. |

---

## Appendix A — Field offset quick reference (350-byte transaction record)

```
 1-16   TRAN-ID              X(16)
17-18   TRAN-TYPE-CD         X(02)
19-22   TRAN-CAT-CD          9(04)
23-32   TRAN-SOURCE          X(10)
33-132  TRAN-DESC            X(100)
133-143 TRAN-AMT             S9(09)V99   (sign overpunch on byte 143)
144-152 TRAN-MERCHANT-ID     9(09)
153-202 TRAN-MERCHANT-NAME   X(50)
203-252 TRAN-MERCHANT-CITY   X(50)
253-262 TRAN-MERCHANT-ZIP    X(10)
263-278 TRAN-CARD-NUM        X(16)
279-304 TRAN-ORIG-TS         X(26)
305-330 TRAN-PROC-TS         X(26)
331-350 FILLER               X(20)
```

## Appendix B — Reject record quick reference (430 bytes)

```
  1-350  original DALYTRAN record (Appendix A layout)
351-354  reason code   9(04)   0100 | 0101 | 0102 | 0103
355-430  reason text   X(76)   INVALID CARD NUMBER FOUND
                               ACCOUNT RECORD NOT FOUND
                               OVERLIMIT TRANSACTION
                               TRANSACTION RECEIVED AFTER ACCT EXPIRATION
```
