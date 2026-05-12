package com.theater.reservation.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.reservation.application.dto.ConfirmedReservationView.SeatPriceLine;
import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.UserId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfirmedReservationViewTest {

  private static final ReservationId R = new ReservationId("r-1");
  private static final UserId U = new UserId("u-1");
  private static final ScreeningId S = new ScreeningId("s-1");

  @Test
  void constructs_with_valid_inputs() {
    var view = new ConfirmedReservationView(R, U, S, List.of(line("A-1", 1500)));
    assertThat(view.seats()).hasSize(1);
    assertThat(view.seats().get(0).price()).isEqualTo(Money.jpy(1500));
  }

  @Test
  void rejects_empty_seats() {
    assertThatThrownBy(() -> new ConfirmedReservationView(R, U, S, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be empty");
  }

  @Test
  void rejects_null_reservation_id() {
    assertThatThrownBy(() -> new ConfirmedReservationView(null, U, S, List.of(line("A-1", 1500))))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejects_null_seats_list() {
    assertThatThrownBy(() -> new ConfirmedReservationView(R, U, S, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void seats_list_is_defensively_copied() {
    var mutable = new ArrayList<SeatPriceLine>();
    mutable.add(line("A-1", 1500));

    var view = new ConfirmedReservationView(R, U, S, mutable);

    // 外側の list を変更しても view 内部は変わらない (防御コピー済)
    mutable.add(line("A-2", 2000));
    assertThat(view.seats()).hasSize(1);
  }

  @Test
  void seats_list_is_immutable() {
    var view = new ConfirmedReservationView(R, U, S, List.of(line("A-1", 1500)));
    assertThatThrownBy(() -> view.seats().add(line("A-2", 2000)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void seat_price_line_rejects_null_components() {
    assertThatThrownBy(() -> new SeatPriceLine(null, Money.jpy(1500)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SeatPriceLine(new SeatId("A-1"), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void total_via_money_plus_does_not_use_int_arithmetic() {
    var view =
        new ConfirmedReservationView(
            R, U, S, List.of(line("A-1", 1500), line("A-2", 1500), line("PREMIUM-1", 3000)));

    Money total =
        view.seats().stream()
            .map(SeatPriceLine::price)
            .reduce(Money.zero(com.theater.shared.kernel.Currency.JPY), Money::plus);

    assertThat(total).isEqualTo(Money.jpy(6000));
  }

  private static SeatPriceLine line(String seatId, long yen) {
    return new SeatPriceLine(new SeatId(seatId), Money.jpy(yen));
  }
}
