package dev.fumaz.infuse.bind;

import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.injector.InfuseInjector;
import dev.fumaz.infuse.injector.Injector;
import dev.fumaz.infuse.provider.Provider;
import dev.fumaz.infuse.provider.SingletonProvider;
import dev.fumaz.infuse.util.InjectionUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A {@link BindingBuilder} is used to create a {@link Binding}.
 *
 * @param <T> the type of the class
 */
public class BindingBuilder<T> {

    private final @NotNull Class<T> type;
    private final @NotNull Collection<Binding<?>> bindings;

    private @Nullable Provider<T> provider;
    private @NotNull BindingQualifier qualifier = BindingQualifier.none();
    private @NotNull BindingScope scope = BindingScope.UNSCOPED;
    private boolean collectionContribution = false;

    public BindingBuilder(@NotNull Class<T> type, @NotNull Collection<Binding<?>> bindings) {
        this.type = type;
        this.bindings = bindings;
    }

    public Binding<T> toProvider(@NotNull Provider<T> provider) {
        this.provider = provider;

        return build();
    }

    public BindingBuilder<T> named(@NotNull String name) {
        qualifier = BindingQualifier.named(name);
        return this;
    }

    public BindingBuilder<T> qualifiedBy(@NotNull Class<? extends Annotation> qualifierType) {
        qualifier = BindingQualifier.of(qualifierType);
        return this;
    }

    public BindingBuilder<T> qualifiedBy(@NotNull Annotation qualifierAnnotation) {
        qualifier = BindingQualifier.from(qualifierAnnotation);
        return this;
    }

    public BindingBuilder<T> inScope(@NotNull BindingScope scope) {
        this.scope = scope.isAny() ? BindingScope.UNSCOPED : scope;
        return this;
    }

    public BindingBuilder<T> inScope(@NotNull String scopeName) {
        return inScope(BindingScope.custom(scopeName));
    }

    public BindingBuilder<T> intoCollection() {
        this.collectionContribution = true;
        return this;
    }

    public BindingBuilder<T> requestScoped() {
        return inScope(BindingScope.REQUEST);
    }

    public BindingBuilder<T> sessionScoped() {
        return inScope(BindingScope.SESSION);
    }

    public Binding<T> to(@NotNull Class<? extends T> implementation) {
        Objects.requireNonNull(implementation, "implementation");
        ensureAssignable(implementation);

        return toProvider(context -> type.cast(context.getInjector().construct(implementation)));
    }

    public Binding<T> toSingleton() {
        this.provider = Provider.singleton(type);
        this.scope = BindingScope.SINGLETON;

        return build();
    }

    public Binding<T> toSingleton(@NotNull Class<? extends T> implementation) {
        Objects.requireNonNull(implementation, "implementation");
        ensureAssignable(implementation);

        this.provider = new SingletonProvider<>(cast(implementation), false);
        this.scope = BindingScope.SINGLETON;

        return build();
    }

    public Binding<T> toEagerSingleton() {
        this.provider = Provider.eagerSingleton(type);
        this.scope = BindingScope.EAGER_SINGLETON;

        return build();
    }

    public Binding<T> toEagerSingleton(@NotNull Class<? extends T> implementation) {
        Objects.requireNonNull(implementation, "implementation");
        ensureAssignable(implementation);

        this.provider = new SingletonProvider<>(cast(implementation), true);
        this.scope = BindingScope.EAGER_SINGLETON;

        return build();
    }

    public Binding<T> toInstance(@Nullable T instance) {
        this.provider = Provider.instance(instance);
        this.scope = BindingScope.INSTANCE;

        return build();
    }

    public Binding<T> toImmutableInstance(@Nullable T instance) {
        this.provider = Provider.immutableInstance(instance);
        this.scope = BindingScope.IMMUTABLE_INSTANCE;

        return build();
    }

    public Binding<T> toRequestScoped() {
        requestScoped();
        return to(type);
    }

    public Binding<T> toRequestScoped(@NotNull Class<? extends T> implementation) {
        requestScoped();
        return to(implementation);
    }

    public Binding<T> toSessionScoped() {
        sessionScoped();
        return to(type);
    }

    public Binding<T> toSessionScoped(@NotNull Class<? extends T> implementation) {
        sessionScoped();
        return to(implementation);
    }

    public Binding<T> toSupplier(@NotNull Supplier<? extends T> supplier) {
        Objects.requireNonNull(supplier, "supplier");

        return toProvider(context -> type.cast(supplier.get()));
    }

    public <A> Binding<T> toProvider(@NotNull Class<A> dependency,
                                     @NotNull Function<? super A, ? extends T> factory) {
        Objects.requireNonNull(dependency, "dependency");
        Objects.requireNonNull(factory, "factory");

        return toProvider(context -> {
            A resolved = resolveDependency(context, dependency);
            return type.cast(factory.apply(resolved));
        });
    }

    public <A, B> Binding<T> toProvider(@NotNull Class<A> first,
                                         @NotNull Class<B> second,
                                         @NotNull BiFunction<? super A, ? super B, ? extends T> factory) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(factory, "factory");

        return toProvider(context -> {
            A left = resolveDependency(context, first);
            B right = resolveDependency(context, second);
            return type.cast(factory.apply(left, right));
        });
    }

    public Binding<T> toConstructor(@NotNull Constructor<? extends T> constructor) {
        Objects.requireNonNull(constructor, "constructor");
        ensureAssignable(constructor.getDeclaringClass());

        return toProvider(context -> instantiateWithConstructor(context, constructor));
    }

    public Binding<T> build() {
        if (provider == null) {
            throw new IllegalStateException("No provider was set");
        }

        Binding<T> binding = new Binding<>(type, provider, qualifier, scope, collectionContribution);
        bindings.add(binding);

        return binding;
    }

    private <A> A resolveDependency(Context<?> parentContext, Class<A> dependency) {
        Injector injector = parentContext.getInjector();
        Context<A> dependencyContext = new Context<>(dependency, parentContext.getObject(), injector, ElementType.FIELD,
                dependency.getSimpleName(), new Annotation[0]);
        A resolved = injector.provide(dependency, dependencyContext);

        if (resolved == null) {
            throw new IllegalStateException("Unable to resolve dependency " + dependency.getName()
                    + " while binding " + type.getName());
        }

        return resolved;
    }

    private T instantiateWithConstructor(Context<?> context, Constructor<? extends T> constructor) {
        try {
            Injector injector = context.getInjector();

            if (injector instanceof InfuseInjector) {
                @SuppressWarnings("unchecked")
                Constructor<T> typed = (Constructor<T>) constructor;
                return ((InfuseInjector) injector).construct(typed);
            }

            Object[] arguments = Arrays.stream(constructor.getParameters())
                    .map(parameter -> resolveParameter(context, parameter))
                    .toArray();

            constructor.setAccessible(true);
            T instance = constructor.newInstance(arguments);

            injector.inject(instance);

            return instance;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke constructor " + constructor, e);
        }
    }

    private Object resolveParameter(Context<?> parentContext, Parameter parameter) {
        Annotation[] annotations = parameter.getAnnotations();
        boolean optional = InjectionUtils.isOptional(annotations);
        Class<?> parameterType = parameter.getType();
        Injector injector = parentContext.getInjector();

        Context<?> parameterContext = new Context<>(parameter.getDeclaringExecutable().getDeclaringClass(),
                parentContext.getObject(), injector, ElementType.CONSTRUCTOR, parameter.getName(), annotations);

        Object resolved = injector.provide(parameterType, parameterContext);

        if (resolved == null) {
            if (optional) {
                if (parameterType.isPrimitive()) {
                    throw new IllegalStateException("Optional constructor parameter " + parameter.getName()
                            + " cannot target primitive type " + parameterType.getName());
                }

                return null;
            }

            throw new IllegalStateException("Unable to resolve constructor parameter " + parameter.getName()
                    + " for type " + type.getName());
        }

        return resolved;
    }

    private void ensureAssignable(Class<?> implementation) {
        if (!type.isAssignableFrom(implementation)) {
            throw new IllegalArgumentException("Type " + implementation.getName()
                    + " is not assignable to " + type.getName());
        }
    }

    @SuppressWarnings("unchecked")
    private Class<T> cast(Class<? extends T> implementation) {
        return (Class<T>) implementation;
    }

}
