package com.theater.shared.di;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ContainerTest {

    @AfterEach
    void resetGlobal() {
        Container.resetGlobal();
    }

    @Test
    void registers_and_resolves_singleton() {
        Container c = new Container();
        c.registerSingleton(String.class, x -> "hello");
        assertThat(c.resolve(String.class)).isEqualTo("hello");
    }

    @Test
    void singleton_returns_same_instance() {
        AtomicInteger called = new AtomicInteger();
        Container c = new Container();
        c.registerSingleton(
                Object.class,
                x -> {
                    called.incrementAndGet();
                    return new Object();
                });

        Object first = c.resolve(Object.class);
        Object second = c.resolve(Object.class);

        assertThat(first).isSameAs(second);
        assertThat(called.get()).isEqualTo(1);
    }

    @Test
    void prototype_creates_new_instance_each_resolve() {
        AtomicInteger called = new AtomicInteger();
        Container c = new Container();
        c.registerPrototype(
                Object.class,
                x -> {
                    called.incrementAndGet();
                    return new Object();
                });

        Object first = c.resolve(Object.class);
        Object second = c.resolve(Object.class);

        assertThat(first).isNotSameAs(second);
        assertThat(called.get()).isEqualTo(2);
    }

    @Test
    void rejects_double_registration() {
        Container c = new Container();
        c.registerSingleton(String.class, x -> "a");
        assertThatThrownBy(() -> c.registerSingleton(String.class, x -> "b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already bound");
    }

    @Test
    void resolve_unknown_type_throws() {
        Container c = new Container();
        assertThatThrownBy(() -> c.resolve(Long.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No binding");
    }

    @Test
    void install_module_runs_bind() {
        Container c = new Container();
        Module module = container -> container.registerSingleton(String.class, x -> "module");
        c.install(module);
        assertThat(c.resolve(String.class)).isEqualTo("module");
    }

    @Test
    void global_throws_when_not_set() {
        Container.resetGlobal();
        assertThatThrownBy(Container::global).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void global_returns_set_instance() {
        Container c = new Container();
        Container.setGlobal(c);
        assertThat(Container.global()).isSameAs(c);
    }
}
