package dev.fumaz.infuse.bind;

import dev.fumaz.infuse.provider.Provider;

import java.util.Set;

/**
 * A {@link BindingBuilder} is used to create a {@link Binding}.
 *
 * @param <T> the type of the class
 */
public class BindingBuilder<T> {

    private final Class<T> type;
    private final Set<Binding<?>> bindings;

    private Provider<T> provider;

    public BindingBuilder(Class<T> type, Set<Binding<?>> bindings) {
        this.type = type;
        this.bindings = bindings;
    }

    public Binding<T> to(Provider<T> provider) {
        this.provider = provider;

        return build();
    }

    public Binding<T> singleton() {
        this.provider = Provider.singleton(type);

        return build();
    }

    public Binding<T> instance(T instance) {
        this.provider = Provider.instance(instance);

        return build();
    }

    public Binding<T> build() {
        Binding<T> binding = new Binding<>(type, provider);
        bindings.add(binding);

        return binding;
    }

}
