package com.theater.ticketing.application;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.catalog.domain.CatalogQueryRepository.ScreeningDetail;
import com.theater.shared.error.NotFoundException;
import com.theater.shared.kernel.TicketId;
import com.theater.shared.tx.TransactionalUseCase;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import com.theater.ticketing.domain.Ticket;
import com.theater.ticketing.domain.TicketRepository;
import java.util.Objects;

/**
 * 単一チケットの詳細を取得する Query UseCase。
 *
 * <p>チケットが存在しなければ {@link NotFoundException}。 catalog の {@code findScreeningDetail} を 1 回呼んで title /
 * screenName / startTime / endTime を補完する。
 */
public final class GetTicketDetailUseCase
    extends TransactionalUseCase<GetTicketDetailUseCase.Command, TicketDetailView> {

  private final TicketRepository tickets;
  private final CatalogQueryRepository catalog;

  public GetTicketDetailUseCase(
      UnitOfWork uow, TicketRepository tickets, CatalogQueryRepository catalog) {
    super(uow);
    this.tickets = Objects.requireNonNull(tickets, "tickets");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  public record Command(TicketId ticketId) {
    public Command {
      Objects.requireNonNull(ticketId, "ticketId");
    }
  }

  @Override
  protected Tx txMode() {
    return Tx.READ_ONLY;
  }

  @Override
  protected TicketDetailView handle(Command cmd) {
    Ticket ticket =
        tickets
            .findById(cmd.ticketId())
            .orElseThrow(() -> new NotFoundException("Ticket", cmd.ticketId().value()));
    ScreeningDetail detail =
        catalog
            .findScreeningDetail(ticket.screeningId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Screening referenced by ticket not found: "
                            + ticket.screeningId().value()));
    return TicketDetailView.from(
        ticket,
        detail.movie().title(),
        detail.screen().name(),
        detail.screening().startTime(),
        detail.screening().endTime());
  }
}
