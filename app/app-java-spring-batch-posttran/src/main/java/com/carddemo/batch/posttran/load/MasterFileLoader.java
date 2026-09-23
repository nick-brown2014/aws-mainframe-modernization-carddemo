package com.carddemo.batch.posttran.load;

import com.carddemo.batch.posttran.io.CopybookRecordParser;
import com.carddemo.batch.posttran.repo.AccountRepository;
import com.carddemo.batch.posttran.repo.CardXrefRepository;
import com.carddemo.batch.posttran.repo.TranCategoryBalanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Loads the VSAM master files (ASCII text form, one copybook record per line, e.g.
 * {@code app/data/ASCII/cardxref.txt}, {@code acctdata.txt}, {@code tcatbal.txt}) into the tables.
 */
@Service
public class MasterFileLoader {

    private final CardXrefRepository cardXrefRepository;
    private final AccountRepository accountRepository;
    private final TranCategoryBalanceRepository tranCategoryBalanceRepository;

    public MasterFileLoader(CardXrefRepository cardXrefRepository, AccountRepository accountRepository,
                            TranCategoryBalanceRepository tranCategoryBalanceRepository) {
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
        this.tranCategoryBalanceRepository = tranCategoryBalanceRepository;
    }

    @Transactional
    public int loadCardXref(Path file, Charset charset) {
        return load(file, charset, line -> cardXrefRepository.insert(CopybookRecordParser.parseCardXref(line)));
    }

    @Transactional
    public int loadAccounts(Path file, Charset charset) {
        return load(file, charset, line -> accountRepository.insert(CopybookRecordParser.parseAccount(line)));
    }

    @Transactional
    public int loadTranCategoryBalances(Path file, Charset charset) {
        return load(file, charset,
                line -> tranCategoryBalanceRepository.insert(CopybookRecordParser.parseTranCategoryBalance(line)));
    }

    private static int load(Path file, Charset charset, Consumer<String> sink) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, charset);
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR OPENING " + file, e);
        }
        int n = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            sink.accept(line);
            n++;
        }
        return n;
    }
}
