package dev.fumaz.infuse.scope;

import dev.fumaz.infuse.bind.BindingKey;
import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.context.ContextView;
import dev.fumaz.infuse.injector.Injector;
import dev.fumaz.infuse.provider.Provider;
import dev.fumaz.infuse.provider.Provider.ContextViewAware;

import java.util.Objects;

/**
 * Provider wrapper that memoises instances for the lifetime of an active session scope.
 */
final class SessionScopeProvider<T> implements Provider<T>, Provider.ContextViewAware<T> {

    private final BindingKey key;
    private final Provider<T> delegate;

    SessionScopeProvider(BindingKey key, Provider<T> delegate) {
        this.key = Objects.requireNonNull(key, "key");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public T provide(Context<?> context) {
        return provide((ContextView<?>) context);
    }

    @Override
    public T provide(ContextView<?> context) {
        Injector injector = context.getInjector();
        ScopeState state = ScopeContexts.currentSessionState(injector);

        return state.getOrCompute(key,
                () -> resolveDelegate(context),
                instance -> ScopeSupport.invokePreDestroy(injector, instance));
    }

    private T resolveDelegate(ContextView<?> context) {
        if (delegate instanceof ContextViewAware) {
            @SuppressWarnings("unchecked")
            ContextViewAware<T> viewAware = (ContextViewAware<T>) delegate;
            return viewAware.provide(context);
        }

        if (context instanceof Context) {
            return delegate.provide((Context<?>) context);
        }

        Context<?> borrowed = Context.borrow(context);

        try {
            return delegate.provide(borrowed);
        } finally {
            borrowed.release();
        }
    }
}
