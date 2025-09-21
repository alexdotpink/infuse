package dev.fumaz.infuse.module;

import dev.fumaz.infuse.annotation.PostConstruct;
import dev.fumaz.infuse.annotation.PostInject;
import dev.fumaz.infuse.annotation.PreDestroy;
import dev.fumaz.infuse.annotation.Scope;
import dev.fumaz.infuse.annotation.Singleton;
import dev.fumaz.infuse.bind.BindingBuilder;
import dev.fumaz.infuse.bind.BindingScope;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Factory helpers for {@link PackageBindingRule} implementations used by {@link InfuseModule#bindPackage}.
 */
public final class PackageBindingRules {

    private PackageBindingRules() {
    }

    /**
     * Default filter applied to discovered classes.
     */
    public static final Predicate<Class<?>> DEFAULT_CLASS_FILTER = type ->
            !type.isInterface()
                    && !type.isAnnotation()
                    && !type.isEnum()
                    && !Modifier.isAbstract(type.getModifiers())
                    && !type.isAnonymousClass()
                    && !type.isLocalClass();

    /**
     * Returns the default rule set applied by package scanning.
     */
    public static @NotNull List<PackageBindingRule> defaultRules() {
        List<PackageBindingRule> defaults = new ArrayList<>();
        defaults.add(singleton());
        defaults.add(scoped());
        defaults.add(lifecycle());
        return defaults;
    }

    /**
     * Creates a rule that binds classes annotated with {@link Singleton}.
     */
    public static @NotNull PackageBindingRule singleton() {
        return (module, type) -> {
            Singleton singleton = type.getAnnotation(Singleton.class);
            if (singleton == null) {
                return false;
            }

            bindSingletonInternal(module, type, !singleton.lazy());
            return true;
        };
    }

    /**
     * Creates a rule that binds classes annotated with a custom scope annotation marked by {@link Scope}.
     */
    public static @NotNull PackageBindingRule scoped() {
        return (module, type) -> {
            ScopeDefinition scope = findScopeDefinition(type);
            if (scope == null) {
                return false;
            }

            bindScope(module, type, scope);
            return true;
        };
    }

    /**
     * Creates a rule that binds classes declaring lifecycle methods such as {@link PostConstruct}.
     */
    public static @NotNull PackageBindingRule lifecycle() {
        return (module, type) -> {
            if (!hasLifecycleMethods(type)) {
                return false;
            }

            bindSelf(module, type);
            return true;
        };
    }

    /**
     * Creates a rule that binds every matched class to itself, useful as a fallback.
     */
    public static @NotNull PackageBindingRule selfBinding() {
        return (module, type) -> {
            bindSelf(module, type);
            return true;
        };
    }

    /**
     * Creates a rule that triggers for classes annotated with {@code annotation} and delegates to {@code binder}.
     */
    public static @NotNull PackageBindingRule annotatedWith(@NotNull Class<? extends Annotation> annotation,
                                                            @NotNull BiConsumer<InfuseModule, Class<?>> binder) {
        Objects.requireNonNull(annotation, "annotation");
        Objects.requireNonNull(binder, "binder");

        return predicate(type -> type.isAnnotationPresent(annotation), binder);
    }

    /**
     * Creates a rule driven by an arbitrary predicate.
     */
    public static @NotNull PackageBindingRule predicate(@NotNull Predicate<Class<?>> predicate,
                                                        @NotNull BiConsumer<InfuseModule, Class<?>> binder) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(binder, "binder");

        return (module, type) -> {
            if (!predicate.test(type)) {
                return false;
            }

            binder.accept(module, type);
            return true;
        };
    }

    private static boolean hasLifecycleMethods(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            Method[] methods;

            try {
                methods = current.getDeclaredMethods();
            } catch (NoClassDefFoundError error) {
                continue;
            }

            for (Method method : methods) {
                if (method.isAnnotationPresent(PostConstruct.class)
                        || method.isAnnotationPresent(PreDestroy.class)
                        || method.isAnnotationPresent(PostInject.class)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static ScopeDefinition findScopeDefinition(Class<?> type) {
        for (Annotation annotation : type.getAnnotations()) {
            Scope scope = annotation.annotationType().getAnnotation(Scope.class);
            if (scope != null) {
                return ScopeDefinition.of(scope, annotation);
            }
        }

        return null;
    }

    private static void bindSingleton(InfuseModule module, Class<?> type, boolean eager) {
        bindSingletonInternal(module, type, eager);
    }

    private static void bindScope(InfuseModule module, Class<?> type, ScopeDefinition scope) {
        if (scope.isSingleton()) {
            bindSingletonInternal(module, type, scope.isEager());
            return;
        }

        bindSelf(module, type, builder -> builder.inScope(scope.getScope()));
    }

    private static void bindSelf(InfuseModule module, Class<?> type) {
        bindSelf(module, type, builder -> {});
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void bindSelf(InfuseModule module,
                                 Class<?> type,
                                 Consumer<BindingBuilder<?>> customiser) {
        BindingBuilder<?> builder = module.bind((Class) type);
        customiser.accept(builder);
        builder.to((Class) type);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void bindSingletonInternal(InfuseModule module, Class<?> type, boolean eager) {
        BindingBuilder<?> builder = module.bind((Class) type);
        if (eager) {
            builder.toEagerSingleton();
        } else {
            builder.toSingleton();
        }
    }

    private static String normaliseScopeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String deriveScopeName(Class<? extends Annotation> annotationType) {
        String simpleName = annotationType.getSimpleName();
        if (simpleName.endsWith("Scoped")) {
            simpleName = simpleName.substring(0, simpleName.length() - "Scoped".length());
        } else if (simpleName.endsWith("Scope")) {
            simpleName = simpleName.substring(0, simpleName.length() - "Scope".length());
        }

        return toSnakeCase(simpleName);
    }

    private static String toSnakeCase(String input) {
        if (input.isEmpty()) {
            return input;
        }

        StringBuilder builder = new StringBuilder();
        char[] chars = input.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    builder.append('_');
                }
                builder.append(Character.toLowerCase(c));
            } else {
                builder.append(Character.toLowerCase(c));
            }
        }

        return builder.toString();
    }

    private static BindingScope resolveScope(String requested) {
        String value = normaliseScopeName(requested);

        switch (value) {
            case "singleton":
                return BindingScope.SINGLETON;
            case "eager_singleton":
                return BindingScope.EAGER_SINGLETON;
            case "instance":
                return BindingScope.INSTANCE;
            case "immutable_instance":
                return BindingScope.IMMUTABLE_INSTANCE;
            default:
                return BindingScope.custom(value);
        }
    }

    private static final class ScopeDefinition {
        private final BindingScope scope;
        private final boolean singleton;
        private final boolean eager;

        private ScopeDefinition(BindingScope scope, boolean singleton, boolean eager) {
            this.scope = scope;
            this.singleton = singleton;
            this.eager = eager;
        }

        static ScopeDefinition of(Scope scope, Annotation annotation) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            String name = scope.value().isEmpty() ? deriveScopeName(annotationType) : scope.value();
            BindingScope bindingScope = resolveScope(name);
            boolean singleton = bindingScope == BindingScope.SINGLETON || bindingScope == BindingScope.EAGER_SINGLETON;
            boolean eager = singleton && (scope.eager() || bindingScope == BindingScope.EAGER_SINGLETON);

            if (singleton && annotation instanceof Singleton) {
                eager = !((Singleton) annotation).lazy();
            }

            return new ScopeDefinition(bindingScope, singleton, eager);
        }

        BindingScope getScope() {
            return scope;
        }

        boolean isSingleton() {
            return singleton;
        }

        boolean isEager() {
            return eager;
        }
    }
}
