# CBIMPORT Migration Specification: COBOL Batch to Java + Spring Batch

| | |
|---|---|
| Source program | `app/cbl/CBIMPORT.cbl` (CardDemo v2.0-44-gb6e9c27-254) |
| Source JCL | `app/jcl/CBIMPORT.jcl`, `STEP01 EXEC PGM=CBIMPORT` |
| Copybooks | `app/cpy/CVEXPORT.cpy` (input), `CVCUS01Y.cpy`, `CVACT01Y.cpy`, `CVACT03Y.cpy`, `CVTRA05Y.cpy`, `CVACT02Y.cpy` (outputs) |
| Companion spec | `docs/specs/CBEXPORT-migration-spec.md` — **§4.2 there is the authoritative 500-byte export record layout; this document references it and does not redefine it** |
| Shared-table reference | `docs/specs/CBTRN02C-migration-spec.md` §4.9 (`account`, `card_xref`, `transaction`); `docs/specs/CB-read-utilities-migration-spec.md` §4.6 (`customer`, `card`) |
| Sample data | `app/data/EBCDIC/AWS.M2.CARDDEMO.EXPORT.DATA.PS` (input example, 500 records) |
| Target | Java 17+, Spring Boot 3.x, Spring Batch 5.x, JDBC/JPA repository over an RDBMS |
| Status | Draft for review |

Line references (`L123`) point into `CBIMPORT.cbl` unless prefixed (`CBIMPORT.jcl L33`,
`CVEXPORT L25`).

---

## 1. Overview & Purpose

`CBIMPORT` is the receiving half of the CardDemo branch-migration pair. It reads the 500-byte
multi-type export file produced by `CBEXPORT` (`EXPFILE`) in sequence-number order and
**de-multiplexes** it into five fixed-length sequential files, one per master copybook, plus an
error file (`CBIMPORT.jcl L20`, comments L30-L32):

| Export type | Output DD | Output record | LRECL | Paragraph |
|---|---|---|---|---|
| `C` | `CUSTOUT` | `CUSTOMER-RECORD` (`CVCUS01Y`) | 500 | `2300` L288-L320 |
| `A` | `ACCTOUT` | `ACCOUNT-RECORD` (`CVACT01Y`) | 300 | `2400` L323-L349 |
| `X` | `XREFOUT` | `CARD-XREF-RECORD` (`CVACT03Y`) | 50 | `2500` L352-L369 |
| `T` | `TRNXOUT` | `TRAN-RECORD` (`CVTRA05Y`) | 350 | `2600` L372-L399 |
| `D` | `CARDOUT` | `CARD-RECORD` (`CVACT02Y`) | 150 | `2650` L402-L422 |
| other | `ERROUT` | `WS-ERROR-RECORD` | 132 | `2700`/`2750` L425-L446 |

The outputs are plain PS datasets (`DISP=(NEW,CATLG,DELETE)`, `CBIMPORT.jcl L33-L60`) in the exact
layouts of the master copybooks, i.e. ready to be `REPRO`'d into the target installation's KSDS
files by a later job (not part of this program).

Facts an implementer needs up front:

* **The program performs no validation.** The source comment "Validate data integrity using
  checksums" (L31) is aspirational: `3000-VALIDATE-IMPORT` (L449-L452) only prints two fixed
  messages. There are no checksums, no referential checks, no duplicate checks, no field
  validation. The **only** error condition is an unrecognised `EXPORT-REC-TYPE`.
* Every recognised record is written through with straight `MOVE`s; the packed/binary fields of
  the export payload are converted back to the zoned/display pictures of the master copybooks by
  COBOL `MOVE` semantics.
* **The shipped JCL is broken**: it has no `CARDOUT` DD (`CBIMPORT.jcl L33-L60` define `CUSTOUT`,
  `ACCTOUT`, `XREFOUT`, `TRNXOUT`, `ERROUT` only), so `OPEN OUTPUT CARD-OUTPUT` (L233) fails and the
  program abends before reading a single record. See §5.4.
* Normal completion is `RETURN-CODE 0` regardless of how many unknown-type records were written to
  `ERROUT`; there is no warning return code.

---

## 2. Current-State (COBOL) Analysis

### 2.1 Control flow

```
0000-MAIN-PROCESSING                              L165
 ├─ 1000-INITIALIZE                               L174
 │   ├─ DISPLAY 'CBIMPORT: Starting Customer Data Import'
 │   ├─ build WS-IMPORT-DATE 'YYYY-MM-DD' and WS-IMPORT-TIME 'HH:MM:SS' from FUNCTION CURRENT-DATE   L178-L188
 │   ├─ 1100-OPEN-FILES                           L196   (OPEN INPUT EXPFILE; OPEN OUTPUT ×6; abend on any non-'00')
 │   ├─ DISPLAY 'CBIMPORT: Import Date: ' WS-IMPORT-DATE
 │   └─ DISPLAY 'CBIMPORT: Import Time: ' WS-IMPORT-TIME
 ├─ 2000-PROCESS-EXPORT-FILE                      L248
 │   ├─ 2100-READ-EXPORT-RECORD (priming read)
 │   └─ PERFORM UNTIL WS-EXPORT-EOF
 │         ADD 1 TO WS-TOTAL-RECORDS-READ
 │         2200-PROCESS-RECORD-BY-TYPE            L270   EVALUATE EXPORT-REC-TYPE → 2300/2400/2500/2600/2650/2700
 │         2100-READ-EXPORT-RECORD
 ├─ 3000-VALIDATE-IMPORT                          L449   (two DISPLAYs, nothing else)
 ├─ 4000-FINALIZE                                 L455   (CLOSE ×7, DISPLAY nine lines)
 └─ GOBACK                                        L171
```

Each type paragraph has the same shape (customers, L288-L320):

```
INITIALIZE CUSTOMER-RECORD                 -- L290: FD record → numerics 0, alphanumerics spaces (filler spaces)
MOVE EXP-* TO CUST-*  (18 moves)           -- L293-L310
WRITE CUSTOMER-RECORD                      -- L312
IF NOT WS-CUSTOMER-OK → DISPLAY 'ERROR: Writing customer record, Status: ' nn ; abend  -- L314-L318
ADD 1 TO WS-CUSTOMER-RECORDS-IMPORTED      -- L320
```

### 2.2 Paragraph reference

| Paragraph | Lines | Behaviour |
|---|---|---|
| `0000-MAIN-PROCESSING` | L165-L171 | Straight-line; `GOBACK` with `RETURN-CODE` 0. |
| `1000-INITIALIZE` | L174-L193 | Banner; `WS-IMPORT-DATE` ← `CURRENT-DATE(1:4) '-' (5:2) '-' (7:2)`; `WS-IMPORT-TIME` ← `(9:2) ':' (11:2) ':' (13:2)`; open files; date/time banners. (Uses `FUNCTION CURRENT-DATE`, unlike CBEXPORT's `ACCEPT`.) |
| `1100-OPEN-FILES` | L196-L245 | `OPEN INPUT EXPORT-INPUT`; `OPEN OUTPUT` `CUSTOMER-OUTPUT`, `ACCOUNT-OUTPUT`, `XREF-OUTPUT`, `TRANSACTION-OUTPUT`, `CARD-OUTPUT`, `ERROR-OUTPUT`, in that order, each with `IF NOT …-OK → DISPLAY 'ERROR: Cannot open <select-name>, Status: ' nn → 9999-ABEND-PROGRAM`. |
| `2000-PROCESS-EXPORT-FILE` | L248-L256 | Priming read + loop; `WS-TOTAL-RECORDS-READ` counts every record including unknown types. |
| `2100-READ-EXPORT-RECORD` | L259-L267 | `READ EXPORT-INPUT INTO EXPORT-RECORD` (FD `X(500)` → WORKING-STORAGE copy of `CVEXPORT`). Status ∉ {`00`,`10`} → `DISPLAY 'ERROR: Reading EXPORT-INPUT, Status: ' nn` → abend. |
| `2200-PROCESS-RECORD-BY-TYPE` | L270-L285 | `EVALUATE EXPORT-REC-TYPE` on `'C'`,`'A'`,`'X'`,`'T'`,`'D'`, `WHEN OTHER` → `2700`. Comparison is exact, case-sensitive, single EBCDIC byte. |
| `2300-PROCESS-CUSTOMER-RECORD` | L288-L320 | `C` → `CUSTOMER-RECORD`, write to `CUSTOUT`, count. Field map §4.2. |
| `2400-PROCESS-ACCOUNT-RECORD` | L323-L349 | `A` → `ACCOUNT-RECORD`, write to `ACCTOUT`. §4.3. |
| `2500-PROCESS-XREF-RECORD` | L352-L369 | `X` → `CARD-XREF-RECORD`, write to `XREFOUT`. §4.4. |
| `2600-PROCESS-TRAN-RECORD` | L372-L399 | `T` → `TRAN-RECORD`, write to `TRNXOUT`. §4.5. |
| `2650-PROCESS-CARD-RECORD` | L402-L422 | `D` → `CARD-RECORD`, write to `CARDOUT`. §4.6. |
| `2700-PROCESS-UNKNOWN-RECORD` | L425-L434 | `ADD 1 TO WS-UNKNOWN-RECORD-TYPE-COUNT`; `ERR-TIMESTAMP ← FUNCTION CURRENT-DATE` (21 chars into `X(26)`, space padded); `ERR-RECORD-TYPE ← EXPORT-REC-TYPE`; `ERR-SEQUENCE ← EXPORT-SEQUENCE-NUM` (`9(9) COMP` → `9(07)`: **high-order truncation** above 9 999 999); `ERR-MESSAGE ← 'Unknown record type encountered'`; `PERFORM 2750-WRITE-ERROR`. |
| `2750-WRITE-ERROR` | L437-L446 | `WRITE ERROR-OUTPUT-RECORD FROM WS-ERROR-RECORD` (130 bytes → 132-byte FD, space padded). If status ≠ `00` → `DISPLAY 'ERROR: Writing error record, Status: ' nn` **but does not abend** (contrast every other write). `ADD 1 TO WS-ERROR-RECORDS-WRITTEN` **unconditionally**, so the counter can exceed the number of records actually written. |
| `3000-VALIDATE-IMPORT` | L449-L452 | `DISPLAY 'CBIMPORT: Import validation completed'`, `DISPLAY 'CBIMPORT: No validation errors detected'`. No logic. |
| `4000-FINALIZE` | L455-L478 | `CLOSE` all seven files (status ignored); nine `DISPLAY` lines (§7.3). |
| `9999-ABEND-PROGRAM` | L481-L484 | `DISPLAY 'CBIMPORT: ABENDING PROGRAM'`; `CALL 'CEE3ABD'` **without arguments** (same defect as CBEXPORT §2.2). |

### 2.3 File SELECT / FILE STATUS handling

| Select name | DD | Lines | Organization / access | Key | Status field (`88`s) | Checked |
|---|---|---|---|---|---|---|
| `EXPORT-INPUT` | `EXPFILE` | L37-L41 | INDEXED / SEQUENTIAL | `EXPORT-SEQUENCE-NUM` (WORKING-STORAGE item — see note) | `WS-EXPORT-STATUS` (`EOF='10'`, `OK='00'`) | open L199, read L263 |
| `CUSTOMER-OUTPUT` | `CUSTOUT` | L43-L46 | SEQUENTIAL / SEQUENTIAL | — | `WS-CUSTOMER-STATUS` (`OK='00'`) | open L206, write L314 |
| `ACCOUNT-OUTPUT` | `ACCTOUT` | L48-L51 | SEQUENTIAL | — | `WS-ACCOUNT-STATUS` | open L213, write L343 |
| `XREF-OUTPUT` | `XREFOUT` | L53-L56 | SEQUENTIAL | — | `WS-XREF-STATUS` | open L220, write L363 |
| `TRANSACTION-OUTPUT` | `TRNXOUT` | L58-L61 | SEQUENTIAL | — | `WS-TRANSACTION-STATUS` | open L227, write L393 |
| `CARD-OUTPUT` | `CARDOUT` | L63-L66 | SEQUENTIAL | — | `WS-CARD-STATUS` | open L234, write L416 |
| `ERROR-OUTPUT` | `ERROUT` | L68-L71 | SEQUENTIAL | — | `WS-ERROR-STATUS` | open L241, write L441 (**no abend**) |

Notes:

* The FD for `EXPORT-INPUT` is a bare `PIC X(500)` (L76-L79) while `RECORD KEY IS
  EXPORT-SEQUENCE-NUM` names a field inside the WORKING-STORAGE copy of `CVEXPORT` (L113). Standard
  COBOL requires the record key to be in the file's record description; Enterprise COBOL accepts
  this only because the key is resolved by offset/length and the data name is merely used to locate
  them. The program never uses the key (sequential access), so this has no runtime effect, but it
  will not compile on stricter compilers. Java equivalent: none (flat file read).
* Sequential `READ` of the KSDS returns records in ascending `EXPORT-SEQUENCE-NUM` order —
  the write order of `CBEXPORT`. Gaps in the sequence (as in the sample: 451-459 absent) are
  invisible to the program.
* All output FDs are `RECORDING MODE F` with `RECORD CONTAINS n` matching the copybook length
  (L81-L109); the JCL `DCB=(RECFM=FB,LRECL=n)` agrees for the five DDs present.
* Only `'00'` counts as OK for writes; `'10'` is treated as OK only for the input read.

---

## 3. Files / Datasets

From `app/jcl/CBIMPORT.jcl`:

| DD | Dataset | JCL lines | Org / RECFM | LRECL | Key | Copybook | Direction |
|---|---|---|---|---|---|---|---|
| `EXPFILE` | `AWS.M2.CARDDEMO.EXPORT.DATA` | L28-L29 | VSAM KSDS (defined by `CBEXPORT.jcl` STEP01) | 500 | 4-byte binary seq (see CBEXPORT spec §3/§5.4 for the off-by-one) | `CVEXPORT` | in |
| `CUSTOUT` | `AWS.M2.CARDDEMO.CUSTDATA.IMPORT` | L33-L37 | PS FB, `DISP=(NEW,CATLG,DELETE)` | 500 | — | `CVCUS01Y` | out |
| `ACCTOUT` | `AWS.M2.CARDDEMO.ACCTDATA.IMPORT` | L38-L42 | PS FB | 300 | — | `CVACT01Y` | out |
| `XREFOUT` | `AWS.M2.CARDDEMO.CARDXREF.IMPORT` | L43-L47 | PS FB | 50 | — | `CVACT03Y` | out |
| `TRNXOUT` | `AWS.M2.CARDDEMO.TRANSACT.IMPORT` | L48-L52 | PS FB | 350 | — | `CVTRA05Y` | out |
| `CARDOUT` | **missing from JCL** (program expects it, L63) | — | should be PS FB | 150 | — | `CVACT02Y` | out |
| `ERROUT` | `AWS.M2.CARDDEMO.IMPORT.ERRORS` | L56-L60 | PS FB | 132 | — | `WS-ERROR-RECORD` (L152-L160) | out |
| `SYSOUT`, `SYSPRINT` | `SYSOUT=*` | L64-L65 | — | — | — | — | console |

`DISP=(NEW,CATLG,DELETE)` means each output must not pre-exist (JCL error otherwise) and is
deleted if the step abends — so a failed run leaves **no** output files. The Java equivalent is
"create, and remove all outputs on failure" (§6.7).

---

## 4. Data Model & Field Mappings

### 4.1 Input record

The input is the 500-byte `CVEXPORT` record. **Layout, offsets, encodings and Java model: see
CBEXPORT spec §4.2 (4.2.1 header, 4.2.2-4.2.6 payloads, 4.2.7 Java model, Appendix A).**
`CBIMPORT` uses `ExportRecordCodec.decode(byte[500])` from the shared module.

The only header field CBIMPORT interprets is `EXPORT-REC-TYPE` (pos 1, L272);
`EXPORT-SEQUENCE-NUM` (pos 28-31) is copied into the error record for unknown types (L431).
`EXPORT-TIMESTAMP`, `EXPORT-BRANCH-ID`, `EXPORT-REGION-CODE` are never read.

Sections 4.2-4.6 below give, per type, the export-payload → master-record mapping, including the
implicit conversion performed by each COBOL `MOVE`. Master-record offsets are 1-based within the
output record. Output copybook layouts: `CVCUS01Y`/`CVACT02Y` are fully specified in the
read-utilities spec §4.4/§4.2; `CVACT01Y`, `CVACT03Y`, `CVTRA05Y` in CBTRN02C §4.4/§4.3/§4.6.

### 4.2 `C` → `CUSTOMER-RECORD` (`CUSTOUT`, 500 bytes) — L293-L310

| Export field (pos, encoding) | → | `CVCUS01Y` field | PIC | Out pos | Conversion by `MOVE` |
|---|---|---|---|---|---|
| `EXP-CUST-ID` (41-44, bin4) | → | `CUST-ID` | `9(09)` | 1-9 | binary → zoned unsigned, zero-padded (`x'00000001'` → `000000001`) |
| `EXP-CUST-FIRST-NAME` (45-69) | → | `CUST-FIRST-NAME` | `X(25)` | 10-34 | copy |
| `EXP-CUST-MIDDLE-NAME` (70-94) | → | `CUST-MIDDLE-NAME` | `X(25)` | 35-59 | copy |
| `EXP-CUST-LAST-NAME` (95-119) | → | `CUST-LAST-NAME` | `X(25)` | 60-84 | copy |
| `EXP-CUST-ADDR-LINE(1)` (120-169) | → | `CUST-ADDR-LINE-1` | `X(50)` | 85-134 | copy |
| `EXP-CUST-ADDR-LINE(2)` (170-219) | → | `CUST-ADDR-LINE-2` | `X(50)` | 135-184 | copy |
| `EXP-CUST-ADDR-LINE(3)` (220-269) | → | `CUST-ADDR-LINE-3` | `X(50)` | 185-234 | copy |
| `EXP-CUST-ADDR-STATE-CD` (270-271) | → | `CUST-ADDR-STATE-CD` | `X(02)` | 235-236 | copy |
| `EXP-CUST-ADDR-COUNTRY-CD` (272-274) | → | `CUST-ADDR-COUNTRY-CD` | `X(03)` | 237-239 | copy |
| `EXP-CUST-ADDR-ZIP` (275-284) | → | `CUST-ADDR-ZIP` | `X(10)` | 240-249 | copy |
| `EXP-CUST-PHONE-NUM(1)` (285-299) | → | `CUST-PHONE-NUM-1` | `X(15)` | 250-264 | copy |
| `EXP-CUST-PHONE-NUM(2)` (300-314) | → | `CUST-PHONE-NUM-2` | `X(15)` | 265-279 | copy |
| `EXP-CUST-SSN` (315-323, zoned9) | → | `CUST-SSN` | `9(09)` | 280-288 | numeric copy (same picture) |
| `EXP-CUST-GOVT-ISSUED-ID` (324-343) | → | `CUST-GOVT-ISSUED-ID` | `X(20)` | 289-308 | copy |
| `EXP-CUST-DOB-YYYY-MM-DD` (344-353) | → | `CUST-DOB-YYYY-MM-DD` | `X(10)` | 309-318 | copy |
| `EXP-CUST-EFT-ACCOUNT-ID` (354-363) | → | `CUST-EFT-ACCOUNT-ID` | `X(10)` | 319-328 | copy |
| `EXP-CUST-PRI-CARD-HOLDER-IND` (364) | → | `CUST-PRI-CARD-HOLDER-IND` | `X(01)` | 329 | copy |
| `EXP-CUST-FICO-CREDIT-SCORE` (365-366, pack2) | → | `CUST-FICO-CREDIT-SCORE` | `9(03)` | 330-332 | packed → zoned unsigned (`x'300F'` → `300`) |
| — (`INITIALIZE`) | → | `FILLER` | `X(168)` | 333-500 | spaces |

### 4.3 `A` → `ACCOUNT-RECORD` (`ACCTOUT`, 300 bytes) — L328-L339

| Export field (pos, encoding) | → | `CVACT01Y` field | PIC | Out pos | Conversion |
|---|---|---|---|---|---|
| `EXP-ACCT-ID` (41-51, zoned11) | → | `ACCT-ID` | `9(11)` | 1-11 | copy |
| `EXP-ACCT-ACTIVE-STATUS` (52) | → | `ACCT-ACTIVE-STATUS` | `X(01)` | 12 | copy |
| `EXP-ACCT-CURR-BAL` (53-59, pack7) | → | `ACCT-CURR-BAL` | `S9(10)V99` | 13-24 | packed → zoned signed with overpunch on pos 24 (`x'0000000000000C'` → `00000000000{`) |
| `EXP-ACCT-CREDIT-LIMIT` (60-71, zoned12) | → | `ACCT-CREDIT-LIMIT` | `S9(10)V99` | 25-36 | copy |
| `EXP-ACCT-CASH-CREDIT-LIMIT` (72-78, pack7) | → | `ACCT-CASH-CREDIT-LIMIT` | `S9(10)V99` | 37-48 | packed → zoned signed |
| `EXP-ACCT-OPEN-DATE` (79-88) | → | `ACCT-OPEN-DATE` | `X(10)` | 49-58 | copy |
| `EXP-ACCT-EXPIRAION-DATE` (89-98) | → | `ACCT-EXPIRAION-DATE` | `X(10)` | 59-68 | copy |
| `EXP-ACCT-REISSUE-DATE` (99-108) | → | `ACCT-REISSUE-DATE` | `X(10)` | 69-78 | copy |
| `EXP-ACCT-CURR-CYC-CREDIT` (109-120, zoned12) | → | `ACCT-CURR-CYC-CREDIT` | `S9(10)V99` | 79-90 | copy |
| `EXP-ACCT-CURR-CYC-DEBIT` (121-128, bin8 ×100) | → | `ACCT-CURR-CYC-DEBIT` | `S9(10)V99` | 91-102 | binary → zoned signed (`x'000000000003DA84'` → `00000025250{`, i.e. 2525.00) |
| `EXP-ACCT-ADDR-ZIP` (129-138) | → | `ACCT-ADDR-ZIP` | `X(10)` | 103-112 | copy |
| `EXP-ACCT-GROUP-ID` (139-148) | → | `ACCT-GROUP-ID` | `X(10)` | 113-122 | copy |
| — | → | `FILLER` | `X(178)` | 123-300 | spaces |

Sign encoding of the zoned outputs follows CBTRN02C §4.7 (`{`/`A-I` positive, `}`/`J-R`
negative in the record charset). A packed zero (`x'…0C'`) becomes `00000000000{` (positive zero),
which is what the ASCII fixtures contain.

### 4.4 `X` → `CARD-XREF-RECORD` (`XREFOUT`, 50 bytes) — L357-L359

| Export field | → | `CVACT03Y` field | PIC | Out pos | Conversion |
|---|---|---|---|---|---|
| `EXP-XREF-CARD-NUM` (41-56) | → | `XREF-CARD-NUM` | `X(16)` | 1-16 | copy |
| `EXP-XREF-CUST-ID` (57-65, zoned9) | → | `XREF-CUST-ID` | `9(09)` | 17-25 | copy |
| `EXP-XREF-ACCT-ID` (66-73, bin8) | → | `XREF-ACCT-ID` | `9(11)` | 26-36 | binary → zoned unsigned (`x'…32'` → `00000000050`) |
| — | → | `FILLER` | `X(14)` | 37-50 | spaces |

### 4.5 `T` → `TRAN-RECORD` (`TRNXOUT`, 350 bytes) — L377-L389

| Export field | → | `CVTRA05Y` field | PIC | Out pos | Conversion |
|---|---|---|---|---|---|
| `EXP-TRAN-ID` (41-56) | → | `TRAN-ID` | `X(16)` | 1-16 | copy |
| `EXP-TRAN-TYPE-CD` (57-58) | → | `TRAN-TYPE-CD` | `X(02)` | 17-18 | copy |
| `EXP-TRAN-CAT-CD` (59-62, zoned4) | → | `TRAN-CAT-CD` | `9(04)` | 19-22 | copy |
| `EXP-TRAN-SOURCE` (63-72) | → | `TRAN-SOURCE` | `X(10)` | 23-32 | copy |
| `EXP-TRAN-DESC` (73-172) | → | `TRAN-DESC` | `X(100)` | 33-132 | copy |
| `EXP-TRAN-AMT` (173-178, pack6) | → | `TRAN-AMT` | `S9(09)V99` | 133-143 | packed → zoned signed (`x'00000050477C'` → `0000005047G`) |
| `EXP-TRAN-MERCHANT-ID` (179-182, bin4) | → | `TRAN-MERCHANT-ID` | `9(09)` | 144-152 | binary → zoned (`x'2FAF0800'` → `800000000`) |
| `EXP-TRAN-MERCHANT-NAME` (183-232) | → | `TRAN-MERCHANT-NAME` | `X(50)` | 153-202 | copy |
| `EXP-TRAN-MERCHANT-CITY` (233-282) | → | `TRAN-MERCHANT-CITY` | `X(50)` | 203-252 | copy |
| `EXP-TRAN-MERCHANT-ZIP` (283-292) | → | `TRAN-MERCHANT-ZIP` | `X(10)` | 253-262 | copy |
| `EXP-TRAN-CARD-NUM` (293-308) | → | `TRAN-CARD-NUM` | `X(16)` | 263-278 | copy |
| `EXP-TRAN-ORIG-TS` (309-334) | → | `TRAN-ORIG-TS` | `X(26)` | 279-304 | copy |
| `EXP-TRAN-PROC-TS` (335-360) | → | `TRAN-PROC-TS` | `X(26)` | 305-330 | copy |
| — | → | `FILLER` | `X(20)` | 331-350 | spaces |

### 4.6 `D` → `CARD-RECORD` (`CARDOUT`, 150 bytes) — L407-L412

| Export field | → | `CVACT02Y` field | PIC | Out pos | Conversion |
|---|---|---|---|---|---|
| `EXP-CARD-NUM` (41-56) | → | `CARD-NUM` | `X(16)` | 1-16 | copy |
| `EXP-CARD-ACCT-ID` (57-64, bin8) | → | `CARD-ACCT-ID` | `9(11)` | 17-27 | binary → zoned |
| `EXP-CARD-CVV-CD` (65-66, bin2) | → | `CARD-CVV-CD` | `9(03)` | 28-30 | binary → zoned (`x'02EB'` → `747`) |
| `EXP-CARD-EMBOSSED-NAME` (67-116) | → | `CARD-EMBOSSED-NAME` | `X(50)` | 31-80 | copy |
| `EXP-CARD-EXPIRAION-DATE` (117-126) | → | `CARD-EXPIRAION-DATE` | `X(10)` | 81-90 | copy |
| `EXP-CARD-ACTIVE-STATUS` (127) | → | `CARD-ACTIVE-STATUS` | `X(01)` | 91 | copy |
| — | → | `FILLER` | `X(59)` | 92-150 | spaces |

### 4.7 `WS-ERROR-RECORD` → `ERROUT` (132 bytes) — L152-L160, L429-L432

| # | Field | PIC | Pos | Len | Value |
|---|---|---|---|---|---|
| 1 | `ERR-TIMESTAMP` | `X(26)` | 1-26 | 26 | `FUNCTION CURRENT-DATE` = 21 chars `YYYYMMDDHHMMSShhsHHMM` (e.g. `2025092822534017+0000`) left-justified, **5 trailing spaces**. Note: a different format from both `WS-IMPORT-DATE` and the export timestamp. |
| 2 | `FILLER` | `X(01) VALUE '\|'` | 27 | 1 | `\|` |
| 3 | `ERR-RECORD-TYPE` | `X(01)` | 28 | 1 | the offending `EXPORT-REC-TYPE` byte, as-is (may be non-printable) |
| 4 | `FILLER` | `X(01) VALUE '\|'` | 29 | 1 | `\|` |
| 5 | `ERR-SEQUENCE` | `9(07)` | 30-36 | 7 | `EXPORT-SEQUENCE-NUM mod 10 000 000`, zero-padded (high-order truncation) |
| 6 | `FILLER` | `X(01) VALUE '\|'` | 37 | 1 | `\|` |
| 7 | `ERR-MESSAGE` | `X(50)` | 38-87 | 50 | `Unknown record type encountered` (31 chars) + 19 spaces — the only message the program can ever produce |
| 8 | `FILLER` | `X(43) VALUE SPACES` | 88-130 | 43 | spaces |
| 9 | (FD padding) | — | 131-132 | 2 | spaces: `WRITE … FROM` a 130-byte item into a 132-byte record pads with spaces |

Example line (ASCII rendering):
`2025092822534017+0000     |Z|0000123|Unknown record type encountered                   ` + 45 spaces.

### 4.8 Relational design

CBIMPORT is a *loader*. In the target state its five outputs are inserts into the five master
tables — `account`, `card_xref`, `transaction` (CBTRN02C §4.9) and `customer`, `card`
(read-utilities spec §4.6) — no new columns. New table for the error file:

```sql
CREATE TABLE import_error (
  import_run_id  BIGINT      NOT NULL,          -- Spring Batch jobExecutionId
  err_timestamp  CHAR(26)    NOT NULL,          -- ERR-TIMESTAMP as written (§4.7 #1)
  record_type    CHAR(1)     NOT NULL,
  sequence_num   NUMERIC(9)  NOT NULL,          -- full value; the file view truncates to 7 digits
  message        CHAR(50)    NOT NULL,
  raw_record     BYTEA/BLOB  NOT NULL           -- the 500 input bytes (not in COBOL; aids triage)
);
```

The **file-parity mode** (§6.2) does not use the tables at all and writes the six flat files.

---

## 5. Business Rules / Validation

### 5.1 Dispatch rule

| # | Rule | Source |
|---|---|---|
| I-1 | `EXPORT-REC-TYPE` ∈ {`C`,`A`,`X`,`T`,`D`} → map and write to the corresponding output; exactly one output record per input record. | L272-L282 |
| I-2 | Any other byte (including lower-case `c`, space, `x'00'`) → one 132-byte `ERROUT` record (§4.7), `WS-UNKNOWN-RECORD-TYPE-COUNT += 1`, `WS-ERROR-RECORDS-WRITTEN += 1`; processing continues. | L283-L284, L425-L446 |
| I-3 | Records are processed strictly in input (sequence) order; relative order within each output file equals relative order in the input. | L252-L256 |
| I-4 | No content validation of any kind (no checksum, referential, duplicate, numeric or date checks). `3000-VALIDATE-IMPORT` always prints "No validation errors detected". | L449-L452 |
| I-5 | No selection: every record is written. | — |

### 5.2 Counters

| Counter (`PIC 9(09)`) | Incremented | Displayed (L465-L478) |
|---|---|---|
| `WS-TOTAL-RECORDS-READ` | every record read (before dispatch), L253 | `Total Records Read` |
| `WS-CUSTOMER-RECORDS-IMPORTED` | after successful `C` write, L320 | `Customers Imported` |
| `WS-ACCOUNT-RECORDS-IMPORTED` | L349 | `Accounts Imported` |
| `WS-XREF-RECORDS-IMPORTED` | L369 | `XRefs Imported` |
| `WS-TRAN-RECORDS-IMPORTED` | L399 | `Transactions Imported` |
| `WS-CARD-RECORDS-IMPORTED` | L422 | `Cards Imported` |
| `WS-ERROR-RECORDS-WRITTEN` | after every error-write **attempt**, L446 | `Errors Written` |
| `WS-UNKNOWN-RECORD-TYPE-COUNT` | per unknown type, L427 | `Unknown Record Types` |

Invariant on a clean run: `total = C + A + X + T + D + unknown` and `errors = unknown`.

### 5.3 Error-path rules

| # | Condition | Action | Exact message |
|---|---|---|---|
| F-1 | `OPEN INPUT EXPORT-INPUT` ≠ `00` | abend | `ERROR: Cannot open EXPORT-INPUT, Status: nn` |
| F-2 | Any `OPEN OUTPUT` ≠ `00` | abend | `ERROR: Cannot open <CUSTOMER-OUTPUT\|ACCOUNT-OUTPUT\|XREF-OUTPUT\|TRANSACTION-OUTPUT\|CARD-OUTPUT\|ERROR-OUTPUT>, Status: nn` |
| F-3 | `READ EXPORT-INPUT` ∉ {`00`,`10`} | abend | `ERROR: Reading EXPORT-INPUT, Status: nn` |
| F-4 | `WRITE` to any of the five master outputs ≠ `00` | abend | `ERROR: Writing <customer\|account\|xref\|transaction\|card> record, Status: nn` |
| F-5 | `WRITE` to `ERROUT` ≠ `00` | **log and continue**; counter still incremented | `ERROR: Writing error record, Status: nn` |
| F-6 | abend | `CBIMPORT: ABENDING PROGRAM`; `CALL 'CEE3ABD'` (no args) | — |

**Decision on F-5: deviate.** A failure to write the error file is a real I/O failure and silently
continuing produces an under-reported error file with an over-reported counter. The Java job fails
the step on any error-writer exception (consistent with F-4). Documented in §9; the difference is
only observable when the error file itself is unwritable.

### 5.4 Latent defects and surprising behaviour (decisions)

| # | Observation | Replicate / deviate | Rationale |
|---|---|---|---|
| D-1 | `CBIMPORT.jcl` has no `CARDOUT` DD, so the shipped job abends at `OPEN OUTPUT CARD-OUTPUT` (L233) with status `35`/`96`-class error before reading anything — it cannot have produced any output as checked in. | **Deviate** (fix): the Java job defines all six outputs; the `CARDOUT` equivalent is `AWS.M2.CARDDEMO.CARDDATA.IMPORT`-style, LRECL 150. | Clear omission; program intent is unambiguous (L63-L66, L101-L104, L402-L422). Report upstream. |
| D-2 | `3000-VALIDATE-IMPORT` claims validation that does not exist. | **Replicate** the messages verbatim (§7.3) but make them truthful: the Java step logs them only when zero unknown-type records were seen; otherwise logs `CBIMPORT: Import validation completed` followed by `CBIMPORT: Validation errors detected: nnnnnnnnn` (new). Optional real checks (§9 Q2) are out of scope for parity. | Keeping the literal "No validation errors detected" after writing error records is misleading to operators. |
| D-3 | `ERR-SEQUENCE` truncates the 9-digit sequence to 7 digits. | **Replicate** in the file layout (the 132-byte record has no room); store the full value in `import_error.sequence_num`. | Byte-layout parity of `ERROUT`. |
| D-4 | `ERR-TIMESTAMP` is a 21-char `CURRENT-DATE` string padded to 26, inconsistent with other timestamps. | **Replicate** (format `yyyyMMddHHmmssSSxxx` as `YYYYMMDDHHMMSShh` + `±HHMM` offset, 5 spaces). | Parity; consumers of `ERROUT` (if any) expect it. |
| D-5 | `WS-ERROR-RECORDS-WRITTEN` counts failed writes. | **Deviate** (see F-5): a failed write fails the job, so the counter always equals records written. | Consequence of F-5 decision. |
| D-6 | `CALL 'CEE3ABD'` without parameters. | Deviate: exit code 8. | As CBEXPORT §7.3. |
| D-7 | Zoned-sign normalisation: packed/binary → zoned `MOVE` always yields `C`/`D` sign nibbles, so an account whose master had an unsigned-style `F` sign (`…0`) round-trips through export/import as `…{`. Byte-identical round-trip of the *masters* is therefore not guaranteed even on the mainframe; **value** round-trip is. | Replicate (Java zoned writer emits `C`/`D`). | Parity with COBOL output. |
| D-8 | Names in the EBCDIC sample are upper-cased relative to `custdata.txt` — a property of how the fixture was produced, not of the program. | n/a | Do not add case folding. |

---

## 6. Target Spring Batch Design

### 6.1 Module and package layout (proposed)

```
carddemo-batch/
  shared/   com.carddemo.batch.shared.export   ExportRecord*, ExportRecordCodec     (from CBEXPORT spec §4.2.7)
            com.carddemo.batch.shared.codec    Zoned/Packed/Binary codecs, FixedWidthWriter
            com.carddemo.batch.shared.domain   Customer, Account, CardXref, Transaction, Card + fixed-width serializers
  cbimport/ com.carddemo.batch.cbimport        CbImportJobConfig, ExportFileItemReader, ImportRouter,
                                               ImportError, ImportErrorLineAggregator, ImportSummaryListener,
                                               writers.{FileMode,DbMode}
```

### 6.2 Job structure

```
Job cbimportJob  (identifying params: inputPath, run.id; non-identifying: outputDir, mode=FILE|DB, charset)
 ├─ Step initImport      (Tasklet)  – banners (start, Import Date, Import Time); create/truncate the six outputs (FILE mode)
 ├─ Step importRecords   (chunk N)  reader: exportFileItemReader                (500-byte binary records → ExportRecord)
 │                                  processor: ImportRouter                    (ExportRecord → ImportItem = Customer|Account|CardXref|Transaction|Card|ImportError)
 │                                  writer: ClassifierCompositeItemWriter<ImportItem>
 │                                      Customer    → customerWriter   (CUSTOUT 500 | customer table)
 │                                      Account     → accountWriter    (ACCTOUT 300 | account table)
 │                                      CardXref    → xrefWriter       (XREFOUT  50 | card_xref table)
 │                                      Transaction → transactionWriter(TRNXOUT 350 | transaction table)
 │                                      Card        → cardWriter       (CARDOUT 150 | card table)
 │                                      ImportError → errorWriter      (ERROUT  132 | import_error table)
 ├─ Step validateImport  (Tasklet)  – D-2 messages
 └─ Step finalizeImport  (Tasklet)  – nine summary lines (§7.3)
```

A single chunk step preserves I-3 (relative order) because `ClassifierCompositeItemWriter`
delivers items to each delegate in item order within a chunk and chunks are sequential.

### 6.3 Reader — `ExportFileItemReader`

Custom `AbstractItemCountingItemStreamItemReader<ExportRecord>` (not `FlatFileItemReader`, for
the binary-content reason given in CBEXPORT §6.5):

* reads exactly 500 bytes per `doRead()` from a `ReadableByteChannel`; EOF at a 500-byte boundary →
  `null` (status `10`); a **short final record** (file length not a multiple of 500) →
  `ItemStreamException` (the COBOL would get a VSAM/QSAM error → abend, F-3);
* decodes with `ExportRecordCodec.decode(bytes, charset)`; decoding of `PIC X` fields uses
  `cbimport.input.charset` (default `IBM037`);
* **does not** decode the payload eagerly for unknown types — `ExportRecordCodec.decodeHeader`
  first; payload decode only for known types, so a garbage record cannot throw before it reaches
  the error path (I-2);
* saves `itemCount` in the `ExecutionContext` for restart (§6.7).

### 6.4 Processor — `ImportRouter`

```java
public ImportItem process(ExportRecord r) {
    return switch (r.type()) {
        case C -> customerMapper.map((CustomerPayload) r.payload());       // §4.2
        case A -> accountMapper.map((AccountPayload) r.payload());         // §4.3
        case X -> xrefMapper.map((XrefPayload) r.payload());               // §4.4
        case T -> transactionMapper.map((TransactionPayload) r.payload()); // §4.5
        case D -> cardMapper.map((CardPayload) r.payload());               // §4.6
        case UNKNOWN -> new ImportError(clock.now(), r.rawTypeByte(), r.sequenceNum(),
                                        "Unknown record type encountered", r.rawBytes());
    };
}
```

`ExportRecordType` gets an `UNKNOWN(byte raw)` member (or the codec returns an
`Optional<ExportRecordType>` plus the raw byte) so that the original byte is available for
`ERR-RECORD-TYPE`. Mappers are pure field copies; numeric conversions are already done by the
codec (`BigDecimal`/`long`), and the fixed-width serializers re-encode to zoned on output.

### 6.5 Writers

**FILE mode (parity):** six custom fixed-length writers (`FixedLengthRecordItemWriter<T>`),
each with the record length and a `FixedWidthSerializer<T>` derived from the copybook tables
(§4.2-§4.7), writing raw bytes with **no line separator** in the configured charset. Zoned signed
fields use the overpunch of CBTRN02C §4.7; filler is spaces.

For `ERROUT` an `ImportErrorLineAggregator` produces the 132-byte layout of §4.7:
`ts(26) '|' type(1) '|' seq%10_000_000 as %07d '|' message padded to 50 + 45 spaces`.

**DB mode (target):** `JdbcBatchItemWriter`/repository writers into `customer`, `account`,
`card_xref`, `transaction`, `card`, `import_error`. Repository interfaces:

```java
interface CustomerRepository    extends CrudRepository<Customer, Long>     { }   // read-utilities spec §4.6
interface AccountRepository     extends CrudRepository<Account, Long>      { }   // CBTRN02C §6.6
interface CardXrefRepository    extends CrudRepository<CardXref, String>   { }
interface TransactionRepository extends CrudRepository<Transaction, String>{ }
interface CardRepository        extends CrudRepository<Card, String>       { }
interface ImportErrorRepository extends CrudRepository<ImportError, Long>  { }
```

DB mode adds a semantic the COBOL does not have: primary-key collisions. The COBOL writes to
empty PS files, so duplicates are impossible. In DB mode a `DuplicateKeyException` **fails the
step** (the branch being migrated must not already exist in the target). An `upsert` variant is an
explicit future option (§9 Q3).

### 6.6 Listeners

`ImportSummaryListener` prints §7.3 lines from `StepExecution` counts plus per-type counters kept
in a `@JobScope` `ImportCounters` bean (incremented in the classifier writer after each successful
write, mirroring "increment after WRITE").

### 6.7 Transactions, restart and fault tolerance

* One transaction per chunk. In FILE mode the writers are `ItemStream`s whose buffers are flushed
  at commit; in DB mode the inserts roll back with the chunk.
* **No skip/retry.** Any codec, I/O or DB exception fails the step (F-1..F-4). Per §5.3 F-5 the
  error writer is treated the same way (documented deviation).
* **Restart:** allowed in DB mode (reader position in the execution context; committed chunks are
  not re-inserted). In FILE mode restart is disabled and a rerun **deletes and recreates** all six
  outputs, mirroring `DISP=(NEW,CATLG,DELETE)` (§3): a `JobExecutionListener.afterJob` on
  `FAILED` removes partially written files.
* Chunk size does not affect output content (I-3 holds within and across chunks).

### 6.8 Configuration

| Property | Default | Meaning |
|---|---|---|
| `cbimport.input.path` | job parameter | the export file (`EXPFILE`) |
| `cbimport.input.charset` | `IBM037` | charset of `PIC X` fields in the export file; must match the producer |
| `cbimport.output.charset` | `IBM037` | charset for FILE-mode outputs (use `US-ASCII` to produce files comparable with `app/data/ASCII`) |
| `cbimport.output.dir` | job parameter | directory receiving `custout.dat`, `acctout.dat`, `xrefout.dat`, `trnxout.dat`, `cardout.dat`, `errout.dat` |
| `cbimport.mode` | `FILE` | `FILE` or `DB` |
| `cbimport.timestampZone` | system default | zone for `WS-IMPORT-DATE/TIME` and `ERR-TIMESTAMP` |

---

## 7. Output & Exit Behavior

### 7.1 Output files (FILE mode) — byte layouts

| File | LRECL | Layout | Record count |
|---|---|---|---|
| `CUSTOUT` | 500 | §4.2 (positions 1-500) | = `C` records |
| `ACCTOUT` | 300 | §4.3 | = `A` |
| `XREFOUT` | 50 | §4.4 | = `X` |
| `TRNXOUT` | 350 | §4.5 | = `T` |
| `CARDOUT` | 150 | §4.6 | = `D` |
| `ERROUT` | 132 | §4.7 | = unknown-type records |

All six are created even when empty (COBOL `OPEN OUTPUT` creates an empty dataset). No delimiters.

### 7.2 Console messages — errors

See §5.3 F-1..F-6 for the exact texts.

### 7.3 Console messages — normal run (exact, in order; `n` = 9-digit zero-padded)

```
CBIMPORT: Starting Customer Data Import                 L176
CBIMPORT: Import Date: YYYY-MM-DD                       L192
CBIMPORT: Import Time: HH:MM:SS                         L193
CBIMPORT: Import validation completed                   L451
CBIMPORT: No validation errors detected                 L452   (Java: only when unknown = 0, D-2)
CBIMPORT: Import completed                              L465
CBIMPORT: Total Records Read: nnnnnnnnn                 L466
CBIMPORT: Customers Imported: nnnnnnnnn                 L468
CBIMPORT: Accounts Imported: nnnnnnnnn                  L470
CBIMPORT: XRefs Imported: nnnnnnnnn                     L472
CBIMPORT: Transactions Imported: nnnnnnnnn              L473
CBIMPORT: Cards Imported: nnnnnnnnn                     L475
CBIMPORT: Errors Written: nnnnnnnnn                     L476
CBIMPORT: Unknown Record Types: nnnnnnnnn               L477
```

Unlike CBEXPORT there are no per-type progress banners.

### 7.4 Exit status mapping

| COBOL outcome | COBOL result | Spring Batch `ExitStatus` | Process exit code |
|---|---|---|---|
| EOF reached, all writes OK (any number of unknown-type records) | `RETURN-CODE 0` | `COMPLETED` when unknown = 0; **`COMPLETED WITH ERRORS`** (custom) when unknown > 0 | `0` / **`4`** (documented deviation: the COBOL returns 0 in both cases; a warning code lets the scheduler surface unknown-type records — decision §9 Q1) |
| Open/read/master-write failure | `CALL 'CEE3ABD'` (no args) → LE abend | `FAILED` | `8` |
| Error-file write failure | COBOL: continue, RC 0 | `FAILED` (F-5 deviation) | `8` |

---

## 8. Acceptance Criteria & Test Plan

### 8.1 Fixtures

* `app/data/EBCDIC/AWS.M2.CARDDEMO.EXPORT.DATA.PS` — 500 records: 50 `C`, 50 `A`, 50 `X`, 300 `T`,
  50 `D`; sequence 1-450 then 460-509 (gap). Primary input fixture.
* Synthetic variants built with `ExportRecordCodec`: (a) sample + 3 records with types `Z`,
  space and `x'00'` interleaved; (b) sample truncated to 250 250 bytes (short last record);
  (c) empty file; (d) a `C` record with `EXPORT-SEQUENCE-NUM = 12 345 678` and type `Q`
  (7-digit truncation).
* `app/data/ASCII/{custdata,acctdata,cardxref,carddata}.txt` — value oracles for the fields whose
  keys coincide with the sample (e.g. card `0500024453765740`, cust `000000050`).

### 8.2 Unit-level acceptance criteria

| ID | Criterion |
|---|---|
| U-1 | Router: each of `C/A/X/T/D` produces the corresponding item type; any other byte produces `ImportError` with the raw byte (I-1, I-2). |
| U-2 | Customer mapping §4.2: for sample record 1, output bytes 1-9 = `000000001`, 330-332 = `300`, 333-500 spaces; total 500 bytes. |
| U-3 | Account mapping §4.3: packed `x'0000000102000C'` → `00000010200{` at 37-48; binary `x'000000000003DA84'` → `00000025250{` at 91-102; negative packed `x'0000000102500D'` → `00000010250}` (`}` = −0 overpunch). |
| U-4 | Xref mapping §4.4: `x'0000000000000032'` → `00000000050` at 26-36; record is 50 bytes with spaces 37-50 (the ASCII fixture line is 36 chars because trailing spaces were stripped — the writer must still emit them). |
| U-5 | Transaction mapping §4.5: `x'00000050477C'` → `0000005047G`; `x'2FAF0800'` → `800000000`. |
| U-6 | Card mapping §4.6: `x'02EB'` → `747`; 150 bytes. |
| U-7 | `ERROUT` layout §4.7: fixed clock `2025-09-28T22:53:40.17+00:00` → bytes 1-26 `2025092822534017+0000` + 5 spaces; `|` at 27, 29, 37; seq `12345678` → `2345678` at 30-36 (D-3); message padded to 50; 88-132 spaces; total 132. |
| U-8 | Counters/invariant §5.2 on fixture (a): total 503, per-type 50/50/50/300/50, errors 3, unknown 3; summary lines exactly as §7.3 with `000000503` etc. |
| U-9 | Order preservation (I-3): output record k of each file corresponds to the k-th input record of that type. |
| U-10 | Short last record (fixture b) → job `FAILED`, exit 8, no output files remain (FILE mode). |
| U-11 | Empty input (fixture c) → six empty files, all counters `000000000`, `No validation errors detected`, exit 0. |
| U-12 | Error-writer `IOException` → job `FAILED`, exit 8 (F-5 deviation). |
| U-13 | DB mode: duplicate primary key → `FAILED`; committed chunks visible, uncommitted rolled back; restart resumes after last committed chunk without duplicating rows. |
| U-14 | Charset: with `input.charset=IBM037`, `output.charset=US-ASCII`, the `CARDOUT` record for `0500024453765740` equals `carddata.txt` line 1 padded to 150; the `XREFOUT` record equals `cardxref.txt` line 1 padded to 50. |

### 8.3 End-to-end parity criterion

1. Run the COBOL `CBIMPORT` on the mainframe against `AWS.M2.CARDDEMO.EXPORT.DATA` **with a
   `CARDOUT` DD added** (D-1); capture the six output datasets (binary, EBCDIC) and SYSOUT.
2. Run `cbimportJob` (`mode=FILE`, both charsets `IBM037`) on the same input.
3. `cmp` each of the six outputs — must be byte-identical. `ERROUT` is empty for the shipped
   sample; repeat with fixture (a) and mask bytes 1-26 (timestamp) of `ERROUT`.
4. SYSOUT lines §7.3 identical (the two validation lines identical because unknown = 0).

Round-trip criterion with the CBEXPORT spec: `cbexportJob` (fixtures → export) followed by
`cbimportJob` (export → files, `output.charset=US-ASCII`) reproduces `custdata.txt`,
`acctdata.txt`, `cardxref.txt`, `carddata.txt` after right-padding the fixture lines to LRECL and
normalising positive-zero signs (`…0` vs `…{`, D-7).

### 8.4 Non-functional

* Streaming; memory independent of input size; ≥ 50 000 records/s in FILE mode.
* No `double`/`float`.

---

## 9. Assumptions, Risks & Open Questions

### 9.1 Assumptions

* Input is produced by CBEXPORT (COBOL or Java) in the §4.2 layout with the same `PIC X` charset.
* The loading of the five output files into the target KSDS/tables is a separate job (or the DB
  mode replaces it).
* `import_error` is acceptable as a new table (no COBOL equivalent beyond the flat file).

### 9.2 Risks

| Risk | Mitigation |
|---|---|
| Shipped JCL cannot run (no `CARDOUT`), so no authoritative COBOL output exists for parity | Obtain a corrected JCL run (§8.3 step 1); until then rely on codec round-trip tests and the ASCII fixtures. |
| A corrupt or mis-encoded input (wrong charset) will route *every* record to `ERROUT` with RC 0 in COBOL — silent total failure | Exit code 4 on unknown > 0 (§7.4) and a configurable threshold `cbimport.maxUnknownRecords` (default: unlimited for parity; recommended 0 in production). |
| Sign-nibble normalisation (D-7) means master files are not byte-identical after a round trip | Compare values, not bytes, when validating a migration; document for QA. |
| `ERR-SEQUENCE` truncation hides the real sequence for files > 9 999 999 records | Full value in `import_error.sequence_num`. |
| DB-mode duplicate keys | Fail fast; pre-check target emptiness in `initImport` (optional tasklet). |

### 9.3 Open questions

1. Exit code for "completed with unknown-type records": keep COBOL `0` or adopt `4`? Proposed `4`.
2. Should real validation be added now (e.g. `X` referencing an unknown `A`/`C`, `T` referencing an
   unknown card), turning `3000-VALIDATE-IMPORT` into a real step? Proposed: out of scope for the
   parity release; design hook = `validateImport` tasklet.
3. DB mode semantics on existing keys: fail (proposed) vs upsert.
4. Is FILE mode needed after cut-over, or only for parity testing? Determines whether the six
   fixed-width serializers stay in production code (they are also needed by the read-utilities
   spec, so they remain in `shared`).
5. Confirm `IBM037` (vs `IBM1047`) as the mainframe code page; the sample decodes correctly with
   CP037 (`{`/`}` = `x'C0'`/`x'D0'`), which is consistent with the ASCII fixtures' overpunch chars.
