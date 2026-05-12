package com.theater.ordering.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.ordering.domain.Payment;
import com.theater.ordering.domain.PaymentId;
import com.theater.ordering.domain.PaymentRepository;
import com.theater.ordering.domain.PaymentStatus;
import com.theater.shared.error.OptimisticLockException;
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

class JdbcPaymentRepositoryIT {

  private static final Instant NOW = Instant.parse("2026-05-12T00:00:00Z");
  private static final Money AMOUNT = new Money(3000L, Currency.JPY);

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private PaymentRepository repository;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    repository = new JdbcPaymentRepository(uow);
    seedPrerequisites();
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void save_inserts_payment_and_findByOrderId_reads_it_back() {
    Payment payment = pendingPayment("p-1", "o-1");
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(payment));

    Payment found =
        uow.execute(Tx.READ_ONLY, () -> repository.findByOrderId(new OrderId("o-1"))).orElseThrow();
    assertThat(found.id()).isEqualTo(new PaymentId("p-1"));
    assertThat(found.amount()).isEqualTo(AMOUNT);
    assertThat(found.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(found.processedAt()).isNull();
    assertThat(found.version()).isEqualTo(0L);
  }

  @Test
  void save_update_increments_version_and_updates_status() {
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(pendingPayment("p-2", "o-1")));

    Payment loaded =
        uow.execute(Tx.READ_ONLY, () -> repository.findByOrderId(new OrderId("o-1"))).orElseThrow();
    Payment paid =
        new Payment(
            loaded.id(),
            loaded.orderId(),
            loaded.amount(),
            PaymentStatus.PAID,
            NOW,
            loaded.createdAt(),
            NOW,
            loaded.version() + 1);
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(paid));

    Payment reloaded =
        uow.execute(Tx.READ_ONLY, () -> repository.findByOrderId(new OrderId("o-1"))).orElseThrow();
    assertThat(reloaded.status()).isEqualTo(PaymentStatus.PAID);
    assertThat(reloaded.processedAt()).isEqualTo(NOW);
    assertThat(reloaded.version()).isEqualTo(1L);
  }

  @Test
  void save_with_stale_version_throws_optimistic_lock_exception() {
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(pendingPayment("p-3", "o-1")));

    Payment loaded =
        uow.execute(Tx.READ_ONLY, () -> repository.findByOrderId(new OrderId("o-1"))).orElseThrow();
    Payment paid =
        new Payment(
            loaded.id(),
            loaded.orderId(),
            loaded.amount(),
            PaymentStatus.PAID,
            NOW,
            loaded.createdAt(),
            NOW,
            loaded.version() + 1);
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(paid));

    assertThatThrownBy(() -> uow.executeVoid(Tx.REQUIRED, () -> repository.save(paid)))
        .isInstanceOf(OptimisticLockException.class);
  }

  @Test
  void unique_order_id_prevents_duplicate_payment() {
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(pendingPayment("p-4", "o-1")));

    assertThatThrownBy(
            () -> uow.executeVoid(Tx.REQUIRED, () -> repository.save(pendingPayment("p-5", "o-1"))))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  @Test
  void fk_nonexistent_order_throws() {
    assertThatThrownBy(
            () ->
                uow.executeVoid(
                    Tx.REQUIRED, () -> repository.save(pendingPayment("p-6", "no-order"))))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  private Payment pendingPayment(String paymentId, String orderId) {
    return new Payment(
        new PaymentId(paymentId),
        new OrderId(orderId),
        AMOUNT,
        PaymentStatus.PENDING,
        null,
        NOW,
        NOW,
        0L);
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
          insertOrder(conn, "o-1", "u-1", "sc-1", "rv-1");
        });
  }

  static void insertOrder(
      Connection conn, String orderId, String userId, String screeningId, String reservationId) {
    try (var ps =
        conn.prepareStatement(
            """
            INSERT INTO orders(
              order_id, user_id, screening_id, reservation_id,
              total_amount, payment_status, order_status,
              purchased_at, canceled_at, created_at, updated_at, version)
            VALUES (?,?,?,?,?,?,?,NULL,NULL,?,?,0)
            """)) {
      ps.setString(1, orderId);
      ps.setString(2, userId);
      ps.setString(3, screeningId);
      ps.setString(4, reservationId);
      ps.setLong(5, 3000L);
      ps.setString(6, "PENDING");
      ps.setString(7, "CREATED");
      ps.setLong(8, NOW.toEpochMilli());
      ps.setLong(9, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
