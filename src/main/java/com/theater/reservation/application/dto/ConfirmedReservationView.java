package com.theater.reservation.application.dto;

import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.UserId;
import java.util.List;
import java.util.Objects;

/**
 * reservation BC が ordering BC へ HOLD 中予約の確定情報を渡すための cross-BC DTO。
 *
 * <p>OR-04 Checkout のフロー:
 *
 * <ol>
 *   <li>ReservationRepository から HOLD 中 reservation を取得
 *   <li>SeatStateRepository から該当 seat_states (= 確定対象の座席群と価格) を取得
 *   <li>両者をまとめた本 view を ordering 層に渡す
 *   <li>ordering 層は本 view から合計金額再計算 → Order 作成 → 後続処理
 * </ol>
 *
 * <p>**設計判断**: 価格を {@code int} ではなく {@link Money} で表現する (CLAUDE.md §3.4)。これにより 合計金額計算で {@code
 * Money.plus} のみが許容され、生の {@code int} 加算事故を防ぐ。
 *
 * <p>**不変性**: {@code seats} は {@link List#copyOf} で防御コピー済の immutable list。
 */
public record ConfirmedReservationView(
    ReservationId reservationId,
    UserId userId,
    ScreeningId screeningId,
    List<SeatPriceLine> seats) {

  public ConfirmedReservationView {
    Objects.requireNonNull(reservationId, "reservationId");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(screeningId, "screeningId");
    seats = List.copyOf(Objects.requireNonNull(seats, "seats"));
    if (seats.isEmpty()) {
      throw new IllegalArgumentException("seats must not be empty");
    }
  }

  /** 1 座席分の価格行。合計金額は {@code seats().stream().map(SeatPriceLine::price).reduce(Money::plus)} で計算。 */
  public record SeatPriceLine(SeatId seatId, Money price) {

    public SeatPriceLine {
      Objects.requireNonNull(seatId, "seatId");
      Objects.requireNonNull(price, "price");
    }
  }
}
