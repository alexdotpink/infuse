package dev.fumaz.infuse.context;

import dev.fumaz.infuse.injector.Injector;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

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
    private Supplier<Annotation[]> annotationsSupplier;
    private boolean pooled;

    public Context(@NotNull Class<T> type, @NotNull Object object, @NotNull Injector injector,
                   @NotNull ElementType element, @NotNull String name, Annotation[] annotations) {
        initialise(type, object, injector, element, name, annotations);
        this.pooled = false;
    }

    public Context(@NotNull Class<T> type, @NotNull Object object, @NotNull Injector injector,
                   @NotNull ElementType element, @NotNull String name,
                   @NotNull Supplier<Annotation[]> annotationsSupplier) {
        initialise(type, object, injector, element, name, annotationsSupplier);
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

    public static <T> @NotNull Context<T> borrow(@NotNull Class<T> type, @NotNull Object object,
                                                 @NotNull Injector injector, @NotNull ElementType element,
                                                 @NotNull String name,
                                                 @NotNull Supplier<Annotation[]> annotationsSupplier) {
        Deque<Context<?>> pool = POOL.get();
        Context<T> context;

        Context<?> pooled = pool.pollLast();
        if (pooled == null) {
            context = new Context<>();
        } else {
            @SuppressWarnings("unchecked") Context<T> cast = (Context<T>) pooled;
            context = cast;
        }

        context.initialise(type, object, injector, element, name, annotationsSupplier);
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
        this.annotationsSupplier = null;
        this.annotations = annotations == null || annotations.length == 0 ? EMPTY_ANNOTATIONS : annotations;
    }

    private void initialise(Class<T> type, Object object, Injector injector, ElementType element, String name,
                            Supplier<Annotation[]> annotationsSupplier) {
        this.type = type;
        this.object = object;
        this.injector = injector;
        this.element = element;
        this.name = name;
        if (annotationsSupplier == null) {
            this.annotations = EMPTY_ANNOTATIONS;
            this.annotationsSupplier = null;
        } else {
            this.annotations = null;
            this.annotationsSupplier = annotationsSupplier;
        }
    }

    private void clear() {
        this.type = null;
        this.object = null;
        this.injector = null;
        this.element = null;
        this.name = null;
        this.annotations = null;
        this.annotationsSupplier = null;
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
        if (type == null) {
            throw new IllegalStateException("Context has been released back to the pool");
        }

        Annotation[] current = annotations;
        if (current != null) {
            return current;
        }

        Supplier<Annotation[]> supplier = annotationsSupplier;
        if (supplier == null) {
            current = EMPTY_ANNOTATIONS;
        } else {
            current = supplier.get();

            if (current == null || current.length == 0) {
                current = EMPTY_ANNOTATIONS;
            }

            annotations = current;
            annotationsSupplier = null;
        }

        return current;
    }

}
