package dev.fumaz.infuse.provider;

import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.injector.InfuseInjector;
import org.jetbrains.annotations.NotNull;

/**
 * A {@link SingletonProvider} is a {@link Provider} that provides a singleton instance.
 *
 * @param <T> the type of the class
 */
public class SingletonProvider<T> implements Provider<T> {

    private final @NotNull Class<T> type;
    private final boolean eager;
    private T instance;

    public SingletonProvider(@NotNull Class<T> type, boolean eager) {
        this.type = type;
        this.eager = eager;
    }

    @Override
    public @NotNull T provide(Context<?> context) {
        if (instance == null) {
            instance = context.getInjector().construct(type);
            validate();
        }

        return instance;
    }

    public @NotNull T provideWithoutInjecting(Context<?> context) {
        if (instance == null) {
            instance = ((InfuseInjector) context.getInjector()).constructWithoutInjecting(type, context);
            validate();
        }

        return instance;
    }

    public boolean isEager() {
        return eager;
    }

    private void validate() {
        if (instance != null) {
            return;
        }

        throw new IllegalStateException("Singleton cannot be null");
    }

}
