package dev.fumaz.infuse.scope;

import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.context.ContextView;
import dev.fumaz.infuse.injector.InfuseInjector;
import dev.fumaz.infuse.provider.InstanceProvider;
import dev.fumaz.infuse.provider.Provider;
import dev.fumaz.infuse.provider.Provider.ContextViewAware;
import dev.fumaz.infuse.provider.SingletonProvider;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Memoizes the result of a delegate provider, behaving like a singleton while deferring instantiation to the delegate.
 */
public final class MemoizingProvider<T> implements Provider<T>, Provider.ContextViewAware<T> {

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
        return provide((ContextView<?>) context);
    }

    @Override
    public T provide(ContextView<?> context) {
        return getOrCreate(() -> supplyFromDelegate(context));
    }

    public T provideWithoutInjecting(InfuseInjector injector) {
        Annotation[] annotations = new Annotation[0];
        ContextView<T> eagerView = ContextView.of(type, injector, injector, ElementType.FIELD, "eager", annotations);

        return getOrCreate(() -> {
            if (delegate instanceof SingletonProvider) {
                return ((SingletonProvider<T>) delegate).provideWithoutInjecting(eagerView);
            }

            if (delegate instanceof InstanceProvider) {
                return ((InstanceProvider<T>) delegate).provideWithoutInjecting(eagerView);
            }

            return supplyFromDelegate(eagerView);
        });
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

    private T supplyFromDelegate(ContextView<?> context) {
        if (delegate instanceof ContextViewAware) {
            @SuppressWarnings("unchecked")
            ContextViewAware<T> viewAware = (ContextViewAware<T>) delegate;
            return viewAware.provide(context);
        }

        if (context instanceof Context) {
            return delegate.provide((Context<?>) context);
        }

        Context<?> borrowed = Context.borrow(context);
        try {
            return delegate.provide(borrowed);
        } finally {
            borrowed.release();
        }
    }
}
