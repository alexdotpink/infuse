package dev.fumaz.infuse.scope;

import dev.fumaz.infuse.bind.Binding;
import dev.fumaz.infuse.bind.BindingKey;
import dev.fumaz.infuse.bind.BindingScope;
import dev.fumaz.infuse.injector.Injector;
import dev.fumaz.infuse.provider.Provider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Central registry for scope-aware provider decoration and lifecycle coordination.
 */
public final class ScopeProviders {

    private ScopeProviders() {
    }

    private static final ScopeWrapper IDENTITY = new ScopeWrapper() {
        @Override
        public <T> Provider<T> wrap(@NotNull BindingKey key, @NotNull Provider<T> provider) {
            return provider;
        }
    };
    private static final ConcurrentMap<BindingScope, ScopeRegistration> REGISTRY = new ConcurrentHashMap<>();
    private static final ScopeRegistration DEFAULT = new ScopeRegistration(IDENTITY, null, false, false);

    static {
        registerInternal(BindingScope.UNSCOPED, IDENTITY, null, false, false);
        registerInternal(BindingScope.INSTANCE, IDENTITY, null, false, false);
        registerInternal(BindingScope.IMMUTABLE_INSTANCE, IDENTITY, null, false, false);
        registerInternal(BindingScope.SINGLETON, new SingletonWrapper(false), null, true, false);
        registerInternal(BindingScope.EAGER_SINGLETON, new SingletonWrapper(true), null, true, true);
        registerInternal(BindingScope.REQUEST,
                new RequestWrapper(),
                (injector, scope, identifier) -> ScopeContexts.openRequest(injector, identifier),
                false,
                false);
        registerInternal(BindingScope.SESSION,
                new SessionWrapper(),
                (injector, scope, identifier) -> ScopeContexts.openSession(injector, identifier),
                false,
                false);
    }

    public static <T> Binding<T> decorate(@NotNull Binding<T> binding) {
        Objects.requireNonNull(binding, "binding");
        ScopeRegistration registration = REGISTRY.getOrDefault(binding.getScope(), DEFAULT);

        @SuppressWarnings("unchecked")
        BindingKey key = binding.getKey();
        Provider<T> provider = binding.getProvider();
        Provider<T> wrapped = registration.wrapper.wrap(key, provider);

        if (provider == wrapped) {
            return binding;
        }

        return new Binding<>(binding.getType(), wrapped, binding.getQualifier(), binding.getScope(),
                binding.isCollectionContribution());
    }

    public static boolean shouldTrackForShutdown(@NotNull BindingScope scope) {
        return REGISTRY.getOrDefault(scope, DEFAULT).trackForShutdown;
    }

    public static boolean isEager(@NotNull BindingScope scope) {
        return REGISTRY.getOrDefault(scope, DEFAULT).eager;
    }

    public static ScopeHandle open(@NotNull Injector injector, @NotNull BindingScope scope) {
        return open(injector, scope, null);
    }

    public static ScopeHandle open(@NotNull Injector injector,
                                   @NotNull BindingScope scope,
                                   @Nullable Object identifier) {
        ScopeRegistration registration = REGISTRY.get(scope);

        if (registration == null || registration.activator == null) {
            throw new IllegalStateException("Scope " + scope + " does not support manual activation");
        }

        return registration.activator.open(injector, scope, identifier);
    }

    public static void register(@NotNull BindingScope scope,
                                @NotNull ScopeWrapper wrapper,
                                boolean trackForShutdown,
                                boolean eager,
                                @Nullable ScopeActivator activator) {
        registerInternal(scope, wrapper, activator, trackForShutdown, eager);
    }

    public static void shutdown(@NotNull Injector injector) {
        ScopeContexts.shutdown(injector);
    }

    private static void registerInternal(BindingScope scope,
                                         ScopeWrapper wrapper,
                                         @Nullable ScopeActivator activator,
                                         boolean trackForShutdown,
                                         boolean eager) {
        REGISTRY.put(scope, new ScopeRegistration(wrapper, activator, trackForShutdown, eager));
    }

    @FunctionalInterface
    public interface ScopeWrapper {
        <T> Provider<T> wrap(@NotNull BindingKey key, @NotNull Provider<T> provider);
    }

    @FunctionalInterface
    public interface ScopeActivator {
        @NotNull ScopeHandle open(@NotNull Injector injector, @NotNull BindingScope scope, @Nullable Object identifier);
    }

    private static final class ScopeRegistration {
        private final ScopeWrapper wrapper;
        private final ScopeActivator activator;
        private final boolean trackForShutdown;
        private final boolean eager;

        private ScopeRegistration(ScopeWrapper wrapper,
                                  ScopeActivator activator,
                                  boolean trackForShutdown,
                                  boolean eager) {
            this.wrapper = Objects.requireNonNull(wrapper, "wrapper");
            this.activator = activator;
            this.trackForShutdown = trackForShutdown;
            this.eager = eager;
        }
    }

    private static final class SingletonWrapper implements ScopeWrapper {
        private final boolean eager;

        private SingletonWrapper(boolean eager) {
            this.eager = eager;
        }

        @Override
        public <T> Provider<T> wrap(@NotNull BindingKey key, @NotNull Provider<T> provider) {
            if (provider instanceof MemoizingProvider) {
                @SuppressWarnings("unchecked")
                MemoizingProvider<T> memoizing = (MemoizingProvider<T>) provider;
                if (memoizing.isEager() == eager) {
                    return provider;
                }
            }

            @SuppressWarnings("unchecked")
            Class<T> type = (Class<T>) key.getType();
            return new MemoizingProvider<>(type, provider, eager);
        }
    }

    private static final class RequestWrapper implements ScopeWrapper {
        @Override
        public <T> Provider<T> wrap(@NotNull BindingKey key, @NotNull Provider<T> provider) {
            return new RequestScopeProvider<>(key, provider);
        }
    }

    private static final class SessionWrapper implements ScopeWrapper {
        @Override
        public <T> Provider<T> wrap(@NotNull BindingKey key, @NotNull Provider<T> provider) {
            return new SessionScopeProvider<>(key, provider);
        }
    }
}
