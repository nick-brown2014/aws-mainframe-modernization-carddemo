package com.carddemo.posttran.layout;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.posttran.TestRecords;
import com.carddemo.posttran.batch.DailyTransactionLineMapper;
import com.carddemo.posttran.domain.Account;
import com.carddemo.posttran.domain.CardXref;
import com.carddemo.posttran.domain.DailyTransaction;
import com.carddemo.posttran.domain.RejectedTransaction;
import com.carddemo.posttran.domain.TranCatBalance;
import com.carddemo.posttran.domain.ValidationResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Field positions from the copybooks, checked against records from app/data/ASCII. */
class LayoutTest {

    private static final String DALYTRAN_SAMPLE =
            "0000000000683580010001POS TERM  Purchase at Abshire-Lowe"
            + " ".repeat(76)
            + "0000005047G800000000Abshire-Lowe"
            + " ".repeat(38)
            + "North Enoshaven"
            + " ".repeat(35)
            + "72112     48594526128770652022-06-10 19:27:53.000000"
            + " ".repeat(46);

    @Test
    void dailyTransactionLineMapperFollowsCvtra06y() {
        assertThat(DALYTRAN_SAMPLE).hasSize(350);

        DailyTransaction t = new DailyTransactionLineMapper().mapLine(DALYTRAN_SAMPLE, 1);

        assertThat(t.getRawRecord()).isEqualTo(DALYTRAN_SAMPLE);
        assertThat(t.getTranId()).isEqualTo("0000000000683580");
        assertThat(t.getTypeCd()).isEqualTo("01");
        assertThat(t.getCatCd()).isEqualTo(1);
        assertThat(t.getSource()).isEqualTo("POS TERM  ");
        assertThat(t.getDescription()).startsWith("Purchase at Abshire-Lowe").hasSize(100);
        assertThat(t.getAmount()).isEqualByComparingTo("504.77");
        assertThat(t.getMerchantId()).isEqualTo(800000000L);
        assertThat(t.getMerchantName()).startsWith("Abshire-Lowe").hasSize(50);
        assertThat(t.getMerchantCity()).startsWith("North Enoshaven").hasSize(50);
        assertThat(t.getMerchantZip()).isEqualTo("72112     ");
        assertThat(t.getCardNum()).isEqualTo("4859452612877065");
        assertThat(t.getOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
        assertThat(t.getOrigDate()).isEqualTo("2022-06-10");
        assertThat(t.getProcTs()).isEqualTo(" ".repeat(26));
    }

    @Test
    void shortLinesArePaddedToRecordLength() {
        DailyTransaction t = new DailyTransactionLineMapper().mapLine(DALYTRAN_SAMPLE.stripTrailing(), 1);
        assertThat(t.getRawRecord()).hasSize(350);
        assertThat(t.getOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
    }

    @Test
    void accountLayoutFollowsCvact01y() {
        String line = "00000000001Y00000001940{00000020200{00000010200{2014-11-202025-05-202025-05-20"
                + "00000000000{00000000000{A000000000";
        Account a = AccountLayout.parse(line);

        assertThat(a.getAcctId()).isEqualTo(1L);
        assertThat(a.getActiveStatus()).isEqualTo("Y");
        assertThat(a.getCurrBal()).isEqualByComparingTo("194.00");
        assertThat(a.getCreditLimit()).isEqualByComparingTo("2020.00");
        assertThat(a.getCashCreditLimit()).isEqualByComparingTo("1020.00");
        assertThat(a.getOpenDate()).isEqualTo("2014-11-20");
        assertThat(a.getExpirationDate()).isEqualTo("2025-05-20");
        assertThat(a.getReissueDate()).isEqualTo("2025-05-20");
        assertThat(a.getCurrCycCredit()).isEqualByComparingTo("0.00");
        assertThat(a.getCurrCycDebit()).isEqualByComparingTo("0.00");
        assertThat(a.getAddrZip()).isEqualTo("A000000000");
        assertThat(a.getGroupId()).isEqualTo(" ".repeat(10));
    }

    @Test
    void cardXrefLayoutFollowsCvact03y() {
        CardXref x = CardXrefLayout.parse("050002445376574000000005000000000050");

        assertThat(x.getCardNum()).isEqualTo("0500024453765740");
        assertThat(x.getCustId()).isEqualTo(50L);
        assertThat(x.getAcctId()).isEqualTo(50L);
    }

    @Test
    void tranCatBalanceLayoutFollowsCvtra01y() {
        TranCatBalance b = TranCatBalanceLayout.parse("000000000010100010000000000{0000000000000000000000");

        assertThat(b.getId().getAcctId()).isEqualTo(1L);
        assertThat(b.getId().getTypeCd()).isEqualTo("01");
        assertThat(b.getId().getCatCd()).isEqualTo(1);
        assertThat(b.getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void rejectRecordIs430BytesWithReasonTrailer() {
        DailyTransaction t = TestRecords.dalytran("T1", new BigDecimal("1.00"), "2022-06-10 19:27:53.000000");
        String rec = RejectRecordLayout.format(new RejectedTransaction(t, ValidationResult.AFTER_EXPIRATION));

        assertThat(rec).hasSize(430);
        assertThat(rec.substring(0, 350)).isEqualTo(t.getRawRecord());
        assertThat(rec.substring(350, 354)).isEqualTo("0103");
        assertThat(rec.substring(354)).isEqualTo(
                String.format("%-76s", "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"));
    }
}
