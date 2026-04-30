package com.theater.shared.kernel;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;

/**
 * 成功/失敗を型で表す sealed 値オブジェクト。
 *
 * <p>パターン: Algebraic Data Type via Java 17 sealed interface + records。
 *
 * @param <T> 成功時の値
 * @param <E> 失敗時のエラー
 */
public sealed interface Result<T, E> permits Result.Ok, Result.Err {

  static <T, E> Result<T, E> ok(T value) {
    return new Ok<>(value);
  }

  static <T, E> Result<T, E> err(E error) {
    return new Err<>(error);
  }

  default boolean isOk() {
    return this instanceof Ok<?, ?>;
  }

  default boolean isErr() {
    return this instanceof Err<?, ?>;
  }

  default T getOrThrow() {
    if (this instanceof Ok<T, E> ok) {
      return ok.value();
    }
    if (this instanceof Err<T, E> err) {
      throw new NoSuchElementException("Result is Err: " + err.error());
    }
    throw new IllegalStateException("Unreachable");
  }

  default <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
    if (this instanceof Ok<T, E> ok) {
      return Result.ok(mapper.apply(ok.value()));
    }
    if (this instanceof Err<T, E> err) {
      return Result.err(err.error());
    }
    throw new IllegalStateException("Unreachable");
  }

  record Ok<T, E>(T value) implements Result<T, E> {
    public Ok {
      Objects.requireNonNull(value, "value");
    }
  }

  record Err<T, E>(E error) implements Result<T, E> {
    public Err {
      Objects.requireNonNull(error, "error");
    }
  }
}
