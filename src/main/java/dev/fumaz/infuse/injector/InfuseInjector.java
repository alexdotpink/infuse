package dev.fumaz.infuse.injector;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import dev.fumaz.infuse.annotation.Inject;
import dev.fumaz.infuse.annotation.PostConstruct;
import dev.fumaz.infuse.annotation.PostInject;
import dev.fumaz.infuse.annotation.PreDestroy;
import dev.fumaz.infuse.bind.Binding;
import dev.fumaz.infuse.bind.BindingKey;
import dev.fumaz.infuse.bind.BindingQualifier;
import dev.fumaz.infuse.bind.BindingRegistry;
import dev.fumaz.infuse.bind.BindingScope;
import dev.fumaz.infuse.exception.ConfigurationException;
import dev.fumaz.infuse.exception.ProvisionException;
import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.context.ContextView;
import dev.fumaz.infuse.module.Module;
import dev.fumaz.infuse.provider.InstanceProvider;
import dev.fumaz.infuse.provider.Provider;
import dev.fumaz.infuse.provider.SingletonProvider;
import dev.fumaz.infuse.scope.MemoizingProvider;
import dev.fumaz.infuse.scope.ScopeProviders;
import dev.fumaz.infuse.util.InjectionUtils;

public class InfuseInjector implements Injector {

    private static final int NEGATIVE_BINDING_CACHE_LIMIT = 256;
    private static final MethodHandles.Lookup ROOT_LOOKUP = MethodHandles.lookup();
    private static final ConcurrentMap<Class<?>, MethodHandles.Lookup> PRIVATE_LOOKUPS = new ConcurrentHashMap<>();
    private static final ClassValue<InjectionPlan> INJECTION_PLANS = new ClassValue<>() {
        @Override
        protected InjectionPlan computeValue(Class<?> clazz) {
            return new InjectionPlan(clazz);
        }
    };

    private static MethodHandles.Lookup lookupFor(Class<?> type) {
        return PRIVATE_LOOKUPS.computeIfAbsent(type, InfuseInjector::createLookupFor);
    }

    private static MethodHandles.Lookup createLookupFor(Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(type, ROOT_LOOKUP);
        } catch (IllegalAccessException | RuntimeException e) {
            return ROOT_LOOKUP;
        }
    }

    private final @Nullable Injector parent;
    private final @NotNull List<Module> modules;
    private final @NotNull ConcurrentMap<Class<?>, ConstructorCache> constructorCaches;
    private final @NotNull ConcurrentMap<Constructor<?>, ConstructorArgumentPlan> constructorArgumentPlans;
    private final @NotNull ConcurrentMap<Constructor<?>, MethodHandle> constructorInvokers;
    private final @NotNull BindingRegistry bindingRegistry;
    private final @NotNull ConcurrentMap<BindingQueryKey, List<Binding<?>>> bindingViewCache;
    private final @NotNull Set<NegativeBindingKey> negativeBindingCache;
    private final @NotNull ConcurrentLinkedQueue<NegativeBindingKey> negativeBindingOrder;
    private final @NotNull List<Binding<?>> ownBindings;
    private final @NotNull ScopedInstanceRegistry scopedInstances;
    private final @NotNull ResolutionScopes resolutionScopes;
    private final boolean customLoggerBinding;

    public InfuseInjector(@Nullable Injector parent, @NotNull List<Module> modules) {
        this.parent = parent;
        this.modules = modules;
        this.constructorCaches = new ConcurrentHashMap<>();
        this.constructorArgumentPlans = new ConcurrentHashMap<>();
        this.constructorInvokers = new ConcurrentHashMap<>();
        this.bindingRegistry = new BindingRegistry();
        this.bindingViewCache = new ConcurrentHashMap<>();
        this.negativeBindingCache = ConcurrentHashMap.newKeySet();
        this.negativeBindingOrder = new ConcurrentLinkedQueue<>();
        this.ownBindings = new ArrayList<>();
        this.scopedInstances = new ScopedInstanceRegistry();
        this.resolutionScopes = new ResolutionScopes(this);

        for (Module module : modules) {
            module.reset();
            module.configure();

            registerBindings(module.getBindings());
        }

        registerBinding(new Binding<>(Injector.class, new InstanceProvider<>(this),
                BindingQualifier.none(), BindingScope.INSTANCE, false));

        this.customLoggerBinding = !bindingRegistry
                .find(Logger.class, BindingQualifier.none(), BindingScope.ANY)
                .isEmpty();

        if (!customLoggerBinding) {
            registerBinding(new Binding<>(Logger.class,
                    (context) -> Logger.getLogger(context.getType().getSimpleName()),
                    BindingQualifier.none(), BindingScope.UNSCOPED, false));
        }

        List<EagerInstanceRecord> eagerSingletons = new ArrayList<>();
        EagerProvisionContext eagerProvision = new EagerProvisionContext(getClass(), this, this);
        try {
            for (Binding<?> binding : getOwnBindings()) {
                Provider<?> provider = binding.getProvider();
                Object instance = null;

                if (ScopeProviders.isEager(binding.getScope())) {
                    instance = eagerInstantiate(binding, eagerProvision);
                } else if (provider instanceof InstanceProvider) {
                    instance = ((InstanceProvider<?>) provider).provideWithoutInjecting(eagerProvision.view());
                }

                if (instance != null) {
                    InjectionPlan plan = getInjectionPlan(instance.getClass());
                    eagerSingletons.add(new EagerInstanceRecord(binding, instance, plan));
                }
            }
        } finally {
            eagerProvision.release();
        }

        List<PostInjectInvocation> postInjectInvocations = new ArrayList<>();
        for (EagerInstanceRecord eagerSingleton : eagerSingletons) {
            Object singleton = eagerSingleton.instance();
            InjectionPlan plan = eagerSingleton.plan();
            ResolutionScopeHandle scope = resolutionScopes.enter(singleton);

            try {
                injectInjectionPoints(singleton);
                postConstruct(singleton);
            } finally {
                resolutionScopes.exit(scope);
            }

            for (MethodInjectionPoint point : plan.getPostInjectMethods()) {
                postInjectInvocations.add(new PostInjectInvocation(singleton, point));
            }
        }

        postInjectInvocations.sort(Comparator.comparingInt(PostInjectInvocation::priority));

        for (PostInjectInvocation invocation : postInjectInvocations) {
            invokeMethod(invocation.target(), invocation.point(), "Failed to eagerly inject method ");
        }

        for (EagerInstanceRecord eagerSingleton : eagerSingletons) {
            recordScopedInstance(eagerSingleton.binding(), eagerSingleton.instance());
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
        postConstruct(object);
        postInject(object);
    }

    private void registerBinding(@NotNull Binding<?> binding) {
        registerBindings(Collections.singletonList(binding));
    }

    private void registerBindings(@NotNull Collection<? extends Binding<?>> bindings) {
        if (bindings.isEmpty()) {
            return;
        }

        List<Binding<?>> scopedBindings = new ArrayList<>(bindings.size());

        for (Binding<?> binding : bindings) {
            scopedBindings.add(ScopeProviders.decorate(binding));
        }

        bindingRegistry.addAll(scopedBindings);
        ownBindings.addAll(scopedBindings);
        bindingViewCache.clear();
        clearNegativeLookupCache();
    }

    private void recordScopedInstance(@NotNull Binding<?> binding, @Nullable Object instance) {
        if (instance == null) {
            return;
        }

        BindingScope scope = binding.getScope();

        if (!ScopeProviders.shouldTrackForShutdown(scope)) {
            return;
        }

        if (ownBindings.contains(binding) || !(parent instanceof InfuseInjector)) {
            scopedInstances.record(binding, instance);
            return;
        }

        ((InfuseInjector) parent).recordScopedInstance(binding, instance);
    }

    private Object eagerInstantiate(@NotNull Binding<?> binding, @NotNull EagerProvisionContext eager) {
        Provider<?> provider = binding.getProvider();

        if (provider instanceof SingletonProvider && ((SingletonProvider<?>) provider).isEager()) {
            return ((SingletonProvider<?>) provider).provideWithoutInjecting(eager.view());
        }

        if (provider instanceof MemoizingProvider) {
            return ((MemoizingProvider<?>) provider).provideWithoutInjecting(this);
        }

        if (provider instanceof InstanceProvider) {
            return ((InstanceProvider<?>) provider).provideWithoutInjecting(eager.view());
        }

        if (provider instanceof Provider.ContextViewAware) {
            @SuppressWarnings("unchecked")
            Provider.ContextViewAware<Object> viewAware = (Provider.ContextViewAware<Object>) provider;
            return viewAware.provide(eager.view());
        }

        @SuppressWarnings("unchecked")
        Provider<Object> typed = (Provider<Object>) provider;
        return typed.provide(eager.context());
    }

    @SuppressWarnings("unchecked")
    private <T> List<Binding<T>> resolveBindings(@NotNull Class<T> type,
                                                 @NotNull BindingQualifier qualifier,
                                                 @NotNull BindingScope scope) {
        BindingQueryKey key = new BindingQueryKey(type, qualifier, scope);
        List<Binding<?>> view = getBindingView(key);

        if (view.isEmpty()) {
            return Collections.emptyList();
        }

        if (key.scope.isAny()) {
            forgetNegativeLookup(key.type, key.qualifier);
        }

        return (List<Binding<T>>) (List<?>) view;
    }

    private List<Binding<?>> getBindingView(@NotNull BindingQueryKey key) {
        return bindingViewCache.computeIfAbsent(key, this::computeBindingView);
    }

    @SuppressWarnings("unchecked")
    private List<Binding<?>> computeBindingView(@NotNull BindingQueryKey key) {
        List<Binding<?>> local = (List<Binding<?>>) (List<?>) bindingRegistry.find((Class<Object>) key.type,
                key.qualifier, key.scope);
        List<Binding<?>> parentView = computeParentBindingView(key);

        if (local.isEmpty()) {
            return parentView;
        }

        if (parentView.isEmpty()) {
            return local;
        }

        return new CompositeBindingList<>(local, parentView);
    }

    private List<Binding<?>> computeParentBindingView(@NotNull BindingQueryKey key) {
        if (parent == null) {
            return Collections.emptyList();
        }

        if (parent instanceof InfuseInjector) {
            return ((InfuseInjector) parent).getBindingView(key);
        }

        return filterParentBindings(parent.getBindings(), key);
    }

    private static List<Binding<?>> filterParentBindings(@NotNull List<Binding<?>> bindings,
                                                         @NotNull BindingQueryKey key) {
        if (bindings.isEmpty()) {
            return Collections.emptyList();
        }

        List<Binding<?>> matches = new ArrayList<>();

        for (Binding<?> binding : bindings) {
            BindingKey bindingKey = binding.getKey();

            if (!bindingKey.getType().equals(key.type)) {
                continue;
            }

            if (!bindingKey.getQualifier().equals(key.qualifier)) {
                continue;
            }

            if (!key.scope.isAny() && !bindingKey.getScope().equals(key.scope)) {
                continue;
            }

            matches.add(binding);
        }

        if (matches.isEmpty()) {
            return Collections.emptyList();
        }

        return Collections.unmodifiableList(matches);
    }

    private boolean isNegativeLookupCached(@NotNull Class<?> type, @NotNull BindingQualifier qualifier) {
        return negativeBindingCache.contains(new NegativeBindingKey(type, qualifier));
    }

    private void recordNegativeLookup(@NotNull Class<?> type, @NotNull BindingQualifier qualifier) {
        NegativeBindingKey key = new NegativeBindingKey(type, qualifier);

        if (negativeBindingCache.add(key)) {
            negativeBindingOrder.add(key);
            trimNegativeLookupCache();
        }
    }

    private void forgetNegativeLookup(@NotNull Class<?> type, @NotNull BindingQualifier qualifier) {
        NegativeBindingKey key = new NegativeBindingKey(type, qualifier);

        if (negativeBindingCache.remove(key)) {
            negativeBindingOrder.remove(key);
        }
    }

    private void trimNegativeLookupCache() {
        while (negativeBindingCache.size() > NEGATIVE_BINDING_CACHE_LIMIT) {
            NegativeBindingKey evicted = negativeBindingOrder.poll();

            if (evicted == null) {
                return;
            }

            negativeBindingCache.remove(evicted);
        }
    }

    private void clearNegativeLookupCache() {
        negativeBindingCache.clear();
        negativeBindingOrder.clear();
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
            BindingQualifier qualifier = InjectionUtils.resolveQualifier(context.getAnnotations());
            Object existing = resolutionScopes.lookup(type);

            if (existing != null) {
                if (resolutionScopes.isResolving(type, qualifier)) {
                    resolutionScopes.detectExistingCycle(type, qualifier, context);
                }

                return type.cast(existing);
            }

            if (optional && isNegativeLookupCached(type, qualifier)) {
                return null;
            }

            List<Binding<T>> matches = resolveBindings(type, qualifier, BindingScope.ANY);

            if (matches.isEmpty()) {
                if (optional) {
                    recordNegativeLookup(type, qualifier);
                    return null;
                }
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

            frame = resolutionScopes.begin(type, qualifier, context);

            Binding<T> binding = matches.isEmpty() ? null : matches.get(0);

            frame.attachBinding(binding);

            T instance = binding != null
                    ? binding.getProvider().provide(context)
                    : construct(type);

            if (instance == null) {
                if (optional) {
                    return null;
                }
            } else if (binding != null) {
                recordScopedInstance(binding, instance);
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
        Context<?> context = Context.borrow(calling.getClass(), calling, this, ElementType.FIELD, "field",
                new Annotation[0]);
        try {
            return provide(type, context);
        } finally {
            context.release();
        }
    }

    @Override
    public <T> T construct(@NotNull Class<T> type, @NotNull Object... args) {
        Constructor<T> constructor = resolveConstructor(type, args);

        try {
            Object[] resolved = getConstructorArguments(constructor, args);
            @SuppressWarnings("unchecked")
            T t = (T) instantiateConstructor(constructor, resolved);

            ResolutionScopeHandle scope = resolutionScopes.enter(t);

            try {
                injectWithinScope(t);
            } finally {
                resolutionScopes.exit(scope);
            }

            return t;
        } catch (Throwable throwable) {
            if (throwable instanceof Error) {
                throw (Error) throwable;
            }

            Throwable cause = unwrapConstructorThrowable(throwable);
            System.err.println("Failed to construct " + type.getName());
            cause.printStackTrace();
            throw new ProvisionException("Failed to construct " + type.getName(), cause);
        }
    }

    public <T> T constructWithoutInjecting(@NotNull Class<T> type, @NotNull Object... args) {
        Constructor<T> constructor = resolveConstructor(type, args);

        try {
            Object[] resolved = getConstructorArguments(constructor, args);
            @SuppressWarnings("unchecked")
            T t = (T) instantiateConstructor(constructor, resolved);
            return t;
        } catch (Throwable throwable) {
            if (throwable instanceof Error) {
                throw (Error) throwable;
            }

            Throwable cause = unwrapConstructorThrowable(throwable);
            System.err.println("Failed to construct without injecting " + type.getName());
            cause.printStackTrace();
            throw new ProvisionException("Failed to construct without injecting " + type.getName(), cause);
        }
    }

    public <T> T construct(@NotNull Constructor<T> constructor) {
        try {
            Object[] resolved = getConstructorArguments(constructor);
            T instance = instantiateConstructor(constructor, resolved);

            ResolutionScopeHandle scope = resolutionScopes.enter(instance);

            try {
                injectWithinScope(instance);
            } finally {
                resolutionScopes.exit(scope);
            }

            return instance;
        } catch (Throwable throwable) {
            if (throwable instanceof Error) {
                throw (Error) throwable;
            }

            Throwable cause = unwrapConstructorThrowable(throwable);
            System.err.println("Failed to construct via constructor " + constructor.toGenericString());
            cause.printStackTrace();
            throw new ProvisionException("Failed to construct via constructor " + constructor.toGenericString(), cause);
        }
    }

    @Override
    public void destroy() {
        ScopeProviders.shutdown(this);

        List<ScopedInstanceEntry> recorded = scopedInstances.drain();
        Collections.reverse(recorded);

        for (ScopedInstanceEntry entry : recorded) {
            preDestroy(entry.instance);
        }

        if (parent != null) {
            parent.destroy();
        }
    }

    public void invokePreDestroy(@NotNull Object instance) {
        preDestroy(instance);
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
            throw new ConfigurationException("No binding found for " + type.getName());
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

    public <T> Constructor<T> findSuitableConstructor(Class<T> clazz, Object... args) {
        ConstructorCache cache = constructorCaches.computeIfAbsent(clazz, ConstructorCache::new);
        return cache.findSuitableConstructor(this, args);
    }

    private <T> Constructor<T> resolveConstructor(Class<T> type, Object... args) {
        ConstructorCache cache = constructorCaches.computeIfAbsent(type, ConstructorCache::new);
        return cache.resolve(this, args);
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
        ConstructorArgumentPlan plan = constructorArgumentPlans.computeIfAbsent(constructor, this::buildConstructorArgumentPlan);
        return plan.resolve(this, provided);
    }

    private ConstructorArgumentPlan buildConstructorArgumentPlan(@NotNull Constructor<?> constructor) {
        return ConstructorArgumentPlan.create(this, constructor);
    }

    private <T> T instantiateConstructor(@NotNull Constructor<T> constructor, @NotNull Object[] arguments) throws Throwable {
        MethodHandle invoker = constructorInvokers.computeIfAbsent(constructor, this::createConstructorInvoker);
        @SuppressWarnings("unchecked")
        T instance = (T) invoker.invoke(arguments);
        return instance;
    }

    private MethodHandle createConstructorInvoker(@NotNull Constructor<?> constructor) {
        constructor.setAccessible(true);

        MethodHandle handle = unreflectConstructor(constructor);
        handle = handle.asSpreader(Object[].class, constructor.getParameterCount());
        return handle.asType(MethodType.methodType(Object.class, Object[].class));
    }

    private MethodHandle unreflectConstructor(@NotNull Constructor<?> constructor) {
        try {
            return lookupFor(constructor.getDeclaringClass()).unreflectConstructor(constructor);
        } catch (IllegalAccessException firstFailure) {
            try {
                return ROOT_LOOKUP.unreflectConstructor(constructor);
            } catch (IllegalAccessException secondFailure) {
                IllegalStateException exception = new IllegalStateException(
                        "Unable to access constructor handle for " + constructor, secondFailure);
                exception.addSuppressed(firstFailure);
                throw exception;
            }
        }
    }

    private Throwable unwrapConstructorThrowable(@NotNull Throwable throwable) {
        if (throwable instanceof InvocationTargetException) {
            Throwable target = ((InvocationTargetException) throwable).getTargetException();
            return target != null ? target : throwable;
        }

        return throwable;
    }

    private void injectVariables(Object object) {
        InjectionPlan plan = getInjectionPlan(object.getClass());

        for (FieldInjectionPoint point : plan.getInjectableFields()) {
            Field field = point.field();

            try {
                @SuppressWarnings("unchecked")
                Class<Object> contextType = (Class<Object>) object.getClass();
                Context<?> fieldContext = Context.borrow(contextType, object, this, ElementType.FIELD,
                        field.getName(), point.annotations());

                try {
                    Object value = provide(point.type(), fieldContext);

                    if (value == null && point.optional()) {
                        if (point.primitive()) {
                            continue;
                        }

                        point.set(object, null);
                        continue;
                    }

                    point.set(object, value);
                } finally {
                    fieldContext.release();
                }
            } catch (Throwable e) {
                String message = "Failed to inject field " + field.getName() + " in "
                        + object.getClass().getName();
                System.err.println(message);
                throw new ProvisionException(message, e);
            }
        }
    }

    private void injectMethods(Object object) {
        InjectionPlan plan = getInjectionPlan(object.getClass());

        for (MethodInjectionPoint point : plan.getInjectableMethods()) {
            invokeMethod(object, point, "Failed to inject method ");
        }
    }

    private void preDestroy(Object object) {
        InjectionPlan plan = getInjectionPlan(object.getClass());

        for (MethodInjectionPoint point : plan.getPreDestroyMethods()) {
            invokeMethod(object, point, "Failed to call pre-destroy method ");
        }
    }

    private void postInject(Object object) {
        InjectionPlan plan = getInjectionPlan(object.getClass());

        for (MethodInjectionPoint point : plan.getPostInjectMethods()) {
            invokeMethod(object, point, "Failed to call post-inject method ");
        }
    }

    private void postConstruct(Object object) {
        InjectionPlan plan = getInjectionPlan(object.getClass());

        for (MethodInjectionPoint point : plan.getPostConstructMethods()) {
            invokeMethod(object, point, "Failed to call post-construct method ");
        }
    }

    private void invokeMethod(Object object, MethodInjectionPoint point, String failurePrefix) {
        Method method = point.method();

        try {
            Object[] arguments = point.resolveArguments(this);
            point.invoke(object, arguments);
        } catch (Throwable e) {
            String message = failurePrefix + method.getName() + " in " + object.getClass().getName();
            System.err.println(message);
            throw new ProvisionException(message, e);
        }
    }

    private InjectionPlan getInjectionPlan(Class<?> clazz) {
        return INJECTION_PLANS.get(clazz);
    }

    private static final class ResolutionScopes {

        private final ThreadLocal<ResolutionScopeState> state;

        private ResolutionScopes(InfuseInjector root) {
            this.state = ThreadLocal.withInitial(() -> ResolutionScopeState.create(root));
        }

        private ResolutionScopeHandle enter(Object owner) {
            ResolutionScopeState state = currentState();
            ResolutionScope current = state.stack.peek();

            if (current != null && current.isOwner(owner)) {
                current.retain();
                return new ResolutionScopeHandle(state, current, false, null);
            }

            ResolutionScope scope = ResolutionScope.object(owner);
            scope.store(owner.getClass(), owner);
            ResolutionRequest ownerRequest = registerOwner(state, owner);
            state.stack.push(scope);

            return new ResolutionScopeHandle(state, scope, true, ownerRequest);
        }

        private void exit(ResolutionScopeHandle handle) {
            ResolutionScopeState state = handle.state;
            ResolutionScope scope = handle.scope;

            if (scope.isRoot()) {
                return;
            }

            if (!handle.newScope) {
                scope.release();
                return;
            }

            Deque<ResolutionScope> stack = state.stack;

            if (stack.peek() != scope) {
                throw new IllegalStateException("Scope mismatch while exiting dependency scope");
            }

            if (scope.release()) {
                stack.pop();
            }

            if (handle.ownerRequest != null) {
                unregisterOwner(state, handle.ownerRequest);
            }
        }

        private Object lookup(Class<?> type) {
            ResolutionScopeState state = currentState();

            for (ResolutionScope scope : state.stack) {
                Object candidate = scope.lookup(type);

                if (candidate != null) {
                    return candidate;
                }
            }

            return null;
        }

        private ProvisionFrame begin(Class<?> type, BindingQualifier qualifier, Context<?> context) {
            ResolutionScopeState state = currentState();
            ResolutionRequest request = new ResolutionRequest(type, qualifier, context);
            ResolutionKey key = request.key();
            int depth = state.inProgress.getOrDefault(key, 0);

            if (depth > 0) {
                throwCycle(state, request);
            }

            state.path.push(request);
            state.inProgress.put(key, depth + 1);

            return new ProvisionFrame(request);
        }

        private void end(ProvisionFrame frame) {
            ResolutionScopeState state = currentState();
            ResolutionRequest request = frame.request();
            ResolutionKey key = request.key();
            int depth = state.inProgress.getOrDefault(key, 0);

            if (depth <= 1) {
                state.inProgress.remove(key);
            } else {
                state.inProgress.put(key, depth - 1);
            }

            ResolutionRequest finished = state.path.pop();

            if (finished != request) {
                throw new IllegalStateException("Provision stack mismatch for " + request.describeType());
            }
        }

        private void record(Class<?> requestedType, @Nullable Object instance) {
            if (instance == null) {
                return;
            }

            ResolutionScopeState state = currentState();
            ResolutionScope scope = state.stack.peek();

            if (scope == null) {
                throw new IllegalStateException("No active scope while recording instance for " + requestedType.getName());
            }

            scope.store(requestedType, instance);
            scope.store(instance.getClass(), instance);
        }

        private ResolutionScopeState currentState() {
            return state.get();
        }

        private void throwCycle(ResolutionScopeState state, ResolutionRequest request) {
            List<ResolutionRequest> ordered = new ArrayList<>(state.path);
            Collections.reverse(ordered);
            ordered.add(request);

            ResolutionKey key = request.key();
            int startIndex = -1;

            for (int i = 0; i < ordered.size() - 1; i++) {
                if (ordered.get(i).key().equals(key)) {
                    startIndex = i;
                    break;
                }
            }

            if (startIndex == -1) {
                startIndex = ordered.size() - 1;
            }

            List<ResolutionRequest> cycle = ordered.subList(startIndex, ordered.size());
            String lineSeparator = System.lineSeparator();
            StringBuilder builder = new StringBuilder();

            builder.append("Dependency cycle detected while resolving ")
                    .append(request.describeType())
                    .append(lineSeparator)
                    .append("Cycle path:");

            for (ResolutionRequest step : cycle) {
                builder.append(lineSeparator).append(" - ").append(step.describe());
            }

            throw new IllegalStateException(builder.toString());
        }

        boolean isResolving(Class<?> type, BindingQualifier qualifier) {
            ResolutionScopeState state = currentState();
            return state.inProgress.containsKey(new ResolutionKey(type, qualifier));
        }

        void detectExistingCycle(Class<?> type, BindingQualifier qualifier, Context<?> context) {
            ResolutionScopeState state = currentState();
            ResolutionRequest request = new ResolutionRequest(type, qualifier, context);
            throwCycle(state, request);
        }

        private ResolutionRequest registerOwner(ResolutionScopeState state, Object owner) {
            if (owner == null) {
                return null;
            }

            Class<?> ownerType = owner.getClass();
            BindingQualifier qualifier = BindingQualifier.none();

            ResolutionRequest current = state.path.peek();
            if (current != null && current.matches(ownerType, qualifier)) {
                return null;
            }

            ResolutionRequest request = new ResolutionRequest(ownerType, qualifier, null);
            state.path.push(request);
            state.inProgress.merge(request.key(), 1, Integer::sum);
            return request;
        }

        private void unregisterOwner(ResolutionScopeState state, ResolutionRequest request) {
            if (request == null) {
                return;
            }

            ResolutionKey key = request.key();
            int depth = state.inProgress.getOrDefault(key, 0);

            if (depth <= 1) {
                state.inProgress.remove(key);
            } else {
                state.inProgress.put(key, depth - 1);
            }

            ResolutionRequest finished = state.path.pop();
            if (finished != request) {
                throw new IllegalStateException("Provision stack mismatch while exiting " + request.describeType());
            }
        }

        private static final class ResolutionScopeState {
            private final Deque<ResolutionScope> stack;
            private final Map<ResolutionKey, Integer> inProgress;
            private final Deque<ResolutionRequest> path;

            private ResolutionScopeState(Deque<ResolutionScope> stack,
                                         Map<ResolutionKey, Integer> inProgress,
                                         Deque<ResolutionRequest> path) {
                this.stack = stack;
                this.inProgress = inProgress;
                this.path = path;
            }

            private static ResolutionScopeState create(InfuseInjector root) {
                Deque<ResolutionScope> stack = new ArrayDeque<>();
                ResolutionScope scope = ResolutionScope.root(root);
                scope.store(root.getClass(), root);
                scope.store(Injector.class, root);
                stack.push(scope);

                return new ResolutionScopeState(stack, new HashMap<>(), new ArrayDeque<>());
            }
        }

        private static final class ResolutionKey {
            private final Class<?> type;
            private final BindingQualifier qualifier;

            private ResolutionKey(Class<?> type, BindingQualifier qualifier) {
                this.type = type;
                this.qualifier = qualifier;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }

                if (!(o instanceof ResolutionKey)) {
                    return false;
                }

                ResolutionKey that = (ResolutionKey) o;
                return Objects.equals(type, that.type) && Objects.equals(qualifier, that.qualifier);
            }

            @Override
            public int hashCode() {
                return Objects.hash(type, qualifier);
            }
        }

        private static final class ResolutionRequest {
            private final Class<?> type;
            private final BindingQualifier qualifier;
            private final ResolutionKey key;
            private final RequestOrigin origin;
            private @Nullable Binding<?> binding;

            private ResolutionRequest(Class<?> type, BindingQualifier qualifier, @Nullable Context<?> context) {
                this.type = type;
                this.qualifier = qualifier;
                this.key = new ResolutionKey(type, qualifier);
                this.origin = context == null ? RequestOrigin.direct() : RequestOrigin.from(context);
            }

            private ResolutionKey key() {
                return key;
            }

            private void attachBinding(@Nullable Binding<?> binding) {
                this.binding = binding;
            }

            private boolean matches(Class<?> candidate, BindingQualifier qualifier) {
                return type.equals(candidate) && Objects.equals(this.qualifier, qualifier);
            }

            private String describeType() {
                if (qualifier == null || qualifier.isDefault()) {
                    return type.getName();
                }

                return type.getName() + " " + qualifier;
            }

            private String describe() {
                StringBuilder builder = new StringBuilder(describeType());

                if (binding != null) {
                    builder.append(" [scope=").append(binding.getScope()).append("]");

                    if (binding.isCollectionContribution()) {
                        builder.append(" [collection contribution]");
                    }
                } else {
                    builder.append(" [implicit construction]");
                }

                if (origin != null) {
                    builder.append(" requested at ").append(origin.describe());
                }

                return builder.toString();
            }
        }

        private static final class RequestOrigin {
            private final ElementType element;
            private final @Nullable Class<?> ownerType;
            private final @Nullable String memberName;

            private RequestOrigin(ElementType element, @Nullable Class<?> ownerType, @Nullable String memberName) {
                this.element = element;
                this.ownerType = ownerType;
                this.memberName = memberName;
            }

            private static RequestOrigin from(Context<?> context) {
                return new RequestOrigin(context.getElement(), context.getType(), context.getName());
            }

            private static RequestOrigin direct() {
                return new RequestOrigin(ElementType.TYPE, null, null);
            }

            private String describe() {
                String owner = ownerType != null ? ownerType.getName() : "direct injector request";

                switch (element) {
                    case FIELD:
                        return "field '" + memberName + "' of " + owner;
                    case METHOD:
                        return "method parameter '" + memberName + "' of " + owner;
                    case CONSTRUCTOR:
                        return "constructor parameter '" + memberName + "' of " + owner;
                    case PARAMETER:
                        return "parameter '" + memberName + "' of " + owner;
                    case TYPE:
                        return owner;
                    default:
                        return element.name().toLowerCase() + " '" + memberName + "' of " + owner;
                }
            }
        }
    }

    private static final class ProvisionFrame {
        private final ResolutionScopes.ResolutionRequest request;

        private ProvisionFrame(ResolutionScopes.ResolutionRequest request) {
            this.request = request;
        }

        private void attachBinding(@Nullable Binding<?> binding) {
            request.attachBinding(binding);
        }

        private ResolutionScopes.ResolutionRequest request() {
            return request;
        }
    }

        private static final class ResolutionScopeHandle {
            private final ResolutionScopes.ResolutionScopeState state;
            private final ResolutionScope scope;
            private final boolean newScope;
            private final @Nullable ResolutionScopes.ResolutionRequest ownerRequest;

        private ResolutionScopeHandle(ResolutionScopes.ResolutionScopeState state,
                                      ResolutionScope scope,
                                      boolean newScope,
                                      @Nullable ResolutionScopes.ResolutionRequest ownerRequest) {
            this.state = state;
            this.scope = scope;
            this.newScope = newScope;
            this.ownerRequest = ownerRequest;
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

    private static final class EagerProvisionContext {
        private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];

        private final Class<?> type;
        private final Object object;
        private final Injector injector;
        private final ContextView<Object> view;
        private Context<?> context;

        private EagerProvisionContext(Class<?> type, Object object, Injector injector) {
            this.type = Objects.requireNonNull(type, "type");
            this.object = Objects.requireNonNull(object, "object");
            this.injector = Objects.requireNonNull(injector, "injector");
            @SuppressWarnings("unchecked")
            Class<Object> viewType = (Class<Object>) this.type;
            this.view = ContextView.of(viewType, this.object, this.injector, ElementType.FIELD, "eager",
                    NO_ANNOTATIONS);
        }

        private ContextView<?> view() {
            return view;
        }

        private Context<?> context() {
            if (context == null) {
                @SuppressWarnings("unchecked")
                Class<Object> ctxType = (Class<Object>) type;
                context = Context.borrow(ctxType, object, injector, ElementType.FIELD, "eager", NO_ANNOTATIONS);
            }

            return context;
        }

        private void release() {
            if (context != null) {
                context.release();
                context = null;
            }
        }
    }

    private static final class EagerInstanceRecord {
        private final Binding<?> binding;
        private final Object instance;
        private final InjectionPlan plan;

        private EagerInstanceRecord(Binding<?> binding, Object instance, InjectionPlan plan) {
            this.binding = binding;
            this.instance = instance;
            this.plan = plan;
        }

        private Binding<?> binding() {
            return binding;
        }

        private Object instance() {
            return instance;
        }

        private InjectionPlan plan() {
            return plan;
        }
    }

    private static final class PostInjectInvocation {
        private final Object target;
        private final MethodInjectionPoint point;
        private final int priority;

        private PostInjectInvocation(Object target, MethodInjectionPoint point) {
            this.target = target;
            this.point = point;
            this.priority = point.priority();
        }

        private Object target() {
            return target;
        }

        private MethodInjectionPoint point() {
            return point;
        }

        private int priority() {
            return priority;
        }
    }

    private static final class ConstructorArgumentPlan {
        private final ConstructorParameter[] parameters;
        private final Context<?>[] contexts;
        private final ConcurrentMap<ConstructorArgumentsKey, int[]> assignmentCache;
        private final int[] defaultMapping;
        private final ConcurrentMap<Class<?>, int[]> parameterIndexCache;
        private final int[] nullCompatibleParameters;
        private static final Object[] NO_ARGUMENTS = new Object[0];
        private static final int[] EMPTY_INDICES = new int[0];

        private ConstructorArgumentPlan(ConstructorParameter[] parameters, Context<?>[] contexts) {
            this.parameters = parameters;
            this.contexts = contexts;
            this.assignmentCache = new ConcurrentHashMap<>();
            this.defaultMapping = initialiseDefaultMapping(parameters.length);
            this.parameterIndexCache = new ConcurrentHashMap<>();
            this.nullCompatibleParameters = resolveNullCompatibleParameters(parameters);
        }

        @SuppressWarnings("unchecked")
        private static ConstructorArgumentPlan create(InfuseInjector injector, Constructor<?> constructor) {
            Parameter[] reflectionParameters = constructor.getParameters();
            ConstructorParameter[] parameters = new ConstructorParameter[reflectionParameters.length];
            Context<?>[] contexts = new Context<?>[reflectionParameters.length];

            for (int i = 0; i < reflectionParameters.length; i++) {
                Parameter parameter = reflectionParameters[i];
                Annotation[] annotations = parameter.getAnnotations();
                boolean optional = InjectionUtils.isOptional(annotations);
                BindingQualifier qualifier = InjectionUtils.resolveQualifier(annotations);
                Class<?> parameterType = parameter.getType();
                Class<?> declaringType = constructor.getDeclaringClass();

                Function<InfuseInjector, Object> direct = null;

                if (parameterType == Injector.class && qualifier.isDefault()) {
                    direct = self -> self;
                } else if (parameterType == Logger.class
                        && qualifier.isDefault()
                        && !injector.customLoggerBinding) {
                    String loggerName = declaringType.getSimpleName();
                    direct = ignored -> Logger.getLogger(loggerName);
                }

                Context<?> context = null;

                if (direct == null) {
                    @SuppressWarnings("unchecked")
                    Class<Object> ctxType = (Class<Object>) declaringType;
                    context = Context.borrow(ctxType, injector, injector, ElementType.CONSTRUCTOR,
                            parameter.getName(), annotations).detach();
                }

                contexts[i] = context;
                parameters[i] = new ConstructorParameter(parameterType, parameter.getName(), optional,
                        direct, declaringType);
            }

            return new ConstructorArgumentPlan(parameters, contexts);
        }

        private Object[] resolve(InfuseInjector injector, Object[] provided) {
            if (parameters.length == 0) {
                return NO_ARGUMENTS;
            }

            Object[] resolved = new Object[parameters.length];

            int[] mapping;
            if (provided.length == 0) {
                mapping = defaultMapping;
            } else {
                ConstructorArgumentsKey key = new ConstructorArgumentsKey(provided);
                mapping = assignmentCache.computeIfAbsent(key, unused -> computeMapping(provided));
            }

            for (int i = 0; i < parameters.length; i++) {
                int providedIndex = mapping.length > i ? mapping[i] : -1;

                if (providedIndex >= 0 && providedIndex < provided.length) {
                    Object candidate = provided[providedIndex];

                    if (parameters[i].supports(candidate)) {
                        resolved[i] = candidate;
                        continue;
                    }
                }

                resolved[i] = parameters[i].resolve(injector, contexts[i]);
            }

            return resolved;
        }

        private int[] computeMapping(Object[] provided) {
            int[] mapping = new int[parameters.length];
            Arrays.fill(mapping, -1);

            for (int providedIndex = 0; providedIndex < provided.length; providedIndex++) {
                Object candidate = provided[providedIndex];

                int[] matchingParameters;
                if (candidate == null) {
                    matchingParameters = nullCompatibleParameters;
                } else {
                    Class<?> candidateClass = candidate.getClass();
                    matchingParameters = parameterIndexCache.computeIfAbsent(candidateClass,
                            this::resolveParameterIndexesFor);
                }

                if (matchingParameters.length == 0) {
                    continue;
                }

                for (int parameterIndex : matchingParameters) {
                    if (mapping[parameterIndex] < 0) {
                        mapping[parameterIndex] = providedIndex;
                    }
                }
            }

            return mapping;
        }

        private int[] resolveParameterIndexesFor(Class<?> candidateType) {
            int[] buffer = new int[parameters.length];
            int count = 0;

            for (int parameterIndex = 0; parameterIndex < parameters.length; parameterIndex++) {
                if (parameters[parameterIndex].supports(candidateType)) {
                    buffer[count++] = parameterIndex;
                }
            }

            if (count == 0) {
                return EMPTY_INDICES;
            }

            return Arrays.copyOf(buffer, count);
        }

        private static int[] resolveNullCompatibleParameters(ConstructorParameter[] parameters) {
            if (parameters.length == 0) {
                return EMPTY_INDICES;
            }

            int[] buffer = new int[parameters.length];
            int count = 0;

            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].supports((Class<?>) null)) {
                    buffer[count++] = i;
                }
            }

            return count == 0 ? EMPTY_INDICES : Arrays.copyOf(buffer, count);
        }

        private static int[] initialiseDefaultMapping(int length) {
            if (length == 0) {
                return EMPTY_INDICES;
            }

            int[] mapping = new int[length];
            Arrays.fill(mapping, -1);
            return mapping;
        }
    }

    private static final class ConstructorParameter {
        private final Class<?> type;
        private final boolean optional;
        private final boolean primitive;
        private final String name;
        private final Function<InfuseInjector, Object> direct;
        private final Class<?> declaringType;

        private ConstructorParameter(Class<?> type,
                                     String name,
                                     boolean optional,
                                     @Nullable Function<InfuseInjector, Object> direct,
                                     Class<?> declaringType) {
            this.type = type;
            this.optional = optional;
            this.primitive = type.isPrimitive();
            this.name = name;
            this.direct = direct;
            this.declaringType = declaringType;
        }

        private boolean supports(Object candidate) {
            return supports(candidate == null ? null : candidate.getClass());
        }

        private boolean supports(@Nullable Class<?> candidateType) {
            if (candidateType == null) {
                return !primitive;
            }

            return type.isAssignableFrom(candidateType);
        }

        private Object resolve(InfuseInjector injector, @Nullable Context<?> context) {
            if (optional && primitive) {
                throw new IllegalArgumentException("Optional constructor parameter " + name
                        + " in " + declaringType.getName()
                        + " cannot target primitive type " + type.getName());
            }

            Object value;

            if (direct != null) {
                value = direct.apply(injector);
            } else {
                Context<?> resolverContext = Objects.requireNonNull(context, "context");
                value = injector.provide(type, resolverContext);
            }

            if (optional && value == null) {
                return null;
            }

            return value;
        }
    }

    private static final class ConstructorArgumentsKey {
        private final Class<?>[] argumentTypes;
        private final int hash;

        private ConstructorArgumentsKey(Object[] args) {
            this.argumentTypes = new Class<?>[args.length];

            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                this.argumentTypes[i] = arg == null ? null : arg.getClass();
            }

            this.hash = Arrays.hashCode(argumentTypes);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (!(obj instanceof ConstructorArgumentsKey)) {
                return false;
            }

            ConstructorArgumentsKey other = (ConstructorArgumentsKey) obj;
            return Arrays.equals(argumentTypes, other.argumentTypes);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class ConstructorCache {
        private static final Constructor<?>[] EMPTY_CONSTRUCTORS = new Constructor<?>[0];

        private final Class<?> type;
        private final Constructor<?> injectable;
        private final Map<Integer, Constructor<?>[]> constructorsByArity;
        private final ConcurrentMap<ConstructorArgsKey, Constructor<?>> heuristicCache;

        private ConstructorCache(Class<?> type) {
            this.type = type;
            this.heuristicCache = new ConcurrentHashMap<>();

            Constructor<?>[] declaredConstructors = type.getDeclaredConstructors();
            Constructor<?> injectableCandidate = null;
            Constructor<?> zeroArgCandidate = null;
            Map<Integer, List<Constructor<?>>> groupedByArity = new HashMap<>();

            for (Constructor<?> constructor : declaredConstructors) {
                groupedByArity.computeIfAbsent(constructor.getParameterCount(), key -> new ArrayList<>())
                        .add(constructor);

                if (constructor.isAnnotationPresent(Inject.class)) {
                    if (injectableCandidate != null && injectableCandidate.isAnnotationPresent(Inject.class)) {
                        throw new IllegalArgumentException(
                                "Multiple injectable constructors found for type " + type);
                    }

                    injectableCandidate = constructor;
                }

                if (constructor.getParameterCount() == 0 && zeroArgCandidate == null) {
                    zeroArgCandidate = constructor;
                }
            }

            Constructor<?> selected = injectableCandidate;

            if (selected == null) {
                if (zeroArgCandidate != null) {
                    selected = zeroArgCandidate;
                } else if (declaredConstructors.length == 1) {
                    selected = declaredConstructors[0];
                }
            }

            this.injectable = selected;

            Map<Integer, Constructor<?>[]> arityMap = new HashMap<>(groupedByArity.size());
            for (Map.Entry<Integer, List<Constructor<?>>> entry : groupedByArity.entrySet()) {
                arityMap.put(entry.getKey(), entry.getValue().toArray(new Constructor<?>[0]));
            }

            this.constructorsByArity = arityMap;
        }

        @SuppressWarnings("unchecked")
        private <T> Constructor<T> resolve(InfuseInjector injector, Object... args) {
            Class<T> requestedType = (Class<T>) type;
            Constructor<?> candidate = injectable;

            if (candidate != null) {
                if (candidate.isAnnotationPresent(Inject.class)) {
                    injector.requireArgumentsCompatible(requestedType, candidate, args);
                    return (Constructor<T>) candidate;
                }

                if (args.length == 0 || injector.isConstructorCompatible(candidate, args)) {
                    return (Constructor<T>) candidate;
                }
            }

            Constructor<?> heuristic = resolveHeuristic(injector, args);

            if (heuristic != null) {
                return (Constructor<T>) heuristic;
            }

            if (candidate != null && candidate.isAnnotationPresent(Inject.class)) {
                throw new IllegalArgumentException("Annotated constructor for " + requestedType.getName()
                        + " cannot be satisfied by the provided arguments.");
            }

            throw new ProvisionException("No suitable constructor found for " + requestedType.getName());
        }

        @SuppressWarnings("unchecked")
        private <T> Constructor<T> findSuitableConstructor(InfuseInjector injector, Object... args) {
            Constructor<?> constructor = resolveHeuristic(injector, args);
            return constructor == null ? null : (Constructor<T>) constructor;
        }

        private Constructor<?> resolveHeuristic(InfuseInjector injector, Object... args) {
            ConstructorArgsKey key = new ConstructorArgsKey(args);
            Constructor<?> cached = heuristicCache.get(key);

            if (cached != null) {
                return cached;
            }

            Constructor<?>[] candidates = constructorsByArity.getOrDefault(args.length, EMPTY_CONSTRUCTORS);
            Constructor<?> bestMatch = null;
            int bestMatchScore = Integer.MAX_VALUE;

            for (Constructor<?> constructor : candidates) {
                int score = computeMatchScore(injector, constructor, args);

                if (score == -1) {
                    continue;
                }

                if (score < bestMatchScore) {
                    bestMatch = constructor;
                    bestMatchScore = score;
                }
            }

            if (bestMatch != null) {
                Constructor<?> previous = heuristicCache.putIfAbsent(key, bestMatch);
                return previous != null ? previous : bestMatch;
            }

            return null;
        }

        private int computeMatchScore(InfuseInjector injector, Constructor<?> constructor, Object... args) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();

            if (parameterTypes.length != args.length) {
                return -1;
            }

            int matchScore = 0;

            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];

                if (arg == null) {
                    continue;
                }

                Class<?> expectedType = parameterTypes[i];
                Class<?> actualType = arg.getClass();

                if (!expectedType.isAssignableFrom(actualType)) {
                    return -1;
                }

                int distance = injector.getClassDistance(expectedType, actualType);

                if (distance == -1) {
                    return -1;
                }

                matchScore += distance;
            }

            return matchScore;
        }

        private static final class ConstructorArgsKey {
            private final Class<?>[] argumentTypes;
            private final int hash;

            private ConstructorArgsKey(Object... args) {
                this.argumentTypes = new Class<?>[args.length];

                for (int i = 0; i < args.length; i++) {
                    this.argumentTypes[i] = args[i] == null ? null : args[i].getClass();
                }

                this.hash = Arrays.hashCode(argumentTypes);
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) {
                    return true;
                }

                if (!(obj instanceof ConstructorArgsKey)) {
                    return false;
                }

                ConstructorArgsKey other = (ConstructorArgsKey) obj;
                return Arrays.equals(argumentTypes, other.argumentTypes);
            }

            @Override
            public int hashCode() {
                return hash;
            }
        }
    }

    private static final class CompositeBindingList<E> extends AbstractList<E> {
        private final List<? extends E> first;
        private final List<? extends E> second;

        private CompositeBindingList(@NotNull List<? extends E> first, @NotNull List<? extends E> second) {
            this.first = Objects.requireNonNull(first, "first");
            this.second = Objects.requireNonNull(second, "second");
        }

        @Override
        public E get(int index) {
            int firstSize = first.size();

            if (index < firstSize) {
                return first.get(index);
            }

            return second.get(index - firstSize);
        }

        @Override
        public int size() {
            return first.size() + second.size();
        }
    }

    private static final class NegativeBindingKey {
        private final Class<?> type;
        private final BindingQualifier qualifier;
        private final int hash;

        private NegativeBindingKey(@NotNull Class<?> type, @NotNull BindingQualifier qualifier) {
            this.type = Objects.requireNonNull(type, "type");
            this.qualifier = Objects.requireNonNull(qualifier, "qualifier");
            this.hash = Objects.hash(this.type, this.qualifier);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (!(obj instanceof NegativeBindingKey)) {
                return false;
            }

            NegativeBindingKey other = (NegativeBindingKey) obj;
            return type.equals(other.type) && qualifier.equals(other.qualifier);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class BindingQueryKey {
        private final Class<?> type;
        private final BindingQualifier qualifier;
        private final BindingScope scope;
        private final int hash;

        private BindingQueryKey(@NotNull Class<?> type,
                                @NotNull BindingQualifier qualifier,
                                @NotNull BindingScope scope) {
            this.type = Objects.requireNonNull(type, "type");
            this.qualifier = Objects.requireNonNull(qualifier, "qualifier");
            this.scope = Objects.requireNonNull(scope, "scope");
            this.hash = Objects.hash(this.type, this.qualifier, this.scope);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (!(obj instanceof BindingQueryKey)) {
                return false;
            }

            BindingQueryKey other = (BindingQueryKey) obj;
            return type.equals(other.type)
                    && qualifier.equals(other.qualifier)
                    && scope.equals(other.scope);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class ScopedInstanceRegistry {
        private final Map<Object, ScopedInstanceEntry> instances = new IdentityHashMap<>();
        private final List<ScopedInstanceEntry> order = new ArrayList<>();

        private synchronized void record(Binding<?> binding, Object instance) {
            if (binding == null || instance == null) {
                return;
            }

            BindingScope scope = binding.getScope();
            if (!ScopeProviders.shouldTrackForShutdown(scope)) {
                return;
            }

            if (instances.containsKey(instance)) {
                return;
            }

            ScopedInstanceEntry entry = new ScopedInstanceEntry(binding, instance);
            instances.put(instance, entry);
            order.add(entry);
        }

        private synchronized List<ScopedInstanceEntry> drain() {
            List<ScopedInstanceEntry> snapshot = new ArrayList<>(order);
            instances.clear();
            order.clear();
            return snapshot;
        }
    }

    private static final class ScopedInstanceEntry {
        private final Binding<?> binding;
        private final Object instance;

        private ScopedInstanceEntry(Binding<?> binding, Object instance) {
            this.binding = binding;
            this.instance = instance;
        }
    }

    private static final class FieldInjectionPoint {
        private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];

        private final Field field;
        private final @Nullable VarHandle handle;
        private final boolean optional;
        private final boolean primitive;
        private final boolean isStatic;
        private final Annotation[] annotations;

        private FieldInjectionPoint(Field field,
                                    @Nullable VarHandle handle,
                                    boolean optional,
                                    boolean primitive,
                                    boolean isStatic,
                                    Annotation[] annotations) {
            this.field = field;
            this.handle = handle;
            this.optional = optional;
            this.primitive = primitive;
            this.isStatic = isStatic;
            this.annotations = annotations == null || annotations.length == 0 ? NO_ANNOTATIONS : annotations;
        }

        private static FieldInjectionPoint create(Field field) {
            Annotation[] annotations = field.getAnnotations();
            boolean optional = InjectionUtils.isOptional(annotations);
            boolean primitive = field.getType().isPrimitive();
            boolean isStatic = Modifier.isStatic(field.getModifiers());
            VarHandle handle = null;

            try {
                MethodHandles.Lookup lookup = lookupFor(field.getDeclaringClass());
                handle = lookup.unreflectVarHandle(field);
            } catch (IllegalAccessException | RuntimeException ignored) {
            }

            return new FieldInjectionPoint(field, handle, optional, primitive, isStatic, annotations);
        }

        private Field field() {
            return field;
        }

        private Class<?> type() {
            return field.getType();
        }

        private boolean optional() {
            return optional;
        }

        private boolean primitive() {
            return primitive;
        }

        private Annotation[] annotations() {
            return annotations;
        }

        private void set(Object target, Object value) throws Throwable {
            if (handle != null) {
                if (isStatic) {
                    handle.set(value);
                } else {
                    handle.set(target, value);
                }

                return;
            }

            Object receiver = isStatic ? null : target;
            field.set(receiver, value);
        }
    }

    private static final class MethodInjectionPoint {
        private static final Object[] NO_ARGUMENTS = new Object[0];

        private final Method method;
        private final @Nullable MethodHandle handle;
        private final boolean isStatic;
        private final int priority;
        private final MethodParameter[] parameters;

        private MethodInjectionPoint(Method method,
                                     @Nullable MethodHandle handle,
                                     boolean isStatic,
                                     int priority,
                                     MethodParameter[] parameters) {
            this.method = method;
            this.handle = handle;
            this.isStatic = isStatic;
            this.priority = priority;
            this.parameters = parameters;
        }

        private static MethodInjectionPoint create(Method method, int priority) {
            boolean isStatic = Modifier.isStatic(method.getModifiers());
            MethodParameter[] parameters = MethodParameter.createAll(method);
            MethodHandle handle = null;

            try {
                MethodHandles.Lookup lookup = lookupFor(method.getDeclaringClass());
                MethodHandle base = lookup.unreflect(method);
                MethodHandle spread = base.asSpreader(Object[].class, method.getParameterCount());

                if (isStatic) {
                    spread = MethodHandles.dropArguments(spread, 0, Object.class);
                }

                MethodType targetType = MethodType.methodType(method.getReturnType(), Object.class, Object[].class);
                handle = spread.asType(targetType);
            } catch (IllegalAccessException | RuntimeException ignored) {
            }

            return new MethodInjectionPoint(method, handle, isStatic, priority, parameters);
        }

        private Method method() {
            return method;
        }

        private int priority() {
            return priority;
        }

        private Object[] resolveArguments(InfuseInjector injector) {
            MethodParameter[] current = parameters;

            if (current.length == 0) {
                return NO_ARGUMENTS;
            }

            Object[] resolved = new Object[current.length];

            for (int i = 0; i < current.length; i++) {
                resolved[i] = current[i].resolve(injector);
            }

            return resolved;
        }

        private void invoke(Object target, Object[] arguments) throws Throwable {
            if (handle != null) {
                handle.invoke(target, arguments);
                return;
            }

            Object receiver = isStatic ? null : target;

            try {
                method.invoke(receiver, arguments);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private static final class MethodParameter {
            private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];

            private final Class<?> type;
            private final String name;
            private final Annotation[] annotations;
            private final boolean optional;
            private final boolean primitive;
            private final Class<?> declaringType;

            private MethodParameter(Class<?> type,
                                    String name,
                                    Annotation[] annotations,
                                    boolean optional,
                                    boolean primitive,
                                    Class<?> declaringType) {
                this.type = type;
                this.name = name;
                this.annotations = annotations == null || annotations.length == 0 ? NO_ANNOTATIONS : annotations;
                this.optional = optional;
                this.primitive = primitive;
                this.declaringType = declaringType;
            }

            private static MethodParameter[] createAll(Method method) {
                Parameter[] reflectionParameters = method.getParameters();

                if (reflectionParameters.length == 0) {
                    return new MethodParameter[0];
                }

                MethodParameter[] parameters = new MethodParameter[reflectionParameters.length];
                Class<?> declaringType = method.getDeclaringClass();

                for (int i = 0; i < reflectionParameters.length; i++) {
                    Parameter parameter = reflectionParameters[i];
                    Class<?> parameterType = parameter.getType();
                    Annotation[] annotations = parameter.getAnnotations();
                    boolean optional = InjectionUtils.isOptional(annotations);
                    boolean primitive = parameterType.isPrimitive();

                    if (optional && primitive) {
                        throw new IllegalArgumentException("Optional method parameter " + parameter.getName()
                                + " in " + declaringType.getName()
                                + " cannot target primitive type " + parameterType.getName());
                    }

                    parameters[i] = new MethodParameter(parameterType, parameter.getName(), annotations, optional,
                            primitive, declaringType);
                }

                return parameters;
            }

            private Object resolve(InfuseInjector injector) {
                @SuppressWarnings("unchecked")
                Class<Object> ctxType = (Class<Object>) declaringType;
                Context<?> parameterContext = Context.borrow(ctxType, injector, injector, ElementType.METHOD, name,
                        annotations);

                try {
                    Object value = injector.provide(type, parameterContext);

                    if (optional && value == null) {
                        return null;
                    }

                    return value;
                } finally {
                    parameterContext.release();
                }
            }
        }
    }

    private static class InjectionPlan {
        private final List<FieldInjectionPoint> injectableFields;
        private final List<MethodInjectionPoint> injectableMethods;
        private final List<MethodInjectionPoint> postConstructMethods;
        private final List<MethodInjectionPoint> preDestroyMethods;
        private final List<MethodInjectionPoint> postInjectMethods;

        public InjectionPlan(Class<?> clazz) {
            this.injectableFields = new ArrayList<>();
            this.injectableMethods = new ArrayList<>();
            this.postConstructMethods = new ArrayList<>();
            this.preDestroyMethods = new ArrayList<>();
            this.postInjectMethods = new ArrayList<>();

            for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (field.isAnnotationPresent(Inject.class)) {
                        ensureAccessible(field);
                        injectableFields.add(FieldInjectionPoint.create(field));
                    }
                }

                for (Method method : current.getDeclaredMethods()) {
                    boolean inject = method.isAnnotationPresent(Inject.class);
                    boolean postConstruct = !inject && method.isAnnotationPresent(PostConstruct.class);
                    boolean preDestroy = !inject && !postConstruct && method.isAnnotationPresent(PreDestroy.class);
                    boolean postInject = !inject && !postConstruct && !preDestroy
                            && method.isAnnotationPresent(PostInject.class);

                    if (!inject && !postConstruct && !preDestroy && !postInject) {
                        continue;
                    }

                    ensureAccessible(method);

                    if (inject) {
                        injectableMethods.add(MethodInjectionPoint.create(method, 0));
                    } else if (postConstruct) {
                        PostConstruct annotation = method.getAnnotation(PostConstruct.class);
                        int priority = annotation == null ? 0 : annotation.priority();
                        postConstructMethods.add(MethodInjectionPoint.create(method, priority));
                    } else if (preDestroy) {
                        preDestroyMethods.add(MethodInjectionPoint.create(method, 0));
                    } else if (postInject) {
                        PostInject annotation = method.getAnnotation(PostInject.class);
                        int priority = annotation == null ? 0 : annotation.priority();
                        postInjectMethods.add(MethodInjectionPoint.create(method, priority));
                    }
                }
            }

            postConstructMethods.sort(Comparator.comparingInt(MethodInjectionPoint::priority));
            postInjectMethods.sort(Comparator.comparingInt(MethodInjectionPoint::priority));
        }

        public List<FieldInjectionPoint> getInjectableFields() {
            return injectableFields;
        }

        public List<MethodInjectionPoint> getInjectableMethods() {
            return injectableMethods;
        }

        public List<MethodInjectionPoint> getPostConstructMethods() {
            return postConstructMethods;
        }

        public List<MethodInjectionPoint> getPreDestroyMethods() {
            return preDestroyMethods;
        }

        public List<MethodInjectionPoint> getPostInjectMethods() {
            return postInjectMethods;
        }

        private static void ensureAccessible(AccessibleObject accessibleObject) {
            if (!accessibleObject.isAccessible()) {
                accessibleObject.setAccessible(true);
            }
        }
    }

}
