package dev.fumaz.infuse.provider;

import dev.fumaz.infuse.context.Context;

/**
 * A {@link Provider} is a class that provides an instance of a class.
 *
 * @param <T> the type of the class
 */
@FunctionalInterface
public interface Provider<T> {

    static <T> Provider<T> instance(T instance) {
        return new InstanceProvider<>(instance);
    }

    static <T> Provider<T> singleton(Class<T> type) {
        return new SingletonProvider<>(type, false);
    }

    static <T> Provider<T> eagerSingleton(Class<T> type) {
        return new SingletonProvider<>(type, true);
    }

    T provide(Context<?> context);

}
