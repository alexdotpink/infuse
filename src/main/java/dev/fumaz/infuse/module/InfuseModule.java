package dev.fumaz.infuse.module;

import dev.fumaz.infuse.annotation.Singleton;
import dev.fumaz.infuse.bind.Binding;
import dev.fumaz.infuse.bind.BindingBuilder;
import org.jetbrains.annotations.NotNull;
import org.reflections.Reflections;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class InfuseModule implements Module {

    private final List<Binding<?>> bindings = new ArrayList<>();

    @Override
    public @NotNull List<Binding<?>> getBindings() {
        return bindings;
    }

    public <T> @NotNull BindingBuilder<T> bind(Class<T> type) {
        return new BindingBuilder<>(type, bindings);
    }

    public void bindPackage(String name) {
        Reflections reflections = new Reflections(name);

        reflections.getTypesAnnotatedWith(Singleton.class).forEach(type -> {
            if (type.getAnnotation(Singleton.class).lazy()) {
                bind(type).toSingleton();
            } else {
                bind(type).toEagerSingleton();
            }
        });

        // TODO: Add more annotations
    }

}
