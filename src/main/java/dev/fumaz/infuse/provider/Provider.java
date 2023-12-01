package dev.fumaz.infuse.provider;

import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.injector.Injector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;

/**
 * A {@link Provider} is a class that provides an instance of a class.
 *
 * @param <T> the type of the class
 */
@FunctionalInterface
public interface Provider<T> {

    static <T> @NotNull Provider<T> instance(T instance) {
        return new InstanceProvider<>(instance);
    }

    static <T> @NotNull Provider<T> immutableInstance(T instance) {
        return new ImmutableInstanceProvider<>(instance);
    }

    static <T> @NotNull Provider<T> singleton(Class<T> type) {
        return new SingletonProvider<>(type, false);
    }

    static <T> @NotNull Provider<T> eagerSingleton(Class<T> type) {
        return new SingletonProvider<>(type, true);
    }

    @Nullable T provide(Context<?> context);

    default @Nullable T provide(Injector injector, Object calling) {
        Context<?> context = new Context<>(calling.getClass(), calling, injector, ElementType.FIELD, "field", new Annotation[0]);

        return provide(context);
    }

}
