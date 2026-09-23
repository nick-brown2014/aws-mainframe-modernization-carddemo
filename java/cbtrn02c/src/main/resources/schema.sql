CREATE TABLE IF NOT EXISTS card_xref (
    card_num VARCHAR(16) PRIMARY KEY,
    cust_id VARCHAR(9) NOT NULL,
    acct_id VARCHAR(11) NOT NULL,
    filler VARCHAR(14) NOT NULL
);
CREATE TABLE IF NOT EXISTS account (
    acct_id VARCHAR(11) PRIMARY KEY,
    active_status VARCHAR(1) NOT NULL,
    curr_bal DECIMAL(12, 2) NOT NULL,
    credit_limit DECIMAL(12, 2) NOT NULL,
    cash_credit_limit DECIMAL(12, 2) NOT NULL,
    open_date VARCHAR(10) NOT NULL,
    expiration_date VARCHAR(10) NOT NULL,
    reissue_date VARCHAR(10) NOT NULL,
    curr_cyc_credit DECIMAL(12, 2) NOT NULL,
    curr_cyc_debit DECIMAL(12, 2) NOT NULL,
    addr_zip VARCHAR(10) NOT NULL,
    group_id VARCHAR(10) NOT NULL,
    filler VARCHAR(178) NOT NULL
);
CREATE TABLE IF NOT EXISTS tran_cat_balance (
    acct_id VARCHAR(11) NOT NULL,
    type_cd VARCHAR(2) NOT NULL,
    cat_cd VARCHAR(4) NOT NULL,
    balance DECIMAL(11, 2) NOT NULL,
    filler VARCHAR(22) NOT NULL,
    PRIMARY KEY (acct_id, type_cd, cat_cd)
);
CREATE TABLE IF NOT EXISTS transaction_master (
    tran_id VARCHAR(16) PRIMARY KEY,
    type_cd VARCHAR(2) NOT NULL,
    cat_cd VARCHAR(4) NOT NULL,
    source VARCHAR(10) NOT NULL,
    description VARCHAR(100) NOT NULL,
    amount DECIMAL(11, 2) NOT NULL,
    merchant_id VARCHAR(9) NOT NULL,
    merchant_name VARCHAR(50) NOT NULL,
    merchant_city VARCHAR(50) NOT NULL,
    merchant_zip VARCHAR(10) NOT NULL,
    card_num VARCHAR(16) NOT NULL,
    orig_ts VARCHAR(26) NOT NULL,
    proc_ts VARCHAR(27) NOT NULL,
    filler VARCHAR(20) NOT NULL
);
