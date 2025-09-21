package dev.fumaz.infuse.bind;

import dev.fumaz.infuse.annotation.Named;
import dev.fumaz.infuse.annotation.Qualifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an identifying qualifier for a binding.
 */
public final class BindingQualifier {

    private static final BindingQualifier NONE = new BindingQualifier(null, Collections.emptyMap(), "default");

    private final @Nullable Class<? extends Annotation> annotationType;
    private final @NotNull Map<String, Object> attributes;
    private final @NotNull String alias;

    private BindingQualifier(@Nullable Class<? extends Annotation> annotationType,
                              @NotNull Map<String, Object> attributes,
                              @NotNull String alias) {
        this.annotationType = annotationType;
        this.attributes = attributes;
        this.alias = alias;
    }

    public static @NotNull BindingQualifier none() {
        return NONE;
    }

    public static @NotNull BindingQualifier named(@NotNull String name) {
        return new BindingQualifier(Named.class, Collections.singletonMap("value", name), "@" + Named.class.getSimpleName() + "(\"" + name + "\")");
    }

    public static @NotNull BindingQualifier of(@NotNull Class<? extends Annotation> qualifierType) {
        if (!qualifierType.isAnnotationPresent(Qualifier.class)) {
            throw new IllegalArgumentException("Annotation " + qualifierType.getName() + " is not marked with @Qualifier");
        }

        Map<String, Object> attributes = new LinkedHashMap<>();

        for (Method method : qualifierType.getDeclaredMethods()) {
            Object defaultValue = method.getDefaultValue();

            if (defaultValue != null) {
                attributes.put(method.getName(), normalizeValue(defaultValue));
            }
        }

        return new BindingQualifier(qualifierType, Collections.unmodifiableMap(attributes), qualifierType.getName());
    }

    public static @NotNull BindingQualifier from(@NotNull Annotation annotation) {
        Class<? extends Annotation> annotationType = annotation.annotationType();

        if (!annotationType.isAnnotationPresent(Qualifier.class)) {
            throw new IllegalArgumentException("Annotation " + annotationType.getName() + " is not marked with @Qualifier");
        }

        Map<String, Object> attributes = new LinkedHashMap<>();

        for (Method method : annotationType.getDeclaredMethods()) {
            try {
                Object value = method.invoke(annotation);
                attributes.put(method.getName(), normalizeValue(value));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unable to read qualifier attribute " + method.getName(), e);
            }
        }

        return new BindingQualifier(annotationType, Collections.unmodifiableMap(attributes), annotation.toString());
    }

    public @Nullable Class<? extends Annotation> getAnnotationType() {
        return annotationType;
    }

    public @NotNull Map<String, Object> getAttributes() {
        return attributes;
    }

    public @NotNull String getAlias() {
        return alias;
    }

    public boolean isDefault() {
        return this == NONE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof BindingQualifier)) {
            return false;
        }

        BindingQualifier that = (BindingQualifier) o;
        return Objects.equals(annotationType, that.annotationType)
                && Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(annotationType, attributes);
    }

    @Override
    public String toString() {
        return alias;
    }

    private static Object normalizeValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            Object[] normalized = new Object[length];

            for (int i = 0; i < length; i++) {
                normalized[i] = normalizeValue(java.lang.reflect.Array.get(value, i));
            }

            return java.util.Arrays.asList(normalized);
        }

        if (value instanceof Annotation) {
            return from((Annotation) value);
        }

        return value;
    }
}
