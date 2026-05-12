package com.theater.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.identity.domain.PasswordHash;
import org.junit.jupiter.api.Test;

/**
 * bcrypt 実装のラウンドトリップ + 境界テスト。
 *
 * <p>テストは cost=4 で実行 (デフォルト 12 はテストでは遅い)。
 */
class BcryptPasswordHasherTest {

  private final BcryptPasswordHasher hasher = new BcryptPasswordHasher(4);

  @Test
  void hash_and_verify_round_trip() {
    PasswordHash h = hasher.hash("correct horse battery staple");

    assertThat(hasher.verify("correct horse battery staple", h)).isTrue();
    assertThat(hasher.verify("wrong password", h)).isFalse();
  }

  @Test
  void hash_produces_different_outputs_for_same_input() {
    PasswordHash a = hasher.hash("same-password");
    PasswordHash b = hasher.hash("same-password");

    // bcrypt は salt 毎回ランダム → 結果は毎回違う
    assertThat(a.value()).isNotEqualTo(b.value());
    // どちらも検証は通る
    assertThat(hasher.verify("same-password", a)).isTrue();
    assertThat(hasher.verify("same-password", b)).isTrue();
  }

  @Test
  void rejects_empty_plain_on_hash() {
    assertThatThrownBy(() -> hasher.hash(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be empty");
  }

  @Test
  void rejects_null_plain_on_hash() {
    assertThatThrownBy(() -> hasher.hash(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejects_null_hash_on_verify() {
    assertThatThrownBy(() -> hasher.verify("anything", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejects_invalid_cost_factor() {
    assertThatThrownBy(() -> new BcryptPasswordHasher(3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BcryptPasswordHasher(32))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
