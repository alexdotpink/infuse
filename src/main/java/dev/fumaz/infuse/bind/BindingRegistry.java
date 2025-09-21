package dev.fumaz.infuse.bind;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BindingRegistry {

    private final Map<Class<?>, TypeIndex> bindings = new ConcurrentHashMap<>();
    private final List<Binding<?>> insertionOrder = new CopyOnWriteArrayList<>();

    public void add(@NotNull Binding<?> binding) {
        BindingKey key = binding.getKey();
        TypeIndex typeIndex = bindings.computeIfAbsent(key.getType(), ignored -> new TypeIndex());
        QualifierIndex qualifierIndex = typeIndex.qualifiers.computeIfAbsent(key.getQualifier(), ignored -> new QualifierIndex());

        qualifierIndex.add(key, binding);
        typeIndex.recordAddition(binding);
        insertionOrder.add(binding);
    }

    public List<Binding<?>> all() {
        return new ArrayList<>(insertionOrder);
    }

    public List<Binding<?>> byType(@NotNull Class<?> type) {
        TypeIndex typeIndex = bindings.get(type);

        if (typeIndex == null) {
            return Collections.emptyList();
        }

        return new ArrayList<>(typeIndex.allBindings());
    }

    @SuppressWarnings("unchecked")
    public <T> List<Binding<T>> find(@NotNull Class<T> type,
                                     @NotNull BindingQualifier qualifier,
                                     @NotNull BindingScope scope) {
        TypeIndex typeIndex = bindings.get(type);

        if (typeIndex == null) {
            return Collections.emptyList();
        }

        QualifierIndex qualifierIndex = typeIndex.qualifiers.get(qualifier);

        if (qualifierIndex == null) {
            return Collections.emptyList();
        }

        List<Binding<?>> source = scope.isAny()
                ? qualifierIndex.anyBindings()
                : qualifierIndex.bindingsForScope(scope);

        if (source.isEmpty()) {
            return Collections.emptyList();
        }

        return (List<Binding<T>>) (List<?>) source;
    }

    public boolean isEmpty() {
        return insertionOrder.isEmpty();
    }

    private static final class TypeIndex {

        private final Map<BindingQualifier, QualifierIndex> qualifiers = new ConcurrentHashMap<>();
        private final List<Binding<?>> insertionOrder = new CopyOnWriteArrayList<>();

        void recordAddition(@NotNull Binding<?> binding) {
            insertionOrder.add(binding);
        }

        List<Binding<?>> allBindings() {
            return insertionOrder;
        }
    }

    private static final class QualifierIndex {

        private final Map<BindingScope, CopyOnWriteArrayList<Binding<?>>> byScope = new ConcurrentHashMap<>();
        private final Map<BindingScope, List<Binding<?>>> scopedViews = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<Binding<?>> insertionOrder = new CopyOnWriteArrayList<>();
        private final List<Binding<?>> anyView = Collections.unmodifiableList(insertionOrder);

        void add(@NotNull BindingKey key, @NotNull Binding<?> binding) {
            BindingScope scope = key.getScope();

            byScope.compute(scope, (ignored, existing) -> {
                CopyOnWriteArrayList<Binding<?>> scoped = existing != null ? existing : new CopyOnWriteArrayList<>();
                ensureCompatible(scoped, binding, key);
                scoped.add(binding);
                scopedViews.computeIfAbsent(scope, ignoredScope -> Collections.unmodifiableList(scoped));
                return scoped;
            });

            insertionOrder.add(binding);
        }

        List<Binding<?>> bindingsForScope(@NotNull BindingScope scope) {
            List<Binding<?>> view = scopedViews.get(scope);

            if (view == null || view.isEmpty()) {
                return Collections.emptyList();
            }

            return view;
        }

        List<Binding<?>> anyBindings() {
            if (insertionOrder.isEmpty()) {
                return Collections.emptyList();
            }

            return anyView;
        }

        private static void ensureCompatible(@NotNull List<Binding<?>> scopedBindings,
                                             @NotNull Binding<?> candidate,
                                             @NotNull BindingKey key) {
            if (scopedBindings.isEmpty()) {
                return;
            }

            boolean existingSupportsCollections = scopedBindings.stream().allMatch(Binding::isCollectionContribution);

            if (!existingSupportsCollections || !candidate.isCollectionContribution()) {
                throw new IllegalStateException("Duplicate binding registered for " + key.describe());
            }
        }
    }
}
