package com.theater.shared.eventbus;

import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.tx.UnitOfWork;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Outbox パターン実装。{@link UnitOfWork#currentConnection()} 経由で **現 Tx の Connection** に対して {@code
 * domain_events_outbox} へ INSERT する。
 *
 * <p>これにより、ビジネスロジックの書込と event の永続化が**同一 Tx に乗る**:
 *
 * <ul>
 *   <li>Tx commit → outbox 行が残る (= 後続の OutboxPublisher Job が配信できる)
 *   <li>Tx rollback → outbox 行も消える (= ロジック失敗時に偽イベントが配信されない)
 * </ul>
 *
 * <p>本クラス自体は **配信** はしない。配信は別 Job (PLAT-06 OutboxPublisher) が行う。
 */
public final class OutboxDomainEventBus implements DomainEventBus {

  private static final String INSERT_SQL =
      "INSERT INTO domain_events_outbox("
          + "event_id, aggregate_type, aggregate_id, event_type, payload_json, occurred_at) "
          + "VALUES (?, ?, ?, ?, ?, ?)";

  private final UnitOfWork uow;
  private final IdGenerator idGenerator;

  public OutboxDomainEventBus(UnitOfWork uow, IdGenerator idGenerator) {
    this.uow = Objects.requireNonNull(uow, "uow");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
  }

  @Override
  public void publish(DomainEvent event) {
    Objects.requireNonNull(event, "event");
    Connection conn = uow.currentConnection(); // Tx 外なら IllegalStateException
    try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
      ps.setString(1, idGenerator.newId());
      ps.setString(2, event.aggregateType());
      ps.setString(3, event.aggregateId());
      ps.setString(4, event.eventType());
      ps.setString(5, event.payloadJson());
      ps.setLong(6, event.occurredAt().toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to write outbox event", e);
    }
  }
}
