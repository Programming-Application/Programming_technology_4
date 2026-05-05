package com.theater.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmailTest {

  @Test
  void accepts_well_formed_address() {
    assertThat(new Email("alice@example.com").value()).isEqualTo("alice@example.com");
  }

  @Test
  void rejects_blank() {
    assertThatThrownBy(() -> new Email("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_missing_at_sign() {
    assertThatThrownBy(() -> new Email("alice.example.com"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_missing_tld() {
    assertThatThrownBy(() -> new Email("alice@localhost"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_null() {
    assertThatThrownBy(() -> new Email(null)).isInstanceOf(NullPointerException.class);
  }
}
