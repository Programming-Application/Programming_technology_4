package com.theater.reservation.application;

import com.theater.reservation.domain.SeatStateRepository;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.tx.TransactionalUseCase;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import java.util.List;
import java.util.Objects;

/** RV-01 上映会の座席状態一覧を返す Query UseCase。 */
public final class LoadSeatMapUseCase
    extends TransactionalUseCase<LoadSeatMapUseCase.Command, List<SeatMapEntry>> {

  private final SeatStateRepository seatStateRepo;

  public LoadSeatMapUseCase(UnitOfWork uow, SeatStateRepository seatStateRepo) {
    super(uow);
    this.seatStateRepo = Objects.requireNonNull(seatStateRepo, "seatStateRepo");
  }

  public record Command(ScreeningId screeningId) {
    public Command {
      Objects.requireNonNull(screeningId, "screeningId");
    }
  }

  @Override
  protected Tx txMode() {
    return Tx.READ_ONLY;
  }

  @Override
  protected List<SeatMapEntry> handle(Command cmd) {
    return seatStateRepo.findByScreening(cmd.screeningId()).stream()
        .map(s -> new SeatMapEntry(s.seatId(), s.status(), s.price()))
        .toList();
  }
}
