# POSTTRAN (CBTRN02C) Spring Batch migration

Java 17 / Spring Boot 3.5 / Spring Batch implementation of
[`CBTRN02C.cbl`](../../app/cbl/CBTRN02C.cbl), invoked by
[`POSTTRAN.jcl`](../../app/jcl/POSTTRAN.jcl). The original sources remain unchanged.

## Run

From this directory, with Maven 3.9+:

```sh
mvn test
mvn package
java -jar target/cbtrn02c-1.0.0.jar \
  --posttran.input=../../app/data/ASCII/dailytran.txt \
  --posttran.rejects=./dailyrejs.txt \
  --posttran.seed-directory=../../app/data/ASCII
echo $?
```

The demo uses an **in-memory H2 database**; each process starts empty.
`posttran.seed-directory` optionally loads `acctdata.txt`, `cardxref.txt`, and
`tcatbal.txt` from `app/data/ASCII` before posting. The sample card xref omits
its 14-byte filler (36 rather than 50 bytes); the demo importer pads it.
Production imports should supply the full copybook layouts. The source input
must have one 350-byte US-ASCII record per line (LF or CRLF). For a native
FB/RECFM=F dataset without line separators, first convert it to newline-delimited
records without changing its 350-byte payloads or signed overpunch bytes.
EBCDIC data must be transcoded to ASCII **with zoned-sign conversion** before
using this reader. The demo ASCII files already contain signed display
overpunch characters. Malformed lengths, encodings, and numeric fields fail
the step rather than becoming business rejects.

Set `--spring.datasource.url=jdbc:...`, `--spring.datasource.username=...`,
and `--spring.datasource.password=...` to use a durable JDBC datasource. Add
the corresponding database driver dependency to `pom.xml`. The four table
definitions are in `src/main/resources/schema.sql`; provision them and
Spring Batch's metadata tables before a production run, and disable automatic
initialization with `--spring.sql.init.mode=never` and
`--spring.batch.jdbc.initialize-schema=never`. Import the VSAM XREF, ACCOUNT,
and TCATBAL records into their matching tables before the job. The sample
loader is for demonstrations only. Duplicate transaction IDs cause a FAILED
job, as an indexed VSAM write would; do not re-post the same daily file into
the same transaction master without an explicit recovery plan.

Process exit code is **0** with no rejects, **4** with one or more rejects,
and **1** on a failed job. The Spring Batch job exit status is `COMPLETED`,
`4`, or `FAILED` respectively. Inspect the batch metadata and logs for
read/reject counts. `dailyrejs.txt` contains 430-byte payloads (350 original
bytes, 4-digit reason, 76-character space-padded description) separated by
line breaks. An empty reject file is created when there are no rejects.

## Mapping

| COBOL/JCL source | Java implementation |
| --- | --- |
| `DALYTRAN` / `1000-DALYTRAN-GET-NEXT` | `dailyReader`, strict `FixedLengthTokenizer` (350-byte lines), `DailyTransaction` |
| `1500-VALIDATE-TRAN` / A / B | `PostingProcessor` (`100` card, `101` account, `102` limit, `103` expiration) |
| `2000-POST-TRANSACTION` / `Z-GET-DB2-FORMAT-TIMESTAMP` | `PostingWriter`, `Transaction.posted` |
| `2700-UPDATE-TCATBAL` / `TCATBALF` | `PostingRepository.balance/saveBalance`; composite `(acct_id, type_cd, cat_cd)` key |
| `2800-UPDATE-ACCOUNT-REC` / `ACCTFILE` | `Account.post`, `PostingRepository.updateAccount`; 11-digit account ID key |
| `2900-WRITE-TRANSACTION-FILE` / `TRANFILE` | `PostingRepository.insertTransaction`; 16-character transaction ID key |
| `XREFFILE` | `PostingRepository.card`; 16-character card number key |
| `2500-WRITE-REJECT-REC` / `DALYREJS` | `PostingItem.rejectRecord`, transactional `FlatFileItemWriter` |
| `WS-TRANSACTION-COUNT`, `WS-REJECT-COUNT`, `RETURN-CODE` | `posttranJob` listener and `PostingJobRunner` |
| `9999-ABEND-PROGRAM` | Unhandled I/O/JDBC/parse exceptions fail the chunk and job |

The copybooks `CVTRA06Y` and `CVTRA05Y` define 350-byte transaction
records, `CVACT03Y` a 50-byte card xref, `CVACT01Y` a 300-byte account,
and `CVTRA01Y` a 50-byte category balance. PIC X fields remain strings
(including their padding); PIC 9 IDs/category/merchant codes remain strings
to retain leading zeroes. PIC S9(09)V99 amounts/category balances are
`DECIMAL(11,2)` and PIC S9(10)V99 account balances/limits are
`DECIMAL(12,2)`, mapped to `BigDecimal`. These copybooks use **signed
DISPLAY/zoned decimals**, not COMP-3: the last byte uses positive `{`/`A`–`I`
or negative `}`/`J`–`R` overpunch (plain digits are also accepted).
The raw daily record is preserved verbatim in rejects.

The requested `yyyy-MM-dd-HH.mm.ss.SSS0000` processing timestamp is
**27 characters**, one longer than COBOL's PIC X(26); the relational
`proc_ts` column deliberately supports 27. Only processing timestamps
diverge from the copybook length. The original daily timestamp remains 26
characters. For strict COBOL file interchange, an explicit 26-character
format conversion would be required.

Validation follows the original order: missing xref, missing account,
credit-limit check `currCycCredit - currCycDebit + amount`, then lexical
comparison of `expirationDate` to the first ten original timestamp
characters. If both limit and expiration fail, `103` overrides `102` as in
the COBOL. Credits add to the credit cycle total; negative amounts add
*as negative numbers* to the debit cycle total, exactly as in paragraph 2800.

The single non-parallel step commits one record per JDBC transaction
(chunk size **1**). This intentionally ensures the next transaction's
validation sees the previous transaction's updated account balance.
Category balance, account, and transaction-master writes share that
transaction; the flat reject writer buffers output until chunk commit.
As with other flat-file batch jobs, a crash between file and database
commit requires operator reconciliation; avoid concurrent POSTTRAN jobs
on the same database and output path.
