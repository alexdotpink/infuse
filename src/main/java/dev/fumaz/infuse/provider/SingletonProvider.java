package dev.fumaz.infuse.provider;

import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.context.ContextView;
import dev.fumaz.infuse.injector.InfuseInjector;
import org.jetbrains.annotations.NotNull;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Supplier;

/**
 * A {@link SingletonProvider} is a {@link Provider} that provides a singleton instance.
 *
 * @param <T> the type of the class
 */
public class SingletonProvider<T> implements Provider<T>, Provider.ContextViewAware<T> {

    private static final VarHandle INSTANCE_HANDLE;

    static {
        try {
            INSTANCE_HANDLE = MethodHandles.lookup().findVarHandle(SingletonProvider.class, "instance", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final @NotNull Class<T> type;
    private final boolean eager;
    private T instance;
    private final Object lock = new Object();

    public SingletonProvider(@NotNull Class<T> type, boolean eager) {
        this.type = type;
        this.eager = eager;
    }

    @Override
    public @NotNull T provide(Context<?> context) {
        return provide((ContextView<?>) context);
    }

    @Override
    public @NotNull T provide(ContextView<?> context) {
        return getOrCreate(() -> context.getInjector().construct(type));
    }

    public @NotNull T provideWithoutInjecting(ContextView<?> context) {
        return getOrCreate(() -> ((InfuseInjector) context.getInjector()).constructWithoutInjecting(type));
    }

    public boolean isEager() {
        return eager;
    }

    private @NotNull T getOrCreate(Supplier<T> supplier) {
        T local = getInitializedInstance();

        if (local != null) {
            return local;
        }

        synchronized (lock) {
            local = getInitializedInstance();

            if (local == null) {
                local = supplier.get();
                validate(local);
                publish(local);
            }

            return local;
        }
    }

    @SuppressWarnings("unchecked")
    private T getInitializedInstance() {
        return (T) INSTANCE_HANDLE.getAcquire(this);
    }

    private void publish(T value) {
        INSTANCE_HANDLE.setRelease(this, value);
    }

    private void validate(T candidate) {
        if (candidate != null) {
            return;
        }

        throw new IllegalStateException("Singleton cannot be null");
    }

}
