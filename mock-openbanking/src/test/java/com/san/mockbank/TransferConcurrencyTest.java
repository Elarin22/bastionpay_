package com.san.mockbank;

import com.san.mockbank.account.AccountConsent;
import com.san.mockbank.account.AccountConsentRepository;
import com.san.mockbank.account.BankAccount;
import com.san.mockbank.account.BankAccountRepository;
import com.san.mockbank.api.ApiException;
import com.san.mockbank.api.ApiPrincipal;
import com.san.mockbank.api.RspCode;
import com.san.mockbank.client.OauthClient;
import com.san.mockbank.client.OauthClientRepository;
import com.san.mockbank.customer.BankCustomer;
import com.san.mockbank.customer.BankCustomerRepository;
import com.san.mockbank.oauth.AccessToken;
import com.san.mockbank.transfer.TransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 계좌에서 동시에 이체해도 돈이 틀어지지 않는지 확인한다.
 * 실제로 커밋해야 하므로 롤백 방식이 아니라, 이 테스트가 만든 전용 고객·계좌·이용기관을 쓰고 끝나면 모두 지운다
 * (시드 데이터는 건드리지 않는다).
 */
@SpringBootTest
class TransferConcurrencyTest {

    @Autowired TransferService service;
    @Autowired OauthClientRepository clients;
    @Autowired BankCustomerRepository customers;
    @Autowired BankAccountRepository accounts;
    @Autowired AccountConsentRepository consents;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;

    private String orgCode;
    private OauthClient client;
    private BankCustomer customer;
    private BankAccount from;
    private BankAccount escrow;
    private ApiPrincipal userPrincipal;
    private ApiPrincipal orgPrincipal;

    @BeforeEach
    void setUp() {
        String u = UUID.randomUUID().toString().replace("-", "");
        orgCode = "T" + u.substring(0, 9).toUpperCase();
        customer = customers.save(new BankCustomer(uniqueDigits(10), "conc-" + u.substring(0, 12), "x", "동시성"));
        BankCustomer escrowOwner = customers.save(new BankCustomer(uniqueDigits(10), "conc-e-" + u.substring(0, 10), "x", "약정"));
        from = accounts.save(new BankAccount(customer.getId(), uniqueDigits(24), "901", "한빛은행", "7" + uniqueDigits(11), "700-***-0001", "테스트", "동시성", 1_000));
        escrow = accounts.save(new BankAccount(escrowOwner.getId(), uniqueDigits(24), "901", "한빛은행", "8" + uniqueDigits(11), "800-***-0001", "약정", "약정", 0));
        client = new OauthClient("conc-" + u.substring(0, 16), "x", "동시성 테스트 앱", orgCode, Set.of("https://localhost:9/cb"));
        client.registerEscrow(escrow.getId(), encoder.encode("pass"));
        client = clients.save(client);
        consents.save(new AccountConsent(client.getId(), from.getId(), true, true));
        userPrincipal = new ApiPrincipal(new AccessToken("h", client.getId(), customer.getId(), "login inquiry transfer", LocalDateTime.now().plusDays(1)), client, customer);
        orgPrincipal = new ApiPrincipal(new AccessToken("h2", client.getId(), null, "oob", LocalDateTime.now().plusDays(1)), client, null);
    }

    @AfterEach
    void cleanUp() {
        if (client == null || from == null || escrow == null) return;
        Long clientPk = client.getId();
        List<Long> accountIds = List.of(from.getId(), escrow.getId());
        jdbc.update("update bank_transaction set transfer_item_id = null where account_id in (?, ?)", accountIds.get(0), accountIds.get(1));
        jdbc.update("delete from bank_transaction where account_id in (?, ?)", accountIds.get(0), accountIds.get(1));
        jdbc.update("delete from bank_transfer where client_pk = ?", clientPk);          // 항목은 ON DELETE CASCADE
        jdbc.update("delete from api_call_log where client_pk = ?", clientPk);
        jdbc.update("delete from account_consent where client_pk = ?", clientPk);
        jdbc.update("delete from oauth_client where id = ?", clientPk);
        jdbc.update("delete from bank_account where id in (?, ?)", accountIds.get(0), accountIds.get(1));
        jdbc.update("delete from bank_customer where id in (?, ?)", customer.getId(), escrow.getCustomerId());
    }

    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    /** 실행마다·호출마다 겹치지 않는 숫자 문자열(앞을 0 으로 채움). */
    private static String uniqueDigits(int length) {
        return String.format("%0" + length + "d", SEQ.incrementAndGet() % (long) Math.pow(10, Math.min(length, 17)));
    }

    private String tranId() {
        return orgCode + "U" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase();
    }

    private TransferService.WithdrawCommand withdraw(String tranId, long amount) {
        return new TransferService.WithdrawCommand(tranId, "20261021101500", from.getFintechUseNum(), String.valueOf(amount), null);
    }

    private TransferService.DepositCommand deposit(String tranId, long amount) {
        return new TransferService.DepositCommand(tranId, "20261021101500", "pass", null, 1,
                List.of(new TransferService.ItemCommand(1, from.getFintechUseNum(), null, null, null, String.valueOf(amount))));
    }

    /** 모든 작업을 같은 순간에 시작시키고 결과(예외 포함)를 모은다. */
    private <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return task.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        ready.await();
        go.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) results.add(f.get(30, TimeUnit.SECONDS));   // 교착이 있으면 여기서 시간 초과로 실패한다
        pool.shutdown();
        return results;
    }

    private long balance(BankAccount a) {
        return accounts.findById(a.getId()).orElseThrow().getBalanceAmt();
    }

    @Test
    void concurrentWithdrawalsNeverOverdrawOrDoubleSpend() throws Exception {
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String id = tranId();
            tasks.add(() -> service.withdraw(userPrincipal, withdraw(id, 300)));
        }

        List<Object> results = runConcurrently(tasks);

        long succeeded = results.stream().filter(r -> r instanceof TransferService.WithdrawResult).count();
        long insufficient = results.stream().filter(r -> r instanceof ApiException e && e.getRspCode() == RspCode.INSUFFICIENT_BALANCE).count();
        assertThat(succeeded).isEqualTo(3);                      // 1,000원으로 300원씩은 3번까지
        assertThat(insufficient).isEqualTo(7);
        assertThat(balance(from)).isEqualTo(100);
        assertThat(balance(escrow)).isEqualTo(900);              // 빠져나간 만큼 정확히 들어와 있다
        assertThat(jdbc.queryForObject("select count(*) from bank_transaction where account_id in (?, ?)", Long.class, from.getId(), escrow.getId())).isEqualTo(6);
    }

    @Test
    void sameTranIdSentConcurrentlyIsProcessedOnce() throws Exception {
        String sameId = tranId();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) tasks.add(() -> service.withdraw(userPrincipal, withdraw(sameId, 100)));

        List<Object> results = runConcurrently(tasks);

        assertThat(results.stream().filter(r -> r instanceof TransferService.WithdrawResult)).hasSize(1);
        // 나머지는 모두 중복으로 거절된다(재사용 확인 또는 DB 유일 제약)
        assertThat(results.stream().filter(r -> r instanceof ApiException e && e.getRspCode() == RspCode.DUPLICATE_TRAN_ID
                || r instanceof DataIntegrityViolationException)).hasSize(7);
        assertThat(balance(from)).isEqualTo(900);
        assertThat(balance(escrow)).isEqualTo(100);
    }

    @Test
    void oppositeDirectionTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        // 한쪽은 계좌 → 약정계좌(출금이체), 다른 쪽은 약정계좌 → 계좌(입금이체)를 동시에 많이 보낸다
        jdbc.update("update bank_account set balance_amt = 100000 where id = ?", escrow.getId());
        long total = balance(from) + balance(escrow);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String id = tranId();
            if (i % 2 == 0) tasks.add(() -> service.withdraw(userPrincipal, withdraw(id, 50)));
            else tasks.add(() -> service.deposit(orgPrincipal, deposit(id, 70), false));
        }

        List<Object> results = runConcurrently(tasks);

        assertThat(results.stream().filter(r -> r instanceof Exception e && !(e instanceof ApiException))).isEmpty();   // 교착·예상 밖 예외 없음
        assertThat(balance(from) + balance(escrow)).isEqualTo(total);                                                   // 돈이 늘거나 줄지 않는다
        assertThat(balance(from)).isGreaterThanOrEqualTo(0);
        assertThat(balance(escrow)).isGreaterThanOrEqualTo(0);
    }
}
