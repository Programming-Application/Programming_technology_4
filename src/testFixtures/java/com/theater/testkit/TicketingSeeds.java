package com.theater.testkit;

import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.MovieId;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.ScreenId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.TicketId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import com.theater.ticketing.domain.Ticket;
import com.theater.ticketing.domain.TicketStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

/**
 * ticketing BC のテスト seed。
 *
 * <p>seed は Repository を経由せず {@code uow.currentConnection()} 経由で raw SQL を発行する流儀 (他 BC Seeds
 * と同じ)。order / screening / movie / screen / seat / user の参照先 row は呼出側で先に作っておく前提。
 */
public record TicketingSeeds(UnitOfWork uow, Clock clock, IdGenerator idGenerator) {

  /** ACTIVE な Ticket を 1 件 INSERT し、record を返す。 */
  public Ticket activeTicket(
      OrderId orderId,
      ScreeningId screeningId,
      MovieId movieId,
      ScreenId screenId,
      SeatId seatId,
      UserId userId,
      Money price) {
    Instant now = clock.now();
    Ticket ticket =
        new Ticket(
            new TicketId(idGenerator.newId()),
            orderId,
            screeningId,
            movieId,
            screenId,
            seatId,
            userId,
            price,
            TicketStatus.ACTIVE,
            now,
            null,
            null,
            now,
            now,
            0);
    uow.executeVoid(Tx.REQUIRED, () -> insertTicket(uow.currentConnection(), ticket));
    return ticket;
  }

  private static void insertTicket(Connection conn, Ticket t) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO tickets(
              ticket_id, order_id, screening_id, movie_id, screen_id, seat_id,
              user_id, price, status, purchased_at, used_at, canceled_at,
              created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?)
            """)) {
      ps.setString(1, t.id().value());
      ps.setString(2, t.orderId().value());
      ps.setString(3, t.screeningId().value());
      ps.setString(4, t.movieId().value());
      ps.setString(5, t.screenId().value());
      ps.setString(6, t.seatId().value());
      ps.setString(7, t.userId().value());
      ps.setLong(8, t.price().minorUnits());
      ps.setString(9, t.status().name());
      ps.setLong(10, t.purchasedAt().toEpochMilli());
      ps.setLong(11, t.createdAt().toEpochMilli());
      ps.setLong(12, t.updatedAt().toEpochMilli());
      ps.setLong(13, t.version());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to seed ticket: " + t.id().value(), e);
    }
  }
}
