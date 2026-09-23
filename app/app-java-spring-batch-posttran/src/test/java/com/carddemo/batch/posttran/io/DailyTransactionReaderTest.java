package com.carddemo.batch.posttran.io;

import com.carddemo.batch.posttran.model.DailyTransaction;
import com.carddemo.batch.posttran.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.transform.IncorrectLineLengthException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AC-R4 and the reader half of AC-R5 (spec 6.3). Step-level FAILED for AC-R5 is asserted in PostTranJobIT. */
class DailyTransactionReaderTest {

    private static FlatFileItemReader<DailyTransaction> reader(org.springframework.core.io.Resource resource) {
        FlatFileItemReader<DailyTransaction> r = new FlatFileItemReaderBuilder<DailyTransaction>()
                .name("test")
                .resource(resource)
                .encoding(StandardCharsets.UTF_8.name())
                .strict(true)
                .lineMapper(new DailyTransactionLineMapper())
                .build();
        r.open(new ExecutionContext());
        return r;
    }

    @Test
    @DisplayName("AC-R4: first sample record tokenises into the copybook fields")
    void tokenisesFirstSampleRecord() throws Exception {
        FlatFileItemReader<DailyTransaction> r = reader(
                new FileSystemResource(TestData.ASCII_DATA.resolve("dailytran.txt")));
        DailyTransaction t = r.read();
        assertThat(t.tranId()).isEqualTo("0000000000683580");
        assertThat(t.tranTypeCd()).isEqualTo("01");
        assertThat(t.tranCatCd()).isEqualTo(1);
        assertThat(t.tranSource()).isEqualTo("POS TERM  ");
        assertThat(t.tranAmt()).isEqualTo(TestData.amt("504.77"));
        assertThat(t.merchantId()).isEqualTo(800000000L);
        assertThat(t.cardNum()).isEqualTo("4859452612877065");
        assertThat(t.origTs()).isEqualTo("2022-06-10 19:27:53.000000");
        assertThat(t.rawRecord()).hasSize(350);
        int n = 1;
        while (r.read() != null) {
            n++;
        }
        assertThat(n).isEqualTo(300);
    }

    @Test
    @DisplayName("AC-R5: a 349-byte record is a FlatFileParseException")
    void rejectsShortRecord() {
        String line = TestData.dailyLine("0000000000000001", TestData.amt("1.00"));
        FlatFileItemReader<DailyTransaction> r = reader(new ByteArrayResource(
                (line.substring(0, 349) + "\n").getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(r::read)
                .isInstanceOf(FlatFileParseException.class)
                .hasCauseInstanceOf(IncorrectLineLengthException.class);
    }

    @Test
    void failsFastOnMalformedNumeric() {
        String line = TestData.dailyLine("0000000000000001", TestData.amt("1.00"));
        String bad = line.substring(0, 18) + "00A1" + line.substring(22);
        FlatFileItemReader<DailyTransaction> r = reader(new ByteArrayResource(
                (bad + "\n").getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(r::read).isInstanceOf(FlatFileParseException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line: 1");
    }
}
