package dev.fumaz.infuse.bind;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BindingRegistry {

    private final Map<Class<?>, TypeIndex> bindings = new ConcurrentHashMap<>();
    private final List<Binding<?>> insertionOrder = new CopyOnWriteArrayList<>();

    public void add(@NotNull Binding<?> binding) {
        addAll(Collections.singletonList(binding));
    }

    public void addAll(@NotNull Collection<? extends Binding<?>> bindings) {
        if (bindings.isEmpty()) {
            return;
        }

        Map<Class<?>, LinkedHashMap<BindingQualifier, List<Binding<?>>>> grouped = new LinkedHashMap<>();
        List<Binding<?>> ordered = new ArrayList<>(bindings.size());

        for (Binding<?> binding : bindings) {
            BindingKey key = binding.getKey();
            ordered.add(binding);

            grouped
                    .computeIfAbsent(key.getType(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(key.getQualifier(), ignored -> new ArrayList<>())
                    .add(binding);
        }

        for (Map.Entry<Class<?>, LinkedHashMap<BindingQualifier, List<Binding<?>>>> typeEntry : grouped.entrySet()) {
            TypeIndex typeIndex = this.bindings.computeIfAbsent(typeEntry.getKey(), ignored -> new TypeIndex());
            List<Binding<?>> typeBindings = new ArrayList<>();

            for (Map.Entry<BindingQualifier, List<Binding<?>>> qualifierEntry : typeEntry.getValue().entrySet()) {
                QualifierIndex qualifierIndex = typeIndex.qualifiers
                        .computeIfAbsent(qualifierEntry.getKey(), ignored -> new QualifierIndex());

                List<Binding<?>> qualifierBindings = qualifierEntry.getValue();
                qualifierIndex.addAll(qualifierBindings);
                typeBindings.addAll(qualifierBindings);
            }

            typeIndex.recordAddition(typeBindings);
        }

        insertionOrder.addAll(ordered);
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

        void recordAddition(@NotNull Collection<Binding<?>> bindings) {
            if (bindings.isEmpty()) {
                return;
            }

            insertionOrder.addAll(bindings);
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

        void addAll(@NotNull List<Binding<?>> bindings) {
            if (bindings.isEmpty()) {
                return;
            }

            Map<BindingScope, List<Binding<?>>> grouped = new LinkedHashMap<>();

            for (Binding<?> binding : bindings) {
                grouped
                        .computeIfAbsent(binding.getScope(), ignored -> new ArrayList<>())
                        .add(binding);
            }

            for (Map.Entry<BindingScope, List<Binding<?>>> entry : grouped.entrySet()) {
                BindingScope scope = entry.getKey();
                List<Binding<?>> additions = entry.getValue();

                CopyOnWriteArrayList<Binding<?>> scoped = byScope.computeIfAbsent(scope, ignored -> new CopyOnWriteArrayList<>());
                ensureCompatibleBatch(scoped, additions);
                scoped.addAll(additions);
                scopedViews.computeIfAbsent(scope, ignoredScope -> Collections.unmodifiableList(scoped));
            }

            insertionOrder.addAll(bindings);
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

        private static void ensureCompatibleBatch(@NotNull List<Binding<?>> scopedBindings,
                                                  @NotNull List<Binding<?>> additions) {
            if (additions.isEmpty()) {
                return;
            }

            List<Binding<?>> preview = new ArrayList<>(scopedBindings);

            for (Binding<?> addition : additions) {
                ensureCompatible(preview, addition, addition.getKey());
                preview.add(addition);
            }
        }
    }
}
