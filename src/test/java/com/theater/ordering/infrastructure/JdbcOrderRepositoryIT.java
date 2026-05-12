package com.theater.ordering.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.ordering.domain.Order;
import com.theater.ordering.domain.OrderRepository;
import com.theater.ordering.domain.OrderStatus;
import com.theater.ordering.domain.PaymentStatus;
import com.theater.shared.error.OptimisticLockException;
import com.theater.shared.kernel.Currency;
import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.JdbcUnitOfWork;
import com.theater.shared.tx.Tx;
import com.theater.testkit.Db;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcOrderRepositoryIT {

  private static final Instant NOW = Instant.parse("2026-05-12T00:00:00Z");
  private static final Money AMOUNT = new Money(3000L, Currency.JPY);

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private OrderRepository repository;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    repository = new JdbcOrderRepository(uow);
    seedPrerequisites();
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void save_inserts_new_order_and_findById_reads_it_back() {
    Order order = createdOrder("o-1", "u-1", "sc-1", "rv-1");
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(order));

    Order found =
        uow.execute(Tx.READ_ONLY, () -> repository.findById(new OrderId("o-1"))).orElseThrow();
    assertThat(found.userId()).isEqualTo(new UserId("u-1"));
    assertThat(found.reservationId()).isEqualTo(new ReservationId("rv-1"));
    assertThat(found.totalAmount()).isEqualTo(AMOUNT);
    assertThat(found.orderStatus()).isEqualTo(OrderStatus.CREATED);
    assertThat(found.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(found.version()).isEqualTo(0L);
  }

  @Test
  void findByReservationId_returns_order() {
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(createdOrder("o-2", "u-1", "sc-1", "rv-2")));

    Order found =
        uow.execute(Tx.READ_ONLY, () -> repository.findByReservationId(new ReservationId("rv-2")))
            .orElseThrow();
    assertThat(found.id()).isEqualTo(new OrderId("o-2"));
  }

  @Test
  void findByUser_returns_orders_ordered_by_created_at_desc() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          repository.save(createdOrder("o-3", "u-1", "sc-1", "rv-3"));
          repository.save(createdOrder("o-4", "u-1", "sc-1", "rv-4"));
        });

    var orders = uow.execute(Tx.READ_ONLY, () -> repository.findByUser(new UserId("u-1")));
    assertThat(orders).hasSize(2);
  }

  @Test
  void save_update_increments_version_and_updates_status() {
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(createdOrder("o-5", "u-1", "sc-1", "rv-5")));

    Order loaded =
        uow.execute(Tx.READ_ONLY, () -> repository.findById(new OrderId("o-5"))).orElseThrow();
    Order confirmed =
        new Order(
            loaded.id(),
            loaded.userId(),
            loaded.screeningId(),
            loaded.reservationId(),
            loaded.totalAmount(),
            PaymentStatus.PAID,
            OrderStatus.CONFIRMED,
            NOW,
            null,
            loaded.createdAt(),
            NOW,
            loaded.version() + 1);
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(confirmed));

    Order reloaded =
        uow.execute(Tx.READ_ONLY, () -> repository.findById(new OrderId("o-5"))).orElseThrow();
    assertThat(reloaded.orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(reloaded.version()).isEqualTo(1L);
  }

  @Test
  void save_with_stale_version_throws_optimistic_lock_exception() {
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(createdOrder("o-6", "u-1", "sc-1", "rv-6")));

    Order loaded =
        uow.execute(Tx.READ_ONLY, () -> repository.findById(new OrderId("o-6"))).orElseThrow();
    Order updated =
        new Order(
            loaded.id(),
            loaded.userId(),
            loaded.screeningId(),
            loaded.reservationId(),
            loaded.totalAmount(),
            PaymentStatus.PAID,
            OrderStatus.CONFIRMED,
            NOW,
            null,
            loaded.createdAt(),
            NOW,
            loaded.version() + 1);
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(updated));

    assertThatThrownBy(() -> uow.executeVoid(Tx.REQUIRED, () -> repository.save(updated)))
        .isInstanceOf(OptimisticLockException.class);
  }

  @Test
  void unique_reservation_id_prevents_duplicate_order() {
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(createdOrder("o-7", "u-1", "sc-1", "rv-7")));

    assertThatThrownBy(
            () ->
                uow.executeVoid(
                    Tx.REQUIRED, () -> repository.save(createdOrder("o-8", "u-1", "sc-1", "rv-7"))))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  @Test
  void fk_nonexistent_user_throws() {
    assertThatThrownBy(
            () ->
                uow.executeVoid(
                    Tx.REQUIRED,
                    () -> repository.save(createdOrder("o-9", "no-user", "sc-1", "rv-9"))))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  @Test
  void fk_nonexistent_screening_throws() {
    assertThatThrownBy(
            () ->
                uow.executeVoid(
                    Tx.REQUIRED,
                    () -> repository.save(createdOrder("o-10", "u-1", "no-screening", "rv-10"))))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  @Test
  void fk_nonexistent_reservation_throws() {
    assertThatThrownBy(
            () ->
                uow.executeVoid(
                    Tx.REQUIRED,
                    () -> repository.save(createdOrder("o-11", "u-1", "sc-1", "no-reservation"))))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  @Test
  void check_confirmed_without_purchased_at_throws() {
    assertThatThrownBy(
            () ->
                uow.executeVoid(
                    Tx.REQUIRED,
                    () -> {
                      try (PreparedStatement ps =
                          uow.currentConnection()
                              .prepareStatement(
                                  """
                                  INSERT INTO orders(
                                    order_id, user_id, screening_id, reservation_id,
                                    total_amount, payment_status, order_status,
                                    purchased_at, canceled_at, created_at, updated_at, version)
                                  VALUES (?,?,?,?,?,?,?,NULL,NULL,?,?,0)
                                  """)) {
                        ps.setString(1, "o-bad");
                        ps.setString(2, "u-1");
                        ps.setString(3, "sc-1");
                        ps.setString(4, "rv-bad");
                        ps.setLong(5, 3000L);
                        ps.setString(6, "PAID");
                        ps.setString(7, "CONFIRMED");
                        ps.setLong(8, NOW.toEpochMilli());
                        ps.setLong(9, NOW.toEpochMilli());
                        ps.executeUpdate();
                      } catch (SQLException e) {
                        throw new IllegalStateException(e);
                      }
                    }))
        .hasCauseInstanceOf(SQLException.class);
  }

  private Order createdOrder(
      String orderId, String userId, String screeningId, String reservationId) {
    return new Order(
        new OrderId(orderId),
        new UserId(userId),
        new ScreeningId(screeningId),
        new ReservationId(reservationId),
        AMOUNT,
        PaymentStatus.PENDING,
        OrderStatus.CREATED,
        null,
        null,
        NOW,
        NOW,
        0L);
  }

  private void seedPrerequisites() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          insertUser(conn, "u-1");
          insertMovie(conn, "m-1");
          insertScreen(conn, "screen-1");
          insertScreening(conn, "sc-1", "m-1", "screen-1");
          insertReservation(conn, "rv-1", "u-1", "sc-1");
          insertReservation(conn, "rv-2", "u-1", "sc-1");
          insertReservation(conn, "rv-3", "u-1", "sc-1");
          insertReservation(conn, "rv-4", "u-1", "sc-1");
          insertReservation(conn, "rv-5", "u-1", "sc-1");
          insertReservation(conn, "rv-6", "u-1", "sc-1");
          insertReservation(conn, "rv-7", "u-1", "sc-1");
          insertReservation(conn, "rv-9", "u-1", "sc-1");
          insertReservation(conn, "rv-10", "u-1", "sc-1");
        });
  }

  static void insertUser(Connection conn, String userId) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO users(user_id, email, name, password_hash, role, created_at, updated_at, version)
            VALUES (?,?,?,'hash','USER',?,?,0)
            """)) {
      ps.setString(1, userId);
      ps.setString(2, userId + "@example.com");
      ps.setString(3, userId);
      ps.setLong(4, NOW.toEpochMilli());
      ps.setLong(5, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static void insertMovie(Connection conn, String movieId) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO movies(movie_id,title,description,duration_minutes,is_published,created_at,updated_at,version)
            VALUES (?,?,?,120,1,?,?,0)
            """)) {
      ps.setString(1, movieId);
      ps.setString(2, "Test Movie");
      ps.setString(3, "desc");
      ps.setLong(4, NOW.toEpochMilli());
      ps.setLong(5, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static void insertScreen(Connection conn, String screenId) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO screens(screen_id,name,total_seats,created_at,updated_at)"
                + " VALUES (?,?,100,?,?)")) {
      ps.setString(1, screenId);
      ps.setString(2, "Screen 1");
      ps.setLong(3, NOW.toEpochMilli());
      ps.setLong(4, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static void insertScreening(
      Connection conn, String screeningId, String movieId, String screenId) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO screenings(
              screening_id, movie_id, screen_id, start_time, end_time,
              sales_start_at, sales_end_at, status, is_private,
              available_seat_count, reserved_seat_count, sold_seat_count,
              last_updated, created_at, updated_at, version)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
            """)) {
      long start = NOW.plusSeconds(7200).toEpochMilli();
      long end = NOW.plusSeconds(14280).toEpochMilli();
      ps.setString(1, screeningId);
      ps.setString(2, movieId);
      ps.setString(3, screenId);
      ps.setLong(4, start);
      ps.setLong(5, end);
      ps.setLong(6, NOW.minusSeconds(86400).toEpochMilli());
      ps.setLong(7, NOW.plusSeconds(3600).toEpochMilli());
      ps.setString(8, "OPEN");
      ps.setInt(9, 0);
      ps.setInt(10, 100);
      ps.setInt(11, 0);
      ps.setInt(12, 0);
      ps.setLong(13, NOW.toEpochMilli());
      ps.setLong(14, NOW.toEpochMilli());
      ps.setLong(15, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static void insertReservation(
      Connection conn, String reservationId, String userId, String screeningId) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO reservations(
              reservation_id, user_id, screening_id, status,
              expires_at, created_at, updated_at, version)
            VALUES (?,?,?,'HOLD',?,?,?,0)
            """)) {
      ps.setString(1, reservationId);
      ps.setString(2, userId);
      ps.setString(3, screeningId);
      ps.setLong(4, NOW.plusSeconds(600).toEpochMilli());
      ps.setLong(5, NOW.toEpochMilli());
      ps.setLong(6, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
