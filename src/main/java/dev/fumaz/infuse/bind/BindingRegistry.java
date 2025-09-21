package dev.fumaz.infuse.bind;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BindingRegistry {

    private final Map<Class<?>, TypeIndex> bindings = new LinkedHashMap<>();
    private final List<Binding<?>> insertionOrder = new ArrayList<>();

    public synchronized void add(@NotNull Binding<?> binding) {
        BindingKey key = binding.getKey();
        TypeIndex typeIndex = bindings.computeIfAbsent(key.getType(), ignored -> new TypeIndex());
        QualifierIndex qualifierIndex = typeIndex.qualifiers.computeIfAbsent(key.getQualifier(), ignored -> new QualifierIndex());
        List<Binding<?>> scopedBindings = qualifierIndex.bindingsForScope(key.getScope());

        if (!scopedBindings.isEmpty()) {
            boolean existingSupportsCollections = scopedBindings.stream().allMatch(Binding::isCollectionContribution);

            if (!existingSupportsCollections || !binding.isCollectionContribution()) {
                throw new IllegalStateException("Duplicate binding registered for " + key.describe());
            }
        }

        scopedBindings.add(binding);
        qualifierIndex.recordAddition(binding);
        typeIndex.recordAddition(binding);
        insertionOrder.add(binding);
    }

    public synchronized List<Binding<?>> all() {
        return new ArrayList<>(insertionOrder);
    }

    public synchronized List<Binding<?>> byType(@NotNull Class<?> type) {
        TypeIndex typeIndex = bindings.get(type);

        if (typeIndex == null) {
            return Collections.emptyList();
        }

        return new ArrayList<>(typeIndex.allBindings());
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> List<Binding<T>> find(@NotNull Class<T> type,
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
                : qualifierIndex.bindingsForScopeOrEmpty(scope);

        if (source.isEmpty()) {
            return Collections.emptyList();
        }

        List<Binding<T>> matches = new ArrayList<>(source.size());

        for (Binding<?> binding : source) {
            matches.add((Binding<T>) binding);
        }

        return matches;
    }

    public synchronized boolean isEmpty() {
        return insertionOrder.isEmpty();
    }

    private static final class TypeIndex {

        private final Map<BindingQualifier, QualifierIndex> qualifiers = new LinkedHashMap<>();
        private final List<Binding<?>> insertionOrder = new ArrayList<>();

        void recordAddition(@NotNull Binding<?> binding) {
            insertionOrder.add(binding);
        }

        List<Binding<?>> allBindings() {
            return insertionOrder;
        }
    }

    private static final class QualifierIndex {

        private final Map<BindingScope, List<Binding<?>>> byScope = new LinkedHashMap<>();
        private final List<Binding<?>> insertionOrder = new ArrayList<>();
        private List<Binding<?>> cachedAny = Collections.emptyList();
        private boolean anyDirty = true;

        List<Binding<?>> bindingsForScope(@NotNull BindingScope scope) {
            return byScope.computeIfAbsent(scope, ignored -> new ArrayList<>());
        }

        List<Binding<?>> bindingsForScopeOrEmpty(@NotNull BindingScope scope) {
            List<Binding<?>> bindings = byScope.get(scope);
            return bindings != null ? bindings : Collections.emptyList();
        }

        void recordAddition(@NotNull Binding<?> binding) {
            insertionOrder.add(binding);
            anyDirty = true;
        }

        List<Binding<?>> anyBindings() {
            if (anyDirty) {
                if (insertionOrder.isEmpty()) {
                    cachedAny = Collections.emptyList();
                } else {
                    cachedAny = Collections.unmodifiableList(new ArrayList<>(insertionOrder));
                }

                anyDirty = false;
            }

            return cachedAny;
        }
    }
}
