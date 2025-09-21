package dev.fumaz.infuse.module;

import dev.fumaz.infuse.bind.Binding;
import dev.fumaz.infuse.bind.BindingBuilder;
import dev.fumaz.infuse.reflection.Reflections;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public abstract class InfuseModule implements Module {

    private final List<Binding<?>> bindings = new ArrayList<>();
    private final List<Binding<?>> bindingsView = Collections.unmodifiableList(bindings);

    @Override
    public @NotNull List<Binding<?>> getBindings() {
        return bindingsView;
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

    protected final void install(@NotNull Module module) {
        Objects.requireNonNull(module, "module");

        if (module == this) {
            throw new IllegalArgumentException("A module cannot install itself");
        }

        module.reset();
        module.configure();

        List<Binding<?>> produced = module.getBindings();
        bindings.addAll(produced);
    }

}
