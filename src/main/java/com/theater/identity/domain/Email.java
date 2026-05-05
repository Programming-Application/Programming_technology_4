package com.theater.identity.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * メールアドレスの値オブジェクト。簡易パターンチェックのみ (RFC 完全準拠ではない)。
 *
 * <p>正規化方針: アプリ側に持ち込む段階で {@code .trim().toLowerCase()} 済とする (UseCase 側責務)。本クラスは正規化済の文字列を信頼して保持。
 */
public record Email(String value) {

  private static final Pattern PATTERN =
      Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

  public Email {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("email must not be blank");
    }
    if (!PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("invalid email format: " + value);
    }
  }
}
