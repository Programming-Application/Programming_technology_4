package com.theater.shared.eventbus;

import java.time.Instant;

/**
 * 集約から発生するドメインイベントのマーカ。
 *
 * <p>実装は通常 record で、イベント種別ごとに独立した型として定義する:
 *
 * <pre>{@code
 * public record OrderConfirmed(OrderId orderId, Instant occurredAt, String payloadJson)
 *     implements DomainEvent {
 *   @Override public String aggregateType() { return "Order"; }
 *   @Override public String aggregateId()   { return orderId.value(); }
 *   @Override public String eventType()     { return "OrderConfirmed"; }
 * }
 * }</pre>
 *
 * <p>パターン: Observer (publish/subscribe の subject 役は {@link DomainEventBus})。
 */
public interface DomainEvent {

  /** 例: "Order" / "Reservation" / "Ticket"。docs/data_model.md §1 の outbox.aggregate_type に書く。 */
  String aggregateType();

  /** 集約 ID の文字列表現。outbox.aggregate_id に書く。 */
  String aggregateId();

  /** 例: "OrderConfirmed" / "TicketsIssued"。outbox.event_type に書く。 */
  String eventType();

  /** イベント発生時刻 (ドメインの "起きた時刻"、配信時刻ではない)。 */
  Instant occurredAt();

  /** 永続化されるイベント本体。本案件では手書き JSON で十分 (Jackson 等は導入しない)。 */
  String payloadJson();
}
