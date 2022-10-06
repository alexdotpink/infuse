package dev.fumaz.infuse.module;

import dev.fumaz.infuse.bind.Binding;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * A {@link Module} is a collection of bindings.
 */
public interface Module {

    void configure();

    @NotNull Set<Binding<?>> getBindings();

}
