package dev.fumaz.infuse.context;

import dev.fumaz.infuse.injector.Injector;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.util.Objects;

/**
 * Read-only view over the contextual information supplied to providers.
 * Allows lightweight implementations to avoid borrowing pooled {@link Context} instances when mutation isn't needed.
 *
 * @param <T> the type being injected
 */
public interface ContextView<T> {

    @NotNull Class<T> getType();

    @NotNull Object getObject();

    @NotNull Injector getInjector();

    @NotNull ElementType getElement();

    @NotNull String getName();

    @NotNull Annotation[] getAnnotations();

    static <T> @NotNull ContextView<T> of(@NotNull Class<T> type,
                                          @NotNull Object object,
                                          @NotNull Injector injector,
                                          @NotNull ElementType element,
                                          @NotNull String name,
                                          Annotation[] annotations) {
        return new SimpleContextView<>(type, object, injector, element, name, annotations);
    }

    final class SimpleContextView<T> implements ContextView<T> {
        private static final Annotation[] EMPTY = new Annotation[0];

        private final Class<T> type;
        private final Object object;
        private final Injector injector;
        private final ElementType element;
        private final String name;
        private final Annotation[] annotations;

        private SimpleContextView(Class<T> type,
                                  Object object,
                                  Injector injector,
                                  ElementType element,
                                  String name,
                                  Annotation[] annotations) {
            this.type = Objects.requireNonNull(type, "type");
            this.object = Objects.requireNonNull(object, "object");
            this.injector = Objects.requireNonNull(injector, "injector");
            this.element = Objects.requireNonNull(element, "element");
            this.name = Objects.requireNonNull(name, "name");
            this.annotations = annotations == null || annotations.length == 0 ? EMPTY : annotations;
        }

        @Override
        public @NotNull Class<T> getType() {
            return type;
        }

        @Override
        public @NotNull Object getObject() {
            return object;
        }

        @Override
        public @NotNull Injector getInjector() {
            return injector;
        }

        @Override
        public @NotNull ElementType getElement() {
            return element;
        }

        @Override
        public @NotNull String getName() {
            return name;
        }

        @Override
        public @NotNull Annotation[] getAnnotations() {
            return annotations;
        }
    }
}
