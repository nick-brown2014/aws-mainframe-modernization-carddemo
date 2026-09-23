package com.carddemo.posttran.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JCL DD statements of {@code POSTTRAN} step {@code CBTRN02C} that map to files.
 *
 * @param dalytran   DD DALYTRAN - sequential daily transaction input (LRECL 350)
 * @param dalyrejs   DD DALYREJS - sequential rejects output (LRECL 430)
 * @param chunkSize  number of records per Spring Batch chunk / database transaction
 * @param load       optional VSAM extracts to load into the relational tables before the job runs
 */
@ConfigurationProperties(prefix = "posttran")
public record PostTranProperties(String dalytran, String dalyrejs, int chunkSize, Load load) {

    public PostTranProperties {
        if (chunkSize <= 0) {
            chunkSize = 100;
        }
        if (load == null) {
            load = new Load(null, null, null, false);
        }
    }

    /**
     * @param acctfile DD ACCTFILE extract (CVACT01Y, LRECL 300)
     * @param xreffile DD XREFFILE extract (CVACT03Y, LRECL 50)
     * @param tcatbalf DD TCATBALF extract (CVTRA01Y, LRECL 50)
     * @param truncate clear the target tables before loading
     */
    public record Load(String acctfile, String xreffile, String tcatbalf, boolean truncate) {
    }
}
