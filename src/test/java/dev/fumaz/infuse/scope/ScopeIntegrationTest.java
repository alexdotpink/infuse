package dev.fumaz.infuse.scope;

import dev.fumaz.infuse.annotation.PreDestroy;
import dev.fumaz.infuse.bind.BindingKey;
import dev.fumaz.infuse.bind.BindingScope;
import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.injector.Injector;
import dev.fumaz.infuse.module.InfuseModule;
import dev.fumaz.infuse.scope.ScopeProviders.ScopeActivator;
import dev.fumaz.infuse.scope.ScopeProviders.ScopeWrapper;
import dev.fumaz.infuse.scope.ScopeHandle;
import dev.fumaz.infuse.scope.ScopeSupport;
import dev.fumaz.infuse.provider.Provider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ScopeIntegrationTest {

    private static final BindingScope CUSTOM_SCOPE = BindingScope.custom("test_custom");

    @BeforeAll
    static void registerCustomScope() {
        ThreadLocal<Deque<MapCache>> cacheStack = ThreadLocal.withInitial(ArrayDeque::new);

        ScopeWrapper wrapper = new ScopeWrapper() {
            @Override
            public <T> Provider<T> wrap(BindingKey key, Provider<T> provider) {
                return new Provider<T>() {
                    @Override
                    public T provide(Context<?> context) {
                        Deque<MapCache> stack = cacheStack.get();
                        if (stack.isEmpty()) {
                            throw new IllegalStateException("Custom scope is not active");
                        }

                        MapCache cache = stack.peek();
                        Object existing = cache.instances.get(key);

                        if (existing != null) {
                            @SuppressWarnings("unchecked")
                            T typed = (T) existing;
                            return typed;
                        }

                        T created = provider.provide(context);
                        cache.instances.put(key, created);

                        if (created != null) {
                            Context<?> retained = context.detach();
                            cache.destroyHooks.add(() -> ScopeSupport.invokePreDestroy(retained.getInjector(), created));
                        }

                        return created;
                    }
                };
            }
        };

        ScopeActivator activator = (injector, scope, identifier) -> {
            MapCache cache = new MapCache();
            cacheStack.get().push(cache);

            return () -> {
                MapCache current = cacheStack.get().poll();

                if (current != null) {
                    List<Runnable> hooks = current.destroyHooks;
                    for (int i = hooks.size() - 1; i >= 0; i--) {
                        hooks.get(i).run();
                    }
                    current.instances.clear();
                    hooks.clear();
                }

                if (cacheStack.get().isEmpty()) {
                    cacheStack.remove();
                }
            };
        };

        Scopes.register(CUSTOM_SCOPE, wrapper, false, false, activator);
    }

    @Test
    void requestScopeMemoizesWithinHandle() {
        RequestResource.reset();

        Injector injector = Injector.create(new InfuseModule() {
            @Override
            public void configure() {
                bind(RequestResource.class).requestScoped().to(RequestResource.class);
            }
        });

        RequestResource first;
        RequestResource second;
        try (ScopeHandle handle = injector.openRequest()) {
            first = injector.provide(RequestResource.class, this);
            second = injector.provide(RequestResource.class, this);

            assertSame(first, second, "request scope should reuse instance within handle");
        }

        assertEquals(1, RequestResource.destroyed.get(), "request scope should call @PreDestroy on close");

        try (ScopeHandle handle = injector.openRequest()) {
            RequestResource third = injector.provide(RequestResource.class, this);
            assertNotSame(first, third, "new request scope should create fresh instance");
        }

        assertEquals(2, RequestResource.destroyed.get(), "second request should destroy instance on close");
    }

    @Test
    void sessionScopePersistsUntilSessionClose() {
        SessionComponent.reset();

        Injector injector = Injector.create(new InfuseModule() {
            @Override
            public void configure() {
                bind(SessionComponent.class).sessionScoped().to(SessionComponent.class);
            }
        });

        SessionComponent initial;

        try (ScopeHandle session = injector.openSession("session-1")) {
            initial = injector.provide(SessionComponent.class, this);
            SessionComponent again = injector.provide(SessionComponent.class, this);
            assertSame(initial, again, "session scope should reuse instance across provides");

            try (ScopeHandle nestedRequest = injector.openRequest()) {
                SessionComponent withinRequest = injector.provide(SessionComponent.class, this);
                assertSame(initial, withinRequest, "session scope should outlive request");
            }

            assertEquals(0, SessionComponent.destroyed.get(), "destroy should wait for session close");
        }

        assertEquals(1, SessionComponent.destroyed.get(), "session close should trigger destroy");
    }

    @Test
    void customScopeCanBeRegistered() {
        CustomScopedComponent.reset();

        Injector injector = Injector.create(new InfuseModule() {
            @Override
            public void configure() {
                bind(CustomScopedComponent.class).inScope(CUSTOM_SCOPE).to(CustomScopedComponent.class);
            }
        });

        try (ScopeHandle handle = injector.openScope(CUSTOM_SCOPE)) {
            CustomScopedComponent first = injector.provide(CustomScopedComponent.class, this);
            CustomScopedComponent second = injector.provide(CustomScopedComponent.class, this);
            assertSame(first, second, "custom scope should memoize within activation");
        }

        assertEquals(1, CustomScopedComponent.destroyed.get(), "custom scope should run destroy hooks on close");
    }

    private static final class MapCache {
        private final Map<BindingKey, Object> instances = new LinkedHashMap<>();
        private final List<Runnable> destroyHooks = new ArrayList<>();
    }

    public static final class RequestResource {
        private static final AtomicInteger destroyed = new AtomicInteger();

        static void reset() {
            destroyed.set(0);
        }

        @PreDestroy
        void onDestroy() {
            destroyed.incrementAndGet();
        }
    }

    public static final class SessionComponent {
        private static final AtomicInteger destroyed = new AtomicInteger();

        static void reset() {
            destroyed.set(0);
        }

        @PreDestroy
        void onDestroy() {
            destroyed.incrementAndGet();
        }
    }

    public static final class CustomScopedComponent {
        private static final AtomicInteger destroyed = new AtomicInteger();

        static void reset() {
            destroyed.set(0);
        }

        @PreDestroy
        void onDestroy() {
            destroyed.incrementAndGet();
        }
    }
}
