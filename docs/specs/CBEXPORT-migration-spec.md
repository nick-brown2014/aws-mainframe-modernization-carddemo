# CBEXPORT Migration Specification: COBOL Batch to Java + Spring Batch

| | |
|---|---|
| Source program | `app/cbl/CBEXPORT.cbl` (CardDemo v2.0-44-gb6e9c27-254) |
| Source JCL | `app/jcl/CBEXPORT.jcl`, `STEP01 EXEC PGM=IDCAMS` (define output KSDS), `STEP02 EXEC PGM=CBEXPORT` |
| Copybooks | `app/cpy/CVEXPORT.cpy` (export record), `CVCUS01Y.cpy`, `CVACT01Y.cpy`, `CVACT03Y.cpy`, `CVTRA05Y.cpy`, `CVACT02Y.cpy` |
| Companion spec | `docs/specs/CBIMPORT-migration-spec.md` (consumer of the file produced here) |
| Shared-table reference | `docs/specs/CBTRN02C-migration-spec.md` §4.9 (`account`, `card_xref`, `transaction`) |
| Sample data | `app/data/EBCDIC/AWS.M2.CARDDEMO.EXPORT.DATA.PS` (500 records × 500 bytes, EBCDIC), `app/data/ASCII/{custdata,acctdata,cardxref,carddata}.txt` |
| Target | Java 17+, Spring Boot 3.x, Spring Batch 5.x, JDBC/JPA repository over an RDBMS |
| Status | Draft for review |

This document is derived directly from the COBOL source, copybooks and JCL listed above. Line
references (`L123`) point into `CBEXPORT.cbl` unless prefixed with a file name
(`CVEXPORT L25`, `CBEXPORT.jcl L32`). **§4.2 of this document is the single authoritative
definition of the 500-byte `CVEXPORT` record layout**; the CBIMPORT spec references it and does
not redefine it.

---

## 1. Overview & Purpose

`CBEXPORT` is the CardDemo **branch-migration export** program (`CBEXPORT.jcl L20`, source
comments L27-L30). It reads the five VSAM master files of a CardDemo installation in full and
writes a single **multi-record-type export file** in which every record is 500 bytes and carries a
one-character record type, a timestamp, a global sequence number, a branch id and a region code,
followed by a type-specific payload:

| Order written | Type | Source KSDS | Source copybook | Paragraph |
|---|---|---|---|---|
| 1 | `C` customer | `CUSTFILE` | `CVCUS01Y` | `2000`/`2200` (L243-L310) |
| 2 | `A` account | `ACCTFILE` | `CVACT01Y` | `3000`/`3200` (L312-L373) |
| 3 | `X` card cross-reference | `XREFFILE` | `CVACT03Y` | `4000`/`4200` (L375-L428) |
| 4 | `T` transaction | `TRANSACT` | `CVTRA05Y` | `5000`/`5200` (L430-L493) |
| 5 | `D` card | `CARDFILE` | `CVACT02Y` | `5500`/`5700` (L495-L551) |

The output is itself a VSAM KSDS keyed on the 4-byte binary sequence number (defined by `STEP01`,
`CBEXPORT.jcl L30-L38`), so the physical order of the file is the sequence order. `CBIMPORT`
reverses the process (see companion spec).

Key facts an implementer needs up front:

* **There are no selection criteria.** Every record of every input file is exported
  (`PERFORM UNTIL ...-EOF` loops, L249, L318, L381, L437, L502). "Branch `0001` / region `NORTH`"
  are hard-coded constants (L278-L279 and equivalents), not filters.
* **One timestamp per run**, generated once in `1050-GENERATE-TIMESTAMP` (L172-L195) and copied
  into every record.
* **One global sequence counter** (`WS-SEQUENCE-COUNTER`, L123) incremented before every write
  (L276, L345, L409, L464, L529); it never resets between record types, so the sequence numbers
  are `1..N` across the whole file in the order C, A, X, T, D.
* The program either completes with `RETURN-CODE 0` or abends (`9999-ABEND-PROGRAM`, L576-L579).
  There is no warning path.

---

## 2. Current-State (COBOL) Analysis

### 2.1 Control flow

```
0000-MAIN-PROCESSING                              L149
 ├─ 1000-INITIALIZE                               L161
 │   ├─ DISPLAY 'CBEXPORT: Starting Customer Data Export'
 │   ├─ 1050-GENERATE-TIMESTAMP                   L172   (ACCEPT DATE/TIME, build 3 strings)
 │   ├─ 1100-OPEN-FILES                           L198   (OPEN INPUT ×5, OPEN OUTPUT ×1; abend on any non-'00')
 │   ├─ DISPLAY 'CBEXPORT: Export Date: ' WS-EXPORT-DATE
 │   └─ DISPLAY 'CBEXPORT: Export Time: ' WS-EXPORT-TIME
 ├─ 2000-EXPORT-CUSTOMERS                         L243
 │   ├─ DISPLAY 'CBEXPORT: Processing customer records'
 │   ├─ 2100-READ-CUSTOMER-RECORD (priming read)
 │   ├─ PERFORM UNTIL WS-CUSTOMER-EOF
 │   │     2200-CREATE-CUSTOMER-EXP-REC ; 2100-READ-CUSTOMER-RECORD
 │   └─ DISPLAY 'CBEXPORT: Customers exported: ' WS-CUSTOMER-RECORDS-EXPORTED
 ├─ 3000-EXPORT-ACCOUNTS                          L312   (same shape; 3100 read / 3200 create)
 ├─ 4000-EXPORT-XREFS                             L375   (4100 read / 4200 create)
 ├─ 5000-EXPORT-TRANSACTIONS                      L430   (5100 read / 5200 create)
 ├─ 5500-EXPORT-CARDS                             L495   (5600 read / 5700 create)
 ├─ 6000-FINALIZE                                 L553   (CLOSE ×6, DISPLAY six totals)
 └─ GOBACK                                        L158
```

Every "create" paragraph has the identical skeleton (shown for customers, L269-L310):

```
INITIALIZE EXPORT-RECORD                       -- L271: header fields & 460-byte data area → spaces/zeros
MOVE 'C'                   TO EXPORT-REC-TYPE  -- L274
MOVE WS-FORMATTED-TIMESTAMP TO EXPORT-TIMESTAMP -- L275
ADD 1 TO WS-SEQUENCE-COUNTER                   -- L276
MOVE WS-SEQUENCE-COUNTER   TO EXPORT-SEQUENCE-NUM -- L277 (display 9(9) → binary 9(9) COMP)
MOVE '0001'                TO EXPORT-BRANCH-ID -- L278
MOVE 'NORTH'               TO EXPORT-REGION-CODE -- L279
MOVE <every source field>  TO <EXP-* field>    -- L282-L299 (field-by-field, no transformation)
WRITE EXPORT-OUTPUT-RECORD FROM EXPORT-RECORD  -- L301
IF NOT WS-EXPORT-OK → DISPLAY 'ERROR: Writing export record, Status: ' ..., abend -- L303-L307
ADD 1 TO WS-CUSTOMER-RECORDS-EXPORTED          -- L309
ADD 1 TO WS-TOTAL-RECORDS-EXPORTED             -- L310
```

`INITIALIZE EXPORT-RECORD` (L271 etc.) only touches the elementary items that are **not**
`REDEFINES` (`EXPORT-REC-TYPE`, `EXPORT-TIMESTAMP`, `EXPORT-SEQUENCE-NUM`, `EXPORT-BRANCH-ID`,
`EXPORT-REGION-CODE`, `EXPORT-RECORD-DATA`); because `EXPORT-RECORD-DATA` is `PIC X(460)` the
whole payload area becomes **spaces** (`x'40'` EBCDIC) before the type-specific fields are moved in.
Consequently all `FILLER` areas in the payload are spaces, confirmed in the sample file (e.g. the
427-byte xref filler is all `x'40'`).

### 2.2 Paragraph reference

| Paragraph | Lines | Purpose / behaviour |
|---|---|---|
| `0000-MAIN-PROCESSING` | L149-L158 | Straight-line performs; `GOBACK` with `RETURN-CODE` untouched (0). |
| `1000-INITIALIZE` | L161-L169 | Start banner, timestamp, opens, date/time banners. |
| `1050-GENERATE-TIMESTAMP` | L172-L195 | `ACCEPT WS-CURRENT-DATE FROM DATE YYYYMMDD` (L175), `ACCEPT WS-CURRENT-TIME FROM TIME` (L176, `HHMMSShh`). Builds `WS-EXPORT-DATE` = `YYYY-MM-DD` (L179-L182), `WS-EXPORT-TIME` = `HH:MM:SS` (L185-L188), `WS-FORMATTED-TIMESTAMP` = `WS-EXPORT-DATE ' ' WS-EXPORT-TIME '.00'` (L191-L194). **Latent defect**: the `STRING` produces 22 characters into a 26-byte field with no `VALUE` clause; `STRING` does not pad, so bytes 23-26 are whatever the field held before (uninitialised WORKING-STORAGE). See §5.3 and §9. Hundredths (`WS-CURR-HUNDREDTH`, L135) are never used. |
| `1100-OPEN-FILES` | L198-L240 | `OPEN INPUT` customer, account, xref, transaction, card; `OPEN OUTPUT` export. Each followed by `IF NOT ...-OK` → `DISPLAY 'ERROR: Cannot open <FILE>, Status: ' <status>` → `9999-ABEND-PROGRAM`. Message file names are the COBOL select names (`CUSTOMER-INPUT`, `ACCOUNT-INPUT`, `XREF-INPUT`, `TRANSACTION-INPUT`, `CARD-INPUT`, `EXPORT-OUTPUT`). |
| `2000-EXPORT-CUSTOMERS` | L243-L255 | Banner, priming read, loop, count banner (`'CBEXPORT: Customers exported: '`). |
| `2100-READ-CUSTOMER-RECORD` | L258-L266 | `READ CUSTOMER-INPUT` (into FD record `CUSTOMER-RECORD`). Status not `00` and not `10` → `DISPLAY 'ERROR: Reading CUSTOMER-INPUT, Status: '` + abend. |
| `2200-CREATE-CUSTOMER-EXP-REC` | L269-L310 | Builds a `C` record (§4.3), writes, counts. |
| `3000-EXPORT-ACCOUNTS` | L312-L324 | Banner `'CBEXPORT: Processing account records'`, loop, `'CBEXPORT: Accounts exported: '`. |
| `3100-READ-ACCOUNT-RECORD` | L327-L335 | As 2100 for `ACCOUNT-INPUT`. |
| `3200-CREATE-ACCOUNT-EXP-REC` | L338-L373 | Builds an `A` record (§4.4). |
| `4000-EXPORT-XREFS` | L375-L387 | Banner `'CBEXPORT: Processing xref records'`, `'CBEXPORT: XRefs exported: '`. |
| `4100-READ-XREF-RECORD` | L390-L398 | As 2100 for `XREF-INPUT`. |
| `4200-CREATE-XREF-EXPORT-RECORD` | L402-L428 | Builds an `X` record (§4.5). |
| `5000-EXPORT-TRANSACTIONS` | L431-L443 | Banner `'CBEXPORT: Processing transaction records'`, `'CBEXPORT: Transactions exported: '`. |
| `5100-READ-TRANSACTION-RECORD` | L446-L454 | As 2100 for `TRANSACTION-INPUT`. |
| `5200-CREATE-TRAN-EXP-REC` | L457-L493 | Builds a `T` record (§4.6). |
| `5500-EXPORT-CARDS` | L496-L508 | Banner `'CBEXPORT: Processing card records'`, `'CBEXPORT: Cards exported: '`. |
| `5600-READ-CARD-RECORD` | L511-L519 | As 2100 for `CARD-INPUT`. |
| `5700-CREATE-CARD-EXPORT-RECORD` | L522-L551 | Builds a `D` record (§4.7). |
| `6000-FINALIZE` | L554-L573 | `CLOSE` all six files (status **not checked**), then six `DISPLAY` totals (§7.2). |
| `9999-ABEND-PROGRAM` | L576-L579 | `DISPLAY 'CBEXPORT: ABENDING PROGRAM'` then `CALL 'CEE3ABD'` **with no arguments** (L579). Contrast with the read utilities, which pass `ABCODE=999`. LE's `CEE3ABD` expects two parameters; calling it without them yields an LE-detected error/abend with an unpredictable code rather than a clean `U0999`. Treat as "abend, code unspecified" (§7.3). |

### 2.3 File SELECT / FILE STATUS handling

| Select name | DD | Lines | Organization / access / key | Status field | `88` levels | Checked where |
|---|---|---|---|---|---|---|
| `CUSTOMER-INPUT` | `CUSTFILE` | L35-L39 | INDEXED / SEQUENTIAL / `CUST-ID` | `WS-CUSTOMER-STATUS` (L100) | `EOF='10'`, `OK='00'` | open L201, read L262 |
| `ACCOUNT-INPUT` | `ACCTFILE` | L41-L45 | INDEXED / SEQUENTIAL / `ACCT-ID` | `WS-ACCOUNT-STATUS` (L103) | `'10'`, `'00'` | open L208, read L331 |
| `XREF-INPUT` | `XREFFILE` | L47-L51 | INDEXED / SEQUENTIAL / `XREF-CARD-NUM` | `WS-XREF-STATUS` (L106) | `'10'`, `'00'` | open L215, read L394 |
| `TRANSACTION-INPUT` | `TRANSACT` | L53-L57 | INDEXED / SEQUENTIAL / `TRAN-ID` | `WS-TRANSACTION-STATUS` (L109) | `'10'`, `'00'` | open L222, read L450 |
| `CARD-INPUT` | `CARDFILE` | L59-L63 | INDEXED / SEQUENTIAL / `CARD-NUM` | `WS-CARD-STATUS` (L112) | `'10'`, `'00'` | open L229, read L515 |
| `EXPORT-OUTPUT` | `EXPFILE` | L65-L69 | INDEXED / SEQUENTIAL / `EXPORT-SEQUENCE-NUM` | `WS-EXPORT-STATUS` (L115) | `OK='00'` only | open L236, every write (L303, L366, L421, L486, L544) |

Behavioural notes:

* The FD record for each input is the copybook `01` itself (L74-L87), so a `READ` places data
  directly into `CUSTOMER-RECORD`, `ACCOUNT-RECORD`, etc.
* `READ` on a KSDS in sequential access mode returns records in **ascending key order**. Export
  order within a type is therefore: customers by `CUST-ID`, accounts by `ACCT-ID`, xrefs by
  `XREF-CARD-NUM`, transactions by `TRAN-ID`, cards by `CARD-NUM`. The Java job must reproduce
  this order (§6.3).
* `WRITE` to a KSDS opened `OUTPUT` in sequential mode requires strictly ascending keys; the
  monotonic sequence counter guarantees that. Status `'02'` (duplicate alternate key) cannot occur;
  `'21'` (key out of sequence) or `'22'` (duplicate key) would indicate a counter bug and abend.
* Status `'04'`/`'05'`/`'07'` (successful-with-info) are treated as errors by `NOT ...-OK`
  because only `'00'` is "OK". In practice VSAM KSDS opens return `'00'`; an empty input KSDS
  returns `'00'` on open and `'10'` on the first read, producing zero records of that type
  (not an error).
* `CLOSE` statuses in `6000-FINALIZE` are ignored.

---

## 3. Files / Datasets

From `app/jcl/CBEXPORT.jcl`:

| DD | Dataset | Lines | Org / RECFM | LRECL | Key | Copybook | Direction |
|---|---|---|---|---|---|---|---|
| `CUSTFILE` | `AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS` | L49-L50 | VSAM KSDS | 500 | `CUST-ID` 9(09), offset 0 | `CVCUS01Y` | in |
| `ACCTFILE` | `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` | L51-L52 | VSAM KSDS | 300 | `ACCT-ID` 9(11), offset 0 | `CVACT01Y` | in |
| `XREFFILE` | `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` | L53-L54 | VSAM KSDS | 50 | `XREF-CARD-NUM` X(16), offset 0 | `CVACT03Y` | in |
| `TRANSACT` | `AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS` | L55-L56 | VSAM KSDS | 350 | `TRAN-ID` X(16), offset 0 | `CVTRA05Y` | in |
| `CARDFILE` | `AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS` | L57-L58 | VSAM KSDS | 150 | `CARD-NUM` X(16), offset 0 | `CVACT02Y` | in |
| `EXPFILE` | `AWS.M2.CARDDEMO.EXPORT.DATA` | L62-L63 | VSAM KSDS, `RECORDSIZE(500 500)`, `KEYS(4 28)` (L32-L33), `FREESPACE(10 10)`, `SHAREOPTIONS(2 3)` | 500 | 4-byte binary at offset 28 (0-based) = `EXPORT-SEQUENCE-NUM` | `CVEXPORT` | out |
| `SYSOUT`, `SYSPRINT` | `SYSOUT=*` | L67-L68 | — | — | — | — | console |

`STEP01` (L24-L39) unconditionally `DELETE ... CLUSTER PURGE`, `SET MAXCC = 0`, then `DEFINE
CLUSTER`; i.e. the export dataset is **recreated from scratch on every run**. The Java
equivalent is "truncate/overwrite the output file" (§6.2). Note `KEYS(4 28)` uses a 0-based
offset (IDCAMS convention): byte 28 is the 29th byte, immediately after the 1-byte type and
26-byte timestamp (1 + 26 = 27 bytes → offsets 0-26), so the key occupies 0-based offsets 27-30 …
**this is off by one.** `EXPORT-SEQUENCE-NUM` sits at 0-based offset 27 (1-based 28-31), but the
IDCAMS key is declared at 0-based offset 28 (1-based 29-32), i.e. the last 3 bytes of the sequence
number plus the first byte of `EXPORT-BRANCH-ID` (`'0'`). See §5.4 for consequences.

The sample `AWS.M2.CARDDEMO.EXPORT.DATA.PS` is the sequential (REPRO'd) image of that KSDS:
250 000 bytes = 500 records × 500 bytes, EBCDIC (CP037), no record delimiters.

---

## 4. Data Model & Field Mappings

### 4.1 Type-mapping conventions

Identical to CBTRN02C spec §4.1 with these additions needed by the export record:

| COBOL picture | Storage | Java type | Notes |
|---|---|---|---|
| `PIC 9(n) COMP` (binary, unsigned) | n ≤ 4 → 2 bytes, 5-9 → 4 bytes, 10-18 → 8 bytes; **big-endian two's complement** | `int` (≤ 9 digits) / `long` | With default `TRUNC(STD)` a `MOVE` into `9(9) COMP` truncates to 9 decimal digits; the export counter and ids never exceed their pictures in practice. Java must write big-endian (`ByteBuffer.order(BIG_ENDIAN)`). |
| `PIC S9(n)V99 COMP` | 12 digits → 8 bytes binary, value scaled ×100 | `BigDecimal` scale 2 (`BigDecimal.valueOf(long, 2)`) | Only `EXP-ACCT-CURR-CYC-DEBIT` (`CVEXPORT L57`). |
| `PIC 9(n) COMP-3` / `PIC S9(n)V99 COMP-3` (packed) | ⌈(n+1)/2⌉ bytes; two digits per byte, low nibble of last byte is the sign: `C` = +, `D` = −, `F` = unsigned | `int` / `BigDecimal` scale 2 | 9(03) → 2 bytes (`x'300F'` = 300); S9(09)V99 → 6 bytes (`x'00000050477C'` = +504.77); S9(10)V99 → 7 bytes (`x'0000000102000C'` = +1 020.00). Positive values written by COBOL `MOVE` from a signed source carry `C`; unsigned pictures carry `F`. Readers must accept `A`-`F` sign nibbles (`A`,`C`,`E`,`F` positive; `B`,`D` negative); writers emit `C`/`D` for signed, `F` for unsigned. |
| `PIC X(n)` | EBCDIC bytes | `String` | The whole 500-byte record is EBCDIC in the mainframe file; ASCII fixtures in `app/data/ASCII` are line-delimited text and only cover the *source* masters, not the export file. See §6.8 for charset handling. |

### 4.2 `CVEXPORT` — the shared 500-byte export record (authoritative definition)

Source: `app/cpy/CVEXPORT.cpy` L9-L100. Offsets are **1-based byte positions within the
500-byte record**; the payload area starts at position 41. This section is referenced verbatim by
the CBIMPORT spec.

#### 4.2.1 Common header (positions 1-40), `CVEXPORT L10-L19`

| # | Field | PIC | Pos | Len | Java type | Value written by CBEXPORT |
|---|---|---|---|---|---|---|
| 1 | `EXPORT-REC-TYPE` | `X(1)` | 1 | 1 | `char` / enum `ExportRecordType {C,A,X,T,D}` | `'C'`,`'A'`,`'X'`,`'T'`,`'D'` (L274, L343, L407, L462, L527) |
| 2 | `EXPORT-TIMESTAMP` | `X(26)` | 2-27 | 26 | `String` (26) | `WS-FORMATTED-TIMESTAMP` (§5.3) |
| 2a | ↳ `EXPORT-DATE` (REDEFINES) | `X(10)` | 2-11 | 10 | — | `YYYY-MM-DD` |
| 2b | ↳ `EXPORT-DATE-TIME-SEP` | `X(1)` | 12 | 1 | — | space |
| 2c | ↳ `EXPORT-TIME` | `X(15)` | 13-27 | 15 | — | `HH:MM:SS.00` + 4 bytes (§5.3) |
| 3 | `EXPORT-SEQUENCE-NUM` | `9(9) COMP` | 28-31 | 4 | `int` (`long` in DB) | global counter, 1-based |
| 4 | `EXPORT-BRANCH-ID` | `X(4)` | 32-35 | 4 | `String` (4) | constant `'0001'` |
| 5 | `EXPORT-REGION-CODE` | `X(5)` | 36-40 | 5 | `String` (5) | constant `'NORTH'` |
| 6 | `EXPORT-RECORD-DATA` | `X(460)` | 41-500 | 460 | `byte[460]` / one of the payload classes below | type-specific overlay; unused tail is spaces |

Sample (first record of the EBCDIC file, decoded): type `C`, timestamp
`2025-09-28 22:53:40.000000`, sequence `x'00000001'`, branch `0001`, region `NORTH`.

#### 4.2.2 Payload `C` — `EXPORT-CUSTOMER-DATA` (`CVEXPORT L24-L42`, 326 data bytes + 134 filler)

| # | Field | PIC | Pos | Len | Java type | Source (`CVCUS01Y`) via CBEXPORT |
|---|---|---|---|---|---|---|
| 1 | `EXP-CUST-ID` | `9(09) COMP` | 41-44 | 4 | `int`/`long` | `CUST-ID` 9(09) display → binary (L282) |
| 2 | `EXP-CUST-FIRST-NAME` | `X(25)` | 45-69 | 25 | `String` | `CUST-FIRST-NAME` (L283) |
| 3 | `EXP-CUST-MIDDLE-NAME` | `X(25)` | 70-94 | 25 | `String` | `CUST-MIDDLE-NAME` (L284) |
| 4 | `EXP-CUST-LAST-NAME` | `X(25)` | 95-119 | 25 | `String` | `CUST-LAST-NAME` (L285) |
| 5 | `EXP-CUST-ADDR-LINE(1)` | `X(50)` | 120-169 | 50 | `String` | `CUST-ADDR-LINE-1` (L286) |
| 6 | `EXP-CUST-ADDR-LINE(2)` | `X(50)` | 170-219 | 50 | `String` | `CUST-ADDR-LINE-2` (L287) |
| 7 | `EXP-CUST-ADDR-LINE(3)` | `X(50)` | 220-269 | 50 | `String` | `CUST-ADDR-LINE-3` (L288) |
| 8 | `EXP-CUST-ADDR-STATE-CD` | `X(02)` | 270-271 | 2 | `String` | `CUST-ADDR-STATE-CD` (L289) |
| 9 | `EXP-CUST-ADDR-COUNTRY-CD` | `X(03)` | 272-274 | 3 | `String` | `CUST-ADDR-COUNTRY-CD` (L290) |
| 10 | `EXP-CUST-ADDR-ZIP` | `X(10)` | 275-284 | 10 | `String` | `CUST-ADDR-ZIP` (L291) |
| 11 | `EXP-CUST-PHONE-NUM(1)` | `X(15)` | 285-299 | 15 | `String` | `CUST-PHONE-NUM-1` (L292) |
| 12 | `EXP-CUST-PHONE-NUM(2)` | `X(15)` | 300-314 | 15 | `String` | `CUST-PHONE-NUM-2` (L293) |
| 13 | `EXP-CUST-SSN` | `9(09)` | 315-323 | 9 | `String` (9 digits; treat as identifier, never arithmetic) | `CUST-SSN` (L294) |
| 14 | `EXP-CUST-GOVT-ISSUED-ID` | `X(20)` | 324-343 | 20 | `String` | `CUST-GOVT-ISSUED-ID` (L295) |
| 15 | `EXP-CUST-DOB-YYYY-MM-DD` | `X(10)` | 344-353 | 10 | `String` (`YYYY-MM-DD`) | `CUST-DOB-YYYY-MM-DD` (L296) |
| 16 | `EXP-CUST-EFT-ACCOUNT-ID` | `X(10)` | 354-363 | 10 | `String` | `CUST-EFT-ACCOUNT-ID` (L297) |
| 17 | `EXP-CUST-PRI-CARD-HOLDER-IND` | `X(01)` | 364 | 1 | `String`/`char` (`Y`/`N`) | `CUST-PRI-CARD-HOLDER-IND` (L298) |
| 18 | `EXP-CUST-FICO-CREDIT-SCORE` | `9(03) COMP-3` | 365-366 | 2 | `int` (0-999) | `CUST-FICO-CREDIT-SCORE` 9(03) display → packed, sign nibble `F` (L299) |
| 19 | `FILLER` | `X(134)` | 367-500 | 134 | — | spaces |

Cross-check against the sample: record 1 payload begins `x'00000001'` (cust id 1) followed by
`IMMANUEL` padded to 25 at 45-69 (the customer in `custdata.txt` line 1 is `Immanuel`; the
EBCDIC fixture was produced from an upper-cased copy of the data). FICO at 365-366 is `x'300F'`.

#### 4.2.3 Payload `A` — `EXPORT-ACCOUNT-DATA` (`CVEXPORT L47-L60`, 108 data bytes + 352 filler)

| # | Field | PIC | Pos | Len | Java type | Source (`CVACT01Y`) via CBEXPORT |
|---|---|---|---|---|---|---|
| 1 | `EXP-ACCT-ID` | `9(11)` | 41-51 | 11 | `long` | `ACCT-ID` (L351) |
| 2 | `EXP-ACCT-ACTIVE-STATUS` | `X(01)` | 52 | 1 | `String`/`char` | `ACCT-ACTIVE-STATUS` (L352) |
| 3 | `EXP-ACCT-CURR-BAL` | `S9(10)V99 COMP-3` | 53-59 | 7 | `BigDecimal(2)` | `ACCT-CURR-BAL` zoned → packed (L353) |
| 4 | `EXP-ACCT-CREDIT-LIMIT` | `S9(10)V99` (zoned) | 60-71 | 12 | `BigDecimal(2)` | `ACCT-CREDIT-LIMIT` (L354); sign overpunch (CBTRN02C §4.7) |
| 5 | `EXP-ACCT-CASH-CREDIT-LIMIT` | `S9(10)V99 COMP-3` | 72-78 | 7 | `BigDecimal(2)` | `ACCT-CASH-CREDIT-LIMIT` (L355) |
| 6 | `EXP-ACCT-OPEN-DATE` | `X(10)` | 79-88 | 10 | `String` | `ACCT-OPEN-DATE` (L356) |
| 7 | `EXP-ACCT-EXPIRAION-DATE` (sic) | `X(10)` | 89-98 | 10 | `String` (`expirationDate`) | `ACCT-EXPIRAION-DATE` (L357) |
| 8 | `EXP-ACCT-REISSUE-DATE` | `X(10)` | 99-108 | 10 | `String` | `ACCT-REISSUE-DATE` (L358) |
| 9 | `EXP-ACCT-CURR-CYC-CREDIT` | `S9(10)V99` (zoned) | 109-120 | 12 | `BigDecimal(2)` | `ACCT-CURR-CYC-CREDIT` (L359) |
| 10 | `EXP-ACCT-CURR-CYC-DEBIT` | `S9(10)V99 COMP` | 121-128 | 8 | `BigDecimal(2)` | `ACCT-CURR-CYC-DEBIT` zoned → binary ×100 (L360) |
| 11 | `EXP-ACCT-ADDR-ZIP` | `X(10)` | 129-138 | 10 | `String` | `ACCT-ADDR-ZIP` (L361) |
| 12 | `EXP-ACCT-GROUP-ID` | `X(10)` | 139-148 | 10 | `String` | `ACCT-GROUP-ID` (L362) |
| 13 | `FILLER` | `X(352)` | 149-500 | 352 | — | spaces |

Note the deliberate mix of three numeric encodings for the five money fields (packed, zoned,
packed, zoned, binary). Cross-check (sample record 51, first `A`): `00000000001` `Y`
`x'0000000000000C'` `00000020200{` `x'0000000102000C'` `2020-10-22` `2025-06-20` `2025-05-20`
`00000000000{` `x'0000000000000000'`. Note the sample fixture was created from a different data
snapshot than `acctdata.txt` (balance 0.00 and open date 2020-10-22 vs 1 940.00 / 2014-11-20);
offsets, not values, are what the cross-check confirms.

#### 4.2.4 Payload `X` — `EXPORT-CARD-XREF-DATA` (`CVEXPORT L84-L88`, 33 data bytes + 427 filler)

| # | Field | PIC | Pos | Len | Java type | Source (`CVACT03Y`) via CBEXPORT |
|---|---|---|---|---|---|---|
| 1 | `EXP-XREF-CARD-NUM` | `X(16)` | 41-56 | 16 | `String` | `XREF-CARD-NUM` (L415) |
| 2 | `EXP-XREF-CUST-ID` | `9(09)` (zoned) | 57-65 | 9 | `long` | `XREF-CUST-ID` (L416) |
| 3 | `EXP-XREF-ACCT-ID` | `9(11) COMP` | 66-73 | 8 | `long` | `XREF-ACCT-ID` zoned → binary (L417) |
| 4 | `FILLER` | `X(427)` | 74-500 | 427 | — | spaces |

Cross-check (sample record 101): `0500024453765740` `000000050` `x'0000000000000032'` (= 50),
matching `cardxref.txt` line 1 `0500024453765740 000000050 00000000050`.

#### 4.2.5 Payload `T` — `EXPORT-TRANSACTION-DATA` (`CVEXPORT L65-L79`, 320 data bytes + 140 filler)

| # | Field | PIC | Pos | Len | Java type | Source (`CVTRA05Y`) via CBEXPORT |
|---|---|---|---|---|---|---|
| 1 | `EXP-TRAN-ID` | `X(16)` | 41-56 | 16 | `String` | `TRAN-ID` (L470) |
| 2 | `EXP-TRAN-TYPE-CD` | `X(02)` | 57-58 | 2 | `String` | `TRAN-TYPE-CD` (L471) |
| 3 | `EXP-TRAN-CAT-CD` | `9(04)` | 59-62 | 4 | `int` | `TRAN-CAT-CD` (L472) |
| 4 | `EXP-TRAN-SOURCE` | `X(10)` | 63-72 | 10 | `String` | `TRAN-SOURCE` (L473) |
| 5 | `EXP-TRAN-DESC` | `X(100)` | 73-172 | 100 | `String` | `TRAN-DESC` (L474) |
| 6 | `EXP-TRAN-AMT` | `S9(09)V99 COMP-3` | 173-178 | 6 | `BigDecimal(2)` | `TRAN-AMT` zoned → packed (L475) |
| 7 | `EXP-TRAN-MERCHANT-ID` | `9(09) COMP` | 179-182 | 4 | `long` | `TRAN-MERCHANT-ID` zoned → binary (L476) |
| 8 | `EXP-TRAN-MERCHANT-NAME` | `X(50)` | 183-232 | 50 | `String` | `TRAN-MERCHANT-NAME` (L477) |
| 9 | `EXP-TRAN-MERCHANT-CITY` | `X(50)` | 233-282 | 50 | `String` | `TRAN-MERCHANT-CITY` (L478) |
| 10 | `EXP-TRAN-MERCHANT-ZIP` | `X(10)` | 283-292 | 10 | `String` | `TRAN-MERCHANT-ZIP` (L479) |
| 11 | `EXP-TRAN-CARD-NUM` | `X(16)` | 293-308 | 16 | `String` | `TRAN-CARD-NUM` (L480) |
| 12 | `EXP-TRAN-ORIG-TS` | `X(26)` | 309-334 | 26 | `String` | `TRAN-ORIG-TS` (L481) |
| 13 | `EXP-TRAN-PROC-TS` | `X(26)` | 335-360 | 26 | `String` | `TRAN-PROC-TS` (L482) |
| 14 | `FILLER` | `X(140)` | 361-500 | 140 | — | spaces |

Cross-check (sample record 151): amount at 173-178 = `x'00000050477C'` = +504.77, merchant id at
179-182 = `x'2FAF0800'` = 800 000 000, timestamps `2022-06-10 19:27:53.000000` at 309-334.

#### 4.2.6 Payload `D` — `EXPORT-CARD-DATA` (`CVEXPORT L93-L100`, 87 data bytes + 373 filler)

| # | Field | PIC | Pos | Len | Java type | Source (`CVACT02Y`) via CBEXPORT |
|---|---|---|---|---|---|---|
| 1 | `EXP-CARD-NUM` | `X(16)` | 41-56 | 16 | `String` | `CARD-NUM` (L535) |
| 2 | `EXP-CARD-ACCT-ID` | `9(11) COMP` | 57-64 | 8 | `long` | `CARD-ACCT-ID` zoned → binary (L536) |
| 3 | `EXP-CARD-CVV-CD` | `9(03) COMP` | 65-66 | 2 | `int` | `CARD-CVV-CD` zoned → binary (L537) |
| 4 | `EXP-CARD-EMBOSSED-NAME` | `X(50)` | 67-116 | 50 | `String` | `CARD-EMBOSSED-NAME` (L538) |
| 5 | `EXP-CARD-EXPIRAION-DATE` (sic) | `X(10)` | 117-126 | 10 | `String` | `CARD-EXPIRAION-DATE` (L539) |
| 6 | `EXP-CARD-ACTIVE-STATUS` | `X(01)` | 127 | 1 | `String`/`char` | `CARD-ACTIVE-STATUS` (L540) |
| 7 | `FILLER` | `X(373)` | 128-500 | 373 | — | spaces |

Cross-check (sample record 451): `0500024453765740` `x'0000000000000032'` `x'02EB'` (= 747)
`Aniya Von…` `2023-03-09` `Y`, matching `carddata.txt` line 1.

#### 4.2.7 Java model

```java
public sealed interface ExportPayload permits CustomerPayload, AccountPayload,
                                             XrefPayload, TransactionPayload, CardPayload {}

public record ExportRecord(ExportRecordType type,      // pos 1
                           String timestamp,           // pos 2-27  (26)
                           int sequenceNum,            // pos 28-31 (binary)
                           String branchId,            // pos 32-35
                           String regionCode,          // pos 36-40
                           ExportPayload payload) {}   // pos 41-500
```

A single `ExportRecordCodec` (`byte[500] encode(ExportRecord)` / `ExportRecord decode(byte[500])`)
lives in a shared module used by both `cbexport` and `cbimport`; §8.2 requires it to round-trip
the sample file byte-for-byte.

### 4.3-4.7 Source record copybooks

* `CVCUS01Y` (customer, 500 bytes) and `CVACT02Y` (card, 150 bytes): fully specified in the read
  utilities spec, `docs/specs/CB-read-utilities-migration-spec.md` §4.4 and §4.2 — do not
  redefine here.
* `CVACT01Y` (account, 300 bytes), `CVACT03Y` (xref, 50 bytes), `CVTRA05Y` (transaction, 350 bytes):
  specified in the CBTRN02C spec §4.4, §4.3, §4.6.

### 4.8 Relational design

CBEXPORT is a *reader* of the masters. In the target architecture the five masters are tables:

* `account`, `card_xref`, `transaction` — **as defined in CBTRN02C spec §4.9**; no new columns.
* `customer`, `card` — new tables, **defined in the read-utilities spec §4.6** (they are the
  natural home for `CVCUS01Y`/`CVACT02Y`); CBEXPORT reuses them.

Optional new audit table for the export itself (not present in COBOL; recommended so that the
"one timestamp / one sequence range per run" can be traced):

```sql
CREATE TABLE export_run (
  run_id            BIGINT        NOT NULL PRIMARY KEY,   -- Spring Batch jobExecutionId
  export_timestamp  CHAR(26)      NOT NULL,
  branch_id         CHAR(4)       NOT NULL,
  region_code       CHAR(5)       NOT NULL,
  customers         INTEGER NOT NULL, accounts INTEGER NOT NULL, xrefs INTEGER NOT NULL,
  transactions      INTEGER NOT NULL, cards    INTEGER NOT NULL, total INTEGER NOT NULL
);
```

---

## 5. Business Rules / Validation

### 5.1 Selection and ordering rules

| # | Rule | Source |
|---|---|---|
| E-1 | All records of all five files are exported; no filtering by status, date, branch or region. | loops L249, L318, L381, L437, L502 |
| E-2 | Record types are emitted in the fixed order `C`, `A`, `X`, `T`, `D`; a type block is never interleaved with another. | L152-L156 |
| E-3 | Within a type, records are in ascending VSAM key order (`CUST-ID`, `ACCT-ID`, `XREF-CARD-NUM`, `TRAN-ID`, `CARD-NUM`), compared as EBCDIC byte strings. For numeric keys this equals numeric order; for `X(16)` keys it is lexicographic. | §2.3 |
| E-4 | `EXPORT-SEQUENCE-NUM` = 1 for the first record written, +1 per record, never reset. The last sequence number equals `WS-TOTAL-RECORDS-EXPORTED`. | L276, L345, L409, L464, L529 |
| E-5 | `EXPORT-BRANCH-ID` = `'0001'`, `EXPORT-REGION-CODE` = `'NORTH'` on every record. | L278-L279 etc. |
| E-6 | No field is transformed; every payload field is a straight `MOVE` (with the implicit COBOL numeric conversions listed in §4.2). | L282-L299, L351-L362, L415-L417, L470-L482, L535-L540 |

### 5.2 Counters

| Counter | PIC | Incremented | Displayed |
|---|---|---|---|
| `WS-CUSTOMER-RECORDS-EXPORTED` | `9(09)` | per `C` record written (L309) | L254, L564 |
| `WS-ACCOUNT-RECORDS-EXPORTED` | `9(09)` | per `A` (L372) | L323, L566 |
| `WS-XREF-RECORDS-EXPORTED` | `9(09)` | per `X` (L427) | L386, L568 |
| `WS-TRAN-RECORDS-EXPORTED` | `9(09)` | per `T` (L492) | L442, L569 |
| `WS-CARD-RECORDS-EXPORTED` | `9(09)` | per `D` (L550) | L507, L571 |
| `WS-TOTAL-RECORDS-EXPORTED` | `9(09)` | per record (L310, L373, L428, L493, L551) | L572 |
| `WS-SEQUENCE-COUNTER` | `9(09)` | before each write | never displayed; equals total |

Counters are incremented only after a successful `WRITE`; on abend the displayed totals are never
printed (no summary on failure).

### 5.3 Timestamp construction (and its latent defect)

```
WS-CURRENT-DATE  ← ACCEPT FROM DATE YYYYMMDD          (L175)   e.g. 20250928
WS-CURRENT-TIME  ← ACCEPT FROM TIME                   (L176)   HHMMSShh, e.g. 22534017
WS-EXPORT-DATE   ← YYYY '-' MM '-' DD                 (L179)   "2025-09-28"          (10)
WS-EXPORT-TIME   ← HH ':' MM ':' SS                   (L185)   "22:53:40"            (8)
WS-FORMATTED-TIMESTAMP ← DATE ' ' TIME '.00'          (L191)   "2025-09-28 22:53:40.00" (22 of 26)
```

`WS-FORMATTED-TIMESTAMP` (L122) has no `VALUE`; `STRING` writes 22 bytes and leaves bytes 23-26
untouched. Enterprise COBOL does not guarantee the initial contents of WORKING-STORAGE without
`VALUE` (it depends on `WSCLEAR`/storage-acquisition behaviour), so the last 4 bytes of every
`EXPORT-TIMESTAMP` are **formally undefined** — typically `x'00'` or `x'40'`. The shipped sample
file, in contrast, contains `2025-09-28 22:53:40.000000` (six zeros), which the current source
cannot produce (it emits `.00` + 4 bytes); the fixture was evidently generated by an earlier
variant of the program.

**Decision: deviate (documented).** The Java job emits the canonical 26-character form
`YYYY-MM-DD HH:MM:SS.000000` (matching both the sample file and the `TRAN-ORIG-TS` format in
CBTRN02C §4.8). `CBIMPORT` never interprets the timestamp, so no consumer is affected, and the
value is deterministic. The time zone is the batch host's local time (`ACCEPT FROM TIME` is local);
the Java job takes a `Clock`/`ZoneId` parameter defaulting to the system zone (§9).

### 5.4 The IDCAMS key-offset defect (`KEYS(4 28)`)

`CBEXPORT.jcl L32` declares the KSDS key at 0-based offset 28 whereas `EXPORT-SEQUENCE-NUM` is at
0-based offset 27 (§4.2.1). The COBOL `RECORD KEY IS EXPORT-SEQUENCE-NUM` (L68) describes offset 27.
On z/OS, `OPEN` compares the COBOL key description with the catalog and a mismatch should yield file
status `'39'` (fixed-attribute conflict) → `'ERROR: Cannot open EXPORT-OUTPUT, Status: 39'` →
abend. That the sample file exists shows the job ran successfully at some point, so either the
catalog definition differed from the checked-in JCL or the runtime tolerated it. Either way the
**intended** key is the sequence number at 1-based 28-31.

**Decision: deviate.** The Java output is a flat 500-byte fixed-record file (no key), ordered by
sequence number; the off-by-one has no equivalent. Documented in §9 as a source defect to report.

### 5.5 Error handling rules

| # | Condition | Action | Message (exact) |
|---|---|---|---|
| X-1 | Any input `OPEN` status ≠ `'00'` | abend | `ERROR: Cannot open <CUSTOMER-INPUT|ACCOUNT-INPUT|XREF-INPUT|TRANSACTION-INPUT|CARD-INPUT>, Status: nn` |
| X-2 | Export `OPEN OUTPUT` status ≠ `'00'` | abend | `ERROR: Cannot open EXPORT-OUTPUT, Status: nn` |
| X-3 | Any input `READ` status ∉ {`'00'`,`'10'`} | abend | `ERROR: Reading <select-name>, Status: nn` |
| X-4 | Export `WRITE` status ≠ `'00'` | abend | `ERROR: Writing export record, Status: nn` |
| X-5 | Abend | `DISPLAY 'CBEXPORT: ABENDING PROGRAM'`, `CALL 'CEE3ABD'` (no args) | see §7.3 |

Files opened before the failure are not closed explicitly (LE closes them at abend). No partial
summary is printed.

---

## 6. Target Spring Batch Design

### 6.1 Module and package layout (proposed)

```
carddemo-batch/
  shared/            com.carddemo.batch.shared.codec      ZonedDecimalCodec, PackedDecimalCodec, BinaryCodec,
                                                          FixedWidthWriter, EbcdicCharsets
                     com.carddemo.batch.shared.export     ExportRecord, ExportPayload*, ExportRecordType,
                                                          ExportRecordCodec   (§4.2 — shared with cbimport)
                     com.carddemo.batch.shared.domain     Customer, Account, CardXref, Transaction, Card (+ repos)
  cbexport/          com.carddemo.batch.cbexport          CbExportJobConfig, ExportRunContext,
                                                          *ToExportRecordProcessor, ExportFileItemWriter,
                                                          ExportSummaryListener
```

### 6.2 Job structure

```
Job cbexportJob  (restartable = false; identifying params: run.id incrementer, optional exportTimestampOverride)
 ├─ Step initExport         (Tasklet)  – build ExportRunContext {timestamp, branchId='0001', regionCode='NORTH', seq=0}
 │                                       store in JobExecutionContext; log start/date/time banners (§7.2)
 │                                     – truncate/create the output file (mirrors IDCAMS DELETE/DEFINE, STEP01)
 ├─ Step exportCustomers    (chunk)    reader: customerReader (ordered by cust_id)      processor: CustomerToExport   writer: exportFileWriter
 ├─ Step exportAccounts     (chunk)    reader: accountReader  (ordered by acct_id)      processor: AccountToExport    writer: exportFileWriter
 ├─ Step exportXrefs        (chunk)    reader: cardXrefReader (ordered by card_num)     processor: XrefToExport       writer: exportFileWriter
 ├─ Step exportTransactions (chunk)    reader: transactionReader (ordered by tran_id)   processor: TransactionToExport writer: exportFileWriter
 ├─ Step exportCards        (chunk)    reader: cardReader     (ordered by card_num)     processor: CardToExport       writer: exportFileWriter
 └─ Step finalizeExport     (Tasklet)  – log the six totals (§7.2), persist export_run row (optional)
```

Steps are strictly sequential (E-2). Each chunk step logs its `'Processing … records'` banner in
`beforeStep` and `'… exported: nnnnnnnnn'` in `afterStep` (`StepExecutionListener`).

### 6.3 Readers

Two interchangeable reader families, selected by profile:

* **RDBMS profile (target state)** — `JdbcPagingItemReader<T>` / `RepositoryItemReader<T>` with
  `ORDER BY <key> ASC`. For `CHAR(16)` keys the collation must be binary/byte-wise (`COLLATE "C"`
  in PostgreSQL, `_bin` in MySQL) to reproduce EBCDIC ordering for digit-only keys (all card and
  transaction ids in the sample are numeric strings, so ASCII vs EBCDIC ordering coincide; see §9).
* **File profile (parity testing)** — `FlatFileItemReader<T>` over the ASCII fixtures
  (`custdata.txt`, `acctdata.txt`, `cardxref.txt`, `carddata.txt`, `dailytran.txt`-style
  transaction master) using the `FixedLengthTokenizer` ranges from the copybook tables. The
  fixtures are already in key order; a defensive in-memory sort by key is applied.

Repository interfaces (only what CBEXPORT needs; the `Customer`/`Card` entities are defined in the
read-utilities spec):

```java
interface CustomerRepository    { Stream<Customer>    streamAllByOrderByCustIdAsc(); }
interface AccountRepository     { Stream<Account>     streamAllByOrderByAcctIdAsc(); }
interface CardXrefRepository    { Stream<CardXref>    streamAllByOrderByCardNumAsc(); }
interface TransactionRepository { Stream<Transaction> streamAllByOrderByTranIdAsc(); }
interface CardRepository        { Stream<Card>        streamAllByOrderByCardNumAsc(); }
```

### 6.4 Processors

One `ItemProcessor<T, ExportRecord>` per type. Each obtains the shared `ExportRunContext` (a
job-scoped bean) and does:

```java
int seq = ctx.nextSequence();                       // AtomicInteger, starts at 0 → first record = 1  (E-4)
return new ExportRecord(type, ctx.timestamp(), seq, "0001", "NORTH", payloadMapper.map(item));
```

Because the sequence is assigned in the processor, chunk size must not change the numbering:
processing is single-threaded and ordered, so `seq` is monotonic in write order. **Do not** use
multi-threaded steps or `AsyncItemProcessor` for this job.

Payload mappers are pure field copies (E-6). The numeric conversions (`String` zoned →
`BigDecimal`/`long` → packed/binary) happen in `ExportRecordCodec.encode`, not in the mappers.

### 6.5 Writer — `ExportFileItemWriter`

Spring Batch's `FlatFileItemWriter` is line-oriented and appends a line separator, and the export
record contains arbitrary binary bytes (`COMP`, `COMP-3`) that may equal `x'0A'`/`x'15'`. Use a
custom `ItemStreamWriter<ExportRecord>` that:

* opens the target with `StandardOpenOption.CREATE, TRUNCATE_EXISTING, WRITE` in `open()`
  (`OPEN OUTPUT` semantics, and STEP01's delete/define);
* writes exactly `ExportRecordCodec.encode(rec)` (500 bytes, **no separator**) per item;
* is shared by the five steps (`@StepScope` is *not* used; one job-scoped instance, opened in
  `initExport`, closed in `finalizeExport`, or registered as an `ItemStream` in every step with
  an idempotent `open`);
* encodes text in the configured record charset (`IBM037` by default for mainframe parity,
  `US-ASCII` for an all-Java estate — §6.8).

### 6.6 Listeners / console output

`ExportSummaryListener implements JobExecutionListener, StepExecutionListener` writes the exact
messages of §7.2 to the job log (`SYSOUT` equivalent). Counters are read from
`StepExecution.getWriteCount()` and summed for the total, so they can never disagree with what was
written (same guarantee as the COBOL "increment after WRITE").

### 6.7 Transactions, restart and fault tolerance

* Readers are read-only against the masters; no DB writes except the optional `export_run` row.
  Chunk size (e.g. 500) affects only memory and read paging.
* **No skip/retry**: any `DataAccessException`, `IOException` or codec exception fails the step
  (COBOL abends on any I/O error, X-1..X-4).
* **Restart is disabled** (`preventRestart()`): the COBOL recreates the output from scratch each
  run, the sequence counter lives in memory, and a partial file is useless to CBIMPORT. A failed
  run is simply re-launched (new `JobInstance`), which truncates and regenerates the file.
* Idempotency: two runs over unchanged data differ only in `EXPORT-TIMESTAMP` (unless
  `exportTimestampOverride` is supplied for golden-file tests — §8.3).

### 6.8 Character-set and numeric encoding configuration

| Property | Default | Meaning |
|---|---|---|
| `cbexport.output.charset` | `IBM037` | charset for `PIC X` fields in the export file. Must equal `cbimport.input.charset` on the consuming side. |
| `cbexport.output.path` | job parameter | destination file (equivalent of `EXPFILE`). |
| `cbexport.branchId` / `cbexport.regionCode` | `0001` / `NORTH` | kept configurable but defaulted to the COBOL constants (E-5). |
| `cbexport.timestampZone` | system default | zone for the run timestamp (§5.3). |

Binary and packed fields are charset-independent. Zoned `S9(n)V99` fields are written with the
EBCDIC overpunch (`x'C0'-x'C9'`, `x'D0'-x'D9'`) when charset is `IBM037` and with the ASCII
equivalents `{A-I`/`}J-R` otherwise (CBTRN02C §4.7).

### 6.9 Observability

Log at `INFO` every banner of §7.2 verbatim (so operators grepping for `CBEXPORT:` find the same
lines), plus Micrometer counters `cbexport.records{type=C|A|X|T|D}`.

---

## 7. Output & Exit Behavior

### 7.1 Export file

* Fixed 500-byte records, no delimiters, exactly `total` records; byte layout per §4.2.
* Record `k` (1-based) has `EXPORT-SEQUENCE-NUM = k`.
* Block boundaries: records `1..nC` are `C`, `nC+1..nC+nA` are `A`, then `X`, `T`, `D`.
* Every payload's unused tail is spaces in the record charset (`x'40'` for IBM037).

### 7.2 Console messages (in emission order, exact text; `n` = 9-digit zero-padded)

```
CBEXPORT: Starting Customer Data Export                 L163
CBEXPORT: Export Date: YYYY-MM-DD                       L168
CBEXPORT: Export Time: HH:MM:SS                         L169
CBEXPORT: Processing customer records                   L245
CBEXPORT: Customers exported: nnnnnnnnn                 L254
CBEXPORT: Processing account records                    L314
CBEXPORT: Accounts exported: nnnnnnnnn                  L323
CBEXPORT: Processing xref records                       L377
CBEXPORT: XRefs exported: nnnnnnnnn                     L386
CBEXPORT: Processing transaction records                L433
CBEXPORT: Transactions exported: nnnnnnnnn              L442
CBEXPORT: Processing card records                       L498
CBEXPORT: Cards exported: nnnnnnnnn                     L507
CBEXPORT: Export completed                              L563
CBEXPORT: Customers Exported: nnnnnnnnn                 L564   (note capital E vs. the per-step line)
CBEXPORT: Accounts Exported: nnnnnnnnn                  L566
CBEXPORT: XRefs Exported: nnnnnnnnn                     L568
CBEXPORT: Transactions Exported: nnnnnnnnn              L569
CBEXPORT: Cards Exported: nnnnnnnnn                     L571
CBEXPORT: Total Records Exported: nnnnnnnnn             L572
```

Note that the per-step banners use `'Customers exported: '` (lower-case e) while the summary uses
`'Customers Exported: '`; replicate exactly. Counter fields are `PIC 9(09)` so `DISPLAY` prints 9
digits with leading zeros (`000000050`). Error messages are listed in §5.5.

### 7.3 Exit status mapping

| COBOL outcome | COBOL result | Spring Batch `ExitStatus` | Process exit code |
|---|---|---|---|
| All files read to EOF, all records written | `GOBACK`, `RETURN-CODE 0` | `COMPLETED` | `0` |
| Any open/read/write failure | `CALL 'CEE3ABD'` without arguments (L579) → LE abend, code not controlled by the program (not `U0999`) | `FAILED` | `8` (non-zero; aligned with CBTRN02C §7.5) |

There is no `RC=4` path; zero records in one or more inputs is a normal completion.

---

## 8. Acceptance Criteria & Test Plan

### 8.1 Fixtures

| Fixture | Use |
|---|---|
| `app/data/ASCII/custdata.txt` (50), `acctdata.txt` (50), `cardxref.txt` (50), `carddata.txt` (50) | source masters for the file profile / DB seed |
| transaction master (350-byte, `CVTRA05Y`) — from `app/data/ASCII/dailytran.txt` shape or produced by CBTRN02C parity run (300 records) | source for `T` records |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.EXPORT.DATA.PS` | **layout oracle** (offsets, encodings, ordering), not a value oracle — it was produced from a different data snapshot (§4.2.3) and has a sequence gap (451-459 absent; last seq 509 with 500 records). |

### 8.2 Unit-level acceptance criteria

| ID | Criterion |
|---|---|
| U-1 | `ExportRecordCodec.decode(encode(r)) == r` for every record of the EBCDIC sample, and `encode(decode(bytes)) == bytes` for all 500 records (byte-for-byte). |
| U-2 | Header: type at pos 1; timestamp pos 2-27; big-endian `int` at 28-31; `0001` at 32-35; `NORTH` at 36-40 (E-5). |
| U-3 | `C` payload offsets per §4.2.2; `EXP-CUST-ID` binary; FICO packed 2 bytes with sign nibble `F` (`300` → `x'300F'`); filler 367-500 spaces. |
| U-4 | `A` payload: `curr_bal` and `cash_credit_limit` packed 7 bytes (`+1020.00` → `x'0000000102000C'`; `-1025.00` → `x'0000000102500D'`); `credit_limit` and `curr_cyc_credit` zoned 12 with overpunch (`+2020.00` → `00000020200{`); `curr_cyc_debit` 8-byte big-endian ×100 (`2525.00` → `x'000000000003DA84'`). |
| U-5 | `X` payload: `cust_id` zoned 9, `acct_id` 8-byte binary (`50` → `x'0000000000000032'`). |
| U-6 | `T` payload: amount packed 6 bytes (`+504.77` → `x'00000050477C'`, `-12.34` → `x'00000001234D'`), merchant id 4-byte binary. |
| U-7 | `D` payload: `acct_id` 8-byte binary, `cvv` 2-byte binary (`747` → `x'02EB'`). |
| U-8 | Sequence numbering: for inputs of sizes (nC,nA,nX,nT,nD) the file has nC+nA+nX+nT+nD records with seq 1..total and type blocks in order C,A,X,T,D (E-2, E-4). Includes the edge cases nA=0 (block absent, numbering continues) and all zero (file has 0 records, totals all zero, exit 0). |
| U-9 | Ordering within a block equals ascending byte-wise key order (E-3), tested with keys that differ only in the last byte and with a numeric `CHAR(16)` key containing letters (documents the EBCDIC vs ASCII caveat, §9). |
| U-10 | Timestamp is `YYYY-MM-DD HH:MM:SS.000000` built from the injected `Clock`; identical on every record of a run (§5.3). |
| U-11 | Console lines match §7.2 exactly, including 9-digit zero padding and the lower/upper-case `exported/Exported` difference. |
| U-12 | Reader failure (simulated `DataAccessException`) after k records → job `FAILED`, exit 8, no summary lines, output file not left in a "complete" state (implementation: write to `<path>.tmp` and rename in `finalizeExport`, or delete on failure). |

### 8.3 End-to-end parity criterion

1. Seed the five tables (or files) from the ASCII fixtures; obtain the EBCDIC output of the COBOL
   program for the **same** data snapshot on the mainframe (the shipped sample is from a different
   snapshot and cannot be used for value parity).
2. Run `cbexportJob` with `exportTimestampOverride` = the COBOL run's timestamp and
   `cbexport.output.charset=IBM037`.
3. `cmp` the two files: identical except for bytes 24-27 of every record (the undefined tail of
   the COBOL timestamp, §5.3), which must be masked by the comparison harness.
4. Console totals identical.

Until a same-snapshot COBOL run is available, the fallback parity check is structural: run the
job against the fixtures and assert the record-type histogram (50/50/50/300/50), the sequence
contiguity, and per-type field-offset equality with the sample for records whose keys coincide
(e.g. card `0500024453765740`).

### 8.4 Non-functional

* 1 M mixed records in < 5 minutes single-threaded on the reference environment (pure streaming;
  memory independent of file size).
* No `double`/`float` anywhere in the codec (static analysis rule).

---

## 9. Assumptions, Risks & Open Questions

### 9.1 Assumptions

* The five masters are available as tables per CBTRN02C §4.9 plus `customer`/`card` per the
  read-utilities spec; the export order is defined by those tables' primary keys.
* The consumer of the file is the Java `CBIMPORT` replacement (or the mainframe `CBIMPORT` during
  coexistence, hence the `IBM037` default).
* `EXPORT-BRANCH-ID`/`EXPORT-REGION-CODE` remain constants for this migration.

### 9.2 Risks

| Risk | Mitigation |
|---|---|
| Undefined bytes 24-27 of the timestamp in the COBOL output make bit-exact parity impossible | Masked comparison (§8.3); Java emits `.000000` (documented deviation §5.3). |
| Key ordering: EBCDIC collates digits after letters, ASCII before. Sample keys are all-digit `X(16)` so orders coincide; alphabetic card/transaction ids would export in a different order on Java with a naive collation | Use byte-wise collation on the `CHAR` key columns, or sort in `IBM037` byte order in the processor for parity runs. |
| `PIC 9(9) COMP` sequence/merchant ids and `9(11) COMP` account ids: COBOL `TRUNC(STD)` truncates to the picture; Java must range-check (`> 999 999 999` → fail fast) rather than silently wrap | Codec validation in `encode`. |
| Binary `COMP` fields make the file unsuitable for line-based tooling (bytes `x'0A'`, `x'0D'`, `x'15'`, `x'25'` may appear) | Custom fixed-length reader/writer (§6.5); never use `FlatFileItemWriter`/`Reader` for `EXPFILE`. |
| `CALL 'CEE3ABD'` without parameters (L579) — abend code is not `U0999`; any Control-M/ops runbook keyed on a specific abend code is unreliable | Java maps to exit 8 and documents it. |
| `KEYS(4 28)` off-by-one in the JCL (§5.4) — the checked-in job is likely to fail with status 39 on a fresh define | Report upstream; irrelevant to the Java flat-file output. |

### 9.3 Open questions

1. Should the Java export **keep** `IBM037` as the default record charset (coexistence with
   mainframe CBIMPORT) or switch to `US-ASCII`/`UTF-8` once both ends are Java? Proposed: property,
   default `IBM037`, flip after cut-over.
2. Time zone for the run timestamp (`ACCEPT FROM TIME` = host local time). Proposed: job parameter,
   default `ZoneId.systemDefault()`.
3. Are `branchId`/`regionCode` to become real job parameters (multi-branch export) or stay
   constants? The COBOL has no filtering logic to migrate either way.
4. Confirm that the CBIMPORT consumer tolerates a `.000000` timestamp tail (it does — it never reads
   the field — but the EBCDIC coexistence partner should be re-tested).
5. Is the `export_run` audit table wanted (not in COBOL)?

---

## Appendix A — Header + payload offset quick reference (1-based, 500 bytes)

```
Header   1 type | 2-27 timestamp(26) | 28-31 seq(bin4) | 32-35 branch | 36-40 region | 41-500 payload(460)

C   41-44 custId(bin4) | 45-69 first | 70-94 middle | 95-119 last | 120-169 addr1 | 170-219 addr2 | 220-269 addr3
    270-271 state | 272-274 country | 275-284 zip | 285-299 phone1 | 300-314 phone2 | 315-323 ssn(zoned9)
    324-343 govtId | 344-353 dob | 354-363 eftAcct | 364 priHolder | 365-366 fico(pack2) | 367-500 filler
A   41-51 acctId(zoned11) | 52 status | 53-59 currBal(pack7) | 60-71 creditLimit(zoned12) | 72-78 cashCreditLimit(pack7)
    79-88 openDate | 89-98 expirationDate | 99-108 reissueDate | 109-120 cycCredit(zoned12) | 121-128 cycDebit(bin8)
    129-138 zip | 139-148 groupId | 149-500 filler
X   41-56 cardNum | 57-65 custId(zoned9) | 66-73 acctId(bin8) | 74-500 filler
T   41-56 tranId | 57-58 typeCd | 59-62 catCd(zoned4) | 63-72 source | 73-172 desc(100) | 173-178 amt(pack6)
    179-182 merchantId(bin4) | 183-232 merchantName | 233-282 merchantCity | 283-292 merchantZip | 293-308 cardNum
    309-334 origTs | 335-360 procTs | 361-500 filler
D   41-56 cardNum | 57-64 acctId(bin8) | 65-66 cvv(bin2) | 67-116 embossedName | 117-126 expirationDate | 127 status
    128-500 filler
```
