package com.theater.ticketing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.MovieId;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.ScreenId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.TicketId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.JdbcUnitOfWork;
import com.theater.shared.tx.Tx;
import com.theater.testkit.Db;
import com.theater.ticketing.domain.Ticket;
import com.theater.ticketing.domain.TicketStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link JdbcTicketRepository} の Repository IT。
 *
 * <p>★ 中核は {@code uq_tickets_active_seat} (同一 screening の同一 seat に複数 ACTIVE 不可) の **DB レベル 最終防壁**
 * を直接 assert すること。docs/data_model.md §6 の多層防御 L4 に該当。
 */
class JdbcTicketRepositoryIT {

  private static final Instant NOW = Instant.parse("2026-05-12T00:00:00Z");

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private JdbcTicketRepository repository;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    repository = new JdbcTicketRepository(uow);
    seedReferents();
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Nested
  class InsertAndFind {

    @Test
    void insert_then_find_by_id_returns_ticket() {
      Ticket t = newActive("t-1", "A-1");
      uow.executeVoid(Tx.REQUIRED, () -> repository.insert(t));

      Ticket found =
          uow.execute(Tx.READ_ONLY, () -> repository.findById(new TicketId("t-1")).orElseThrow());
      assertThat(found.seatId().value()).isEqualTo("A-1");
      assertThat(found.price()).isEqualTo(Money.jpy(1500));
      assertThat(found.status()).isEqualTo(TicketStatus.ACTIVE);
    }

    @Test
    void find_by_user_returns_only_that_users_tickets() {
      uow.executeVoid(Tx.REQUIRED, () -> repository.insert(newActive("t-1", "A-1")));
      uow.executeVoid(Tx.REQUIRED, () -> repository.insert(newActive("t-2", "A-2")));

      var owned = uow.execute(Tx.READ_ONLY, () -> repository.findByUser(new UserId("u-1")));
      assertThat(owned).extracting(t -> t.id().value()).containsExactlyInAnyOrder("t-1", "t-2");
    }

    @Test
    void find_by_id_returns_empty_when_missing() {
      assertThat(uow.execute(Tx.READ_ONLY, () -> repository.findById(new TicketId("missing"))))
          .isEmpty();
    }
  }

  @Nested
  class FinalDefenseAgainstDoubleBooking {

    /** ★ {@code uq_tickets_active_seat} が同一 screening × seat の 2 ACTIVE を弾く。 */
    @Test
    void cannot_insert_two_active_tickets_for_same_seat() {
      uow.executeVoid(Tx.REQUIRED, () -> repository.insert(newActive("t-1", "A-1")));

      assertThatThrownBy(
              () -> uow.executeVoid(Tx.REQUIRED, () -> repository.insert(newActive("t-2", "A-1"))))
          .isInstanceOf(IllegalStateException.class)
          .hasRootCauseInstanceOf(SQLException.class);
    }

    /** partial UNIQUE なので、status != ACTIVE は対象外: USED ticket の seat には新規 ACTIVE を INSERT 可能。 */
    @Test
    void can_insert_new_active_ticket_after_existing_is_used() {
      Ticket original = newActive("t-1", "A-1");
      uow.executeVoid(Tx.REQUIRED, () -> repository.insert(original));
      uow.executeVoid(Tx.REQUIRED, () -> repository.markUsed(original.id(), NOW.plusSeconds(60)));

      // 同じ (screening, seat) でも USED は対象外 → 新規 ACTIVE は通る
      uow.executeVoid(Tx.REQUIRED, () -> repository.insert(newActive("t-2", "A-1")));

      assertThat(uow.execute(Tx.READ_ONLY, () -> repository.findById(new TicketId("t-2"))))
          .isPresent();
    }
  }

  @Nested
  class SchemaConstraints {

    @Test
    void negative_price_check_violation() {
      assertThatThrownBy(
              () ->
                  uow.executeVoid(
                      Tx.REQUIRED,
                      () ->
                          rawInsertTicket(
                              uow.currentConnection(), "t-1", "A-1", -1, "ACTIVE", null, null)))
          .isInstanceOf(IllegalStateException.class)
          .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void invalid_status_check_violation() {
      assertThatThrownBy(
              () ->
                  uow.executeVoid(
                      Tx.REQUIRED,
                      () ->
                          rawInsertTicket(
                              uow.currentConnection(), "t-1", "A-1", 1500, "EXPIRED", null, null)))
          .isInstanceOf(IllegalStateException.class)
          .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void used_without_used_at_check_violation() {
      assertThatThrownBy(
              () ->
                  uow.executeVoid(
                      Tx.REQUIRED,
                      () ->
                          rawInsertTicket(
                              uow.currentConnection(),
                              "t-1",
                              "A-1",
                              1500,
                              "USED",
                              null, // ★ used_at が null
                              null)))
          .isInstanceOf(IllegalStateException.class)
          .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void fk_violation_when_screening_missing() {
      assertThatThrownBy(
              () ->
                  uow.executeVoid(
                      Tx.REQUIRED,
                      () -> {
                        try (PreparedStatement ps =
                            uow.currentConnection()
                                .prepareStatement(
                                    """
                                    INSERT INTO tickets(
                                      ticket_id, order_id, screening_id, movie_id, screen_id,
                                      seat_id, user_id, price, status, purchased_at,
                                      created_at, updated_at, version)
                                    VALUES (?,?,?,?,?,?,?,?,'ACTIVE',?,?,?,0)
                                    """)) {
                          ps.setString(1, "t-1");
                          ps.setString(2, "order-x");
                          ps.setString(3, "no-such-screening");
                          ps.setString(4, "movie-1");
                          ps.setString(5, "screen-1");
                          ps.setString(6, "A-1");
                          ps.setString(7, "u-1");
                          ps.setLong(8, 1500);
                          ps.setLong(9, NOW.toEpochMilli());
                          ps.setLong(10, NOW.toEpochMilli());
                          ps.setLong(11, NOW.toEpochMilli());
                          ps.executeUpdate();
                        } catch (SQLException e) {
                          throw new IllegalStateException(e);
                        }
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasRootCauseInstanceOf(SQLException.class);
    }
  }

  @Nested
  class MarkUsedIdempotency {

    @Test
    void mark_used_on_active_ticket_transitions_to_used() {
      Ticket active = newActive("t-1", "A-1");
      uow.executeVoid(Tx.REQUIRED, () -> repository.insert(active));
      uow.executeVoid(Tx.REQUIRED, () -> repository.markUsed(active.id(), NOW.plusSeconds(60)));

      Ticket after =
          uow.execute(Tx.READ_ONLY, () -> repository.findById(active.id()).orElseThrow());
      assertThat(after.status()).isEqualTo(TicketStatus.USED);
      assertThat(after.usedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void mark_used_is_idempotent_no_op_on_already_used() {
      Ticket active = newActive("t-1", "A-1");
      uow.executeVoid(Tx.REQUIRED, () -> repository.insert(active));
      uow.executeVoid(Tx.REQUIRED, () -> repository.markUsed(active.id(), NOW.plusSeconds(60)));

      // 2回目: 既に USED なので影響行数 0 で no-op。例外にならない。
      uow.executeVoid(Tx.REQUIRED, () -> repository.markUsed(active.id(), NOW.plusSeconds(120)));

      Ticket after =
          uow.execute(Tx.READ_ONLY, () -> repository.findById(active.id()).orElseThrow());
      assertThat(after.usedAt()).isEqualTo(NOW.plusSeconds(60)); // 最初の usedAt を維持
    }
  }

  // ---- helpers ----

  private Ticket newActive(String ticketId, String seatId) {
    return new Ticket(
        new TicketId(ticketId),
        new OrderId("order-x"),
        new ScreeningId("screening-1"),
        new MovieId("movie-1"),
        new ScreenId("screen-1"),
        new SeatId(seatId),
        new UserId("u-1"),
        Money.jpy(1500),
        TicketStatus.ACTIVE,
        NOW,
        null,
        null,
        NOW,
        NOW,
        0);
  }

  private void seedReferents() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          rawInsertUser(conn, "u-1", "alice@example.com");
          rawInsertMovie(conn, "movie-1", "Test Movie");
          rawInsertScreen(conn, "screen-1");
          rawInsertSeat(conn, "screen-1", "A-1", "A", 1);
          rawInsertSeat(conn, "screen-1", "A-2", "A", 2);
          rawInsertScreening(conn);
        });
  }

  // 以下、テスト用に他 BC のテーブルへ raw INSERT する小さなヘルパ。BC 越境ではあるが、
  // 「ticketing の Repository IT を独立に走らせる」ためテストに限ってここに置く。

  private static void rawInsertUser(Connection conn, String id, String email) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO users(user_id, email, name, password_hash, role,
                              created_at, updated_at, version)
            VALUES (?,?,?,?,'USER',?,?,0)
            """)) {
      ps.setString(1, id);
      ps.setString(2, email);
      ps.setString(3, "name-" + id);
      ps.setString(4, "h");
      ps.setLong(5, NOW.toEpochMilli());
      ps.setLong(6, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void rawInsertMovie(Connection conn, String id, String title) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO movies(movie_id, title, description, duration_minutes,
                               is_published, created_at, updated_at, version)
            VALUES (?,?,?,?,1,?,?,0)
            """)) {
      ps.setString(1, id);
      ps.setString(2, title);
      ps.setString(3, "");
      ps.setInt(4, 100);
      ps.setLong(5, NOW.toEpochMilli());
      ps.setLong(6, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void rawInsertScreen(Connection conn, String id) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO screens(screen_id, name, total_seats, created_at, updated_at)
            VALUES (?,?,?,?,?)
            """)) {
      ps.setString(1, id);
      ps.setString(2, "Screen " + id);
      ps.setInt(3, 100);
      ps.setLong(4, NOW.toEpochMilli());
      ps.setLong(5, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void rawInsertSeat(
      Connection conn, String screenId, String seatId, String row, int number) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO seats(screen_id, seat_id, row, number, seat_type, is_active)
            VALUES (?,?,?,?,'NORMAL',1)
            """)) {
      ps.setString(1, screenId);
      ps.setString(2, seatId);
      ps.setString(3, row);
      ps.setInt(4, number);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void rawInsertScreening(Connection conn) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO screenings(
              screening_id, movie_id, screen_id, start_time, end_time,
              sales_start_at, sales_end_at, status, is_private,
              available_seat_count, reserved_seat_count, sold_seat_count,
              last_updated, created_at, updated_at, version)
            VALUES ('screening-1','movie-1','screen-1',?,?,?,?,'OPEN',0,100,0,0,?,?,?,0)
            """)) {
      long now = NOW.toEpochMilli();
      long later = NOW.plusSeconds(3_600).toEpochMilli();
      long endTime = NOW.plusSeconds(10_000).toEpochMilli();
      ps.setLong(1, later);
      ps.setLong(2, endTime);
      ps.setLong(3, now);
      ps.setLong(4, later);
      ps.setLong(5, now);
      ps.setLong(6, now);
      ps.setLong(7, now);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void rawInsertTicket(
      Connection conn,
      String ticketId,
      String seatId,
      int price,
      String status,
      Long usedAt,
      Long canceledAt) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO tickets(
              ticket_id, order_id, screening_id, movie_id, screen_id, seat_id,
              user_id, price, status, purchased_at, used_at, canceled_at,
              created_at, updated_at, version)
            VALUES (?, 'order-x', 'screening-1', 'movie-1', 'screen-1', ?,
                    'u-1', ?, ?, ?, ?, ?, ?, ?, 0)
            """)) {
      ps.setString(1, ticketId);
      ps.setString(2, seatId);
      ps.setInt(3, price);
      ps.setString(4, status);
      ps.setLong(5, NOW.toEpochMilli());
      if (usedAt == null) {
        ps.setNull(6, java.sql.Types.INTEGER);
      } else {
        ps.setLong(6, usedAt);
      }
      if (canceledAt == null) {
        ps.setNull(7, java.sql.Types.INTEGER);
      } else {
        ps.setLong(7, canceledAt);
      }
      ps.setLong(8, NOW.toEpochMilli());
      ps.setLong(9, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
