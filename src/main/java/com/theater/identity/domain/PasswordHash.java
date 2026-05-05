package com.theater.identity.domain;

import java.util.Objects;

/**
 * 一方向ハッシュ済のパスワード。
 *
 * <p>**契約**: 平文 password はこの型に来るまでにアプリ層 (UseCase / Hasher) でハッシュ化されている。 ドメインは平文を持ち回らない。
 */
public record PasswordHash(String value) {

  public PasswordHash {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("password hash must not be blank");
    }
  }
}
