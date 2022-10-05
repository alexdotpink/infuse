package dev.fumaz.infuse.bind;

import dev.fumaz.infuse.provider.Provider;

import java.util.Objects;

/**
 * A {@link Binding} is a link between a type and a {@link Provider}.
 *
 * @param <T> the type of the class
 */
public class Binding<T> {

    private final Class<T> type;
    private final Provider<T> provider;

    public Binding(Class<T> type, Provider<T> provider) {
        this.type = type;
        this.provider = provider;
    }

    public Class<T> getType() {
        return type;
    }

    public Provider<T> getProvider() {
        return provider;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof Binding)) {
            return false;
        }

        Binding<?> binding = (Binding<?>) o;
        return type.isAssignableFrom(binding.type) || binding.type.isAssignableFrom(type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type);
    }

}
