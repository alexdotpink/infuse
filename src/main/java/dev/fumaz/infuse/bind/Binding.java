package dev.fumaz.infuse.bind;

import dev.fumaz.infuse.provider.Provider;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A {@link Binding} is a link between a type and a {@link Provider}.
 *
 * @param <T> the type of the class
 */
public class Binding<T> {

    private final @NotNull Class<T> type;
    private final @NotNull Provider<T> provider;

    public Binding(@NotNull Class<T> type, @NotNull Provider<T> provider) {
        this.type = type;
        this.provider = provider;
    }

    public @NotNull Class<T> getType() {
        return type;
    }

    public @NotNull Provider<T> getProvider() {
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
