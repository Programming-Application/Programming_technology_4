package com.theater.identity.infrastructure;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.theater.identity.domain.PasswordHash;
import com.theater.identity.domain.PasswordHasher;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * bcrypt による {@link PasswordHasher} 実装。
 *
 * <p>{@code at.favre.lib:bcrypt} を使用。spring 等の重い依存を避けるため独立ライブラリを採用。
 *
 * <p>**cost factor**: 12 (= 2^12 ラウンド)。本番運用ではマシン性能に応じてチューニングする想定だが、本案件 (学校課題) では 固定値で十分。テストでは低 cost
 * (例: 4) を使う可能性を残し、コンストラクタで指定可能にしている。
 */
public final class BcryptPasswordHasher implements PasswordHasher {

  private static final int DEFAULT_COST = 12;

  private final int cost;

  public BcryptPasswordHasher() {
    this(DEFAULT_COST);
  }

  public BcryptPasswordHasher(int cost) {
    if (cost < 4 || cost > 31) {
      throw new IllegalArgumentException("cost must be in [4, 31]: " + cost);
    }
    this.cost = cost;
  }

  @Override
  public PasswordHash hash(String plain) {
    requireNonBlank(plain);
    char[] chars = plain.toCharArray();
    String hashed = BCrypt.withDefaults().hashToString(cost, chars);
    return new PasswordHash(hashed);
  }

  @Override
  public boolean verify(String plain, PasswordHash hash) {
    requireNonBlank(plain);
    Objects.requireNonNull(hash, "hash");
    BCrypt.Result result =
        BCrypt.verifyer()
            .verify(
                plain.getBytes(StandardCharsets.UTF_8),
                hash.value().getBytes(StandardCharsets.UTF_8));
    return result.verified;
  }

  private static void requireNonBlank(String plain) {
    Objects.requireNonNull(plain, "plain");
    if (plain.isEmpty()) {
      throw new IllegalArgumentException("plain password must not be empty");
    }
  }
}
