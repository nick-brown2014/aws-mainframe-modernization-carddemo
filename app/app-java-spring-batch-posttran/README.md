# CardDemo POSTTRAN (CBTRN02C) — Java / Spring Batch

Java 17 + Spring Boot 3.3 + Spring Batch 5.1 re-implementation of the COBOL batch program
`app/cbl/CBTRN02C.cbl` (JCL `app/jcl/POSTTRAN.jcl`), following
[`docs/specs/CBTRN02C-migration-spec.md`](../../docs/specs/CBTRN02C-migration-spec.md).

The job reads the 350-byte daily transaction file (`CVTRA06Y`), validates each record against
`card_xref` / `account`, posts accepted transactions to `tran_cat_bal`, `account` and `transaction`,
and writes rejected records to a 430-byte fixed-width rejects file (record + 4-digit reason + 76-char text).

## Layout

| Package | Contents |
|---|---|
| `model` | Records for the five copybooks (`DailyTransaction`, `CardXref`, `Account`, `TranCategoryBalance`, `Transaction`), `RejectRecord`, `PostingResult` |
| `io` | `ZonedDecimal` (sign-overpunch codec), `Db2TimestampFormatter`, `FixedLengthTokenizer` ranges, copybook parsers/formatters, reject line aggregator |
| `repo` | JDBC repositories for the four tables (`src/main/resources/schema.sql`) |
| `load` | `MasterFileLoader` for `cardxref.txt`, `acctdata.txt`, `tcatbal.txt` |
| `validation` | Rules 100/101/102/103 (`TransactionValidator`) |
| `posting` | `PostTranItemProcessor`, `PostingService`, `ChunkStateCache`, `PostedItemWriter` |
| `listener` | Console output and exit-code mapping |
| `PostTranJobConfig` | `postTranJob` — one chunk-oriented step `postTransactionsStep` |

## Build

Requires JDK 17+ and Maven 3.6+.

```bash
cd app/app-java-spring-batch-posttran
mvn -q verify            # unit tests (surefire) + integration tests (failsafe, H2 in-memory)
mvn -q package -DskipTests
```

## Run

```bash
java -jar target/carddemo-batch-posttran-1.0.0-SNAPSHOT.jar \
  --dailyTranFile=../data/ASCII/dailytran.txt \
  --rejectsFile=/tmp/dalyrejs.txt
echo "exit=$?"
```

Job parameters (spec §6.2):

| Argument | Required | Meaning |
|---|---|---|
| `--dailyTranFile=<path>` | yes | 350-byte DALYTRAN input |
| `--rejectsFile=<path>` | yes | 430-byte DALYREJS output (created/overwritten; empty when no rejects) |
| `--runDate=<yyyy-mm-dd>` | no | Identifying parameter; defaults to today in `posttran.clock.zone`. Re-running the same `runDate` after a failure restarts the failed `JobInstance` from the last committed chunk |
| `--chunkSize=<n>` | no | Commit interval, default 100 |

Optional one-shot loaders (run before the job, useful for demos/tests):

```bash
  --loadCardXrefFile=../data/ASCII/cardxref.txt \
  --loadAcctFile=../data/ASCII/acctdata.txt \
  --loadTcatbalFile=../data/ASCII/tcatbal.txt
```

The default datasource is an in-memory H2 database (`spring.datasource.url` in `application.properties`);
point it at a real RDBMS with `--spring.datasource.url=...` (`schema.sql` is applied on start with `CREATE TABLE IF NOT EXISTS`).

### Console output (spec §7.4)

```
START OF EXECUTION OF PROGRAM CBTRN02C
TCATBAL record not found for key : 00000000011030001.. Creating.
TRANSACTIONS PROCESSED :000000300
TRANSACTIONS REJECTED  :000000038
END OF EXECUTION OF PROGRAM CBTRN02C
```

### Exit codes (spec §7.5)

| Code | Meaning |
|---|---|
| 0 | Completed, no rejects |
| 4 | Completed, at least one record rejected |
| 8 | Failed (I/O error, malformed record, duplicate `tran_id`, database unavailable, missing parameter). The failed chunk is rolled back including its rejects-file lines |

### Configuration

| Property | Default | Purpose |
|---|---|---|
| `posttran.clock.zone` | `America/Chicago` | Zone used to generate `TRAN-PROC-TS` (`YYYY-MM-DD-HH.MM.SS.hh0000`) |
| `posttran.input.charset` | `UTF-8` | Input encoding (`IBM037`/`IBM1047` for EBCDIC transfers) |
| `posttran.output.charset` | `UTF-8` | Rejects-file encoding |
| `posttran.output.line-separator` | `\n` | Set to empty for true fixed-block (RECFM=F) output |

## Tests

```bash
mvn -q test      # unit tests: *Test
mvn -q verify    # + integration tests: *IT
```

| Class | Acceptance criteria |
|---|---|
| `io.ZonedDecimalTest` | AC-R1, AC-R2, AC-R3 (incl. round-trip of all 300 sample amounts) |
| `io.DailyTransactionReaderTest` | AC-R4, AC-R5 (reader exception) |
| `io.Db2TimestampFormatterTest` | AC-T1 |
| `validation.PostTranItemProcessorTest` | AC-V100, AC-V101, AC-V102a/b/c, AC-V103a/b, AC-V-PREC, AC-V-ORDER |
| `posting.PostingServiceTest` | AC-P1, AC-P2, AC-P3, AC-P4, AC-P5, AC-P6, AC-P7, AC-P8 (arithmetic) |
| `PostTranJobIT` | AC-R5 (step FAILED), AC-P4–P9 (persisted), AC-O1, AC-O3, AC-O4, AC-X2, 300-record end-to-end run |
| `PostTranApplicationExitCodeIT` | AC-O2, AC-O3, AC-X1 via the real `main` entry point |
| `PostTranParityIT` | §8.3 golden-file parity: rejects, TRANSACT (except `proc_ts`), ACCTDATA, TCATBAL (except FILLER), SYSOUT and RC vs. the COBOL run |

### Golden files (spec §8.1)

`src/test/resources/golden/posttran/` holds the outputs of the real `CBTRN02C` run over `app/data/ASCII`:
`dalyrejs.txt`, `transact.txt`, `acctdata.txt`, `tcatbal.txt`, `sysout.txt`, `returncode.txt`. They are produced by
`golden-harness/run-golden.sh`, which compiles `app/cbl/CBTRN02C.cbl` with GnuCOBOL 3.x (`-fsign=EBCDIC`, so the
ASCII fixtures' `{`/`A-I`/`}`/`J-R` sign overpunch decodes as on the mainframe), loads the master files into
indexed files with `golden-harness/LOADIDX.cbl`, runs the program and dumps the masters in key order.

```bash
sudo apt-get install -y gnucobol   # 3.1+, BDB indexed-file handler
golden-harness/run-golden.sh       # rewrites src/test/resources/golden/posttran/
```
