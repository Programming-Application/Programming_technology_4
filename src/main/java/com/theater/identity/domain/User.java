package com.theater.identity.domain;

import com.theater.shared.kernel.UserId;
import java.time.Instant;
import java.util.Objects;

/** User 集約。docs/data_model.md §1 の users テーブルに対応する domain record。 */
public record User(
    UserId id,
    Email email,
    String name,
    PasswordHash passwordHash,
    UserRole role,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  public User {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(email, "email");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(passwordHash, "passwordHash");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
  }
}
