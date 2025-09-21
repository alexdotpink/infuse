package dev.fumaz.infuse.context;

import dev.fumaz.infuse.injector.Injector;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A {@link Context} is the state of the injected member.
 *
 * @param <T> the type of the class being injected
 */
public final class Context<T> implements ContextView<T> {

    private static final ThreadLocal<Deque<Context<?>>> POOL = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Annotation[] EMPTY_ANNOTATIONS = new Annotation[0];

    private Class<T> type;
    private Object object;
    private Injector injector;
    private ElementType element;
    private String name;
    private Annotation[] annotations;
    private boolean pooled;

    public Context(@NotNull Class<T> type, @NotNull Object object, @NotNull Injector injector,
                   @NotNull ElementType element, @NotNull String name, Annotation[] annotations) {
        initialise(type, object, injector, element, name, annotations);
        this.pooled = false;
    }

    private Context() {
    }

    public static <T> @NotNull Context<T> borrow(@NotNull Class<T> type, @NotNull Object object,
                                                 @NotNull Injector injector, @NotNull ElementType element,
                                                 @NotNull String name, Annotation[] annotations) {
        Deque<Context<?>> pool = POOL.get();
        Context<T> context;

        Context<?> pooled = pool.pollLast();
        if (pooled == null) {
            context = new Context<>();
        } else {
            @SuppressWarnings("unchecked") Context<T> cast = (Context<T>) pooled;
            context = cast;
        }

        context.initialise(type, object, injector, element, name, annotations);
        context.pooled = true;
        return context;
    }

    public static <T> @NotNull Context<T> borrow(@NotNull ContextView<T> view) {
        return borrow(view.getType(), view.getObject(), view.getInjector(), view.getElement(), view.getName(),
                view.getAnnotations());
    }

    public @NotNull Context<T> detach() {
        this.pooled = false;
        return this;
    }

    public void release() {
        if (!pooled) {
            return;
        }

        clear();
        POOL.get().offerLast(this);
    }

    private void initialise(Class<T> type, Object object, Injector injector, ElementType element, String name,
                            Annotation[] annotations) {
        this.type = type;
        this.object = object;
        this.injector = injector;
        this.element = element;
        this.name = name;
        this.annotations = annotations == null ? EMPTY_ANNOTATIONS : annotations;
    }

    private void clear() {
        this.type = null;
        this.object = null;
        this.injector = null;
        this.element = null;
        this.name = null;
        this.annotations = null;
        this.pooled = false;
    }

    @Override
    public @NotNull Class<T> getType() {
        Class<T> current = type;
        if (current == null) {
            throw new IllegalStateException("Context has been released back to the pool");
        }
        return current;
    }

    @Override
    public @NotNull Object getObject() {
        Object current = object;
        if (current == null) {
            throw new IllegalStateException("Context has been released back to the pool");
        }
        return current;
    }

    @Override
    public @NotNull Injector getInjector() {
        Injector current = injector;
        if (current == null) {
            throw new IllegalStateException("Context has been released back to the pool");
        }
        return current;
    }

    @Override
    public @NotNull ElementType getElement() {
        ElementType current = element;
        if (current == null) {
            throw new IllegalStateException("Context has been released back to the pool");
        }
        return current;
    }

    @Override
    public @NotNull String getName() {
        String current = name;
        if (current == null) {
            throw new IllegalStateException("Context has been released back to the pool");
        }
        return current;
    }

    @Override
    public Annotation[] getAnnotations() {
        Annotation[] current = annotations;
        if (current == null) {
            throw new IllegalStateException("Context has been released back to the pool");
        }
        return current;
    }

}
