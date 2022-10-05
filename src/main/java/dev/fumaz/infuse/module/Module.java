package dev.fumaz.infuse.module;

import dev.fumaz.infuse.bind.Binding;

import java.util.Set;

/**
 * A {@link Module} is a collection of bindings.
 */
public interface Module {

    void configure();

    Set<Binding<?>> getBindings();

}
