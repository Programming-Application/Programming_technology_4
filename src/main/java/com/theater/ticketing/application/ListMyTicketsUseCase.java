package com.theater.ticketing.application;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.catalog.domain.CatalogQueryRepository.ScreeningDetail;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.TransactionalUseCase;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import com.theater.ticketing.domain.Ticket;
import com.theater.ticketing.domain.TicketRepository;
import com.theater.ticketing.domain.TicketStatus;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 自分のチケット一覧を取得する Query UseCase。{@link TicketStatus} で絞り込み可能。
 *
 * <p>tickets テーブルは movie/screen の名前を持たないため、各 ticket の screening に対し catalog の {@link
 * CatalogQueryRepository#findScreeningDetail} を呼んで title / screenName / startTime を補完する。 同一
 * screening は呼び出し内でメモ化して重複 query を避ける。
 *
 * <p>パターン: Template Method ({@link TransactionalUseCase}) + Command (record)。
 */
public final class ListMyTicketsUseCase
    extends TransactionalUseCase<ListMyTicketsUseCase.Command, List<TicketSummary>> {

  private final TicketRepository tickets;
  private final CatalogQueryRepository catalog;

  public ListMyTicketsUseCase(
      UnitOfWork uow, TicketRepository tickets, CatalogQueryRepository catalog) {
    super(uow);
    this.tickets = Objects.requireNonNull(tickets, "tickets");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  public record Command(UserId userId, Set<TicketStatus> statuses) {
    public Command {
      Objects.requireNonNull(userId, "userId");
      statuses =
          (statuses == null || statuses.isEmpty())
              ? EnumSet.of(TicketStatus.ACTIVE)
              : EnumSet.copyOf(statuses);
    }

    public static Command activeOnly(UserId userId) {
      return new Command(userId, EnumSet.of(TicketStatus.ACTIVE));
    }

    public static Command allStatuses(UserId userId) {
      return new Command(userId, EnumSet.allOf(TicketStatus.class));
    }
  }

  @Override
  protected Tx txMode() {
    return Tx.READ_ONLY;
  }

  @Override
  protected List<TicketSummary> handle(Command cmd) {
    Map<ScreeningId, ScreeningDetail> screeningCache = new HashMap<>();
    return tickets.findByUser(cmd.userId()).stream()
        .filter(t -> cmd.statuses().contains(t.status()))
        .map(t -> toSummary(t, screeningCache))
        .toList();
  }

  private TicketSummary toSummary(Ticket ticket, Map<ScreeningId, ScreeningDetail> cache) {
    ScreeningDetail detail =
        cache.computeIfAbsent(
            ticket.screeningId(),
            id ->
                catalog
                    .findScreeningDetail(id)
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "Screening referenced by ticket not found: " + id.value())));
    return TicketSummary.from(
        ticket, detail.movie().title(), detail.screen().name(), detail.screening().startTime());
  }
}
