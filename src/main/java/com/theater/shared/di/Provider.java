package com.theater.shared.di;

/**
 * 依存の生成戦略。
 *
 * <p>パターン: Factory Method (の関数化)。{@code () -> new Foo(...)} で書ける。
 *
 * @param <T> 生成する型
 */
@FunctionalInterface
public interface Provider<T> {
    T get(Container container);
}
