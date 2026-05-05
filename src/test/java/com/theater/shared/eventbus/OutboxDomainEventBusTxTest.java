package com.theater.shared.eventbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.shared.tx.JdbcUnitOfWork;
import com.theater.shared.tx.Tx;
import com.theater.testkit.Db;
import com.theater.testkit.IncrementingIdGenerator;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link OutboxDomainEventBus} の Tx 統合テスト。
 *
 * <p>本案件の Atomicity 保証の中核: ビジネス書込と event 書込が同 Tx で commit / rollback されること。 docs/testing.md §2.1 の
 * Atomicity 拡張。
 */
class OutboxDomainEventBusTxTest {

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private OutboxDomainEventBus bus;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    bus = new OutboxDomainEventBus(uow, new IncrementingIdGenerator("evt-"));
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void publish_outside_tx_throws() {
    assertThatThrownBy(() -> bus.publish(testEvent()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No transaction in progress");
  }

  @Test
  void committed_event_is_persisted_to_outbox() {
    uow.executeVoid(Tx.REQUIRED, () -> bus.publish(testEvent()));

    assertThat(countOutbox()).isEqualTo(1L);
    assertThat(firstEventType()).isEqualTo("OrderConfirmed");
  }

  @Test
  void rolled_back_event_is_not_persisted() {
    assertThatThrownBy(
            () ->
                uow.executeVoid(
                    Tx.REQUIRED,
                    () -> {
                      bus.publish(testEvent());
                      throw new RuntimeException("boom");
                    }))
        .hasMessage("boom");

    // ★ Atomicity: 業務処理が rollback されたら event も消える
    assertThat(countOutbox()).isZero();
  }

  @Test
  void multiple_events_in_same_tx_all_persist_or_none() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          bus.publish(testEvent("Order", "o-1", "OrderConfirmed"));
          bus.publish(testEvent("Ticket", "t-1", "TicketsIssued"));
          bus.publish(testEvent("Ticket", "t-2", "TicketsIssued"));
        });
    assertThat(countOutbox()).isEqualTo(3L);
  }

  // ---- helpers ----

  private static DomainEvent testEvent() {
    return testEvent("Order", "o-1", "OrderConfirmed");
  }

  private static DomainEvent testEvent(String aggType, String aggId, String evtType) {
    return new TestEvent(
        aggType, aggId, evtType, Instant.parse("2026-05-04T00:00:00Z"), "{\"k\":\"v\"}");
  }

  private record TestEvent(
      String aggregateType,
      String aggregateId,
      String eventType,
      Instant occurredAt,
      String payloadJson)
      implements DomainEvent {}

  private long countOutbox() {
    try (Connection conn = testDb.writable().getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM domain_events_outbox")) {
      rs.next();
      return rs.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private String firstEventType() {
    try (Connection conn = testDb.writable().getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT event_type FROM domain_events_outbox ORDER BY occurred_at LIMIT 1")) {
      rs.next();
      return rs.getString(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
