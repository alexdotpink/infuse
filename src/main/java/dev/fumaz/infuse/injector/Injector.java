package dev.fumaz.infuse.injector;

import dev.fumaz.infuse.bind.Binding;
import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.module.Module;
import dev.fumaz.infuse.provider.Provider;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * An {@link Injector} is responsible for injecting dependencies into objects and providing instances of classes.
 */
public interface Injector {

    static Injector create(List<Module> modules) {
        return new InfuseInjector(null, modules);
    }

    static Injector create(Module... modules) {
        return create(Arrays.asList(modules));
    }

    void inject(Object object);

    <T> T provide(Class<T> type, Context<?> context);

    <T> T construct(Class<T> type, Context<?> context, Object... args);

    <T> Provider<T> getProvider(Class<T> type);

    List<Module> getModules();

    Set<Binding<?>> getBindings();

    Injector getParent();

    Injector child(List<Module> modules);

    default Injector child(Module... modules) {
        return child(Arrays.asList(modules));
    }

}
