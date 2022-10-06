package dev.fumaz.infuse.context;

import dev.fumaz.infuse.injector.Injector;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;

/**
 * A {@link Context} is the state of the injected member.
 *
 * @param <T> the type of the class being injected
 */
public class Context<T> {

    private final @NotNull Class<T> type;
    private final @NotNull Injector injector;
    private final @NotNull ElementType element;
    private final @NotNull String name;
    private final @NotNull Annotation[] annotations;

    public Context(@NotNull Class<T> type, @NotNull Injector injector, @NotNull ElementType element, @NotNull String name, Annotation[] annotations) {
        this.type = type;
        this.injector = injector;
        this.element = element;
        this.name = name;
        this.annotations = annotations;
    }

    public @NotNull Class<T> getType() {
        return type;
    }

    public @NotNull Injector getInjector() {
        return injector;
    }

    public @NotNull ElementType getElement() {
        return element;
    }

    public @NotNull String getName() {
        return name;
    }

    public Annotation[] getAnnotations() {
        return annotations;
    }

}
