package dev.fumaz.infuse.bind;

import dev.fumaz.infuse.provider.Provider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Set;

/**
 * A {@link BindingBuilder} is used to create a {@link Binding}.
 *
 * @param <T> the type of the class
 */
public class BindingBuilder<T> {

    private final @NotNull Class<T> type;
    private final @NotNull Collection<Binding<?>> bindings;

    private @Nullable Provider<T> provider;

    public BindingBuilder(@NotNull Class<T> type, @NotNull Collection<Binding<?>> bindings) {
        this.type = type;
        this.bindings = bindings;
    }

    public Binding<T> toProvider(@NotNull Provider<T> provider) {
        this.provider = provider;

        return build();
    }

    public Binding<T> toSingleton() {
        this.provider = Provider.singleton(type);

        return build();
    }

    public Binding<T> toEagerSingleton() {
        this.provider = Provider.eagerSingleton(type);

        return build();
    }

    public Binding<T> toInstance(@Nullable T instance) {
        this.provider = Provider.instance(instance);

        return build();
    }

    public Binding<T> toImmutableInstance(@Nullable T instance) {
        this.provider = Provider.immutableInstance(instance);

        return build();
    }

    public Binding<T> build() {
        if (provider == null) {
            throw new IllegalStateException("No provider was set");
        }

        Binding<T> binding = new Binding<>(type, provider);
        bindings.add(binding);

        return binding;
    }

}
