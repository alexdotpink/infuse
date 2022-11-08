package dev.fumaz.infuse.injector;

import dev.fumaz.infuse.bind.Binding;
import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.module.Module;
import dev.fumaz.infuse.provider.Provider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * An {@link Injector} is responsible for injecting dependencies into objects and providing instances of classes.
 */
public interface Injector {

    static @NotNull Injector create(@NotNull List<Module> modules) {
        return new InfuseInjector(null, modules);
    }

    static @NotNull Injector create(@NotNull Module... modules) {
        return create(Arrays.asList(modules));
    }

    void inject(@NotNull Object object);

    <T> @Nullable T provide(@NotNull Class<T> type, @NotNull Context<?> context);

    <T> @Nullable T provide(@NotNull Class<T> type, @NotNull Object calling);

    <T> @Nullable T construct(@NotNull Class<T> type, @NotNull Object... args);

    <T> @Nullable Provider<T> getProvider(@NotNull Class<T> type);

    @NotNull List<Module> getModules();

    @NotNull List<Binding<?>> getBindings();

    @NotNull <T> List<Binding<? extends T>> getBindings(Class<T> type);

    @Nullable Injector getParent();

    @NotNull Injector child(@NotNull List<Module> modules);

    void destroy();

    default @NotNull Injector child(@NotNull Module... modules) {
        return child(Arrays.asList(modules));
    }

}
