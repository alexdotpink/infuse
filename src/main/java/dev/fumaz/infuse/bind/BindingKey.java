package dev.fumaz.infuse.bind;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class BindingKey {

    private final @NotNull Class<?> type;
    private final @NotNull BindingQualifier qualifier;
    private final @NotNull BindingScope scope;

    private BindingKey(@NotNull Class<?> type,
                       @NotNull BindingQualifier qualifier,
                       @NotNull BindingScope scope) {
        this.type = type;
        this.qualifier = qualifier;
        this.scope = scope;
    }

    public static @NotNull BindingKey of(@NotNull Class<?> type,
                                         @NotNull BindingQualifier qualifier,
                                         @NotNull BindingScope scope) {
        return new BindingKey(type, qualifier, scope);
    }

    public @NotNull Class<?> getType() {
        return type;
    }

    public @NotNull BindingQualifier getQualifier() {
        return qualifier;
    }

    public @NotNull BindingScope getScope() {
        return scope;
    }

    public boolean matches(@NotNull Class<?> requestedType,
                           @NotNull BindingQualifier requestedQualifier,
                           @NotNull BindingScope requestedScope) {
        if (!type.equals(requestedType)) {
            return false;
        }

        if (!qualifier.equals(requestedQualifier)) {
            return false;
        }

        return requestedScope.isAny() || scope.equals(requestedScope);
    }

    public String describe() {
        return type.getName() + (qualifier.isDefault() ? "" : " qualified by " + qualifier)
                + (scope == BindingScope.UNSCOPED ? "" : " in scope " + scope);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof BindingKey)) {
            return false;
        }

        BindingKey that = (BindingKey) o;
        return Objects.equals(type, that.type)
                && Objects.equals(qualifier, that.qualifier)
                && Objects.equals(scope, that.scope);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, qualifier, scope);
    }
}
