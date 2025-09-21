package dev.fumaz.infuse.scope;

import dev.fumaz.infuse.bind.BindingKey;
import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.injector.Injector;
import dev.fumaz.infuse.provider.Provider;

import java.util.Objects;

/**
 * Provider wrapper that memoises instances for the lifetime of an active session scope.
 */
final class SessionScopeProvider<T> implements Provider<T> {

    private final BindingKey key;
    private final Provider<T> delegate;

    SessionScopeProvider(BindingKey key, Provider<T> delegate) {
        this.key = Objects.requireNonNull(key, "key");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public T provide(Context<?> context) {
        Injector injector = context.getInjector();
        ScopeState state = ScopeContexts.currentSessionState(injector);

        return state.getOrCompute(key,
                () -> delegate.provide(context),
                instance -> ScopeSupport.invokePreDestroy(injector, instance));
    }
}
