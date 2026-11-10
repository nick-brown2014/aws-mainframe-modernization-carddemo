-- CBTRN02C migration spec section 4.9: one table per VSAM KSDS.
CREATE TABLE IF NOT EXISTS card_xref (
  card_num   CHAR(16)      NOT NULL PRIMARY KEY,
  cust_id    NUMERIC(9)    NOT NULL,
  acct_id    NUMERIC(11)   NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_card_xref_acct ON card_xref(acct_id);

CREATE TABLE IF NOT EXISTS account (
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

CREATE TABLE IF NOT EXISTS tran_cat_bal (
  acct_id      NUMERIC(11)   NOT NULL,
  tran_type_cd CHAR(2)       NOT NULL,
  tran_cat_cd  NUMERIC(4)    NOT NULL,
  tran_cat_bal DECIMAL(11,2) NOT NULL,
  PRIMARY KEY (acct_id, tran_type_cd, tran_cat_cd)
);

CREATE TABLE IF NOT EXISTS transaction (
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
CREATE INDEX IF NOT EXISTS ix_transaction_card ON transaction(card_num);
