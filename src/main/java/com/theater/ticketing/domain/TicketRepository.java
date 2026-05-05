package com.theater.ticketing.domain;

import com.theater.shared.kernel.TicketId;
import com.theater.shared.kernel.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Ticket 集約の永続化抽象。実装は {@code ticketing/infrastructure/JdbcTicketRepository} (TK-01 で実装)。
 *
 * <p>{@link #insert} は OR-04 Checkout が同 Tx 内で複数枚発券する用途。 {@link #markUsed} は TK-04 (Sprint 2) 用に
 * interface だけ用意。
 */
public interface TicketRepository {

  Optional<Ticket> findById(TicketId id);

  /** TK-01 ListMyTickets / TK-02 GetTicketDetail で使用。status フィルタは呼出側 or List 受取後。 */
  List<Ticket> findByUser(UserId userId);

  /** OR-04 Checkout が同 Tx 内で呼ぶ。ダブルブッキングは uq_tickets_active_seat で最終防壁。 */
  void insert(Ticket ticket);

  /** TK-04 MarkUsed で使用 (Sprint 2)。冪等性は呼出側で保証。 */
  void markUsed(TicketId id, Instant usedAt);
}
