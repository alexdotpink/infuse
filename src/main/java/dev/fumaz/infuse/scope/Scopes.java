package dev.fumaz.infuse.scope;

import dev.fumaz.infuse.bind.BindingScope;
import dev.fumaz.infuse.injector.Injector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Public facade for interacting with scope activations.
 */
public final class Scopes {

    private Scopes() {
    }

    public static void register(@NotNull BindingScope scope,
                                @NotNull ScopeProviders.ScopeWrapper wrapper) {
        register(scope, wrapper, false, false, null);
    }

    public static void register(@NotNull BindingScope scope,
                                @NotNull ScopeProviders.ScopeWrapper wrapper,
                                boolean trackForShutdown,
                                boolean eager,
                                @Nullable ScopeProviders.ScopeActivator activator) {
        ScopeProviders.register(scope, wrapper, trackForShutdown, eager, activator);
    }

    public static @NotNull ScopeHandle openRequest(@NotNull Injector injector) {
        return ScopeProviders.open(injector, BindingScope.REQUEST);
    }

    public static @NotNull ScopeHandle openRequest(@NotNull Injector injector, @Nullable Object identifier) {
        return ScopeProviders.open(injector, BindingScope.REQUEST, identifier);
    }

    public static @NotNull ScopeHandle openSession(@NotNull Injector injector, @NotNull Object sessionId) {
        return ScopeProviders.open(injector, BindingScope.SESSION, sessionId);
    }

    public static @NotNull ScopeHandle open(@NotNull Injector injector,
                                            @NotNull BindingScope scope,
                                            @Nullable Object identifier) {
        return ScopeProviders.open(injector, scope, identifier);
    }
}
