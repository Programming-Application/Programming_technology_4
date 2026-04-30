package com.theater.shared.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class ResultTest {

    @Test
    void ok_carries_value() {
        Result<Integer, String> r = Result.ok(42);
        assertThat(r.isOk()).isTrue();
        assertThat(r.isErr()).isFalse();
        assertThat(r.getOrThrow()).isEqualTo(42);
    }

    @Test
    void err_carries_error() {
        Result<Integer, String> r = Result.err("boom");
        assertThat(r.isOk()).isFalse();
        assertThat(r.isErr()).isTrue();
        assertThatThrownBy(r::getOrThrow).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void map_transforms_ok_value() {
        Result<Integer, String> r = Result.<Integer, String>ok(3).map(x -> x * 2);
        assertThat(r.getOrThrow()).isEqualTo(6);
    }

    @Test
    void map_passes_err_unchanged() {
        Result<Integer, String> r = Result.<Integer, String>err("e").map(x -> x * 2);
        assertThat(r.isErr()).isTrue();
    }
}
