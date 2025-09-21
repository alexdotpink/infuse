package dev.fumaz.infuse.injector;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.logging.Logger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import dev.fumaz.infuse.annotation.Inject;
import dev.fumaz.infuse.annotation.PostConstruct;
import dev.fumaz.infuse.annotation.PostInject;
import dev.fumaz.infuse.annotation.PreDestroy;
import dev.fumaz.infuse.bind.Binding;
import dev.fumaz.infuse.bind.BindingQualifier;
import dev.fumaz.infuse.bind.BindingRegistry;
import dev.fumaz.infuse.bind.BindingScope;
import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.module.Module;
import dev.fumaz.infuse.provider.InstanceProvider;
import dev.fumaz.infuse.provider.Provider;
import dev.fumaz.infuse.provider.SingletonProvider;
import dev.fumaz.infuse.util.InjectionUtils;

public class InfuseInjector implements Injector {

    private final @Nullable Injector parent;
    private final @NotNull List<Module> modules;
    private final @NotNull Map<Class<?>, InjectionPlan> injectionPlans;
    private final @NotNull BindingRegistry bindingRegistry;
    private final @NotNull List<Binding<?>> ownBindings;
    private final @NotNull ResolutionScopes resolutionScopes;

    public InfuseInjector(@Nullable Injector parent, @NotNull List<Module> modules) {
        this.parent = parent;
        this.modules = modules;
        this.injectionPlans = new HashMap<>();
        this.bindingRegistry = new BindingRegistry();
        this.ownBindings = new ArrayList<>();
        this.resolutionScopes = new ResolutionScopes(this);

        for (Module module : modules) {
            module.configure();

            for (Binding<?> binding : module.getBindings()) {
                registerBinding(binding);
            }
        }

        registerBinding(new Binding<>(Injector.class, new InstanceProvider<>(this),
                BindingQualifier.none(), BindingScope.INSTANCE, false));
        registerBinding(new Binding<>(Logger.class,
                (context) -> Logger.getLogger(context.getType().getSimpleName()),
                BindingQualifier.none(), BindingScope.UNSCOPED, false));

        List<Object> eagerSingletons = new ArrayList<>();
        for (Binding<?> binding : getOwnBindings()) {
            Provider<?> provider = binding.getProvider();
            Object instance = null;

            if (provider instanceof SingletonProvider && ((SingletonProvider<?>) provider).isEager()) {
                instance = ((SingletonProvider<?>) provider).provideWithoutInjecting(
                        new Context<>(getClass(), this, this, ElementType.FIELD, "eager", new Annotation[0]));
            } else if (provider instanceof InstanceProvider) {
                instance = ((InstanceProvider<?>) provider).provideWithoutInjecting(
                        new Context<>(getClass(), this, this, ElementType.FIELD, "eager", new Annotation[0]));
            }

            if (instance != null) {
                eagerSingletons.add(instance);
            }
        }

        for (Object singleton : eagerSingletons) {
            injectInjectionPoints(singleton);
        }

        List<Method> postInjectMethods = new ArrayList<>();
        for (Object singleton : eagerSingletons) {
            postInjectMethods.addAll(getInjectionPlan(singleton.getClass()).getPostInjectMethods());
        }

        postInjectMethods.sort(Comparator.comparingInt(m -> m.getAnnotation(PostInject.class).priority()));

        for (Method method : postInjectMethods) {
            for (Object singleton : eagerSingletons) {
                if (method.getDeclaringClass().isInstance(singleton)) {
                    try {
                        injectMethod(singleton, method);
                    } catch (Exception e) {
                        System.err.println("Failed to eagerly inject method " + method.getName() + " in "
                                + singleton.getClass().getName());
                        throw e;
                    }
                }
            }
        }
    }

    public void inject(@NotNull Object object) {
        ResolutionScopeHandle scope = resolutionScopes.enter(object);

        try {
            injectWithinScope(object);
        } finally {
            resolutionScopes.exit(scope);
        }
    }

    private void injectWithinScope(@NotNull Object object) {
        injectInjectionPoints(object);
        postInject(object);
    }

    private void registerBinding(@NotNull Binding<?> binding) {
        bindingRegistry.add(binding);
        ownBindings.add(binding);
    }

    private <T> List<Binding<T>> resolveBindings(@NotNull Class<T> type,
                                                 @NotNull BindingQualifier qualifier,
                                                 @NotNull BindingScope scope) {
        List<Binding<T>> matches = bindingRegistry.find(type, qualifier, scope);

        if (!matches.isEmpty()) {
            return matches;
        }

        if (parent instanceof InfuseInjector) {
            return ((InfuseInjector) parent).resolveBindings(type, qualifier, scope);
        }

        return Collections.emptyList();
    }

    private void injectInjectionPoints(@NotNull Object object) {
        injectVariables(object);
        injectMethods(object);
    }

    @Override
    public <T> T provide(@NotNull Class<T> type, @NotNull Context<?> context) {
        ResolutionScopeHandle ownerScope = resolutionScopes.enter(context.getObject());
        ProvisionFrame frame = null;
        boolean optional = InjectionUtils.isOptional(context.getAnnotations());

        try {
            Object existing = resolutionScopes.lookup(type);

            if (existing != null) {
                return type.cast(existing);
            }

            BindingQualifier qualifier = InjectionUtils.resolveQualifier(context.getAnnotations());
            List<Binding<T>> matches = resolveBindings(type, qualifier, BindingScope.ANY);

            if (matches.isEmpty() && optional) {
                return null;
            }

            if (matches.size() > 1) {
                boolean allCollections = matches.stream().allMatch(Binding::isCollectionContribution);

                if (allCollections) {
                    throw new IllegalStateException("Collection bindings are not yet supported for type "
                            + type.getName() + (qualifier.isDefault() ? "" : " qualified by " + qualifier));
                }

                throw new IllegalStateException("Multiple bindings found for " + type.getName()
                        + (qualifier.isDefault() ? "" : " qualified by " + qualifier));
            }

            frame = resolutionScopes.begin(type);

            T instance = !matches.isEmpty()
                    ? matches.get(0).getProvider().provide(context)
                    : construct(type);

            if (instance == null && optional) {
                return null;
            }

            resolutionScopes.record(type, instance);

            return instance;
        } catch (Exception e) {
            System.err.println("Failed to provide " + type.getName());
            throw e;
        } finally {
            if (frame != null) {
                resolutionScopes.end(frame);
            }

            resolutionScopes.exit(ownerScope);
        }
    }

    @Override
    public <T> @Nullable T provide(@NotNull Class<T> type, @NotNull Object calling) {
        Context<?> context = new Context<>(calling.getClass(), calling, this, ElementType.FIELD, "field",
                new Annotation[0]);

        return provide(type, context);
    }

    @Override
    public <T> T construct(@NotNull Class<T> type, @NotNull Object... args) {
        Constructor<T> constructor = resolveConstructor(type, args);
        constructor.setAccessible(true);

        try {
            T t = constructor.newInstance(getConstructorArguments(constructor, args));

            ResolutionScopeHandle scope = resolutionScopes.enter(t);

            try {
                postConstruct(t);
                injectWithinScope(t);
            } finally {
                resolutionScopes.exit(scope);
            }

            return t;
        } catch (Exception e) {
            System.err.println("Failed to construct " + type.getName());
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public <T> T constructWithoutInjecting(@NotNull Class<T> type, @NotNull Object... args) {
        Constructor<T> constructor = resolveConstructor(type, args);
        constructor.setAccessible(true);

        try {
            T t = constructor.newInstance(getConstructorArguments(constructor, args));

            ResolutionScopeHandle scope = resolutionScopes.enter(t);

            try {
                postConstruct(t);
            } finally {
                resolutionScopes.exit(scope);
            }

            return t;
        } catch (Exception e) {
            System.err.println("Failed to construct without injecting " + type.getName());
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public <T> T construct(@NotNull Constructor<T> constructor) {
        constructor.setAccessible(true);

        try {
            T instance = constructor.newInstance(getConstructorArguments(constructor));

            ResolutionScopeHandle scope = resolutionScopes.enter(instance);

            try {
                postConstruct(instance);
                injectWithinScope(instance);
            } finally {
                resolutionScopes.exit(scope);
            }

            return instance;
        } catch (Exception e) {
            System.err.println("Failed to construct via constructor " + constructor.toGenericString());
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    @Override
    public void destroy() {
        getBindings().forEach(binding -> {
            preDestroy(binding.getProvider().provide(
                    new Context<>(binding.getType(), this, this, ElementType.FIELD, "field", new Annotation[0])));
        });
    }

    @Override
    public <T> @Nullable Provider<T> getProvider(@NotNull Class<T> type) {
        return getBindingOrThrow(type).getProvider();
    }

    @Override
    public @Nullable Injector getParent() {
        return parent;
    }

    @Override
    public @NotNull Injector child(@NotNull List<Module> modules) {
        return new InfuseInjector(this, modules);
    }

    @Override
    public @NotNull List<Module> getModules() {
        List<Module> modules = new ArrayList<>();

        if (parent != null) {
            modules.addAll(parent.getModules());
        }

        modules.addAll(this.modules);

        return modules;
    }

    @Override
    public @NotNull List<Binding<?>> getBindings() {
        List<Binding<?>> allBindings = new ArrayList<>();
        if (parent != null) {
            allBindings.addAll(parent.getBindings());
        }
        allBindings.addAll(getOwnBindings());
        return allBindings;
    }

    @Override
    public <T> @NotNull List<Binding<? extends T>> getBindings(Class<T> type) {
        List<Binding<? extends T>> matchingBindings = new ArrayList<>();
        for (Binding<?> binding : getBindings()) {
            if (type.isAssignableFrom(binding.getType())) {
                matchingBindings.add((Binding<? extends T>) binding);
            }
        }
        return matchingBindings;
    }

    private List<Binding<?>> getOwnBindings() {
        return new ArrayList<>(ownBindings);
    }

    public <T> @NotNull Binding<T> getBindingOrThrow(@NotNull Class<T> type) {
        Binding<T> binding = getBindingOrNull(type);

        if (binding == null) {
            throw new RuntimeException("No binding found for " + type.getName());
        }

        return binding;
    }

    public <T> @Nullable Binding<T> getBindingOrNull(@NotNull Class<T> type) {
        List<Binding<T>> bindings = resolveBindings(type, BindingQualifier.none(), BindingScope.ANY);

        if (bindings.isEmpty()) {
            return null;
        }

        if (bindings.size() > 1) {
            throw new IllegalStateException("Multiple bindings found for " + type.getName());
        }

        return bindings.get(0);
    }

    private <T> @Nullable Constructor<T> findInjectableConstructor(@NotNull Class<T> type) {
        Constructor<T> injectableConstructor = null;

        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(Inject.class)) {
                if (injectableConstructor != null && injectableConstructor.isAnnotationPresent(Inject.class)) {
                    throw new IllegalArgumentException("Multiple injectable constructors found for type " + type);
                }

                injectableConstructor = (Constructor<T>) constructor;
            }

            if (constructor.getParameterCount() == 0 && injectableConstructor == null) {
                injectableConstructor = (Constructor<T>) constructor;
            }
        }

        return injectableConstructor;
    }

    public <T> Constructor<T> findSuitableConstructor(Class<T> clazz, Object... args) {
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        Constructor<T> bestMatch = null;
        int bestMatchScore = Integer.MAX_VALUE;

        for (Constructor<?> constructor : constructors) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();

            if (parameterTypes.length != args.length) {
                continue;
            }

            int matchScore = 0;
            boolean suitable = true;

            for (int i = 0; i < args.length; i++) {
                Class<?> expectedType = parameterTypes[i];

                if (args[i] == null) {
                    matchScore += 0;
                } else {
                    Class<?> actualType = args[i].getClass();

                    if (expectedType.isAssignableFrom(actualType)) {
                        int distance = getClassDistance(expectedType, actualType);
                        if (distance != -1) {
                            matchScore += distance;
                        } else {
                            suitable = false;
                            break;
                        }
                    } else {
                        suitable = false;
                        break;
                    }
                }
            }

            if (suitable && matchScore < bestMatchScore) {
                bestMatch = (Constructor<T>) constructor;
                bestMatchScore = matchScore;
            }
        }

        return bestMatch;
    }

    private <T> Constructor<T> resolveConstructor(Class<T> type, Object... args) {
        Constructor<T> injectable = findInjectableConstructor(type);

        if (injectable != null && injectable.isAnnotationPresent(Inject.class)) {
            requireArgumentsCompatible(type, injectable, args);
            return injectable;
        }

        if (injectable != null && !injectable.isAnnotationPresent(Inject.class)) {
            if (args.length == 0 || isConstructorCompatible(injectable, args)) {
                return injectable;
            }
        }

        Constructor<T> heuristic = findSuitableConstructor(type, args);

        if (heuristic != null) {
            return heuristic;
        }

        if (injectable != null && injectable.isAnnotationPresent(Inject.class)) {
            throw new IllegalArgumentException("Annotated constructor for " + type.getName()
                    + " cannot be satisfied by the provided arguments.");
        }

        throw new RuntimeException("No suitable constructor found for " + type.getName());
    }

    private boolean isConstructorCompatible(Constructor<?> constructor, Object... args) {
        Class<?>[] parameterTypes = constructor.getParameterTypes();

        if (args.length > parameterTypes.length) {
            return false;
        }

        for (Object arg : args) {
            if (arg == null) {
                continue;
            }

            boolean matched = false;

            for (Class<?> parameterType : parameterTypes) {
                if (parameterType.isInstance(arg)) {
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                return false;
            }
        }

        return true;
    }

    private void requireArgumentsCompatible(Class<?> type, Constructor<?> constructor, Object... args) {
        if (!isConstructorCompatible(constructor, args)) {
            throw new IllegalArgumentException("Annotated constructor for " + type.getName()
                    + " does not accept the provided arguments.");
        }
    }

    private int getClassDistance(Class<?> expected, Class<?> actual) {
        if (expected.equals(actual)) {
            return 0;
        } else if (!expected.isAssignableFrom(actual)) {
            return -1;
        }

        int distance = 0;
        while (actual != null && !actual.equals(expected)) {
            actual = actual.getSuperclass();
            distance++;
        }

        return distance;
    }

    private @NotNull Object[] getConstructorArguments(@NotNull Constructor<?> constructor, Object... provided) {
        return Arrays.stream(constructor.getParameters())
                .map(parameter -> {
                    for (Object arg : provided) {
                        if (parameter.getType().isInstance(arg)) {
                            return arg;
                        }
                    }

                    Annotation[] annotations = parameter.getAnnotations();
                    boolean optional = InjectionUtils.isOptional(annotations);

                    if (optional && parameter.getType().isPrimitive()) {
                        throw new IllegalArgumentException("Optional constructor parameter " + parameter.getName()
                                + " in " + constructor.getDeclaringClass().getName()
                                + " cannot target primitive type " + parameter.getType().getName());
                    }

                    Object value = provide(parameter.getType(), new Context<>(constructor.getDeclaringClass(), this, this,
                            ElementType.CONSTRUCTOR, parameter.getName(), annotations));

                    if (optional && value == null) {
                        return null;
                    }

                    return value;
                })
                .toArray();
    }

    private void injectVariables(Object object) {
        getInjectionPlan(object.getClass())
                .getInjectableFields()
                .forEach(field -> {
                    try {
                        field.setAccessible(true);
                        Annotation[] annotations = field.getAnnotations();
                        boolean optional = InjectionUtils.isOptional(annotations);
                        Object value = provide(field.getType(), new Context<>(object.getClass(), object, this,
                                ElementType.FIELD, field.getName(), annotations));

                        if (value == null && optional) {
                            if (field.getType().isPrimitive()) {
                                return;
                            }

                            field.set(object, null);
                            return;
                        }

                        field.set(object, value);
                    } catch (Exception e) {
                        System.err.println(
                                "Failed to inject field " + field.getName() + " in " + object.getClass().getName());
                        throw new RuntimeException(e);
                    }
                });
    }

    private void injectMethods(Object object) {
        getInjectionPlan(object.getClass())
                .getInjectableMethods()
                .forEach(method -> {
                    try {
                        injectMethod(object, method);
                    } catch (Exception e) {
                        System.err.println("Failed to inject method " + method.getName() + " in "
                                + object.getClass().getName());
                        throw new RuntimeException(e);
                    }
                });
    }

    private void preDestroy(Object object) {
        getInjectionPlan(object.getClass())
                .getPreDestroyMethods()
                .forEach(method -> {
                    try {
                        injectMethod(object, method);
                    } catch (Exception e) {
                        System.err.println("Failed to call pre-destroy method " + method.getName() + " in "
                                + object.getClass().getName());
                        throw new RuntimeException(e);
                    }
                });
    }

    private void postInject(Object object) {
        getInjectionPlan(object.getClass())
                .getPostInjectMethods()
                .forEach(method -> {
                    try {
                        injectMethod(object, method);
                    } catch (Exception e) {
                        System.err.println("Failed to call post-inject method " + method.getName() + " in "
                                + object.getClass().getName());
                        throw new RuntimeException(e);
                    }
                });
    }

    private void postConstruct(Object object) {
        getInjectionPlan(object.getClass())
                .getPostConstructMethods()
                .forEach(method -> {
                    try {
                        injectMethod(object, method);
                    } catch (Exception e) {
                        System.err.println("Failed to call post-construct method " + method.getName() + " in "
                                + object.getClass().getName());
                        throw new RuntimeException(e);
                    }
                });
    }

    private void injectMethod(Object object, Method method) {
        try {
            method.setAccessible(true);
            method.invoke(object, getMethodArguments(method));
        } catch (Exception e) {
            System.err.println("Failed to inject method " + method.getName() + " in " + object.getClass().getName());
            throw new RuntimeException(e);
        }
    }

    private InjectionPlan getInjectionPlan(Class<?> clazz) {
        return injectionPlans.computeIfAbsent(clazz, InjectionPlan::new);
    }

    private @NotNull Object[] getMethodArguments(@NotNull Method method) {
        return Arrays.stream(method.getParameters())
                .map(parameter -> {
                    Annotation[] annotations = parameter.getAnnotations();
                    boolean optional = InjectionUtils.isOptional(annotations);

                    if (optional && parameter.getType().isPrimitive()) {
                        throw new IllegalArgumentException("Optional method parameter " + parameter.getName()
                                + " in " + method.getDeclaringClass().getName()
                                + " cannot target primitive type " + parameter.getType().getName());
                    }

                    Object value = provide(parameter.getType(),
                            new Context<>(method.getDeclaringClass(), this, this, ElementType.METHOD, parameter.getName(),
                                    annotations));

                    if (optional && value == null) {
                        return null;
                    }

                    return value;
                })
                .toArray();
    }

    private static final class ResolutionScopes {

        private final Deque<ResolutionScope> stack = new ArrayDeque<>();
        private final Map<Class<?>, Integer> inProgress = new HashMap<>();
        private final Deque<Class<?>> path = new ArrayDeque<>();

        private ResolutionScopes(InfuseInjector root) {
            ResolutionScope scope = ResolutionScope.root(root);
            scope.store(root.getClass(), root);
            scope.store(Injector.class, root);
            stack.push(scope);
        }

        private ResolutionScopeHandle enter(Object owner) {
            ResolutionScope current = stack.peek();

            if (current != null && current.isOwner(owner)) {
                current.retain();
                return new ResolutionScopeHandle(current, false);
            }

            ResolutionScope scope = ResolutionScope.object(owner);
            scope.store(owner.getClass(), owner);
            stack.push(scope);

            return new ResolutionScopeHandle(scope, true);
        }

        private void exit(ResolutionScopeHandle handle) {
            ResolutionScope scope = handle.scope;

            if (scope.isRoot()) {
                return;
            }

            if (!handle.newScope) {
                scope.release();
                return;
            }

            if (stack.peek() != scope) {
                throw new IllegalStateException("Scope mismatch while exiting dependency scope");
            }

            if (scope.release()) {
                stack.pop();
            }
        }

        private Object lookup(Class<?> type) {
            for (ResolutionScope scope : stack) {
                Object candidate = scope.lookup(type);

                if (candidate != null) {
                    return candidate;
                }
            }

            return null;
        }

        private ProvisionFrame begin(Class<?> type) {
            path.push(type);

            int depth = inProgress.getOrDefault(type, 0) + 1;
            inProgress.put(type, depth);

            if (depth > 1) {
                throwCycle(type);
            }

            return new ProvisionFrame(stack.peek(), type);
        }

        private void end(ProvisionFrame frame) {
            Class<?> type = frame.type;
            int depth = inProgress.getOrDefault(type, 0);

            if (depth <= 1) {
                inProgress.remove(type);
            } else {
                inProgress.put(type, depth - 1);
            }

            Class<?> finished = path.pop();

            if (finished != type) {
                throw new IllegalStateException("Provision stack mismatch for " + type.getName());
            }
        }

        private void record(Class<?> requestedType, @Nullable Object instance) {
            if (instance == null) {
                return;
            }

            ResolutionScope scope = stack.peek();

            if (scope == null) {
                throw new IllegalStateException("No active scope while recording instance for " + requestedType.getName());
            }

            scope.store(requestedType, instance);
            scope.store(instance.getClass(), instance);
        }

        private void throwCycle(Class<?> type) {
            List<Class<?>> snapshot = new ArrayList<>(path);
            StringBuilder builder = new StringBuilder();

            for (int i = snapshot.size() - 1; i >= 0; i--) {
                if (builder.length() > 0) {
                    builder.append(" -> ");
                }

                builder.append(snapshot.get(i).getName());
            }

            throw new IllegalStateException("Dependency cycle detected while resolving " + type.getName() + ": "
                    + builder);
        }
    }

    private static final class ProvisionFrame {
        private final ResolutionScope scope;
        private final Class<?> type;

        private ProvisionFrame(ResolutionScope scope, Class<?> type) {
            this.scope = scope;
            this.type = type;
        }
    }

    private static final class ResolutionScopeHandle {
        private final ResolutionScope scope;
        private final boolean newScope;

        private ResolutionScopeHandle(ResolutionScope scope, boolean newScope) {
            this.scope = scope;
            this.newScope = newScope;
        }
    }

    private static final class ResolutionScope {
        private final Object owner;
        private final boolean root;
        private final Map<Class<?>, Object> instances = new LinkedHashMap<>();
        private int depth;

        private ResolutionScope(Object owner, boolean root) {
            this.owner = owner;
            this.root = root;
            this.depth = root ? Integer.MAX_VALUE : 1;
        }

        private static ResolutionScope root(Object owner) {
            return new ResolutionScope(owner, true);
        }

        private static ResolutionScope object(Object owner) {
            return new ResolutionScope(owner, false);
        }

        private boolean isRoot() {
            return root;
        }

        private boolean isOwner(Object candidate) {
            return owner == candidate;
        }

        private void retain() {
            if (root) {
                return;
            }

            depth++;
        }

        private boolean release() {
            if (root) {
                return false;
            }

            depth--;

            if (depth < 0) {
                throw new IllegalStateException("Scope depth became negative for " + owner.getClass().getName());
            }

            return depth == 0;
        }

        private void store(Class<?> type, Object instance) {
            instances.put(type, instance);
        }

        private Object lookup(Class<?> type) {
            Object direct = instances.get(type);

            if (direct != null && type.isInstance(direct)) {
                return direct;
            }

            for (Object candidate : instances.values()) {
                if (candidate != null && type.isInstance(candidate)) {
                    return candidate;
                }
            }

            return null;
        }
    }

    private static class InjectionPlan {
        private final List<Field> injectableFields;
        private final List<Method> injectableMethods;
        private final List<Method> postConstructMethods;
        private final List<Method> preDestroyMethods;
        private final List<Method> postInjectMethods;

        public InjectionPlan(Class<?> clazz) {
            this.injectableFields = new ArrayList<>();
            this.injectableMethods = new ArrayList<>();
            this.postConstructMethods = new ArrayList<>();
            this.preDestroyMethods = new ArrayList<>();
            this.postInjectMethods = new ArrayList<>();

            for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (field.isAnnotationPresent(Inject.class)) {
                        injectableFields.add(field);
                    }
                }

                for (Method method : current.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Inject.class)) {
                        injectableMethods.add(method);
                    } else if (method.isAnnotationPresent(PostConstruct.class)) {
                        postConstructMethods.add(method);
                    } else if (method.isAnnotationPresent(PreDestroy.class)) {
                        preDestroyMethods.add(method);
                    } else if (method.isAnnotationPresent(PostInject.class)) {
                        postInjectMethods.add(method);
                    }
                }
            }

            postConstructMethods.sort(Comparator.comparingInt(m -> m.getAnnotation(PostConstruct.class).priority()));
            postInjectMethods.sort(Comparator.comparingInt(m -> m.getAnnotation(PostInject.class).priority()));
        }

        public List<Field> getInjectableFields() {
            return injectableFields;
        }

        public List<Method> getInjectableMethods() {
            return injectableMethods;
        }

        public List<Method> getPostConstructMethods() {
            return postConstructMethods;
        }

        public List<Method> getPreDestroyMethods() {
            return preDestroyMethods;
        }

        public List<Method> getPostInjectMethods() {
            return postInjectMethods;
        }
    }

}
