# POSTTRAN — Java / Spring Batch port of `CBTRN02C`

This module reproduces the CardDemo daily transaction posting job
(`app/jcl/POSTTRAN.jcl`, step `CBTRN02C`, program `app/cbl/CBTRN02C.cbl`) as a
Spring Boot 3 / Spring Batch 5 application. The COBOL source is unchanged; this
directory is a standalone Maven project.

| Mainframe                 | Java                                                        |
|---------------------------|-------------------------------------------------------------|
| DD `DALYTRAN` (PS, 350)   | `FlatFileItemReader` over `posttran.dalytran`               |
| DD `DALYREJS` (PS, 430)   | `FlatFileItemWriter` to `posttran.dalyrejs`                 |
| DD `XREFFILE` (KSDS)      | table `CARD_XREF`   (`CardXref`, key `CARD_NUM`)            |
| DD `ACCTFILE` (KSDS)      | table `ACCOUNT`     (`Account`, key `ACCT_ID`)              |
| DD `TCATBALF` (KSDS)      | table `TRAN_CAT_BAL`(`TranCatBalance`, key acct+type+cat)   |
| DD `TRANFILE` (KSDS)      | table `TRANSACT`    (`Transaction`, key `TRAN_ID`)          |
| `RETURN-CODE 4`           | process exit code 4 (`PostTranJobListener` / `ExitCodeGenerator`) |

## Running

```bash
cd java/posttran
mvn test                                  # unit + full-job integration test
mvn spring-boot:run                       # H2 file DB ./posttran-db, reads ../../app/data/ASCII/dailytran.txt
# or
mvn package && java -jar target/posttran-*.jar \
    --posttran.dalytran=/path/dailytran.txt \
    --posttran.dalyrejs=/path/dalyrejs.txt
```

Properties (`application.yml`):

| Property                  | Meaning                                                            |
|---------------------------|--------------------------------------------------------------------|
| `posttran.dalytran`       | input file, 350-byte fixed records (CVTRA06Y)                      |
| `posttran.dalyrejs`       | reject output, 430-byte fixed records                              |
| `posttran.chunk-size`     | Spring Batch commit interval                                       |
| `posttran.load.acctfile`  | optional: load a CVACT01Y extract (e.g. `app/data/ASCII/acctdata.txt`) into `ACCOUNT` before the job |
| `posttran.load.xreffile`  | optional: load a CVACT03Y extract (`cardxref.txt`) into `CARD_XREF` |
| `posttran.load.tcatbalf`  | optional: load a CVTRA01Y extract (`tcatbal.txt`) into `TRAN_CAT_BAL` |
| `posttran.load.truncate`  | truncate the target table before loading                           |
| `spring.datasource.*`     | H2 by default; the PostgreSQL driver is on the classpath           |

The job exits with status 4 when at least one record was rejected (`WS-REJECT-COUNT > 0`),
otherwise 0. Processed/rejected counts are logged exactly as the COBOL `DISPLAY`s them.

## COBOL paragraph → Java component

| COBOL (`CBTRN02C.cbl`)                         | Java                                                                                  |
|------------------------------------------------|---------------------------------------------------------------------------------------|
| `PROCEDURE DIVISION` main loop, `WS-TRANSACTION-COUNT` / `WS-REJECT-COUNT`, end-of-job `DISPLAY`s, `RETURN-CODE 4` | `PostTranJobConfig` (job `POSTTRAN`, single chunk step `CBTRN02C`), `PostTranCounters`, `PostTranJobListener` |
| `0000-DALYTRAN-OPEN` / `9000-DALYTRAN-CLOSE`   | `FlatFileItemReader` lifecycle (`PostTranJobConfig.dalytranReader`)                   |
| `0100-TRANFILE-OPEN` … `0500-TCATBALF-OPEN`, `9100…9500-*-CLOSE` | JPA datasource / `EntityManager` managed by Spring                     |
| `1000-DALYTRAN-GET-NEXT`                       | `FlatFileItemReader` + `DailyTransactionLineMapper` (`FixedLengthTokenizer`, ranges in `DailyTransactionLayout`) |
| `1500-VALIDATE-TRAN`                           | `TransactionValidator.validate`                                                       |
| `1500-A-LOOKUP-XREF` (reason 100)              | `TransactionValidator` → `CardXrefRepository.findById(cardNum)`                       |
| `1500-B-LOOKUP-ACCT` (reason 101)              | `TransactionValidator` → `AccountRepository.findById(xref.acctId)`                    |
| credit-limit check in `1500-B-LOOKUP-ACCT` (reason 102) | `TransactionValidator`: `creditLimit < cycCredit - cycDebit + amount`         |
| expiration check in `1500-B-LOOKUP-ACCT` (reason 103)   | `TransactionValidator`: `expirationDate < origTs(1:10)` (string compare, as COBOL) |
| `2000-POST-TRANSACTION`                        | `DailyTransactionProcessor` → `TransactionPoster.post`                                |
| `2000-POST-TRANSACTION` field moves            | `TransactionPoster.toTransaction`                                                     |
| `2700-UPDATE-TCATBAL` / `2700-A-CREATE-TCATBAL-REC` / `2700-B-UPDATE-TCATBAL-REC` | `TransactionPoster.post` (find-or-create) + `TransactionPoster.updateTranCatBalance` |
| `2800-UPDATE-ACCOUNT-REC`                      | `TransactionPoster.updateAccount`                                                     |
| `2900-WRITE-TRANSACTION-FILE`                  | `PostedTransactionWriter` (`TransactionRepository.save`, duplicate key → `DuplicateTransactionException`, like VSAM status 22) |
| `REWRITE` of account / tcatbal records         | `PostedTransactionWriter` (`AccountRepository.save`, `TranCatBalanceRepository.save`) |
| `2500-WRITE-REJECT-REC`                        | `RejectLineAggregator` → `RejectRecordLayout.format` → `FlatFileItemWriter`           |
| `Z-GET-DB2-FORMAT-TIMESTAMP`                   | `Db2Timestamp`                                                                        |
| `Z-ABEND-PROGRAM` / `Z-DISPLAY-IO-STATUS`      | exceptions propagate and fail the step                                                |
| routing posted vs. rejected                    | `ClassifierCompositeItemWriter` in `PostTranJobConfig.outcomeWriter`                  |

Semantics preserved deliberately:

* Reasons 102 and 103 are both evaluated; when both fail, 103 wins (the COBOL `MOVE`s
  overwrite `WS-VALIDATION-FAIL-REASON`).
* `IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL` — equality passes.
* `DALYTRAN-AMT >= 0` goes to cycle **credit**, so a zero amount counts as credit.
* Expiration is a plain alphanumeric compare of `YYYY-MM-DD` strings.
* `DB2-MIL` is `PIC 9(02)` in the copybook, so the processing timestamp is
  `YYYY-MM-DD-HH.MM.SS.hh0000` (2 fractional digits + literal `0000`, 26 bytes).
* The reject record is the original 350-byte input image followed by the 80-byte trailer;
  the input record is never re-formatted.
* Counts follow the COBOL: every record read increments "processed", rejects also increment
  "rejected".

## Field types

COBOL DISPLAY numerics are parsed by `CobolFields`:

* `PIC 9(n)` → `long` / `int`.
* `PIC S9(n)V99` → `BigDecimal` scale 2. Trailing overpunch sign as found in the ASCII
  extracts is honoured (`{`=+0, `A`–`I`=+1..+9, `}`=-0, `J`–`R`=-1..-9); an explicit
  leading `+`/`-` is also accepted.
* `PIC X(n)` → `String`, kept at exact width (space padded) so output images are byte-identical.

## Copybook → Java model

### `CVTRA06Y` — `DALYTRAN-RECORD` (350) → `DailyTransaction`

| Pos     | COBOL field               | PIC        | Java field     | Type                |
|---------|---------------------------|------------|----------------|---------------------|
| 1–16    | `DALYTRAN-ID`             | X(16)      | `tranId`       | String              |
| 17–18   | `DALYTRAN-TYPE-CD`        | X(02)      | `typeCd`       | String              |
| 19–22   | `DALYTRAN-CAT-CD`         | 9(04)      | `catCd`        | int                 |
| 23–32   | `DALYTRAN-SOURCE`         | X(10)      | `source`       | String              |
| 33–132  | `DALYTRAN-DESC`           | X(100)     | `description`  | String              |
| 133–143 | `DALYTRAN-AMT`            | S9(09)V99  | `amount`       | BigDecimal(2)       |
| 144–152 | `DALYTRAN-MERCHANT-ID`    | 9(09)      | `merchantId`   | long                |
| 153–202 | `DALYTRAN-MERCHANT-NAME`  | X(50)      | `merchantName` | String              |
| 203–252 | `DALYTRAN-MERCHANT-CITY`  | X(50)      | `merchantCity` | String              |
| 253–262 | `DALYTRAN-MERCHANT-ZIP`   | X(10)      | `merchantZip`  | String              |
| 263–278 | `DALYTRAN-CARD-NUM`       | X(16)      | `cardNum`      | String              |
| 279–304 | `DALYTRAN-ORIG-TS`        | X(26)      | `origTs`       | String (`getOrigDate()` = first 10) |
| 305–330 | `DALYTRAN-PROC-TS`        | X(26)      | `procTs`       | String              |
| 331–350 | `FILLER`                  | X(20)      | —              | (kept in `rawRecord`) |

`rawRecord` holds the untouched 350-byte input image used for rejects.

### `CVTRA05Y` — `TRAN-RECORD` (350) → `Transaction` (table `TRANSACT`)

Same layout as `CVTRA06Y` with the `TRAN-` prefix; columns `TRAN_ID` (PK), `TRAN_TYPE_CD`,
`TRAN_CAT_CD`, `TRAN_SOURCE`, `TRAN_DESC`, `TRAN_AMT` (DECIMAL 11,2), `TRAN_MERCHANT_ID`,
`TRAN_MERCHANT_NAME`, `TRAN_MERCHANT_CITY`, `TRAN_MERCHANT_ZIP`, `TRAN_CARD_NUM`,
`TRAN_ORIG_TS`, `TRAN_PROC_TS`. `TRAN-PROC-TS` is set from `Db2Timestamp`; every other
field is copied 1:1 from the daily record (`TransactionPoster.toTransaction`).

### `CVACT03Y` — `CARD-XREF-RECORD` (50) → `CardXref` (table `CARD_XREF`)

| Pos   | COBOL field         | PIC   | Java field | Column     |
|-------|---------------------|-------|------------|------------|
| 1–16  | `XREF-CARD-NUM`     | X(16) | `cardNum`  | `CARD_NUM` (PK) |
| 17–25 | `XREF-CUST-ID`      | 9(09) | `custId`   | `CUST_ID`  |
| 26–36 | `XREF-ACCT-ID`      | 9(11) | `acctId`   | `ACCT_ID`  |
| 37–50 | `FILLER`            | X(14) | —          |            |

### `CVACT01Y` — `ACCOUNT-RECORD` (300) → `Account` (table `ACCOUNT`)

| Pos     | COBOL field              | PIC        | Java field        | Column               |
|---------|--------------------------|------------|-------------------|----------------------|
| 1–11    | `ACCT-ID`                | 9(11)      | `acctId`          | `ACCT_ID` (PK)       |
| 12      | `ACCT-ACTIVE-STATUS`     | X(01)      | `activeStatus`    | `ACTIVE_STATUS`      |
| 13–24   | `ACCT-CURR-BAL`          | S9(10)V99  | `currBal`         | `CURR_BAL`           |
| 25–36   | `ACCT-CREDIT-LIMIT`      | S9(10)V99  | `creditLimit`     | `CREDIT_LIMIT`       |
| 37–48   | `ACCT-CASH-CREDIT-LIMIT` | S9(10)V99  | `cashCreditLimit` | `CASH_CREDIT_LIMIT`  |
| 49–58   | `ACCT-OPEN-DATE`         | X(10)      | `openDate`        | `OPEN_DATE`          |
| 59–68   | `ACCT-EXPIRAION-DATE`    | X(10)      | `expirationDate`  | `EXPIRATION_DATE`    |
| 69–78   | `ACCT-REISSUE-DATE`      | X(10)      | `reissueDate`     | `REISSUE_DATE`       |
| 79–90   | `ACCT-CURR-CYC-CREDIT`   | S9(10)V99  | `currCycCredit`   | `CURR_CYC_CREDIT`    |
| 91–102  | `ACCT-CURR-CYC-DEBIT`    | S9(10)V99  | `currCycDebit`    | `CURR_CYC_DEBIT`     |
| 103–112 | `ACCT-ADDR-ZIP`          | X(10)      | `addrZip`         | `ADDR_ZIP`           |
| 113–122 | `ACCT-GROUP-ID`          | X(10)      | `groupId`         | `GROUP_ID`           |
| 123–300 | `FILLER`                 | X(178)     | —                 |                      |

Only `CURR_BAL`, `CURR_CYC_CREDIT` and `CURR_CYC_DEBIT` are modified by the job.

### `CVTRA01Y` — `TRAN-CAT-BAL-RECORD` (50) → `TranCatBalance` (table `TRAN_CAT_BAL`)

| Pos   | COBOL field          | PIC        | Java field        | Column        |
|-------|----------------------|------------|-------------------|---------------|
| 1–11  | `TRANCAT-ACCT-ID`    | 9(11)      | `id.acctId`       | `ACCT_ID` (PK) |
| 12–13 | `TRANCAT-TYPE-CD`    | X(02)      | `id.typeCd`       | `TYPE_CD` (PK) |
| 14–17 | `TRANCAT-CD`         | 9(04)      | `id.catCd`        | `CAT_CD` (PK)  |
| 18–28 | `TRAN-CAT-BAL`       | S9(09)V99  | `balance`         | `TRAN_CAT_BAL` |
| 29–50 | `FILLER`             | X(22)      | —                 |               |

The composite key `TranCatBalanceId` corresponds to `FD-TRAN-CAT-KEY`
(`FD-TRANCAT-ACCT-ID` + `FD-TRANCAT-TYPE-CD` + `FD-TRANCAT-CD`).

### Reject record (430) — `RejectRecordLayout`

| Pos     | COBOL field                                    | PIC   | Source                          |
|---------|------------------------------------------------|-------|---------------------------------|
| 1–350   | `REJECT-TRAN-DATA`                             | X(350)| `DailyTransaction.rawRecord`    |
| 351–354 | `VALIDATION-TRAILER` / `WS-VALIDATION-FAIL-REASON` | 9(04) | `ValidationResult.reasonCode`   |
| 355–430 | `WS-VALIDATION-FAIL-REASON-DESC`               | X(76) | `ValidationResult.description`  |

Reason codes (`ValidationResult`): `0100 INVALID CARD NUMBER FOUND`,
`0101 ACCOUNT RECORD NOT FOUND`, `0102 OVERLIMIT TRANSACTION`,
`0103 TRANSACTION RECEIVED AFTER ACCT EXPIRATION`.

## Tests

* `CobolFieldsTest`, `Db2TimestampTest` — zoned/overpunch parsing, timestamp format.
* `LayoutTest` — every copybook offset against records from `app/data/ASCII`, 430-byte reject image.
* `TransactionValidatorTest` — reasons 100/101/102/103, boundary equality, 103-overrides-102.
* `TransactionPosterTest` — balance / cycle credit / cycle debit math, category balance create vs. update, transaction mapping.
* `PostTranJobIntegrationTest` — runs job `POSTTRAN` end to end on an H2 database loaded from
  fixed-width extracts, with a 7-record DALYTRAN covering all posting and reject paths;
  asserts table contents, the reject file bytes and exit code 4.
