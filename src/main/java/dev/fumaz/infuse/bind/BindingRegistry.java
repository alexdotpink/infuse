package dev.fumaz.infuse.bind;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BindingRegistry {

    private final Map<BindingKey, List<Binding<?>>> bindings = new LinkedHashMap<>();
    private final List<Binding<?>> insertionOrder = new ArrayList<>();

    public synchronized void add(@NotNull Binding<?> binding) {
        BindingKey key = binding.getKey();
        List<Binding<?>> existing = bindings.computeIfAbsent(key, k -> new ArrayList<>());

        if (!existing.isEmpty()) {
            boolean existingSupportsCollections = existing.stream().allMatch(Binding::isCollectionContribution);

            if (!existingSupportsCollections || !binding.isCollectionContribution()) {
                throw new IllegalStateException("Duplicate binding registered for " + key.describe());
            }
        }

        existing.add(binding);
        insertionOrder.add(binding);
    }

    public synchronized List<Binding<?>> all() {
        return new ArrayList<>(insertionOrder);
    }

    public synchronized List<Binding<?>> byType(@NotNull Class<?> type) {
        List<Binding<?>> result = new ArrayList<>();

        for (Map.Entry<BindingKey, List<Binding<?>>> entry : bindings.entrySet()) {
            if (entry.getKey().getType().equals(type)) {
                result.addAll(entry.getValue());
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> List<Binding<T>> find(@NotNull Class<T> type,
                                                  @NotNull BindingQualifier qualifier,
                                                  @NotNull BindingScope scope) {
        List<Binding<T>> matches = new ArrayList<>();

        for (Map.Entry<BindingKey, List<Binding<?>>> entry : bindings.entrySet()) {
            if (entry.getKey().matches(type, qualifier, scope)) {
                for (Binding<?> binding : entry.getValue()) {
                    matches.add((Binding<T>) binding);
                }
            }
        }

        return matches;
    }

    public synchronized boolean isEmpty() {
        return insertionOrder.isEmpty();
    }
}
