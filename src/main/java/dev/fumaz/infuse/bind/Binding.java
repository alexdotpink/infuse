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

    private final @NotNull BindingKey key;
    private final @NotNull Provider<T> provider;
    private final boolean collectionContribution;

    public Binding(@NotNull Class<T> type, @NotNull Provider<T> provider) {
        this(type, provider, BindingQualifier.none(), BindingScope.UNSCOPED, false);
    }

    public Binding(@NotNull Class<T> type,
                   @NotNull Provider<T> provider,
                   @NotNull BindingQualifier qualifier,
                   @NotNull BindingScope scope,
                   boolean collectionContribution) {
        this.key = BindingKey.of(type, qualifier, scope);
        this.provider = provider;
        this.collectionContribution = collectionContribution;
    }

    @SuppressWarnings("unchecked")
    public @NotNull Class<T> getType() {
        return (Class<T>) key.getType();
    }

    public @NotNull BindingKey getKey() {
        return key;
    }

    public @NotNull BindingQualifier getQualifier() {
        return key.getQualifier();
    }

    public @NotNull BindingScope getScope() {
        return key.getScope();
    }

    public boolean isCollectionContribution() {
        return collectionContribution;
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
        return collectionContribution == binding.collectionContribution
                && Objects.equals(key, binding.key)
                && Objects.equals(provider, binding.provider);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, provider, collectionContribution);
    }
}
