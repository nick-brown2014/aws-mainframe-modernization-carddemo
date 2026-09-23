# CB Read Utilities Migration Specification: COBOL Batch to Java + Spring Batch

Covers the four VSAM "read-and-print" utilities in one document with a shared design:

| Program | Source | JCL | Input DD / copybook | Extra outputs | Section |
|---|---|---|---|---|---|
| `CBACT01C` | `app/cbl/CBACT01C.cbl` | `app/jcl/READACCT.jcl` | `ACCTFILE` / `CVACT01Y` (300) | `OUTFILE` (107 FB), `ARRYFILE` (110 FB), `VBRCFILE` (VB 84) + date conversion via `CODATECN`/`COBDATFT` | §A |
| `CBACT02C` | `app/cbl/CBACT02C.cbl` | `app/jcl/READCARD.jcl` | `CARDFILE` / `CVACT02Y` (150) | — | §B |
| `CBACT03C` | `app/cbl/CBACT03C.cbl` | `app/jcl/READXREF.jcl` | `XREFFILE` / `CVACT03Y` (50) | — | §C |
| `CBCUS01C` | `app/cbl/CBCUS01C.cbl` | `app/jcl/READCUST.jcl` | `CUSTFILE` / `CVCUS01Y` (500) | — | §D |

| | |
|---|---|
| Related specs | `docs/specs/CBTRN02C-migration-spec.md` (§4.9 tables `account`, `card_xref`; §4.4/§4.3 layouts `CVACT01Y`, `CVACT03Y`); `docs/specs/CBEXPORT-migration-spec.md`, `docs/specs/CBIMPORT-migration-spec.md` (consumers of the `customer`/`card` tables and `CVCUS01Y`/`CVACT02Y` layouts defined **here**, §4.2/§4.4/§4.6) |
| Sample data | `app/data/ASCII/acctdata.txt` (50 × 300), `carddata.txt` (50 × 150), `cardxref.txt` (50 × 36, trailing spaces stripped), `custdata.txt` (50 × 500) |
| Target | Java 17+, Spring Boot 3.x, Spring Batch 5.x, JDBC/JPA over an RDBMS |
| Status | Draft for review |

The nine template sections are applied once to the **shared** design (sections 1-9 below); the
per-program subsections A-D hold everything program-specific (control flow, layouts, DISPLAY
format, extra outputs, tests). Line references are into the program named in the subsection.

---

## 1. Overview & Purpose

All four programs implement the same pattern: open a VSAM KSDS for sequential input, read every
record in key order, `DISPLAY` it to `SYSOUT`, close, and stop. They exist as smoke tests /
data-dump utilities for the CardDemo masters (`READACCT.jcl L30`: "RUN THE PROGRAM THAT READS THE
ACCOUNT MASTER VSAM FILE"). `CBACT01C` additionally exercises three output-file techniques
(fixed with `COMP-3`, fixed with `OCCURS`, variable-length `RECORDING MODE V`) and an assembler
date-format routine; its outputs are demonstration artefacts, not consumed by any other CardDemo
job.

Migration intent: one reusable **"dump" Spring Batch job template** (`DumpJob<T>`) parameterised
by entity, repository/reader, and line formatter; four concrete job beans; and, for `CBACT01C`
only, three additional fixed/variable-width writers with the defects documented in §A.5
either replicated or fixed as decided there.

---

## 2. Current-State (COBOL) Analysis — shared skeleton

All four programs share this procedure structure (line numbers identical in 02C/03C/CUS01C;
01C differs and is detailed in §A.2):

```
PROCEDURE DIVISION.                                   L70
    DISPLAY 'START OF EXECUTION OF PROGRAM <PGM>'      L71
    PERFORM 0000-<FILE>-OPEN                           L72
    PERFORM UNTIL END-OF-FILE = 'Y'                    L74
        IF END-OF-FILE = 'N'
            PERFORM 1000-<FILE>-GET-NEXT               L76
            IF END-OF-FILE = 'N'
                DISPLAY <RECORD>                       L78
    END-PERFORM
    PERFORM 9000-<FILE>-CLOSE                          L83
    DISPLAY 'END OF EXECUTION OF PROGRAM <PGM>'        L85
    GOBACK                                             L87

1000-<FILE>-GET-NEXT                                   L92
    READ <FILE> INTO <RECORD>                          L93
    IF status = '00' → APPL-RESULT 0 [03C/CUS01C: DISPLAY <RECORD> again, L96 — see §2.2]
    ELSE IF status = '10' → APPL-RESULT 16 ELSE APPL-RESULT 12
    IF APPL-AOK CONTINUE
    ELSE IF APPL-EOF → END-OF-FILE 'Y'
    ELSE DISPLAY 'ERROR READING <FILE>' ; 9910-DISPLAY-IO-STATUS ; 9999-ABEND-PROGRAM    L110-L113

0000-<FILE>-OPEN      L118   OPEN INPUT; status ≠ '00' → 'ERROR OPENING <FILE>' ; 9910 ; 9999
9000-<FILE>-CLOSE     L136   CLOSE;      status ≠ '00' → 'ERROR CLOSING <FILE>' ; 9910 ; 9999
9999-ABEND-PROGRAM    L154   DISPLAY 'ABENDING PROGRAM'; ABCODE=999, TIMING=0; CALL 'CEE3ABD' USING ABCODE, TIMING   → user abend U0999
9910-DISPLAY-IO-STATUS L161  (CUS01C: Z-DISPLAY-IO-STATUS) formats file status as 'FILE STATUS IS: NNNN' + 4 digits
```

### 2.1 `APPL-RESULT` protocol

`APPL-RESULT PIC S9(9) COMP` with `88 APPL-AOK VALUE 0`, `88 APPL-EOF VALUE 16` (L61-L63). Each I/O
paragraph first sets 8 (undefined), then 0 (OK), 16 (EOF) or 12 (error). Only 0 and 16 have
meaning; 12 always leads to `9999-ABEND-PROGRAM`. The Java design keeps only the three outcomes
OK / EOF / FAIL.

### 2.2 The double `DISPLAY` (03C, CUS01C) and `9910` status formatting

* `CBACT03C L96` and `CBCUS01C L96` `DISPLAY` the record **inside** `1000-…-GET-NEXT` on a
  successful read, and the main loop `DISPLAY`s it **again** at L78 — every record is printed
  twice. In `CBACT02C` the inner display is commented out (L96 `*        DISPLAY CARD-RECORD`),
  so each card is printed once. In `CBACT01C` the inner display is the labelled 12-line block
  (§A.2), followed by the raw record from the main loop. **Decision: replicate** (§5 R-4) — the
  dump is the product, and parity testing compares SYSOUT.
* `9910-DISPLAY-IO-STATUS` (L161-L173): if the 2-byte status is numeric and does not start with
  `9`, prints `FILE STATUS IS: NNNN00nn` (status right-justified in 4 digits, e.g. `0035`).
  If it starts with `9` (VSAM extended status; the second byte is a binary return code), prints
  `9` followed by the second byte's binary value as 3 digits (`TWO-BYTES-BINARY` trick, L53-L56),
  e.g. `9` + `x'12'` → `9018`. Output literal is `FILE STATUS IS: NNNN` immediately followed by
  the 4 digits (no space): `FILE STATUS IS: NNNN0035`. Replicate verbatim in the failure log line.

### 2.3 SELECT / FILE STATUS summary

| Program | Select (L29-L33) | DD | Org / access | Record key (FD) | FD split | Status field | Record moved `INTO` |
|---|---|---|---|---|---|---|---|
| CBACT01C | `ACCTFILE-FILE` | `ACCTFILE` | INDEXED / SEQUENTIAL | `FD-ACCT-ID PIC 9(11)` | 11 + `FD-ACCT-DATA X(289)` = 300 | `ACCTFILE-STATUS` | `ACCOUNT-RECORD` (`CVACT01Y`) |
| CBACT02C | `CARDFILE-FILE` | `CARDFILE` | INDEXED / SEQUENTIAL | `FD-CARD-NUM PIC X(16)` | 16 + `FD-CARD-DATA X(134)` = 150 | `CARDFILE-STATUS` | `CARD-RECORD` (`CVACT02Y`) |
| CBACT03C | `XREFFILE-FILE` | `XREFFILE` | INDEXED / SEQUENTIAL | `FD-XREF-CARD-NUM PIC X(16)` | 16 + `FD-XREF-DATA X(34)` = 50 | `XREFFILE-STATUS` | `CARD-XREF-RECORD` (`CVACT03Y`) |
| CBCUS01C | `CUSTFILE-FILE` | `CUSTFILE` | INDEXED / SEQUENTIAL | `FD-CUST-ID PIC 9(09)` | 9 + `FD-CUST-DATA X(491)` = 500 | `CUSTFILE-STATUS` | `CUSTOMER-RECORD` (`CVCUS01Y`) |

Status handling is identical everywhere: `'00'` OK, `'10'` EOF, anything else fatal (including
`'04'`, `'02'`, `'97'`); `OPEN` and `CLOSE` accept only `'00'`. `READ … INTO` copies the FD area
into the WORKING-STORAGE copybook record; the FD key field is never referenced after the read.

---

## 3. Files / Datasets

| Program | DD | Dataset (JCL lines) | Org | LRECL | Key | Copybook | Dir |
|---|---|---|---|---|---|---|---|
| CBACT01C | `ACCTFILE` | `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` (`READACCT.jcl L35-L36`) | KSDS | 300 | `ACCT-ID` 9(11) @1 | `CVACT01Y` | in |
| CBACT01C | `OUTFILE` | `AWS.M2.CARDDEMO.ACCTDATA.PSCOMP` (L37-L40) `DISP=(NEW,CATLG,DELETE)`, `LRECL=107,RECFM=FB` | PS | 107 | — | inline FD L56-L69 | out |
| CBACT01C | `ARRYFILE` | `AWS.M2.CARDDEMO.ACCTDATA.ARRYPS` (L41-L44) `LRECL=110,RECFM=FB` | PS | 110 | — | inline FD L71-L78 | out |
| CBACT01C | `VBRCFILE` | `AWS.M2.CARDDEMO.ACCTDATA.VBPS` (L45-L48) `LRECL=84,RECFM=VB` | PS | 10-80 data + 4-byte RDW | — | inline FD L80-L85 | out |
| CBACT01C | (PREDEL step, `IEFBR14`, L22-L28) | the three output datasets `DISP=(MOD,DELETE,DELETE)` | — | — | — | — | pre-clean |
| CBACT02C | `CARDFILE` | `AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS` (`READCARD.jcl L25-L26`) | KSDS | 150 | `CARD-NUM` X(16) @1 | `CVACT02Y` | in |
| CBACT03C | `XREFFILE` | `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` (`READXREF.jcl L25-L26`) | KSDS | 50 | `XREF-CARD-NUM` X(16) @1 | `CVACT03Y` | in |
| CBCUS01C | `CUSTFILE` | `AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS` (`READCUST.jcl L24-L25`) | KSDS | 500 | `CUST-ID` 9(09) @1 | `CVCUS01Y` | in |
| all | `SYSOUT`, `SYSPRINT` | `SYSOUT=*` | — | — | — | — | console |

Each job is a single `STEP05 EXEC PGM=<program>` with `STEPLIB AWS.M2.CARDDEMO.LOADLIB`
(`CBACT01C` also needs `COBDATFT` from that library).

---

## 4. Data Model & Field Mappings

Type-mapping conventions: CBTRN02C spec §4.1 and CBEXPORT spec §4.1 (packed/binary). Offsets are
1-based within the record.

### 4.1 `CVACT01Y` — account (300 bytes) — CBACT01C input

Defined in CBTRN02C spec §4.4; summary for offsets used below:
`ACCT-ID 9(11) 1-11 | ACCT-ACTIVE-STATUS X 12 | ACCT-CURR-BAL S9(10)V99 13-24 | ACCT-CREDIT-LIMIT 25-36 |
ACCT-CASH-CREDIT-LIMIT 37-48 | ACCT-OPEN-DATE X(10) 49-58 | ACCT-EXPIRAION-DATE 59-68 | ACCT-REISSUE-DATE 69-78 |
ACCT-CURR-CYC-CREDIT 79-90 | ACCT-CURR-CYC-DEBIT 91-102 | ACCT-ADDR-ZIP X(10) 103-112 | ACCT-GROUP-ID X(10) 113-122 | FILLER X(178) 123-300`.
Fixture check (`acctdata.txt` L1): `00000000001|Y|00000001940{|00000020200{|00000010200{|2014-11-20|2025-05-20|2025-05-20|00000000000{|00000000000{|A000000000|…` → balance 1 940.00, credit limit 2 020.00, cash limit 1 020.00, both cycle amounts 0.

### 4.2 `CVACT02Y` — card (150 bytes) — CBACT02C input (authoritative definition)

Source `app/cpy/CVACT02Y.cpy`.

| # | Field | PIC | Pos | Len | Java type | Notes |
|---|---|---|---|---|---|---|
| 1 | `CARD-NUM` | `X(16)` | 1-16 | 16 | `String` | primary key; digits in fixtures but alphanumeric by picture |
| 2 | `CARD-ACCT-ID` | `9(11)` | 17-27 | 11 | `long` | zoned unsigned |
| 3 | `CARD-CVV-CD` | `9(03)` | 28-30 | 3 | `int` (0-999) | keep leading zeros when serialising |
| 4 | `CARD-EMBOSSED-NAME` | `X(50)` | 31-80 | 50 | `String` | space padded |
| 5 | `CARD-EXPIRAION-DATE` (sic) | `X(10)` | 81-90 | 10 | `String` (`expirationDate`, `YYYY-MM-DD`) | keep raw string; typo preserved only in mapping notes |
| 6 | `CARD-ACTIVE-STATUS` | `X(01)` | 91 | 1 | `String`/`char` (`Y`/`N`) | |
| 7 | `FILLER` | `X(59)` | 92-150 | 59 | — | spaces |

Fixture check (`carddata.txt` L1, 150 chars): `0500024453765740|00000000050|747|Aniya Von…(50)|2023-03-09|Y|59 spaces`.

### 4.3 `CVACT03Y` — card cross-reference (50 bytes) — CBACT03C input

Defined in CBTRN02C spec §4.3: `XREF-CARD-NUM X(16) 1-16 | XREF-CUST-ID 9(09) 17-25 | XREF-ACCT-ID 9(11) 26-36 | FILLER X(14) 37-50`.
Fixture (`cardxref.txt` L1) is 36 chars — the 14-byte filler was stripped when the fixture was
exported; readers of the fixture must right-pad to 50.

### 4.4 `CVCUS01Y` — customer (500 bytes) — CBCUS01C input (authoritative definition)

Source `app/cpy/CVCUS01Y.cpy`.

| # | Field | PIC | Pos | Len | Java type | Notes |
|---|---|---|---|---|---|---|
| 1 | `CUST-ID` | `9(09)` | 1-9 | 9 | `long` | primary key |
| 2 | `CUST-FIRST-NAME` | `X(25)` | 10-34 | 25 | `String` | |
| 3 | `CUST-MIDDLE-NAME` | `X(25)` | 35-59 | 25 | `String` | |
| 4 | `CUST-LAST-NAME` | `X(25)` | 60-84 | 25 | `String` | |
| 5 | `CUST-ADDR-LINE-1` | `X(50)` | 85-134 | 50 | `String` | |
| 6 | `CUST-ADDR-LINE-2` | `X(50)` | 135-184 | 50 | `String` | |
| 7 | `CUST-ADDR-LINE-3` | `X(50)` | 185-234 | 50 | `String` | |
| 8 | `CUST-ADDR-STATE-CD` | `X(02)` | 235-236 | 2 | `String` | |
| 9 | `CUST-ADDR-COUNTRY-CD` | `X(03)` | 237-239 | 3 | `String` | |
| 10 | `CUST-ADDR-ZIP` | `X(10)` | 240-249 | 10 | `String` | |
| 11 | `CUST-PHONE-NUM-1` | `X(15)` | 250-264 | 15 | `String` | e.g. `(516)123-4567` |
| 12 | `CUST-PHONE-NUM-2` | `X(15)` | 265-279 | 15 | `String` | |
| 13 | `CUST-SSN` | `9(09)` | 280-288 | 9 | `String` (9 digits) | PII; identifier, never arithmetic; keep leading zeros |
| 14 | `CUST-GOVT-ISSUED-ID` | `X(20)` | 289-308 | 20 | `String` | |
| 15 | `CUST-DOB-YYYY-MM-DD` | `X(10)` | 309-318 | 10 | `String` | raw `YYYY-MM-DD` |
| 16 | `CUST-EFT-ACCOUNT-ID` | `X(10)` | 319-328 | 10 | `String` | |
| 17 | `CUST-PRI-CARD-HOLDER-IND` | `X(01)` | 329 | 1 | `String`/`char` | |
| 18 | `CUST-FICO-CREDIT-SCORE` | `9(03)` | 330-332 | 3 | `int` | |
| 19 | `FILLER` | `X(168)` | 333-500 | 168 | — | spaces |

Fixture check (`custdata.txt` L1): `000000001|Immanuel|Madeline|Kessler|618 Deshaun Route|Apt. 8…`; line length 500.

### 4.5 `CODATECN` — date-conversion parameter block (CBACT01C)

Source `app/cpy/CODATECN.cpy` L17-L52; 80 bytes total, passed `BY REFERENCE` to `COBDATFT`.

| Field | PIC | Pos | Meaning |
|---|---|---|---|
| `CODATECN-TYPE` | `X` | 1 | input format: `'1'` = `YYYYMMDD` (`88 YYYYMMDD-IN`), `'2'` = `YYYY-MM-DD` (`88 YYYY-MM-DD-IN`) |
| `CODATECN-INP-DATE` | `X(20)` | 2-21 | input date, left-justified |
| `CODATECN-OUTTYPE` | `X` | 22 | output format: `'1'` = `YYYY-MM-DD`, `'2'` = `YYYYMMDD` |
| `CODATECN-0UT-DATE` (sic, digit zero) | `X(20)` | 23-42 | output date |
| `CODATECN-ERROR-MSG` | `X(38)` | 43-80 | `'INVALID INPUT'` on error, otherwise untouched |

### 4.6 Relational design

Tables `account` and `card_xref`: **as CBTRN02C spec §4.9**, unchanged. New tables (authoritative
here; reused by CBEXPORT/CBIMPORT):

```sql
CREATE TABLE customer (
  cust_id              NUMERIC(9)  NOT NULL PRIMARY KEY,
  first_name           CHAR(25)    NOT NULL,
  middle_name          CHAR(25)    NOT NULL,
  last_name            CHAR(25)    NOT NULL,
  addr_line_1          CHAR(50)    NOT NULL,
  addr_line_2          CHAR(50)    NOT NULL,
  addr_line_3          CHAR(50)    NOT NULL,
  addr_state_cd        CHAR(2)     NOT NULL,
  addr_country_cd      CHAR(3)     NOT NULL,
  addr_zip             CHAR(10)    NOT NULL,
  phone_num_1          CHAR(15)    NOT NULL,
  phone_num_2          CHAR(15)    NOT NULL,
  ssn                  CHAR(9)     NOT NULL,        -- PII: encrypt at rest / mask in dumps (§9)
  govt_issued_id       CHAR(20)    NOT NULL,
  dob                  CHAR(10)    NOT NULL,        -- raw YYYY-MM-DD; not DATE, for byte parity
  eft_account_id       CHAR(10)    NOT NULL,
  pri_card_holder_ind  CHAR(1)     NOT NULL,
  fico_credit_score    NUMERIC(3)  NOT NULL
);

CREATE TABLE card (
  card_num             CHAR(16)    NOT NULL PRIMARY KEY,
  acct_id              NUMERIC(11) NOT NULL REFERENCES account(acct_id),
  cvv_cd               NUMERIC(3)  NOT NULL,        -- sensitive: see §9
  embossed_name        CHAR(50)    NOT NULL,
  expiration_date      CHAR(10)    NOT NULL,        -- raw YYYY-MM-DD
  active_status        CHAR(1)     NOT NULL
);
```

`CHAR(n)` (blank padded) is chosen deliberately so the fixed-width serialisers can regenerate the
copybook records byte-for-byte. `CHAR` key columns need byte-wise collation for key-order parity
(CBEXPORT spec §6.3). Filler areas are not stored; they are regenerated as spaces.

The three `CBACT01C` output files are not modelled as tables (they are demonstration artefacts,
§A); they remain files.

---

## 5. Business Rules / Validation — shared

| # | Rule | Source |
|---|---|---|
| R-1 | Every record is read and printed; no filtering, no transformation of the input. | main loops L74-L81 |
| R-2 | Records are processed in ascending key order (sequential KSDS read). | §2.3 |
| R-3 | `'10'` on `READ` ends the loop normally; any other non-`'00'` status on `OPEN`/`READ`/`CLOSE` is fatal (`U0999`). Zero records is a normal run (start line, end line, RC 0). | L93-L113, L118-L152 |
| R-4 | Print format is the raw record image (`DISPLAY <RECORD>`), one line per `DISPLAY`, in the record's character set; for 03C and CUS01C each record appears twice (§2.2). | L78, L96 |
| R-5 | The program has no `RETURN-CODE` manipulation: success is RC 0, failure is user abend `U0999` (`CEE3ABD` with `ABCODE=999`, `TIMING=0`, L154-L158). | |

`CBACT01C`-specific rules and defects: §A.5.

---

## 6. Target Spring Batch Design — shared "dump" job

### 6.1 Structure

```
carddemo-batch/
  shared/   com.carddemo.batch.shared.domain    Account, Card, CardXref, Customer  (+ FixedWidthSerializer<T> each, from §4)
            com.carddemo.batch.shared.codec     ZonedDecimalCodec, PackedDecimalCodec, FixedWidthWriter, Rdw
  dump/     com.carddemo.batch.dump             DumpJobFactory<T>, RecordImageFormatter<T>, DumpLineWriter,
                                                DumpJobListener, acct/{Cbact01cJobConfig, ...} card/… xref/… cust/…
```

`DumpJobFactory.create(name, ItemReader<T>, RecordImageFormatter<T>, int repeatCount)` builds:

```
Job <pgm>Job  (e.g. cbact02cJob)              restartable=false
 └─ Step dump<Entity>   (chunk, size 100)
      reader     : orderedReader<T>            (§6.2)
      processor  : none  (or identity)
      writer     : DumpLineWriter<T>           (§6.3) — writes formatter.format(item) repeatCount times
      listeners  : DumpJobListener → 'START OF EXECUTION OF PROGRAM <PGM>' before, 'END OF EXECUTION OF PROGRAM <PGM>' after (COMPLETED only)
```

`repeatCount` = 2 for `cbact03cJob` and `cbcus01cJob`, 1 for `cbact02cJob` (R-4);
`cbact01cJob` uses a composite writer (§A.6).

### 6.2 Readers

* **RDBMS profile:** `JdbcCursorItemReader<T>` / `RepositoryItemReader<T>` with `ORDER BY <pk>`
  and byte-wise collation. Repository interfaces (reads only):

  ```java
  interface AccountRepository  { Stream<Account>  streamAllByOrderByAcctIdAsc(); }   // CBTRN02C §6.6 entity
  interface CardRepository     { Stream<Card>     streamAllByOrderByCardNumAsc(); }
  interface CardXrefRepository { Stream<CardXref> streamAllByOrderByCardNumAsc(); }
  interface CustomerRepository { Stream<Customer> streamAllByOrderByCustIdAsc(); }
  ```

* **File profile (parity / pre-migration):** `FixedLengthRecordItemReader<T>` over a
  fixed-length binary file (EBCDIC or ASCII, configurable) or a `FlatFileItemReader` with
  `FixedLengthTokenizer` over the line-delimited ASCII fixtures (right-padding short lines such
  as `cardxref.txt` to LRECL, `strict=false`).

### 6.3 Writer — `DumpLineWriter<T>` and `RecordImageFormatter<T>`

`RecordImageFormatter<T>.format(T)` returns the **exact copybook record image** as a `String`
(the `FixedWidthSerializer<T>` output decoded in the output charset) — the equivalent of
`DISPLAY <RECORD>`: fixed width, trailing spaces preserved, zoned numerics shown with their
overpunch character (e.g. `00000001940{`). `DumpLineWriter` appends one line (`\n`) per image to
the configured sink: `System.out` (SYSOUT parity), a `Logger`, or a file (`--dump.output.path`).
Line length = LRECL exactly; nothing is trimmed.

### 6.4 Exit statuses

| COBOL | Spring Batch | Exit code |
|---|---|---|
| RC 0 (also for 0 records) | `COMPLETED` | 0 |
| `U0999` (open/read/close error) | `FAILED`; log `ERROR <verb>ING <FILE>` + `FILE STATUS IS: NNNNnnnn` analogue + `ABENDING PROGRAM` | 8 |

### 6.5 Transactions / restart / fault tolerance

Read-only, no skip/retry, restart disabled (a rerun regenerates the whole dump; `CBACT01C`'s
`PREDEL` step is mirrored by truncating its three output files at job start). Chunk size affects
nothing observable.

### 6.6 Configuration

| Property | Default | Meaning |
|---|---|---|
| `dump.source` | `db` | `db` or `file` |
| `dump.input.path` / `dump.input.charset` | — / `IBM037` | file profile |
| `dump.output.sink` | `stdout` | `stdout`, `log`, `file` |
| `dump.output.charset` | `UTF-8` | charset of the printed lines (SYSOUT parity comparisons convert both sides to this) |
| `dump.repeatCount` | per job | override of R-4 duplication (set 1 to "fix" the double print; default keeps parity) |

---

## 7. Output & Exit Behavior — shared console format

For `CBACT02C` (once per record), `CBACT03C`/`CBCUS01C` (twice per record):

```
START OF EXECUTION OF PROGRAM <PGM>
<record image, LRECL chars>
[<record image again>]
...
END OF EXECUTION OF PROGRAM <PGM>
```

Failure:

```
ERROR OPENING <CARDFILE|XREFFILE|CUSTFILE|ACCTFILE>          (or 'ERROR READING <CARDFILE|XREFFILE|CUSTOMER FILE|ACCOUNT FILE>' / 'ERROR CLOSING <CARDFILE|XREFFILE|CUSTOMER FILE|ACCOUNT FILE>')
FILE STATUS IS: NNNN0035
ABENDING PROGRAM
→ U0999
```

Exact per-program literals: §B.3, §C.3, §D.3, §A.7.

---

## 8. Acceptance Criteria & Test Plan — shared

| ID | Criterion |
|---|---|
| S-1 | `FixedWidthSerializer<Card>`/`<Customer>` round-trip every line of `carddata.txt`/`custdata.txt` (`serialize(parse(line)) == line`, 150/500 bytes); `<CardXref>` round-trips `cardxref.txt` lines right-padded to 50; `<Account>` round-trips `acctdata.txt` (300). |
| S-2 | `cbact02cJob` over the 50-card fixture prints exactly 52 lines: start line, 50 images (each 150 chars, equal to the fixture lines), end line. |
| S-3 | `cbact03cJob` prints 1 + 100 + 1 lines, each image 50 chars, records in pairs; `cbcus01cJob` prints 1 + 100 + 1, images 500 chars. |
| S-4 | Ordering: images appear in ascending key order even if the source is shuffled (byte-wise compare on the key). |
| S-5 | Empty source → exactly the start and end lines, exit 0. |
| S-6 | Reader exception → `FAILED`, exit 8, end line **not** printed, failure lines per §7. |
| S-7 | Golden-file parity: SYSOUT of the COBOL job (EBCDIC → UTF-8) `diff`s clean against the Java output for the same data (masking nothing; these programs print no timestamps). |

`CBACT01C`-specific criteria: §A.8.

---

## 9. Assumptions, Risks & Open Questions — shared

* **PII in dumps:** `CBCUS01C` prints SSN, DOB and government id; `CBACT02C` prints CVV. The COBOL
  does this to SYSOUT. Proposed: keep for parity in non-production, add a `dump.maskPii=true`
  option that replaces SSN/CVV/govt-id with `*` in production (open question Q1).
* Double printing in 03C/CUS01C is almost certainly a leftover debug line; replicated by default
  (`repeatCount`), one flag away from fixing (Q2).
* Key collation (EBCDIC vs ASCII) — same caveat as CBEXPORT spec §9.2; fixture keys are all-digit.
* Questions: Q1 mask PII? Q2 keep double print? Q3 are `OUTFILE`/`ARRYFILE`/`VBRCFILE` consumed by
  anything outside the repo (nothing in `app/` reads `ACCTDATA.PSCOMP/ARRYPS/VBPS`)? If not, §A's
  extra writers could be dropped entirely rather than migrated.

---

# A. `CBACT01C` — account dump with derived files

## A.1 Purpose

Reads `ACCTFILE` (`CVACT01Y`), prints each account twice (labelled block + raw image), and writes
each account to three demonstration files: `OUTFILE` (107-byte fixed record with one `COMP-3`
field and a reformatted reissue date), `ARRYFILE` (110-byte record with a 5-occurrence
balance array, mostly hard-coded values) and `VBRCFILE` (two variable-length records per
account, 12 and 39 bytes). Source header: "READ THE ACCOUNT FILE AND WRITE INTO FILES" (L5).

## A.2 Control flow and paragraphs

```
PROCEDURE DIVISION                                 L139
  DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C' L141
  0000-ACCTFILE-OPEN   L142 (L317-L332)   OPEN INPUT  ACCTFILE   ; ≠'00' → 'ERROR OPENING ACCTFILE'      9910 9999
  2000-OUTFILE-OPEN    L143 (L334-L350)   OPEN OUTPUT OUT-FILE   ; ≠'00' → 'ERROR OPENING OUTFILE'  nn   9910 9999
  3000-ARRFILE-OPEN    L144 (L352-L368)   OPEN OUTPUT ARRY-FILE  ; ≠'00' → 'ERROR OPENING ARRAYFILE' nn  9910 9999
  4000-VBRFILE-OPEN    L145 (L370-L386)   OPEN OUTPUT VBRC-FILE  ; ≠'00' → 'ERROR OPENING VBRC FILE' nn  9910 9999
  PERFORM UNTIL END-OF-FILE = 'Y'                  L147
      1000-ACCTFILE-GET-NEXT                       L149 (L165-L197)
      IF END-OF-FILE = 'N' DISPLAY ACCOUNT-RECORD  L151        ← raw 300-byte image
  9000-ACCTFILE-CLOSE  L156 (L388-L404)   CLOSE ACCTFILE ; ≠'00' → 'ERROR CLOSING ACCOUNT FILE' 9910 9999
  DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'   L158
  GOBACK                                           L160

1000-ACCTFILE-GET-NEXT                              L165
  READ ACCTFILE-FILE INTO ACCOUNT-RECORD            L166
  IF '00':
     INITIALIZE ARR-ARRAY-REC                       L169   (all numerics → 0, filler → spaces)
     1100-DISPLAY-ACCT-RECORD                       L170   12 labelled lines (§A.7)
     1300-POPUL-ACCT-RECORD                         L171   build OUT-ACCT-REC incl. COBDATFT call (§A.3)
     1350-WRITE-ACCT-RECORD                         L172   WRITE OUT-ACCT-REC ; status ∉{'00','10'} → 'ACCOUNT FILE WRITE STATUS IS:' nn 9910 9999
     1400-POPUL-ARRAY-RECORD                        L173   (§A.4)
     1450-WRITE-ARRY-RECORD                         L174   WRITE ARR-ARRAY-REC ; same check, same message text
     INITIALIZE VBRC-REC1                           L175   (REC2 is not initialised — fully overwritten anyway)
     1500-POPUL-VBRC-RECORD                         L176   (§A.5) + DISPLAY 'VBRC-REC1:' … / 'VBRC-REC2:' …
     1550-WRITE-VB1-RECORD                          L177   length 12
     1575-WRITE-VB2-RECORD                          L178   length 39
  ELSE '10' → APPL-RESULT 16 (EOF) ; other → 12 → 'ERROR READING ACCOUNT FILE' 9910 9999   L180-L196
```

**Note the three output files are never `CLOSE`d** (only `9000-ACCTFILE-CLOSE` exists, L388). The
run unit's implicit close at `GOBACK` flushes them, so no data is lost, but a close error would be
invisible. Java: close explicitly (deviation without observable difference).

`9999-ABEND-PROGRAM` (L406-L410): `'ABENDING PROGRAM'`, `ABCODE=999`, `TIMING=0`,
`CALL 'CEE3ABD' USING ABCODE, TIMING` → `U0999`. `9910-DISPLAY-IO-STATUS` (L413-L425) as §2.2.

## A.3 `OUTFILE` — `OUT-ACCT-REC` (107 bytes, FD L56-L69), populated by `1300` (L215-L240)

| # | Field | PIC | Pos | Len | Java type | Source / rule |
|---|---|---|---|---|---|---|
| 1 | `OUT-ACCT-ID` | `9(11)` | 1-11 | 11 | `long` | `ACCT-ID` (L216) |
| 2 | `OUT-ACCT-ACTIVE-STATUS` | `X(01)` | 12 | 1 | `String` | `ACCT-ACTIVE-STATUS` (L217) |
| 3 | `OUT-ACCT-CURR-BAL` | `S9(10)V99` | 13-24 | 12 | `BigDecimal(2)` | `ACCT-CURR-BAL` (L218), zoned overpunch |
| 4 | `OUT-ACCT-CREDIT-LIMIT` | `S9(10)V99` | 25-36 | 12 | `BigDecimal(2)` | `ACCT-CREDIT-LIMIT` (L219) |
| 5 | `OUT-ACCT-CASH-CREDIT-LIMIT` | `S9(10)V99` | 37-48 | 12 | `BigDecimal(2)` | `ACCT-CASH-CREDIT-LIMIT` (L220) |
| 6 | `OUT-ACCT-OPEN-DATE` | `X(10)` | 49-58 | 10 | `String` | `ACCT-OPEN-DATE` (L221) |
| 7 | `OUT-ACCT-EXPIRAION-DATE` | `X(10)` | 59-68 | 10 | `String` | `ACCT-EXPIRAION-DATE` (L222) |
| 8 | `OUT-ACCT-REISSUE-DATE` | `X(10)` | 69-78 | 10 | `String` | `CODATECN-0UT-DATE(1:10)` after `COBDATFT` (L223-L233): `YYYYMMDD` + 2 bytes (§A.3.1) |
| 9 | `OUT-ACCT-CURR-CYC-CREDIT` | `S9(10)V99` | 79-90 | 12 | `BigDecimal(2)` | `ACCT-CURR-CYC-CREDIT` (L235) |
| 10 | `OUT-ACCT-CURR-CYC-DEBIT` | `S9(10)V99 COMP-3` | 91-97 | 7 | `BigDecimal(2)` | **only** `IF ACCT-CURR-CYC-DEBIT = 0 → 2525.00` (L236-L238); otherwise **not assigned** (§A.5 D-1) |
| 11 | `OUT-ACCT-GROUP-ID` | `X(10)` | 98-107 | 10 | `String` | `ACCT-GROUP-ID` (L239) |

`ACCT-ADDR-ZIP` is **not** carried to `OUTFILE` (no field for it; §A.5 D-2). `OUT-ACCT-REC` is an
FD record area that is never `INITIALIZE`d.

### A.3.1 Reissue-date conversion via `COBDATFT` (`app/asm/COBDATFT.asm` L30-L57)

```
MOVE ACCT-REISSUE-DATE TO CODATECN-INP-DATE, WS-REISSUE-DATE   L223-L224   ("YYYY-MM-DD" + 10 spaces)
MOVE '2' TO CODATECN-TYPE        (input is YYYY-MM-DD)          L225
MOVE '2' TO CODATECN-OUTTYPE     (output YYYYMMDD)              L226
CALL 'COBDATFT' USING CODATECN-REC                              L231
MOVE CODATECN-0UT-DATE TO OUT-ACCT-REISSUE-DATE                 L233   (X(20) → X(10): first 10 bytes)
```

Assembler behaviour, exhaustively:

| `TYPE` | `OUTTYPE` | Action (asm lines) | Output bytes 1-10 |
|---|---|---|---|
| `'1'` | `'1'` | if `INP(5:1) = '-'` → error; else `OUT(1:4)=INP(1:4)`, `OUT(5)='-'`, `OUT(6:2)=INP(5:2)`, `OUT(8)='-'`, `OUT(9:2)=INP(7:2)` (L36-L45) | `YYYY-MM-DD` |
| `'1'` | `'2'` | error (L38-L39) | untouched |
| `'2'` | `'1'` | error (L49-L50) | untouched |
| `'2'` | `'2'` | **no input validation** (the `CLC INP+4,'-'` check is commented out, L47-L48); `OUT(1:4)=INP(1:4)`, `OUT(5:2)=INP(6:2)`, `OUT(7:2)=INP(9:2)` (L51-L53) | `YYYYMMDD` + bytes 9-10 of `CODATECN-0UT-DATE` **unchanged** |
| other | any | error (L34) | untouched |
| error path | | `MVC COERMSG,=C'INVALID INPUT'` (L56); no return code; output untouched | |

Consequences for `CBACT01C` (`2`/`2` path):

* Positional slice: `2025-05-20` → `20250520`. Any 10-char input is sliced the same way with
  **no validation** — `2025/05/20` → `20250520`, `20250520  ` → `2025052` + `0` + space… i.e.
  `2025` `05` `  ` → `202505  `; spaces → 8 spaces. The COBOL never inspects
  `CODATECN-ERROR-MSG` (and the `2`/`2` path cannot set it anyway).
* Bytes 9-10 of the output field are never written by the assembler, and `CODATECN-0UT-DATE` has
  no `VALUE` clause, so `OUT-ACCT-REISSUE-DATE(9:2)` is **undefined** (typically `x'00'` or
  `x'40'` depending on the runtime's WORKING-STORAGE initialisation; constant across records
  within a run).

**Decision: deviate to a deterministic equivalent.** Java: `reissueDateYyyymmdd = in.substring(0,4)
+ in.substring(5,7) + in.substring(8,10)`, padded with **two spaces** to 10 (documented; parity
harness masks bytes 77-78). No validation (replicates the slice semantics exactly for any
10-character input; a shorter input is right-padded to 10 first, as COBOL's `MOVE` to `X(20)`
does). Provide the general `CodatecnDateFormatter` implementing the whole table above so the
other three legs are available and unit-tested (§A.8 T-4) even though only `2/2` is used.

## A.4 `ARRYFILE` — `ARR-ARRAY-REC` (110 bytes, FD L71-L78), populated by `1400` (L253-L261)

| # | Field | PIC | Pos | Len | Java | Value |
|---|---|---|---|---|---|---|
| 1 | `ARR-ACCT-ID` | `9(11)` | 1-11 | 11 | `long` | `ACCT-ID` (L254) |
| 2 | `ARR-ACCT-CURR-BAL(1)` | `S9(10)V99` | 12-23 | 12 | `BigDecimal` | `ACCT-CURR-BAL` (L255) |
| 3 | `ARR-ACCT-CURR-CYC-DEBIT(1)` | `S9(10)V99 COMP-3` | 24-30 | 7 | `BigDecimal` | constant **1005.00** (L256) → `x'0000000100500C'` |
| 4 | `ARR-ACCT-CURR-BAL(2)` | `S9(10)V99` | 31-42 | 12 | | `ACCT-CURR-BAL` (L257) |
| 5 | `ARR-ACCT-CURR-CYC-DEBIT(2)` | `COMP-3` | 43-49 | 7 | | constant **1525.00** (L258) → `x'0000000152500C'` |
| 6 | `ARR-ACCT-CURR-BAL(3)` | `S9(10)V99` | 50-61 | 12 | | constant **−1025.00** (L259) → `00000010250}` |
| 7 | `ARR-ACCT-CURR-CYC-DEBIT(3)` | `COMP-3` | 62-68 | 7 | | constant **−2500.00** (L260) → `x'0000000250000D'` |
| 8 | `ARR-ACCT-CURR-BAL(4)` | `S9(10)V99` | 69-80 | 12 | | 0 from `INITIALIZE` (L169) → `00000000000{` |
| 9 | `ARR-ACCT-CURR-CYC-DEBIT(4)` | `COMP-3` | 81-87 | 7 | | 0 → `x'0000000000000C'` |
| 10 | `ARR-ACCT-CURR-BAL(5)` | `S9(10)V99` | 88-99 | 12 | | 0 → `00000000000{` |
| 11 | `ARR-ACCT-CURR-CYC-DEBIT(5)` | `COMP-3` | 100-106 | 7 | | 0 → `x'0000000000000C'` |
| 12 | `ARR-FILLER` | `X(04)` | 107-110 | 4 | | spaces (`INITIALIZE`) |

Only `ARR-ACCT-ID`, occurrences 1-2 of the balance and nothing else carry real data.
**Decision: replicate exactly** (§A.5 D-3) — the file is a fixture for other demos and there is no
business meaning to "fix".

## A.5 `VBRCFILE` — variable-length records (FD L80-L85, WS L122-L130, paragraphs L276-L315)

```
FD VBRC-FILE RECORDING MODE IS V RECORD IS VARYING IN SIZE FROM 10 TO 80 DEPENDING ON WS-RECD-LEN.
01 VBR-REC PIC X(80).
```

Per account, two records are written in this order:

| Record | `WS-RECD-LEN` | Layout (WORKING-STORAGE item moved to `VBR-REC(1:len)`) | Content |
|---|---|---|---|
| `VBRC-REC1` (L123-L125) | 12 (L288) | `VB1-ACCT-ID 9(11)` 1-11, `VB1-ACCT-ACTIVE-STATUS X(01)` 12 | `ACCT-ID`, `ACCT-ACTIVE-STATUS` (L277-L279) |
| `VBRC-REC2` (L126-L130) | 39 (L303) | `VB2-ACCT-ID 9(11)` 1-11, `VB2-ACCT-CURR-BAL S9(10)V99` 12-23, `VB2-ACCT-CREDIT-LIMIT S9(10)V99` 24-35, `VB2-ACCT-REISSUE-YYYY X(04)` 36-39 | `ACCT-ID`, `ACCT-CURR-BAL`, `ACCT-CREDIT-LIMIT`, `WS-ACCT-REISSUE-YYYY` = `ACCT-REISSUE-DATE(1:4)` (L280-L282; the year comes from the `WS-ACCT-REISSUE-DATE` redefinition L131-L137, **not** from `COBDATFT`) |

Physical format on z/OS (`RECFM=VB`, `LRECL=84` = 80 + 4-byte RDW): each record is preceded by a
4-byte Record Descriptor Word `[len+4 as big-endian uint16][x'0000']`; blocks carry a BDW.
Java equivalent: write `RDW(16)`+`RDW(43)` framing (`x'0010 0000'` + 12 bytes, `x'002B 0000'` + 39
bytes) when `vbrc.format=RDW` (mainframe parity / transfer as binary), or one record per line
when `vbrc.format=LINES` (distributed default). Before each write the COBOL also `DISPLAY`s
`'VBRC-REC1:'`+12 chars and `'VBRC-REC2:'`+39 chars (L283-L284).

## A.6 Business rules, latent defects and decisions (`CBACT01C`)

| # | Observation | Replicate / deviate | Rationale / impact |
|---|---|---|---|
| D-1 | `OUT-ACCT-CURR-CYC-DEBIT` is assigned **only when the source cycle debit is zero** (L236-L238), and then to the constant 2525.00. For a non-zero source it keeps whatever the FD buffer held: undefined for the first record, the previous record's value thereafter. The fixture has zero cycle debit on all 50 accounts, so every fixture record gets `x'0000000252500C'`. | **Deviate (deterministic):** `cycDebit == 0 ? 2525.00 : cycDebit` — i.e. carry the real value when non-zero. | The only sane reading of the intent ("demo a COMP-3 field with a non-zero value"); output for the fixture is byte-identical; for non-zero inputs the COBOL output is garbage that cannot be reproduced reliably anyway. Parity harness: assert equality only for records with zero source debit. |
| D-2 | `ACCT-ADDR-ZIP` is dropped from `OUTFILE` (no target field, 107-byte record). | Replicate. | Layout is fixed by the JCL `LRECL=107`. |
| D-3 | `ARRYFILE` occurrences 1-3 hold hard-coded constants (1005.00, 1525.00, −1025.00, −2500.00); 4-5 are zeros. | Replicate. | Demonstration data; byte parity. |
| D-4 | Reissue date bytes 9-10 undefined (§A.3.1). | Deviate: two spaces; mask in parity. | Deterministic output. |
| D-5 | `COBDATFT` type-2 input is not validated; error message never checked. | Replicate slice semantics; no validation. | Parity; `CodatecnDateFormatter` exposes `Optional<String> error` for future use. |
| D-6 | Output files never `CLOSE`d. | Deviate (close in `close()`); no observable difference. | |
| D-7 | Write-status check accepts `'10'` as OK for `WRITE` (L245, L266, L292, L307) — `'10'` cannot occur on `WRITE`; harmless. | n/a | |
| D-8 | Error message for all three output writes is the same `'ACCOUNT FILE WRITE STATUS IS:'` (L246, L268, L294, L309), so a log reader cannot tell which file failed. | Deviate: Java log line adds the file name after the COBOL text: `ACCOUNT FILE WRITE STATUS IS:nn (OUTFILE)`. | Operability; failures are not part of golden files. |
| D-9 | Per-record console output is 12 labelled lines + 2 `VBRC-` lines + the raw 300-byte record (15 lines per account). | Replicate (§A.7). | Dump parity. |
| D-10 | `VBRC-REC2` year is taken from the raw input (`ACCT-REISSUE-DATE(1:4)`), not from the converted date. | Replicate. | Identical result for well-formed dates; documented for odd inputs. |

## A.7 Console output (`CBACT01C`), exact

Per record, in order (values shown for `acctdata.txt` L1; `DISPLAY` of zoned signed items shows the
overpunched image):

```
ACCT-ID                 :00000000001                          L201
ACCT-ACTIVE-STATUS      :Y                                    L202
ACCT-CURR-BAL           :00000001940{                         L203
ACCT-CREDIT-LIMIT       :00000020200{                         L204
ACCT-CASH-CREDIT-LIMIT  :00000010200{                         L205
ACCT-OPEN-DATE          :2014-11-20                           L206
ACCT-EXPIRAION-DATE     :2025-05-20                           L207
ACCT-REISSUE-DATE       :2025-05-20                           L208
ACCT-CURR-CYC-CREDIT    :00000000000{                         L209
ACCT-CURR-CYC-DEBIT     :00000000000{                         L210
ACCT-GROUP-ID           :A000000000                           L211
-------------------------------------------------            L212  (49 dashes)
VBRC-REC1:00000000001Y                                        L283
VBRC-REC2:0000000000100000001940{00000020200{2025             L284
<300-byte raw ACCOUNT-RECORD image>                           L151
```

Labels are padded to 24 characters then `:` (no space after the colon). Job banners:
`START OF EXECUTION OF PROGRAM CBACT01C` / `END OF EXECUTION OF PROGRAM CBACT01C`. Failure lines:
`ERROR OPENING ACCTFILE`, `ERROR OPENING OUTFILE` nn, `ERROR OPENING ARRAYFILE` nn,
`ERROR OPENING VBRC FILE` nn, `ERROR READING ACCOUNT FILE`, `ACCOUNT FILE WRITE STATUS IS:` nn,
`ERROR CLOSING ACCOUNT FILE`, then `FILE STATUS IS: NNNNnnnn`, `ABENDING PROGRAM`, `U0999`.
(`ERROR OPENING ACCTFILE` and `ERROR READING/CLOSING ACCOUNT FILE` print no status inline — only
via `9910`.)

## A.8 Spring Batch design (`cbact01cJob`)

```
Job cbact01cJob   restartable=false
 ├─ Step prepareOutputs (Tasklet)   delete/recreate outfile, arryfile, vbrcfile   (= PREDEL step + OPEN OUTPUT)
 └─ Step dumpAccounts   (chunk 100)
      reader   : accountReader (ordered by acct_id)                                 §6.2
      processor: AccountDerivedRecordsProcessor  → AccountDump { Account src,
                                                                  AcctOutRecord out,   // §A.3 (+ D-1 rule, CodatecnDateFormatter 2/2)
                                                                  AcctArrayRecord arr, // §A.4
                                                                  VbrcRec1 vb1, VbrcRec2 vb2 }  // §A.5
      writer   : CompositeItemWriter, per item in this order:
                   1. DumpLineWriter  – 12 labelled lines (§A.7)                            (mirrors 1100)
                   2. FixedLengthRecordItemWriter<AcctOutRecord>   107 bytes → outfile      (1350)
                   3. FixedLengthRecordItemWriter<AcctArrayRecord> 110 bytes → arryfile     (1450)
                   4. DumpLineWriter  – 'VBRC-REC1:' + 12, 'VBRC-REC2:' + 39                (1500)
                   5. VariableLengthRecordItemWriter  RDW(12)+rec1, RDW(39)+rec2 → vbrcfile (1550/1575)
                   6. DumpLineWriter  – raw 300-byte image                                  (main loop L151)
      listeners: DumpJobListener banners; on failure log per §A.7 and exit 8
```

Serialisers: `AcctOutRecordSerializer` (zoned ×4, packed ×1, text), `AcctArrayRecordSerializer`
(zoned ×5, packed ×5), `VbrcRecordSerializer` (two shapes). Output charset property
`cbact01c.output.charset` (default `IBM037` for parity with mainframe files, `US-ASCII`
otherwise); `cbact01c.vbrc.format` = `RDW` | `LINES`.

## A.9 Acceptance criteria (`CBACT01C`)

| ID | Criterion |
|---|---|
| T-1 | `OUTFILE` for `acctdata.txt` L1 = `00000000001` `Y` `00000001940{` `00000020200{` `00000010200{` `2014-11-20` `2025-05-20` `20250520␠␠` `00000000000{` `x'0000000252500C'` `A000000000` — 107 bytes; 50 records. |
| T-2 | D-1: source cycle debit 0 → packed 2525.00; source 123.45 → packed 123.45 (`x'0000000012345C'`). |
| T-3 | `ARRYFILE` record for L1: `00000000001` `00000001940{` `x'0000000100500C'` `00000001940{` `x'0000000152500C'` `00000010250}` `x'0000000250000D'` `00000000000{` `x'0000000000000C'` `00000000000{` `x'0000000000000C'` `␠␠␠␠` — 110 bytes. |
| T-4 | `CodatecnDateFormatter`: (`1`,`1`,`20250520`) → `2025-05-20`; (`1`,`1`,`2025-05-20`) → error `INVALID INPUT`; (`1`,`2`,…) → error; (`2`,`1`,…) → error; (`2`,`2`,`2025-05-20`) → `20250520`; (`2`,`2`,`2025/05/20`) → `20250520`; (`2`,`2`,`ABCDEFGHIJ`) → `ABCDFGIJ`; (`3`,…) → error. |
| T-5 | `VBRCFILE` (`RDW`): bytes `x'0010 0000'` + `00000000001Y`, then `x'002B 0000'` + `00000000001` `00000001940{` `00000020200{` `2025`; 100 records for 50 accounts; total size 50 × (16 + 43) = 2 950 bytes. `LINES` format: 100 lines of 12/39 chars. |
| T-6 | Console: for 50 accounts exactly 1 + 50 × 15 + 1 = 752 lines, in the order of §A.7; labelled numeric lines show the zoned image (`00000001940{`). |
| T-7 | Golden parity against a mainframe run on the same data: `OUTFILE` equal after masking bytes 77-78 of each record (D-4); `ARRYFILE` byte-equal; `VBRCFILE` equal after stripping BDWs; SYSOUT equal (EBCDIC→UTF-8). |
| T-8 | Failure in any of the three output writers → `FAILED`, exit 8, `ACCOUNT FILE WRITE STATUS IS:` line logged, no `END OF EXECUTION` line, partially written files removed. |

---

# B. `CBACT02C` — card dump

## B.1 Source facts

* `SELECT CARDFILE-FILE ASSIGN TO CARDFILE`, INDEXED, SEQUENTIAL, `RECORD KEY IS FD-CARD-NUM`,
  `FILE STATUS IS CARDFILE-STATUS` (L29-L33). FD: `FD-CARD-NUM X(16)` + `FD-CARD-DATA X(134)` = 150
  (L38-L40). `COPY CVACT02Y` (L45) → `CARD-RECORD`, layout §4.2.
* Control flow = §2 skeleton; inner display commented out (L96), so **one** `DISPLAY CARD-RECORD`
  per record (L78).
* JCL `READCARD.jcl`: `STEP05 EXEC PGM=CBACT02C` (L22), `CARDFILE DD DSN=AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS` (L25-L26).

## B.2 Java

`cbact02cJob` = `DumpJobFactory.create("CBACT02C", cardReader, CardSerializer::image, 1)`.

## B.3 Console, exact

```
START OF EXECUTION OF PROGRAM CBACT02C
0500024453765740000000000050747Aniya Von                                         2023-03-09Y<59 spaces>
... (one 150-char line per card, ascending CARD-NUM)
END OF EXECUTION OF PROGRAM CBACT02C
```

Failure literals: `ERROR OPENING CARDFILE` (L129), `ERROR READING CARDFILE` (L110),
`ERROR CLOSING CARDFILE` (L147), `FILE STATUS IS: NNNNnnnn`, `ABENDING PROGRAM`, `U0999`.

## B.4 Tests

S-1 (card), S-2, S-4..S-7 with the 50-card fixture; additionally assert the CVV is printed
verbatim unless `dump.maskPii` (Q1).

---

# C. `CBACT03C` — card cross-reference dump

## C.1 Source facts

* `SELECT XREFFILE-FILE ASSIGN TO XREFFILE`, INDEXED, SEQUENTIAL, `RECORD KEY IS FD-XREF-CARD-NUM`,
  `FILE STATUS IS XREFFILE-STATUS` (L29-L33). FD: `FD-XREF-CARD-NUM X(16)` + `FD-XREF-DATA X(34)` = 50
  (L38-L40). `COPY CVACT03Y` (L45) → `CARD-XREF-RECORD`, layout §4.3 / CBTRN02C §4.3.
* Control flow = §2 skeleton **with the inner `DISPLAY CARD-XREF-RECORD` active (L96)** → each
  record printed twice (L96 then L78).
* JCL `READXREF.jcl`: `STEP05 EXEC PGM=CBACT03C` (L22), `XREFFILE DD DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` (L25-L26).

## C.2 Java

`cbact03cJob` = `DumpJobFactory.create("CBACT03C", cardXrefReader, CardXrefSerializer::image, 2)`.

## C.3 Console, exact

```
START OF EXECUTION OF PROGRAM CBACT03C
050002445376574000000005000000000050<14 spaces>
050002445376574000000005000000000050<14 spaces>
... (two identical 50-char lines per xref, ascending XREF-CARD-NUM)
END OF EXECUTION OF PROGRAM CBACT03C
```

Failure literals: `ERROR OPENING XREFFILE` (L129), `ERROR READING XREFFILE` (L110),
`ERROR CLOSING XREFFILE` (L147), `FILE STATUS IS: NNNNnnnn`, `ABENDING PROGRAM`, `U0999`.

## C.4 Tests

S-1 (xref, padded to 50), S-3, S-4..S-7; `dump.repeatCount=1` variant prints 52 lines.

---

# D. `CBCUS01C` — customer dump

## D.1 Source facts

* `SELECT CUSTFILE-FILE ASSIGN TO CUSTFILE`, INDEXED, SEQUENTIAL, `RECORD KEY IS FD-CUST-ID`,
  `FILE STATUS IS CUSTFILE-STATUS` (L29-L33). FD: `FD-CUST-ID 9(09)` + `FD-CUST-DATA X(491)` = 500
  (L38-L40). `COPY CVCUS01Y` (L45) → `CUSTOMER-RECORD`, layout §4.4.
* Control flow = §2 skeleton with the inner `DISPLAY CUSTOMER-RECORD` active (L96) → each record
  printed twice. Status paragraph is named `Z-DISPLAY-IO-STATUS` (L161) instead of `9910-…`; same
  logic.
* JCL `READCUST.jcl`: `STEP05 EXEC PGM=CBCUS01C` (L21), `CUSTFILE DD DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS` (L24-L25).

## D.2 Java

`cbcus01cJob` = `DumpJobFactory.create("CBCUS01C", customerReader, CustomerSerializer::image, 2)`.

## D.3 Console, exact

```
START OF EXECUTION OF PROGRAM CBCUS01C
000000001Immanuel                 Madeline                 Kessler                  618 Deshaun Route … (500 chars)
000000001Immanuel                 … (same line again)
...
END OF EXECUTION OF PROGRAM CBCUS01C
```

Failure literals: `ERROR OPENING CUSTFILE` (L129), `ERROR READING CUSTOMER FILE` (L110),
`ERROR CLOSING CUSTOMER FILE` (L147), `FILE STATUS IS: NNNNnnnn`, `ABENDING PROGRAM`, `U0999`.

## D.4 Tests

S-1 (customer), S-3, S-4..S-7; PII masking variant (Q1) replaces positions 280-288 (SSN) and
289-308 (govt id) with `*` while keeping line length 500.
