package dev.fumaz.infuse.provider;

import dev.fumaz.infuse.context.Context;

/**
 * A {@link SingletonProvider} is a {@link Provider} that provides a singleton instance.
 *
 * @param <T> the type of the class
 */
public class SingletonProvider<T> implements Provider<T> {

    private final Class<T> type;
    private T instance;

    public SingletonProvider(Class<T> type) {
        this.type = type;
    }

    @Override
    public T provide(Context<?> context) {
        if (instance == null) {
            instance = context.getInjector().construct(type, context);
            validate();
        }

        return instance;
    }

    private void validate() {
        if (instance != null) {
            return;
        }

        throw new IllegalStateException("Singleton cannot be null");
    }

}
