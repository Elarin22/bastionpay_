package com.san.mockbank;

import com.san.mockbank.account.BankAccount;
import com.san.mockbank.account.BankAccountRepository;
import com.san.mockbank.account.BankTransaction;
import com.san.mockbank.account.BankTransactionRepository;
import com.san.mockbank.account.AccountStatus;
import com.san.mockbank.account.InOutType;
import com.san.mockbank.api.ApiCallLog;
import com.san.mockbank.api.ApiCallLogRepository;
import com.san.mockbank.client.OauthClient;
import com.san.mockbank.client.OauthClientRepository;
import com.san.mockbank.customer.BankCustomer;
import com.san.mockbank.customer.BankCustomerRepository;
import com.san.mockbank.oauth.AccessToken;
import com.san.mockbank.oauth.AccessTokenRepository;
import com.san.mockbank.oauth.AuthorizationCode;
import com.san.mockbank.oauth.AuthorizationCodeRepository;
import com.san.mockbank.account.AccountConsent;
import com.san.mockbank.account.AccountConsentRepository;
import com.san.mockbank.transfer.BankTransfer;
import com.san.mockbank.transfer.BankTransferItem;
import com.san.mockbank.transfer.BankTransferItemRepository;
import com.san.mockbank.transfer.BankTransferRepository;
import com.san.mockbank.transfer.TransferKind;
import com.san.mockbank.transfer.TransferStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flyway 스키마와 JPA 매핑(ddl-auto: validate)이 맞는지, 제약이 의도대로 걸려 있는지 확인한다.
 * 로컬 mockbank-mysql(3307)이 떠 있어야 하고, 모든 테스트는 롤백된다.
 */
@SpringBootTest
@Transactional
class SchemaTest {

    @Autowired OauthClientRepository clients;
    @Autowired BankCustomerRepository customers;
    @Autowired BankAccountRepository accounts;
    @Autowired BankTransactionRepository transactions;
    @Autowired AuthorizationCodeRepository codes;
    @Autowired AccessTokenRepository tokens;
    @Autowired ApiCallLogRepository callLogs;
    @Autowired AccountConsentRepository consents;
    @Autowired BankTransferRepository transfers;
    @Autowired BankTransferItemRepository transferItems;

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private OauthClient client() {
        String u = unique();
        return clients.saveAndFlush(new OauthClient("c-" + u.substring(0, 20), "hash", "테스트 앱", u.substring(0, 10),
                Set.of("https://localhost:8080/openbanking/callback")));
    }

    private BankCustomer customer() {
        String u = unique();
        return customers.saveAndFlush(new BankCustomer(u.substring(0, 10), "user-" + u.substring(0, 12), "hash", "홍길동"));
    }

    private BankAccount account(BankCustomer c, String num, long balance) {
        String u = unique();
        return accounts.saveAndFlush(new BankAccount(c.getId(), u.substring(0, 24), "097", "오픈은행", num,
                "110-***-" + num.substring(num.length() - 4), "월급통장", c.getName(), balance));
    }

    @Test
    void clientRoundTripKeepsRedirectUris() {
        OauthClient saved = client();

        OauthClient found = clients.findByClientId(saved.getClientId()).orElseThrow();

        assertThat(found.allowsRedirectUri("https://localhost:8080/openbanking/callback")).isTrue();
        assertThat(found.allowsRedirectUri("https://evil.example/cb")).isFalse();
        assertThat(found.isEnabled()).isTrue();
    }

    @Test
    void accountAndTransactionsRoundTrip() {
        BankCustomer c = customer();
        BankAccount a = account(c, "1100" + unique().replaceAll("\\D", "0").substring(0, 8), 1_000_000);
        LocalDateTime now = LocalDateTime.now();
        transactions.saveAndFlush(new BankTransaction(a.getId(), now.minusDays(1), InOutType.IN, "급여", "10월 급여", 500_000, 1_500_000, "본점", null, null));
        transactions.saveAndFlush(new BankTransaction(a.getId(), now, InOutType.OUT, "이체", "월세", 500_000, 1_000_000, null, "M202112345UABC123456", null));

        List<BankTransaction> list = transactions.findByAccountIdAndTranAtBetweenOrderByTranAtDesc(a.getId(), now.minusDays(2), now.plusMinutes(1));

        assertThat(list).extracting(BankTransaction::getPrintContent).containsExactly("월세", "10월 급여");
        assertThat(accounts.findByCustomerIdAndStatusOrderByIdDesc(c.getId(), AccountStatus.ACTIVE)).hasSize(1);
        assertThat(accounts.findByFintechUseNum(a.getFintechUseNum())).isPresent();
    }

    @Test
    void negativeBalanceIsRejectedByDatabase() {
        BankCustomer c = customer();

        assertThatThrownBy(() -> account(c, "2200" + unique().replaceAll("\\D", "1").substring(0, 8), -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateAccountNumberInSameBankIsRejected() {
        BankCustomer c = customer();
        String num = "3300" + unique().replaceAll("\\D", "2").substring(0, 8);
        account(c, num, 0);

        assertThatThrownBy(() -> account(c, num, 0)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameBankTranIdFromSameClientIsRejectedButOtherClientIsAllowed() {
        OauthClient one = client();
        OauthClient two = client();
        callLogs.saveAndFlush(new ApiCallLog(one.getId(), "balance", "M202112345U111111111", null, "A0000"));

        callLogs.saveAndFlush(new ApiCallLog(two.getId(), "balance", "M202112345U111111111", null, "A0000"));
        assertThat(callLogs.existsByClientPkAndBankTranId(one.getId(), "M202112345U111111111")).isTrue();
        assertThatThrownBy(() -> callLogs.saveAndFlush(new ApiCallLog(one.getId(), "balance", "M202112345U111111111", null, "A0000")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void authorizationCodeAndTokensRoundTrip() {
        OauthClient c = client();
        BankCustomer u = customer();
        LocalDateTime now = LocalDateTime.now();
        String codeHash = unique() + unique().substring(0, 32);
        String tokenHash = unique() + unique().substring(0, 32);
        codes.saveAndFlush(new AuthorizationCode(codeHash, c.getId(), u.getId(), "https://localhost:8080/openbanking/callback", "login inquiry", now.plusMinutes(10)));
        tokens.saveAndFlush(new AccessToken(tokenHash, c.getId(), u.getId(), "login inquiry", now.plusDays(90)));
        tokens.saveAndFlush(new AccessToken(unique() + unique().substring(0, 32), c.getId(), null, "oob", now.plusDays(1)));

        AuthorizationCode code = codes.findByCodeHash(codeHash).orElseThrow();
        AccessToken token = tokens.findByTokenHash(tokenHash).orElseThrow();

        assertThat(code.isUsed()).isFalse();
        assertThat(code.isExpired(now)).isFalse();
        assertThat(token.isRevoked()).isFalse();
        assertThat(token.getCustomerId()).isEqualTo(u.getId());
    }

    @Test
    void consentIsUniquePerClientAndAccountAndHonorsRevoke() {
        OauthClient c = client();
        BankAccount a = account(customer(), "4400" + unique().replaceAll("\\D", "3").substring(0, 8), 0);
        consents.saveAndFlush(new AccountConsent(c.getId(), a.getId(), true, false));

        AccountConsent found = consents.findByClientPkAndAccountId(c.getId(), a.getId()).orElseThrow();

        assertThat(found.canInquire()).isTrue();
        assertThat(found.canTransfer()).isFalse();
        assertThatThrownBy(() -> consents.saveAndFlush(new AccountConsent(c.getId(), a.getId(), true, true)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void depositTransferWithItemsLinksTransactionsAndEscrow() {
        OauthClient c = client();
        BankCustomer owner = customer();
        BankAccount escrow = account(owner, "5500" + unique().replaceAll("\\D", "4").substring(0, 8), 10_000_000);
        BankAccount receiver = account(customer(), "5600" + unique().replaceAll("\\D", "5").substring(0, 8), 0);
        c.registerEscrow(escrow.getId(), "pass-hash");
        clients.saveAndFlush(c);

        BankTransfer transfer = transfers.saveAndFlush(new BankTransfer(c.getId(), "M202112345UDEP000001", TransferKind.DEPOSIT,
                null, 2, 30_000, TransferStatus.PARTIAL, "A0000"));
        BankTransferItem ok = transferItems.saveAndFlush(new BankTransferItem(transfer.getId(), 1, escrow.getId(), receiver.getId(),
                20_000, "정산금", BankTransferItem.ItemStatus.COMPLETED, "A0000"));
        transferItems.saveAndFlush(new BankTransferItem(transfer.getId(), 2, escrow.getId(), null,
                10_000, "정산금", BankTransferItem.ItemStatus.FAILED, "A0007"));
        transactions.saveAndFlush(new BankTransaction(receiver.getId(), LocalDateTime.now(), InOutType.IN, "입금이체", "정산금",
                20_000, 20_000, null, transfer.getBankTranId(), ok.getId()));

        assertThat(clients.findById(c.getId()).orElseThrow().getEscrowAccountId()).isEqualTo(escrow.getId());
        assertThat(transferItems.findByTransferIdOrderByTranNo(transfer.getId())).extracting(BankTransferItem::getRspCode)
                .containsExactly("A0000", "A0007");
        assertThat(transfers.findByClientPkAndBankTranId(c.getId(), "M202112345UDEP000001")).isPresent();
    }

    @Test
    void duplicateTransferTranIdIsRejected() {
        OauthClient c = client();
        BankTransfer t = transfers.saveAndFlush(new BankTransfer(c.getId(), "M202112345UWDR000001", TransferKind.WITHDRAW,
                null, 1, 1_000, TransferStatus.COMPLETED, "A0000"));
        transferItems.saveAndFlush(new BankTransferItem(t.getId(), 1, null, null, 1_000, null, BankTransferItem.ItemStatus.COMPLETED, "A0000"));

        assertThatThrownBy(() -> transfers.saveAndFlush(new BankTransfer(c.getId(), "M202112345UWDR000001", TransferKind.WITHDRAW,
                null, 1, 1_000, TransferStatus.COMPLETED, "A0000"))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void zeroTotalTransferIsRejected() {
        OauthClient c = client();

        assertThatThrownBy(() -> transfers.saveAndFlush(new BankTransfer(c.getId(), "M202112345UZERO00001", TransferKind.WITHDRAW,
                null, 1, 0, TransferStatus.FAILED, "A0001"))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void itemAmountAndOrderConstraints() {
        OauthClient c = client();
        BankTransfer t = transfers.saveAndFlush(new BankTransfer(c.getId(), "M202112345UITEM00001", TransferKind.DEPOSIT,
                null, 2, 2_000, TransferStatus.COMPLETED, "A0000"));
        transferItems.saveAndFlush(new BankTransferItem(t.getId(), 1, null, null, 1_000, null, BankTransferItem.ItemStatus.COMPLETED, "A0000"));

        assertThatThrownBy(() -> transferItems.saveAndFlush(new BankTransferItem(t.getId(), 1, null, null, 1_000, null,
                BankTransferItem.ItemStatus.COMPLETED, "A0000"))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> transferItems.saveAndFlush(new BankTransferItem(t.getId(), 2, null, null, 0, null,
                BankTransferItem.ItemStatus.FAILED, "A0001"))).isInstanceOf(DataIntegrityViolationException.class);
    }
}
