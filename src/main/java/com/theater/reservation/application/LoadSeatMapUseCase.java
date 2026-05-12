package com.theater.reservation.application;

import com.theater.reservation.domain.SeatStateRepository;
import com.theater.reservation.domain.SeatStateStatus;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.tx.TransactionalUseCase;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 指定上映の座席状態を画面表示用に取得する。 */
public final class LoadSeatMapUseCase
    extends TransactionalUseCase<LoadSeatMapUseCase.Command, LoadSeatMapUseCase.Result> {

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

  public record Result(List<SeatView> seats) {
    public Result {
      seats = List.copyOf(seats);
    }
  }

  public record SeatView(String seatId, SeatStateStatus status, int price) {
    public SeatView {
      Objects.requireNonNull(seatId, "seatId");
      Objects.requireNonNull(status, "status");
    }
  }

  @Override
  protected Tx txMode() {
    return Tx.READ_ONLY;
  }

  @Override
  protected Result handle(Command cmd) {
    List<SeatView> seats =
        seatStateRepo.findByScreening(cmd.screeningId()).stream()
            .map(s -> new SeatView(s.seatId().value(), s.status(), s.price()))
            .sorted(Comparator.comparing(SeatView::seatId, LoadSeatMapUseCase::compareSeatId))
            .toList();
    return new Result(seats);
  }

  private static int compareSeatId(String left, String right) {
    SeatPosition l = SeatPosition.parse(left);
    SeatPosition r = SeatPosition.parse(right);
    int row = l.row().compareTo(r.row());
    if (row != 0) {
      return row;
    }
    return Integer.compare(l.number(), r.number());
  }

  private record SeatPosition(String row, int number) {
    static SeatPosition parse(String seatId) {
      int split = 0;
      while (split < seatId.length() && !Character.isDigit(seatId.charAt(split))) {
        split++;
      }
      if (split == 0 || split == seatId.length()) {
        return new SeatPosition(seatId, 0);
      }
      return new SeatPosition(
          seatId.substring(0, split), Integer.parseInt(seatId.substring(split)));
    }
  }
}
