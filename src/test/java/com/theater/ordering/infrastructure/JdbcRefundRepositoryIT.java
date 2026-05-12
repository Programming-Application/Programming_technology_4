package com.theater.ordering.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.ordering.domain.Refund;
import com.theater.ordering.domain.RefundRepository;
import com.theater.shared.kernel.Currency;
import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.tx.JdbcUnitOfWork;
import com.theater.shared.tx.Tx;
import com.theater.testkit.Db;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcRefundRepositoryIT {

  private static final Instant NOW = Instant.parse("2026-05-12T00:00:00Z");
  private static final Money AMOUNT = new Money(3000L, Currency.JPY);

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private RefundRepository repository;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    repository = new JdbcRefundRepository(uow);
    seedPrerequisites();
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void save_inserts_refund_and_findByOrderId_reads_it_back() {
    Refund refund = new Refund("rf-1", new OrderId("o-1"), AMOUNT, "上映中止", NOW);
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(refund));

    Refund found =
        uow.execute(Tx.READ_ONLY, () -> repository.findByOrderId(new OrderId("o-1"))).orElseThrow();
    assertThat(found.refundId()).isEqualTo("rf-1");
    assertThat(found.amount()).isEqualTo(AMOUNT);
    assertThat(found.reason()).isEqualTo("上映中止");
    assertThat(found.refundedAt()).isEqualTo(NOW);
  }

  @Test
  void unique_order_id_prevents_duplicate_refund() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> repository.save(new Refund("rf-2", new OrderId("o-1"), AMOUNT, "初回返金", NOW)));

    assertThatThrownBy(
            () ->
                uow.executeVoid(
                    Tx.REQUIRED,
                    () ->
                        repository.save(
                            new Refund("rf-3", new OrderId("o-1"), AMOUNT, "二重返金", NOW))))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  @Test
  void fk_nonexistent_order_throws() {
    assertThatThrownBy(
            () ->
                uow.executeVoid(
                    Tx.REQUIRED,
                    () ->
                        repository.save(
                            new Refund("rf-4", new OrderId("no-order"), AMOUNT, "reason", NOW))))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  private void seedPrerequisites() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          JdbcOrderRepositoryIT.insertUser(conn, "u-1");
          JdbcOrderRepositoryIT.insertMovie(conn, "m-1");
          JdbcOrderRepositoryIT.insertScreen(conn, "screen-1");
          JdbcOrderRepositoryIT.insertScreening(conn, "sc-1", "m-1", "screen-1");
          JdbcOrderRepositoryIT.insertReservation(conn, "rv-1", "u-1", "sc-1");
          JdbcPaymentRepositoryIT.insertOrder(conn, "o-1", "u-1", "sc-1", "rv-1");
        });
  }
}
