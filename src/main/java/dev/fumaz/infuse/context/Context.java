package dev.fumaz.infuse.context;

import dev.fumaz.infuse.injector.Injector;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;

/**
 * A {@link Context} is the state of the injected member.
 *
 * @param <T> the type of the class being injected
 */
public class Context<T> {

    private final Class<T> type;
    private final Injector injector;
    private final ElementType element;
    private final String name;
    private final Annotation[] annotations;

    public Context(Class<T> type, Injector injector, ElementType element, String name, Annotation[] annotations) {
        this.type = type;
        this.injector = injector;
        this.element = element;
        this.name = name;
        this.annotations = annotations;
    }

    public Class<T> getType() {
        return type;
    }

    public Injector getInjector() {
        return injector;
    }

    public ElementType getElement() {
        return element;
    }

    public String getName() {
        return name;
    }

    public Annotation[] getAnnotations() {
        return annotations;
    }

}
