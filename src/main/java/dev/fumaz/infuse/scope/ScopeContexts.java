package dev.fumaz.infuse.scope;

import dev.fumaz.infuse.injector.InfuseInjector;
import dev.fumaz.infuse.injector.Injector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Coordinates runtime scope state for request/session/custom scopes across threads and injectors.
 */
final class ScopeContexts {

    private ScopeContexts() {
    }

    private static final ThreadLocal<Deque<ScopeState>> REQUEST_STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Object> CURRENT_SESSION = new ThreadLocal<>();
    private static final ConcurrentMap<Injector, SessionRegistry> SESSIONS = new ConcurrentHashMap<>();

    static ScopeHandle openRequest(Injector injector, Object identifier) {
        InfuseInjector infuse = InjectorBridge.asInfuse(injector);
        ScopeState state = new ScopeState();
        state.retain();

        Deque<ScopeState> stack = REQUEST_STACK.get();
        stack.push(state);

        return () -> {
            ScopeState current = stack.poll();

            if (current != null && current.release()) {
                current.destroy();
            }

            if (stack.isEmpty()) {
                REQUEST_STACK.remove();
            }
        };
    }

    static ScopeState currentRequestState() {
        Deque<ScopeState> stack = REQUEST_STACK.get();

        if (stack.isEmpty()) {
            throw new IllegalStateException("Request scope is not active on the current thread");
        }

        return stack.peek();
    }

    static ScopeHandle openSession(Injector injector, Object sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        InfuseInjector infuse = InjectorBridge.asInfuse(injector);
        SessionRegistry registry = SESSIONS.computeIfAbsent(infuse, ignored -> new SessionRegistry());
        ScopeHandle handle = registry.open(sessionId);

        Object previous = CURRENT_SESSION.get();
        CURRENT_SESSION.set(sessionId);

        return () -> {
            try {
                handle.close();
            } finally {
                if (previous == null) {
                    CURRENT_SESSION.remove();
                } else {
                    CURRENT_SESSION.set(previous);
                }
            }
        };
    }

    static ScopeState currentSessionState(Injector injector) {
        Object sessionId = CURRENT_SESSION.get();
        if (sessionId == null) {
            throw new IllegalStateException("Session scope is not active on the current thread");
        }

        InfuseInjector infuse = InjectorBridge.asInfuse(injector);
        SessionRegistry registry = SESSIONS.get(infuse);

        if (registry == null) {
            throw new IllegalStateException("Session scope " + sessionId + " has not been opened for injector "
                    + infuse);
        }

        return registry.state(sessionId);
    }

    static void shutdown(Injector injector) {
        SessionRegistry registry = SESSIONS.remove(injector);

        if (registry != null) {
            registry.destroyAll();
        }
    }

    private static final class SessionRegistry {
        private final ConcurrentMap<Object, ScopeState> states = new ConcurrentHashMap<>();

        ScopeHandle open(Object identifier) {
            ScopeState state = states.compute(identifier, (id, existing) -> {
                ScopeState current = Optional.ofNullable(existing).orElseGet(ScopeState::new);
                current.retain();
                return current;
            });

            return () -> {
                ScopeState current = states.get(identifier);

                if (current != null && current.release()) {
                    states.remove(identifier, current);
                    current.destroy();
                }
            };
        }

        ScopeState state(Object identifier) {
            ScopeState state = states.get(identifier);

            if (state == null) {
                throw new IllegalStateException("Session " + identifier + " is not active");
            }

            return state;
        }

        void destroyAll() {
            states.forEach((id, state) -> state.destroy());
            states.clear();
        }
    }

    private static final class InjectorBridge {
        private InjectorBridge() {
        }

        static InfuseInjector asInfuse(Injector injector) {
            if (injector instanceof InfuseInjector) {
                return (InfuseInjector) injector;
            }

            throw new IllegalArgumentException("Unsupported injector implementation " + injector.getClass());
        }
    }
}
