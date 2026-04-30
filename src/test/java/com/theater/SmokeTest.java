package com.theater;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Bootstrap smoke test. Replace once real tests land. */
class SmokeTest {

  @Test
  void jvm_runs_test() {
    assertThat(1 + 1).isEqualTo(2);
  }
}
