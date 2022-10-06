package dev.fumaz.infuse.provider;

import dev.fumaz.infuse.context.Context;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An {@link InstanceProvider} is a {@link Provider} that provides a specific instance.
 *
 * @param <T> the type of the class
 */
public class InstanceProvider<T> implements Provider<T> {

    private final @Nullable T instance;

    public InstanceProvider(@Nullable T instance) {
        this.instance = instance;
    }

    @Override
    public @Nullable T provide(Context<?> context) {
        return instance;
    }

}
