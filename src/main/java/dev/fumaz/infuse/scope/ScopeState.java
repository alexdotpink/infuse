package dev.fumaz.infuse.scope;

import dev.fumaz.infuse.bind.BindingKey;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Holds scoped instances and their destruction callbacks for the lifetime of a scope activation.
 */
final class ScopeState {

    private final ConcurrentMap<BindingKey, Object> instances = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Runnable> destroyCallbacks = new ConcurrentLinkedDeque<>();
    private final AtomicInteger references = new AtomicInteger();
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    <T> T getOrCompute(BindingKey key, Supplier<T> supplier, Consumer<T> onCreate) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(onCreate, "onCreate");

        Object cached = instances.get(key);
        if (cached != null) {
            @SuppressWarnings("unchecked")
            T typed = (T) cached;
            return typed;
        }

        Creation<T> creation = new Creation<>();
        Object resolved = instances.computeIfAbsent(key, k -> {
            T instance = supplier.get();
            creation.value = instance;

            if (instance != null) {
                destroyCallbacks.push(() -> onCreate.accept(instance));
            }

            return instance;
        });

        if (creation.value != null) {
            return creation.value;
        }

        @SuppressWarnings("unchecked")
        T typed = (T) resolved;
        return typed;
    }

    void retain() {
        references.incrementAndGet();
    }

    boolean release() {
        return references.decrementAndGet() <= 0;
    }

    void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }

        Runnable callback;
        while ((callback = destroyCallbacks.poll()) != null) {
            try {
                callback.run();
            } catch (Exception ignored) {
            }
        }

        instances.clear();
    }

    private static final class Creation<T> {
        private T value;
    }
}
