# CBTRN01C Migration Specification: COBOL Batch to Java + Spring Batch

| | |
|---|---|
| Source program | `app/cbl/CBTRN01C.cbl` (CardDemo v2.0-25-gdb72e6b-235) |
| Source JCL | **None in the repository.** No member under `app/jcl/` executes `PGM=CBTRN01C`, it is not referenced in `README.md`, and it has no Control-M job in `app/scheduler/CardDemo.controlm`. |
| Copybooks | `app/cpy/CVTRA06Y.cpy`, `CVCUS01Y.cpy`, `CVACT03Y.cpy`, `CVACT02Y.cpy`, `CVACT01Y.cpy`, `CVTRA05Y.cpy` |
| Related spec | `docs/specs/CBTRN02C-migration-spec.md` (successor program; shared table design §4.9 there) |
| Target | Java 17+, Spring Boot 3.x, Spring Batch 5.x (if migrated at all — see §1.1 and §9) |
| Status | Draft for review |

This document is derived directly from the COBOL source and copybooks listed above. Line
references (`L123`) point into `CBTRN01C.cbl` unless another file is named.

---

## 1. Overview & Purpose

`CBTRN01C` is described in its header as "Post the records from daily transaction file" (L5),
but the code **posts nothing and writes nothing**. It is an earlier, read-only precursor of
`CBTRN02C`. For every 350-byte record in the sequential daily transaction file (`DALYTRAN`) it:

1. Echoes the whole record to SYSOUT (`DISPLAY DALYTRAN-RECORD`, L168).
2. Looks up the card number in the card cross-reference file (`XREFFILE`, `2000-LOOKUP-XREF`).
   On success it displays the card, account and customer ids; on failure it displays
   `INVALID CARD NUMBER FOR XREF` and `CARD NUMBER <n> COULD NOT BE VERIFIED. SKIPPING
   TRANSACTION ID-<id>`.
3. If the card was found, looks up the account in `ACCTFILE` (`3000-READ-ACCOUNT`) and
   displays `SUCCESSFUL READ OF ACCOUNT FILE` or `INVALID ACCOUNT NUMBER FOUND` /
   `ACCOUNT <id> NOT FOUND`.

It also opens — but never reads — `CUSTFILE`, `CARDFILE` and `TRANFILE` (all `INPUT`). There are
no counters, no reject file, no `RETURN-CODE`; the program ends `RC=0` unless an I/O error other
than EOF/not-found causes a U0999 abend.

### 1.1 Relationship to CBTRN02C and recommendation (summary)

`CBTRN02C` performs the same two lookups (`1500-A-LOOKUP-XREF`, `1500-B-LOOKUP-ACCT`) with the
same keys, then adds the over-limit and expiration rules and all posting side effects, and writes
rejects to a file instead of SYSOUT. Every observable outcome of `CBTRN01C` is either a subset of
`CBTRN02C`'s behaviour or a console message with no downstream consumer.

**Recommendation: do not build a separate Java job.** Treat `CBTRN01C` as **superseded** by the
`postTranJob` specified in the CBTRN02C spec. If a "validate-only / dry-run" capability is
wanted, add a `dryRun=true` job parameter to `postTranJob` that runs the reader + validator and
suppresses all writers (§6) — that reproduces `CBTRN01C`'s useful behaviour (card/account
verification listing) with the current rule set, at near-zero cost. Full reasoning in §9.

The remaining sections document the program completely so that (a) the supersession claim can
be verified and (b) the dry-run mode can be built to match if the recommendation is accepted.

---

## 2. Current-State (COBOL) Analysis

### 2.1 Control flow

```
MAIN-PARA                                                   (L155-197)
  DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN01C'
  PERFORM 0000-DALYTRAN-OPEN   (OPEN INPUT DALYTRAN-FILE)   sequential
  PERFORM 0100-CUSTFILE-OPEN   (OPEN INPUT CUSTOMER-FILE)   never read
  PERFORM 0200-XREFFILE-OPEN   (OPEN INPUT XREF-FILE)
  PERFORM 0300-CARDFILE-OPEN   (OPEN INPUT CARD-FILE)       never read
  PERFORM 0400-ACCTFILE-OPEN   (OPEN INPUT ACCOUNT-FILE)    INPUT, not I-O
  PERFORM 0500-TRANFILE-OPEN   (OPEN INPUT TRANSACT-FILE)   never read; INPUT, not OUTPUT

  PERFORM UNTIL END-OF-DAILY-TRANS-FILE = 'Y'               (L164-186)
      IF END-OF-DAILY-TRANS-FILE = 'N'                      <-- always true inside the loop
          PERFORM 1000-DALYTRAN-GET-NEXT
          IF END-OF-DAILY-TRANS-FILE = 'N'
              DISPLAY DALYTRAN-RECORD
          END-IF
          MOVE 0 TO WS-XREF-READ-STATUS                     <-- NOT guarded by the EOF test (see §5.3)
          MOVE DALYTRAN-CARD-NUM TO XREF-CARD-NUM
          PERFORM 2000-LOOKUP-XREF
          IF WS-XREF-READ-STATUS = 0
              MOVE 0 TO WS-ACCT-READ-STATUS
              MOVE XREF-ACCT-ID TO ACCT-ID
              PERFORM 3000-READ-ACCOUNT
              IF WS-ACCT-READ-STATUS NOT = 0
                  DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'
              END-IF
          ELSE
              DISPLAY 'CARD NUMBER ' DALYTRAN-CARD-NUM
                      ' COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-' DALYTRAN-ID
          END-IF
      END-IF
  END-PERFORM

  PERFORM 9000..9500 CLOSE all six files
  DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN01C'
  GOBACK
```

### 2.2 Paragraph reference

| Paragraph | Lines | Responsibility | Java equivalent (see §6) |
|---|---|---|---|
| `MAIN-PARA` | L155-197 | Opens, main loop, closes. | Job/step wiring. |
| `1000-DALYTRAN-GET-NEXT` | L202-225 | `READ DALYTRAN-FILE INTO DALYTRAN-RECORD`; `'00'` record, `'10'` → `END-OF-DAILY-TRANS-FILE='Y'`, else `ERROR READING DAILY TRANSACTION FILE` + abend. | `FlatFileItemReader.read()` (identical to CBTRN02C). |
| `2000-LOOKUP-XREF` | L227-239 | `MOVE XREF-CARD-NUM TO FD-XREF-CARD-NUM`; `READ XREF-FILE RECORD INTO CARD-XREF-RECORD KEY IS FD-XREF-CARD-NUM`. `INVALID KEY` → display `INVALID CARD NUMBER FOR XREF`, `WS-XREF-READ-STATUS=4`. `NOT INVALID KEY` → four `DISPLAY`s (§7.2). No other status check: any non-`INVALID KEY` error status (e.g. `'92'`) falls into the `NOT INVALID KEY` branch and is silently treated as success (§5.4). | `CardXrefRepository.findByCardNum`. |
| `3000-READ-ACCOUNT` | L241-250 | `MOVE ACCT-ID TO FD-ACCT-ID`; `READ ACCOUNT-FILE RECORD INTO ACCOUNT-RECORD KEY IS FD-ACCT-ID`. `INVALID KEY` → `INVALID ACCOUNT NUMBER FOUND`, `WS-ACCT-READ-STATUS=4`; else `SUCCESSFUL READ OF ACCOUNT FILE`. Same missing general-status check. | `AccountRepository.findById`. |
| `0000`-`0500-*-OPEN` | L252-359 | Open each file `INPUT`; non-`'00'` → `ERROR OPENING <file>` + `Z-DISPLAY-IO-STATUS` + `Z-ABEND-PROGRAM`. | Reader open / datasource check. |
| `9000-DALYTRAN-CLOSE` | L361-377 | Closes `DALYTRAN-FILE` but on error displays `ERROR CLOSING CUSTOMER FILE` and `CUSTFILE-STATUS` — copy/paste defect (§5.4). | Cleanup. |
| `9100`-`9500-*-CLOSE` | L379-467 | Close remaining files; non-`'00'` → abend. | Cleanup. |
| `Z-ABEND-PROGRAM` | L469-473 | `DISPLAY 'ABENDING PROGRAM'`, `CALL 'CEE3ABD' USING 999, 0` → U0999. | Fatal exception → `FAILED`. |
| `Z-DISPLAY-IO-STATUS` | L476-489 | Same `FILE STATUS IS: NNNN` formatter as CBTRN02C `9910`. | Log. |

### 2.3 File SELECT / FILE STATUS handling

Six status fields (`DALYTRAN-STATUS`, `CUSTFILE-STATUS`, `XREFFILE-STATUS`, `CARDFILE-STATUS`,
`ACCTFILE-STATUS`, `TRANFILE-STATUS`, L100-127). `APPL-RESULT`/`APPL-AOK`/`APPL-EOF` pattern is
used for opens, the sequential read and closes. The two random reads use only `INVALID KEY` /
`NOT INVALID KEY` and never inspect the status field.

| File | Operation | Status treated as normal |
|---|---|---|
| `DALYTRAN` | `READ` | `'10'` = EOF |
| `XREFFILE` | `READ` | `INVALID KEY` (`'23'`) → card not verified (message only) |
| `ACCTFILE` | `READ` | `INVALID KEY` (`'23'`) → account not found (message only) |
| `XREFFILE` / `ACCTFILE` | `READ` | **any other non-`'00'` status** (unintentionally — no check exists) |

All `OPEN`/`CLOSE` failures and any non-`'00'`/`'10'` `DALYTRAN` read status abend.

Differences from `CBTRN02C` in the `SELECT`s (L29-62): `ACCOUNT-FILE` is opened `INPUT` (not
`I-O`); `TRANSACT-FILE` is `INDEXED`/`RANDOM` and opened `INPUT` (CBTRN02C opens it `OUTPUT`);
`CUSTOMER-FILE` and `CARD-FILE` exist only here. There is no `DALYREJS` and no `TCATBALF`.

---

## 3. Files / Datasets

There is no JCL for this program, so DD-to-dataset wiring is **inferred** from the DD names,
which match those used by `POSTTRAN.jcl` and the file-load jobs (`CUSTFILE.jcl`, `CARDFILE.jcl`,
`XREFFILE.jcl`, `ACCTFILE.jcl`, `TRANBKP.jcl`). Datasets marked *inferred* must be confirmed if
the program is ever run.

| DD name | Dataset (inferred) | COBOL file name | Organization | Access | Open mode | Record key (FD) | Copybook (WS layout) | LRECL | Read? |
|---|---|---|---|---|---|---|---|---|---|
| `DALYTRAN` | `AWS.M2.CARDDEMO.DALYTRAN.PS` | `DALYTRAN-FILE` | Sequential (PS) | Sequential | `INPUT` | – | `CVTRA06Y` (`DALYTRAN-RECORD`) | 350 | yes |
| `CUSTFILE` | `AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS` | `CUSTOMER-FILE` | Indexed (KSDS) | Random | `INPUT` | `FD-CUST-ID 9(09)` @1 | `CVCUS01Y` (`CUSTOMER-RECORD`) | 500 | **no** |
| `XREFFILE` | `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` | `XREF-FILE` | Indexed (KSDS) | Random | `INPUT` | `FD-XREF-CARD-NUM X(16)` @1 | `CVACT03Y` (`CARD-XREF-RECORD`) | 50 | yes |
| `CARDFILE` | `AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS` | `CARD-FILE` | Indexed (KSDS) | Random | `INPUT` | `FD-CARD-NUM X(16)` @1 | `CVACT02Y` (`CARD-RECORD`) | 150 | **no** |
| `ACCTFILE` | `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` | `ACCOUNT-FILE` | Indexed (KSDS) | Random | `INPUT` | `FD-ACCT-ID 9(11)` @1 | `CVACT01Y` (`ACCOUNT-RECORD`) | 300 | yes |
| `TRANFILE` | `AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS` | `TRANSACT-FILE` | Indexed (KSDS) | Random | `INPUT` | `FD-TRANS-ID X(16)` @1 | `CVTRA05Y` (`TRAN-RECORD`) | 350 | **no** |

FD record lengths (L66-94) agree with the copybooks: `16+334=350`, `9+491=500`, `16+34=50`,
`16+134=150`, `11+289=300`, `16+334=350`. Sample fixtures: `app/data/ASCII/dailytran.txt`
(300 × 350), `custdata.txt` (50 × 500), `cardxref.txt` (50 × 50), `carddata.txt` (50 × 150),
`acctdata.txt` (50 × 300).

Because `CUSTFILE`, `CARDFILE` and `TRANFILE` are only opened, their sole effect is that a
missing/unopenable dataset aborts the run before any record is processed (§5.2).

---

## 4. Data Model & Field Mappings

Type-mapping conventions are those of CBTRN02C spec §4.1/§4.7 (no `COMP-3` fields exist in any
copybook here).

### 4.1 Records shared with the CBTRN02C spec (not redefined)

| Copybook | Record | Used here | Fields actually referenced | CBTRN02C spec |
|---|---|---|---|---|
| `CVTRA06Y` | `DALYTRAN-RECORD` (350) | input | `DALYTRAN-CARD-NUM` (263-278), `DALYTRAN-ID` (1-16); whole record displayed | §4.2, table n/a (file) |
| `CVACT03Y` | `CARD-XREF-RECORD` (50) | lookup target | `XREF-CARD-NUM`, `XREF-ACCT-ID`, `XREF-CUST-ID` (all displayed) | §4.3, `card_xref` |
| `CVACT01Y` | `ACCOUNT-RECORD` (300) | lookup target | `ACCT-ID` only (used as key and in the not-found message) | §4.4, `account` |
| `CVTRA05Y` | `TRAN-RECORD` (350) | declared only | none | §4.6, `transaction` |

### 4.2 `CVCUS01Y` — Customer (`CUSTOMER-RECORD`, 500 bytes, KSDS key = customer id) — NEW, declared but unused

| # | COBOL field | PIC | Offset | Len | Java field | Java type |
|---|---|---|---|---|---|---|
| 1 | `CUST-ID` | `9(09)` | 1 | 9 | `custId` (PK) | `long` |
| 2 | `CUST-FIRST-NAME` | `X(25)` | 10 | 25 | `firstName` | `String` |
| 3 | `CUST-MIDDLE-NAME` | `X(25)` | 35 | 25 | `middleName` | `String` |
| 4 | `CUST-LAST-NAME` | `X(25)` | 60 | 25 | `lastName` | `String` |
| 5 | `CUST-ADDR-LINE-1` | `X(50)` | 85 | 50 | `addrLine1` | `String` |
| 6 | `CUST-ADDR-LINE-2` | `X(50)` | 135 | 50 | `addrLine2` | `String` |
| 7 | `CUST-ADDR-LINE-3` | `X(50)` | 185 | 50 | `addrLine3` | `String` |
| 8 | `CUST-ADDR-STATE-CD` | `X(02)` | 235 | 2 | `addrStateCd` | `String` |
| 9 | `CUST-ADDR-COUNTRY-CD` | `X(03)` | 237 | 3 | `addrCountryCd` | `String` |
| 10 | `CUST-ADDR-ZIP` | `X(10)` | 240 | 10 | `addrZip` | `String` |
| 11 | `CUST-PHONE-NUM-1` | `X(15)` | 250 | 15 | `phone1` | `String` |
| 12 | `CUST-PHONE-NUM-2` | `X(15)` | 265 | 15 | `phone2` | `String` |
| 13 | `CUST-SSN` | `9(09)` | 280 | 9 | `ssn` | `long` (PII — mask in logs) |
| 14 | `CUST-GOVT-ISSUED-ID` | `X(20)` | 289 | 20 | `govtIssuedId` | `String` (PII) |
| 15 | `CUST-DOB-YYYY-MM-DD` | `X(10)` | 309 | 10 | `dob` | `String` `YYYY-MM-DD` |
| 16 | `CUST-EFT-ACCOUNT-ID` | `X(10)` | 319 | 10 | `eftAccountId` | `String` |
| 17 | `CUST-PRI-CARD-HOLDER-IND` | `X(01)` | 329 | 1 | `priCardHolderInd` | `String`/`char` |
| 18 | `CUST-FICO-CREDIT-SCORE` | `9(03)` | 330 | 3 | `ficoScore` | `int` |
| 19 | `FILLER` | `X(168)` | 333 | 168 | – | not mapped |

Cross-check: `custdata.txt` record 1 = `000000001Immanuel<17 sp>Madeline<17 sp>Kessler<18 sp>618 Deshaun Route…` — id at 1-9, first name at 10, middle at 35, last at 60, address at 85. Consistent.

### 4.3 `CVACT02Y` — Card (`CARD-RECORD`, 150 bytes, KSDS key = card number) — NEW, declared but unused

| # | COBOL field | PIC | Offset | Len | Java field | Java type |
|---|---|---|---|---|---|---|
| 1 | `CARD-NUM` | `X(16)` | 1 | 16 | `cardNum` (PK) | `String` |
| 2 | `CARD-ACCT-ID` | `9(11)` | 17 | 11 | `acctId` | `long` |
| 3 | `CARD-CVV-CD` | `9(03)` | 28 | 3 | `cvvCd` | `int` (sensitive — never log) |
| 4 | `CARD-EMBOSSED-NAME` | `X(50)` | 31 | 50 | `embossedName` | `String` |
| 5 | `CARD-EXPIRAION-DATE` *(sic)* | `X(10)` | 81 | 10 | `expirationDate` | `String` `YYYY-MM-DD` |
| 6 | `CARD-ACTIVE-STATUS` | `X(01)` | 91 | 1 | `activeStatus` | `String`/`char` |
| 7 | `FILLER` | `X(59)` | 92 | 59 | – | not mapped |

Cross-check: `carddata.txt` record 1 = `0500024453765740` `00000000050` `747` `Aniya Von<41 sp>` `2023-03-09` `Y` — offsets 1, 17, 28, 31, 81, 91. Consistent.

### 4.4 Working storage of note

| Item | PIC | Lines | Role |
|---|---|---|---|
| `END-OF-DAILY-TRANS-FILE` | `X(01)` `VALUE 'N'` | L146 | EOF flag |
| `WS-XREF-READ-STATUS` | `9(04)` | L150 | `0` = found, `4` = `INVALID KEY` |
| `WS-ACCT-READ-STATUS` | `9(04)` | L151 | `0` = found, `4` = `INVALID KEY` |

### 4.5 Relational design — additions to CBTRN02C §4.9

`CBTRN01C` itself needs **no** new tables (it only reads `card_xref` and `account`). For
completeness — since the copybooks are declared here and other CardDemo programs will need them —
the natural tables for the two unused records are:

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
  ssn                 NUMERIC(9)  NOT NULL,      -- PII: encrypt at rest / restrict
  govt_issued_id      CHAR(20)    NOT NULL,      -- PII
  dob                 CHAR(10)    NOT NULL,
  eft_account_id      CHAR(10)    NOT NULL,
  pri_card_holder_ind CHAR(1)     NOT NULL,
  fico_credit_score   NUMERIC(3)  NOT NULL
);

CREATE TABLE card (
  card_num        CHAR(16)    NOT NULL PRIMARY KEY,
  acct_id         NUMERIC(11) NOT NULL,
  cvv_cd          NUMERIC(3)  NOT NULL,          -- sensitive
  embossed_name   CHAR(50)    NOT NULL,
  expiration_date CHAR(10)    NOT NULL,
  active_status   CHAR(1)     NOT NULL
);
CREATE INDEX ix_card_acct ON card(acct_id);
```

These are offered as the canonical definitions for whichever spec first needs them (the CICS
customer/card programs); they are **not** a dependency of the recommendation in §9.

---

## 5. Business Rules / Validation

### 5.1 Lookups (the only logic)

| Rule | Condition | Outcome (SYSOUT only) | Where |
|---|---|---|---|
| L1 | `card_xref[DALYTRAN-CARD-NUM]` exists | `SUCCESSFUL READ OF XREF`, `CARD NUMBER: <16>`, `ACCOUNT ID : <11>`, `CUSTOMER ID: <9>`; proceed to L2 | L234-238 |
| L1' | not found | `INVALID CARD NUMBER FOR XREF` then `CARD NUMBER <16> COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-<16>`; L2 skipped | L231-233, L181-183 |
| L2 | `account[XREF-ACCT-ID]` exists | `SUCCESSFUL READ OF ACCOUNT FILE` | L248-249 |
| L2' | not found | `INVALID ACCOUNT NUMBER FOUND` then `ACCOUNT <11> NOT FOUND` | L245-247, L178 |

These are exactly CBTRN02C rules V1 (reason 100) and V2 (reason 101). `CBTRN01C` has **no**
over-limit (102), expiration (103), or posting logic, and no reject output.

### 5.2 Pre-conditions

All six datasets must open successfully, including the three that are never read; otherwise the
program abends before reading the first record.

### 5.3 Latent defect: the last record is processed twice / empty file processes garbage

The lookups at L170-184 sit **outside** the `IF END-OF-DAILY-TRANS-FILE = 'N'` that guards the
`DISPLAY` (L167-169). When `1000-DALYTRAN-GET-NEXT` hits EOF:

* `READ ... INTO` does not update `DALYTRAN-RECORD` on an unsuccessful read, so the record area
  still holds the **previous** record;
* the flag becomes `'Y'`, the `DISPLAY` is skipped, but `2000-LOOKUP-XREF` and (if found)
  `3000-READ-ACCOUNT` run again on that stale record;
* the loop then terminates.

Effect: the last record's lookup messages appear **twice** in SYSOUT. For an **empty** input
file, the lookups run once on the initial working-storage content of `DALYTRAN-RECORD` (no
`VALUE` clause; typically spaces/low-values), yielding `INVALID CARD NUMBER FOR XREF` and
`CARD NUMBER <16 blanks> COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-<16 blanks>`.

**Decision**: do not replicate. Any Java equivalent processes each record exactly once. Recorded
as a known difference for SYSOUT comparison (§8).

### 5.4 Other latent defects (documented, not replicated)

| Defect | Lines | Note |
|---|---|---|
| Random `READ`s check only `INVALID KEY`; a hard I/O error (status `'3x'`/`'9x'`) takes the `NOT INVALID KEY` branch and is reported as `SUCCESSFUL READ ...` with stale record content. | L229-239, L243-250 | Java: any repository exception is fatal. |
| `9000-DALYTRAN-CLOSE` reports `ERROR CLOSING CUSTOMER FILE` with `CUSTFILE-STATUS`. | L372-373 | Message wording only. |
| Header comment claims the program posts records; it does not. | L5 | Documentation. |
| Three files opened and never used (`CUSTFILE`, `CARDFILE`, `TRANFILE`). | L158, L160, L162 | Suggests unfinished development; the successor `CBTRN02C` dropped `CUSTFILE`/`CARDFILE` and added `TCATBALF`/`DALYREJS`. |
| `ACCT-ID` in `ACCOUNT <id> NOT FOUND` (L178) is the value moved from `XREF-ACCT-ID` at L175 — correct — but `ACCOUNT-RECORD` otherwise still contains the previously read account. | L175-178 | No functional impact. |

### 5.5 Derived / generated values

None. No counters, no timestamps, no `RETURN-CODE` (always `0` on normal completion).

---

## 6. Target Spring Batch Design

### 6.1 Recommended: no separate job — `dryRun` mode on `postTranJob`

Add to the CBTRN02C `postTranJob`:

```
parameter: dryRun (optional, default false)
```

When `dryRun=true`:

* `postTransactionsStep` uses the same `dailyTransactionReader` and `TransactionValidator`
  (§6.3-6.4 of the CBTRN02C spec) — all four rules V1-V4 are evaluated, which is a strict
  superset of `CBTRN01C`'s L1/L2.
* The `ItemWriter` is replaced by a `ValidationReportWriter` that logs one line per record in the
  `CBTRN01C` style (§7.2) and writes **nothing** to the database or the rejects file. The step is
  marked read-only (`transactionManager` with `readOnly=true`, or a `ResourcelessTransactionManager`).
* `PostTranJobListener` reports `TRANSACTIONS PROCESSED / REJECTED` and exit code `4` if any
  record would have been rejected (a useful improvement on `CBTRN01C`'s unconditional `RC=0`;
  §9 Q2 if strict parity is wanted).

No new repositories, readers or models are needed. `CardXrefRepository.findByCardNum` and
`AccountRepository.findById` already exist.

### 6.2 Alternative (not recommended): standalone `validateDailyTranJob`

If a separately schedulable job is mandated, its shape is:

```
Job: validateDailyTranJob
  parameters: dailyTranFile (required)
  Step 1: verifyResourcesStep (tasklet)        -- optional; emulates the six OPENs (§5.2) by pinging the
                                               --   card_xref/account/customer/card/transaction tables
  Step 2: validateStep (chunk-oriented, chunkSize 100, read-only transaction)
      reader    : dailyTransactionReader        (FlatFileItemReader<DailyTransaction>, CBTRN02C §6.3)
      processor : cardAccountLookupProcessor    (ItemProcessor<DailyTransaction, LookupResult>)
      writer    : lookupResultLogWriter         (ItemWriter<LookupResult> → log lines §7.2)
```

```java
public LookupResult process(DailyTransaction tran) {
    Optional<CardXref> xref = cardXrefRepo.findByCardNum(tran.cardNum());
    if (xref.isEmpty()) return LookupResult.cardNotFound(tran);
    Optional<Account> acct = accountRepo.findById(xref.get().acctId());
    return acct.isPresent() ? LookupResult.ok(tran, xref.get()) : LookupResult.accountNotFound(tran, xref.get());
}
```

Repository interfaces required (both already defined in CBTRN02C §6.6):

```java
Optional<CardXref> CardXrefRepository.findByCardNum(String cardNum);
Optional<Account>  AccountRepository.findById(long acctId);
```

No `ItemStream` state beyond the reader; fault tolerance none; restart trivially safe (no writes).

### 6.3 Differences from CBTRN02C at a glance

| Aspect | `CBTRN01C` | `CBTRN02C` |
|---|---|---|
| Card lookup (`XREFFILE`) | yes → message | yes → reason 100 |
| Account lookup (`ACCTFILE`) | yes → message; opened `INPUT` | yes → reason 101; opened `I-O` and rewritten |
| Over-limit / expiration rules | no | yes (102, 103) |
| `TCATBALF` upsert | no | yes |
| Transaction master write | no (`TRANFILE` opened `INPUT`, never read) | yes (`OUTPUT`) |
| Rejects file `DALYREJS` | no (SYSOUT only) | yes (430-byte records) |
| `CUSTFILE`, `CARDFILE` | opened, never read | not used |
| Counters / `RETURN-CODE` | none / always 0 | processed & rejected counts / `RC=4` on rejects |
| Per-record SYSOUT | full 350-byte echo + lookup chatter | only `TCATBAL ... Creating.` lines |
| JCL / scheduler | none | `POSTTRAN.jcl`, Control-M daily |

Everything `CBTRN01C` does is contained in `CBTRN02C`'s read/validate path.

---

## 7. Output & Exit Behavior

### 7.1 Files

None written. No dataset is created or modified.

### 7.2 Console output (per record, in order)

```
<350-byte DALYTRAN-RECORD echoed verbatim>
SUCCESSFUL READ OF XREF                         | INVALID CARD NUMBER FOR XREF
CARD NUMBER: <16>                               | CARD NUMBER <16> COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-<16>
ACCOUNT ID : <11>
CUSTOMER ID: <9>
SUCCESSFUL READ OF ACCOUNT FILE                 | INVALID ACCOUNT NUMBER FOUND
                                                | ACCOUNT <11> NOT FOUND
```

Exact literals: `'SUCCESSFUL READ OF XREF'`, `'CARD NUMBER: '`, `'ACCOUNT ID : '` (note the space
before the colon), `'CUSTOMER ID: '`, `'INVALID CARD NUMBER FOR XREF'`,
`'CARD NUMBER '` … `' COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'`,
`'SUCCESSFUL READ OF ACCOUNT FILE'`, `'INVALID ACCOUNT NUMBER FOUND'`, `'ACCOUNT '` … `' NOT FOUND'`.
Numeric ids print zero-padded to their `PIC` width. Job-level: `START OF EXECUTION OF PROGRAM
CBTRN01C` / `END OF EXECUTION OF PROGRAM CBTRN01C`; error path `ERROR <OPENING|READING|CLOSING>
<file>`, `FILE STATUS IS: NNNN`, `ABENDING PROGRAM`.

Because of §5.3 the COBOL SYSOUT ends with a duplicated block for the last record; the Java
`dryRun` output must **not**.

Volume note: the 350-byte echo makes SYSOUT ≈ 500+ bytes per record. In Java, emit the echo at
`DEBUG` and the lookup outcome at `INFO`, or write the report to a file parameter instead of the
log (Q3).

### 7.3 Exit status mapping

| COBOL outcome | COBOL `RETURN-CODE` / abend | Spring Batch `ExitStatus` | Process exit code |
|---|---|---|---|
| Normal completion, regardless of how many cards/accounts were not found | `RC=0` | `COMPLETED` (dry-run: `COMPLETED WITH REJECTS` if any V1-V4 failure and strict parity not required — Q2) | `0` (or `4` per Q2) |
| Any `OPEN`/`CLOSE` failure or non-EOF `DALYTRAN` read error | `U0999` | `FAILED` | `8` |

---

## 8. Acceptance Criteria & Test Plan

Assuming the §6.1 recommendation (dry-run mode on `postTranJob`):

| ID | Criterion | Expected |
|---|---|---|
| AC-D1 | `postTranJob dryRun=true` over `dailytran.txt` (300 records) with seeded `card_xref`/`account` | `readCount=300`; **zero** rows changed in `transaction`, `account`, `tran_cat_bal`; no rejects file created (or empty, per configuration) |
| AC-D2 | record whose card is absent | log contains `INVALID CARD NUMBER FOR XREF` and `CARD NUMBER <16> COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-<16>`; `accountRepo` not called for that record |
| AC-D3 | record whose card exists but account absent | log contains `SUCCESSFUL READ OF XREF`, the three id lines, `INVALID ACCOUNT NUMBER FOUND`, `ACCOUNT <11> NOT FOUND` |
| AC-D4 | record passing both lookups | `SUCCESSFUL READ OF ACCOUNT FILE`; ids zero-padded (`ACCOUNT ID : 00000000027`, `CUSTOMER ID: 000000027` for card `0683586198171516` per `cardxref.txt` row 2) |
| AC-D5 | last record of the file | its lookup lines appear exactly **once** (deviation from §5.3) |
| AC-D6 | empty input file | no per-record lines at all; `COMPLETED`, exit `0` |
| AC-D7 | `dryRun=true` and a database write is attempted (mutation test) | test fails — enforced by read-only transaction / mocked writers |
| AC-D8 | `dryRun=false` | behaviour identical to the CBTRN02C spec (regression guard) |
| AC-D9 | database unreachable at start | `FAILED`, exit `8`, mirroring the COBOL open-time abend |
| AC-D10 (parity) | COBOL `CBTRN01C` SYSOUT captured once on the emulator for `dailytran.txt`, compared with dry-run log filtered to the per-record lines | identical after removing the COBOL's duplicated final block (§5.3) and the COBOL's 350-byte echoes if the Java echo is at `DEBUG` |

If the standalone job (§6.2) is built instead, AC-D1-D10 apply unchanged to `validateDailyTranJob`
with `postTranJob` replaced.

---

## 9. Assumptions, Risks & Open Questions

### 9.1 Recommendation and rationale

**`CBTRN01C` is superseded by the `CBTRN02C` job; no separate Java job should be built.**

Evidence:

1. No JCL, README entry or Control-M definition runs it — it is not part of the batch stream.
2. It has no outputs other than SYSOUT; nothing downstream can depend on it.
3. Its two lookups are exactly `CBTRN02C`'s V1/V2, executed on the same files with the same keys.
4. Its file list (`CUSTFILE`, `CARDFILE`, `TRANFILE` opened but unused) and header comment show an
   unfinished predecessor of the posting program, not an independent function.
5. The header says "Post the records", which `CBTRN02C` actually does.

If a validate-only capability is desired, the `dryRun` parameter (§6.1) delivers it for the cost
of a writer swap and reuses the tested validator, and it stays in sync with future validation
rules (the COBOL `* ADD MORE VALIDATIONS HERE` extension point) automatically.

### 9.2 Assumptions

| # | Assumption |
|---|---|
| A1 | The DD-to-dataset mapping in §3 (inferred from other JCL) is what any historical run used. |
| A2 | The CBTRN02C table design and repositories are built first; this spec adds nothing they need. |
| A3 | `customer` / `card` tables (§4.5) will be owned by the specs of the programs that actually read them (CICS `COCRDxx`/`COUSRxx`, `CBEXPORT`/`CBIMPORT`); they are listed here only because the copybooks appear in this program. |

### 9.3 Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Someone relies on `CBTRN01C` as an ad-hoc "check the daily file" utility outside the scheduled stream. | Provide `dryRun` on `postTranJob`; document in the runbook. |
| R2 | Log volume: the 350-byte per-record echo and up to six lines per record. | `DEBUG` level for the echo; optional report file. |
| R3 | PII exposure: the echo prints merchant and card data; the successor's dry run would also have access to `customer` if extended. | Mask card numbers in `INFO` logs unless a `--unmasked` flag is set; never log `cvv_cd`/`ssn`. |
| R4 | Parity testing of SYSOUT is noisy because of the §5.3 duplicate and the silent-error branches (§5.4). | Compare filtered per-record lines only (AC-D10). |

### 9.4 Open questions

| # | Question | Options / recommendation |
|---|---|---|
| Q1 | Accept "superseded — implement as `dryRun` on `postTranJob`" and close `CBTRN01C` as not migrated? | Recommend yes. |
| Q2 | In dry-run mode, should the exit code be `0` always (strict `CBTRN01C` parity) or `4` when any record would be rejected (more useful)? | Recommend `4`; make it configurable only if an operator process depends on `0`. |
| Q3 | Should the dry-run report go to the log or to a file/table (`daily_transaction_validation_report`)? | Recommend a file parameter (`reportFile`) mirroring the rejects layout (430 bytes, all records with reason `0000` for passes) so the same tooling reads both. |
| Q4 | Should the `customer`/`card` DDL in §4.5 be adopted now as the canonical definition? | Recommend yes, to avoid divergent definitions across specs; ownership per A3. |
