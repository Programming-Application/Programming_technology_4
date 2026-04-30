package com.theater.shared.di;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自作の依存性注入コンテナ。
 *
 * <p>採用パターン:
 *
 * <ul>
 *   <li>Singleton: アプリ全体で1つの {@code global()} 参照
 *   <li>Registry: 型 → Provider の登録テーブル
 *   <li>Factory: Provider を介して生成
 * </ul>
 *
 * <p>テスト用に {@code new Container()} で別インスタンスを作って使うこともできる (グローバルを汚さないため、testkit はこちらを使う)。
 */
public final class Container {

  private static volatile Container global;

  /** プロセス全体で共有される DI コンテナ。{@link #setGlobal} 後にだけ参照可能。 */
  public static Container global() {
    Container c = global;
    if (c == null) {
      throw new IllegalStateException("Container.global() called before setGlobal()");
    }
    return c;
  }

  /** {@code App.java} の bootstrap 終了時にだけ呼ぶ。テストでは呼ばない。 */
  public static void setGlobal(Container container) {
    global = Objects.requireNonNull(container, "container");
  }

  /** テストの後始末用。 */
  public static void resetGlobal() {
    global = null;
  }

  private final Map<Class<?>, Binding<?>> bindings = new ConcurrentHashMap<>();

  /** 同一インスタンスを毎回返す。 */
  public <T> Container registerSingleton(Class<T> type, Provider<T> provider) {
    return register(type, new Binding<>(Scope.SINGLETON, provider));
  }

  /** 呼ばれるたびに新規生成する。 */
  public <T> Container registerPrototype(Class<T> type, Provider<T> provider) {
    return register(type, new Binding<>(Scope.PROTOTYPE, provider));
  }

  private <T> Container register(Class<T> type, Binding<T> binding) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(binding, "binding");
    Binding<?> previous = bindings.putIfAbsent(type, binding);
    if (previous != null) {
      throw new IllegalStateException("Type already bound: " + type.getName());
    }
    return this;
  }

  @SuppressWarnings("unchecked")
  public <T> T resolve(Class<T> type) {
    Binding<T> binding = (Binding<T>) bindings.get(Objects.requireNonNull(type));
    if (binding == null) {
      throw new IllegalStateException("No binding for type: " + type.getName());
    }
    return binding.resolve(this);
  }

  public boolean isBound(Class<?> type) {
    return bindings.containsKey(type);
  }

  public Container install(Module module) {
    Objects.requireNonNull(module, "module").bind(this);
    return this;
  }

  /** 内部状態。バインディング1件分。 */
  private static final class Binding<T> {
    private final Scope scope;
    private final Provider<T> provider;
    private volatile T cached;

    Binding(Scope scope, Provider<T> provider) {
      this.scope = scope;
      this.provider = provider;
    }

    T resolve(Container container) {
      if (scope == Scope.PROTOTYPE) {
        return provider.get(container);
      }
      T existing = cached;
      if (existing != null) {
        return existing;
      }
      synchronized (this) {
        if (cached == null) {
          cached = provider.get(container);
        }
        return cached;
      }
    }
  }
}
