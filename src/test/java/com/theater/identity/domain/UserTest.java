package com.theater.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.shared.kernel.UserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserTest {

  private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");

  @Test
  void valid_user_passes_construction() {
    var user =
        new User(
            new UserId("u-1"),
            new Email("alice@example.com"),
            "Alice",
            new PasswordHash("h"),
            UserRole.USER,
            NOW,
            NOW,
            0);
    assertThat(user.role()).isEqualTo(UserRole.USER);
  }

  @Test
  void rejects_blank_name() {
    assertThatThrownBy(
            () ->
                new User(
                    new UserId("u-1"),
                    new Email("a@b.co"),
                    "  ",
                    new PasswordHash("h"),
                    UserRole.USER,
                    NOW,
                    NOW,
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_negative_version() {
    assertThatThrownBy(
            () ->
                new User(
                    new UserId("u-1"),
                    new Email("a@b.co"),
                    "Alice",
                    new PasswordHash("h"),
                    UserRole.USER,
                    NOW,
                    NOW,
                    -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
