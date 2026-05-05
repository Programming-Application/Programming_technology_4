package com.theater.shared.eventbus;

/**
 * ドメインイベントの publish 抽象。
 *
 * <p>標準実装は {@link OutboxDomainEventBus} で、現 Tx の Connection で {@code domain_events_outbox} に INSERT
 * する。Tx 外で呼ぶと {@link IllegalStateException}。
 *
 * <p>パターン: Observer (subject) + Outbox (永続化)。
 */
public interface DomainEventBus {

  /** 現 Tx 内で event を outbox に書き込む。Tx 外で呼んだ場合は IllegalStateException。 */
  void publish(DomainEvent event);
}
