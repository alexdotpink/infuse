package dev.fumaz.infuse.scope;

import dev.fumaz.infuse.injector.InfuseInjector;
import dev.fumaz.infuse.injector.Injector;

import java.lang.reflect.Method;

/**
 * Support utilities bridging scope infrastructure with the injector implementation.
 */
final class ScopeSupport {

    private ScopeSupport() {
    }

    static void invokePreDestroy(Injector injector, Object instance) {
        if (instance == null) {
            return;
        }

        if (injector instanceof InfuseInjector) {
            ((InfuseInjector) injector).invokePreDestroy(instance);
            return;
        }

        try {
            Method method = injector.getClass().getMethod("invokePreDestroy", Object.class);
            method.setAccessible(true);
            method.invoke(injector, instance);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
