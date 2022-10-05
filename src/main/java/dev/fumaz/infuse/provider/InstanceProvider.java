package dev.fumaz.infuse.provider;

import dev.fumaz.infuse.context.Context;

/**
 * An {@link InstanceProvider} is a {@link Provider} that provides a specific instance.
 *
 * @param <T> the type of the class
 */
public class InstanceProvider<T> implements Provider<T> {

    private final T instance;

    public InstanceProvider(T instance) {
        this.instance = instance;
    }

    @Override
    public T provide(Context<?> context) {
        return instance;
    }

}
