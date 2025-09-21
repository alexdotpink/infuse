package dev.fumaz.infuse.module;

import dev.fumaz.infuse.bind.Binding;
import dev.fumaz.infuse.bind.BindingBuilder;
import dev.fumaz.infuse.reflection.Reflections;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public abstract class InfuseModule implements Module {

    private final List<Binding<?>> bindings = new ArrayList<>();

    @Override
    public @NotNull List<Binding<?>> getBindings() {
        return bindings;
    }

    public <T> @NotNull BindingBuilder<T> bind(Class<T> type) {
        return new BindingBuilder<>(type, bindings);
    }

    @Override
    public void reset() {
        bindings.clear();
    }

    public void bindPackage(ClassLoader classLoader, String name) {
        bindPackage(classLoader, name, PackageScanOptions.defaults());
    }

    public void bindPackage(ClassLoader classLoader, String name, PackageScanOptions options) {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(options, "options");

        Reflections.consume(classLoader, name, options.isRecursive(), type -> {
            if (!options.getFilter().test(type)) {
                return;
            }

            for (PackageBindingRule rule : options.getRules()) {
                if (rule.apply(this, type)) {
                    return;
                }
            }
        });
    }

}
