package dev.fumaz.infuse.bind;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Annotation;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class BindingKey {

    private static final ClassValue<TypePool> POOLS = new ClassValue<TypePool>() {
        @Override
        protected TypePool computeValue(Class<?> type) {
            return new TypePool(type);
        }
    };

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
        return POOLS.get(type).intern(qualifier, scope);
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
        if (type != requestedType) {
            return false;
        }

        TypePool pool = POOLS.get(type);

        if (qualifier != requestedQualifier) {
            BindingQualifier canonicalQualifier = pool.lookupQualifier(requestedQualifier);

            if (canonicalQualifier != qualifier) {
                return false;
            }
        }

        if (requestedScope.isAny()) {
            return true;
        }

        if (scope == requestedScope) {
            return true;
        }

        BindingScope canonicalScope = pool.lookupScope(requestedScope);
        return canonicalScope != null && scope == canonicalScope;
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
        return type == that.type && qualifier == that.qualifier && scope == that.scope;
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + System.identityHashCode(qualifier);
        result = 31 * result + System.identityHashCode(scope);
        return result;
    }

    private static final class TypePool {

        private final Class<?> type;
        private final ConcurrentMap<QualifierSignature, BindingQualifier> qualifiers = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, BindingScope> scopes = new ConcurrentHashMap<>();
        private final ConcurrentMap<KeySignature, BindingKey> keys = new ConcurrentHashMap<>();

        private TypePool(Class<?> type) {
            this.type = type;
        }

        private BindingKey intern(@NotNull BindingQualifier qualifier,
                                  @NotNull BindingScope scope) {
            QualifierSignature qualifierSignature = new QualifierSignature(qualifier);
            BindingQualifier canonicalQualifier = qualifiers.computeIfAbsent(qualifierSignature, ignored -> qualifier);
            BindingScope canonicalScope = canonicalizeScope(scope);

            KeySignature signature = new KeySignature(canonicalQualifier, canonicalScope);
            return keys.computeIfAbsent(signature, ignored -> new BindingKey(type, canonicalQualifier, canonicalScope));
        }

        private BindingQualifier lookupQualifier(@NotNull BindingQualifier qualifier) {
            QualifierSignature signature = new QualifierSignature(qualifier);
            return qualifiers.get(signature);
        }

        private BindingScope lookupScope(@NotNull BindingScope scope) {
            if (scope.isAny()) {
                return BindingScope.ANY;
            }

            return scopes.get(scope.getName());
        }

        private BindingScope canonicalizeScope(@NotNull BindingScope scope) {
            if (scope.isAny()) {
                return BindingScope.ANY;
            }

            return scopes.computeIfAbsent(scope.getName(), ignored -> scope);
        }
    }

    private static final class QualifierSignature {

        private final Class<? extends Annotation> annotationType;
        private final String alias;
        private final int hash;

        private QualifierSignature(@NotNull BindingQualifier qualifier) {
            this.annotationType = qualifier.getAnnotationType();
            this.alias = qualifier.getAlias();
            this.hash = 31 * Objects.hashCode(annotationType) + alias.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof QualifierSignature)) {
                return false;
            }

            QualifierSignature that = (QualifierSignature) o;
            return annotationType == that.annotationType && alias.equals(that.alias);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class KeySignature {

        private final BindingQualifier qualifier;
        private final BindingScope scope;
        private final int hash;

        private KeySignature(@NotNull BindingQualifier qualifier, @NotNull BindingScope scope) {
            this.qualifier = qualifier;
            this.scope = scope;
            int result = System.identityHashCode(qualifier);
            result = 31 * result + System.identityHashCode(scope);
            this.hash = result;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof KeySignature)) {
                return false;
            }

            KeySignature that = (KeySignature) o;
            return qualifier == that.qualifier && scope == that.scope;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
