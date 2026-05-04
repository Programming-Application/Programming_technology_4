package com.theater.shared.di;

/**
 * BC ごとに1つ用意し、{@link Container} へバインディングを登録する。
 *
 * <p>{@code App.java} 起動時に shared / identity / catalog / reservation / ordering / ticketing の順で
 * {@code container.install(new XxxModule())} を呼ぶ。
 */
public interface Module {
  void bind(Container container);
}
