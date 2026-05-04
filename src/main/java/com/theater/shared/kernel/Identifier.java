package com.theater.shared.kernel;

import java.util.Objects;

/**
 * BC 越境で参照される可能性のある ID 型のマーカ。
 *
 * <p>各 BC は record で実装する:
 *
 * <pre>{@code
 * public record MovieId(String value) implements Identifier {
 *   public MovieId { Identifier.requireNonBlank(value, "movie id"); }
 * }
 * }</pre>
 *
 * <p>shared/kernel に置く判断基準: 「他の BC からも参照されうる ID」のみ (catalog の MovieId / ScreeningId など、 複数 BC
 * で使う想定があるもの)。BC 内部にしか出現しない ID はその BC の {@code domain} に置いて構わない。
 */
public interface Identifier {

  String value();

  /** ID 用の共通 validation。null と blank を拒否し値そのものを返す (record の compact ctor 用)。 */
  static String requireNonBlank(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
