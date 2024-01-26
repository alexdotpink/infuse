package dev.fumaz.infuse.provider;

import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.injector.Injector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An {@link InstanceProvider} is a {@link Provider} that provides a specific instance.
 *
 * @param <T> the type of the class
 */
public class InstanceProvider<T> implements Provider<T> {

    private final @Nullable T instance;
    private boolean injected = false;

    public InstanceProvider(@Nullable T instance) {
        this.instance = instance;
    }

    @Override
    public @Nullable T provide(Context<?> context) {
        if (!injected && instance != null) {
            context.getInjector().inject(instance);
            injected = true;
        }

        return instance;
    }

    public @Nullable T provideWithoutInjecting(Context<?> context) {
        return instance;
    }

}
