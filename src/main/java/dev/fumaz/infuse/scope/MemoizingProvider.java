package dev.fumaz.infuse.scope;

import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.injector.InfuseInjector;
import dev.fumaz.infuse.provider.InstanceProvider;
import dev.fumaz.infuse.provider.Provider;
import dev.fumaz.infuse.provider.SingletonProvider;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Memoizes the result of a delegate provider, behaving like a singleton while deferring instantiation to the delegate.
 */
public final class MemoizingProvider<T> implements Provider<T> {

    private final Class<T> type;
    private final Provider<T> delegate;
    private final boolean eager;
    private volatile T instance;
    private final Object lock = new Object();

    MemoizingProvider(Class<T> type, Provider<T> delegate, boolean eager) {
        this.type = Objects.requireNonNull(type, "type");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.eager = eager;
    }

    @Override
    public T provide(Context<?> context) {
        return getOrCreate(() -> delegate.provide(context));
    }

    public T provideWithoutInjecting(InfuseInjector injector) {
        Annotation[] annotations = new Annotation[0];
        Context<?> eagerContext = Context.borrow(type, injector, injector, ElementType.FIELD, "eager", annotations);

        try {
            return getOrCreate(() -> {
                if (delegate instanceof SingletonProvider) {
                    return ((SingletonProvider<T>) delegate).provideWithoutInjecting(eagerContext);
                }

                if (delegate instanceof InstanceProvider) {
                    return ((InstanceProvider<T>) delegate).provideWithoutInjecting(eagerContext);
                }

                return delegate.provide(eagerContext);
            });
        } finally {
            eagerContext.release();
        }
    }

    public boolean isEager() {
        return eager;
    }

    private T getOrCreate(@NotNull Supplier<T> supplier) {
        T local = instance;

        if (local != null) {
            return local;
        }

        synchronized (lock) {
            local = instance;

            if (local == null) {
                local = supplier.get();
                if (local == null) {
                    throw new IllegalStateException("Scoped singleton provider produced null for " + type.getName());
                }

                instance = local;
            }

            return local;
        }
    }
}
