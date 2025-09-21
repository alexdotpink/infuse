package dev.fumaz.infuse.provider;

import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.injector.InfuseInjector;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * A {@link SingletonProvider} is a {@link Provider} that provides a singleton instance.
 *
 * @param <T> the type of the class
 */
public class SingletonProvider<T> implements Provider<T> {

    private final @NotNull Class<T> type;
    private final boolean eager;
    private volatile T instance;
    private final Object lock = new Object();

    public SingletonProvider(@NotNull Class<T> type, boolean eager) {
        this.type = type;
        this.eager = eager;
    }

    @Override
    public @NotNull T provide(Context<?> context) {
        return getOrCreate(() -> context.getInjector().construct(type));
    }

    public @NotNull T provideWithoutInjecting(Context<?> context) {
        return getOrCreate(() -> ((InfuseInjector) context.getInjector()).constructWithoutInjecting(type));
    }

    public boolean isEager() {
        return eager;
    }

    private @NotNull T getOrCreate(Supplier<T> supplier) {
        T local = instance;

        if (local != null) {
            return local;
        }

        synchronized (lock) {
            local = instance;

            if (local == null) {
                local = supplier.get();
                validate(local);
                instance = local;
            }

            return local;
        }
    }

    private void validate(T candidate) {
        if (candidate != null) {
            return;
        }

        throw new IllegalStateException("Singleton cannot be null");
    }

}
