package dev.fumaz.infuse.module;

import org.jetbrains.annotations.NotNull;

/**
 * A rule applied during package scanning that may register bindings for a discovered class.
 */
@FunctionalInterface
public interface PackageBindingRule {

    /**
     * Applies the rule to the given type.
     *
     * @param module the module performing the binding
     * @param type   the discovered class
     * @return {@code true} if the rule created one or more bindings and no further rules should run
     */
    boolean apply(@NotNull InfuseModule module, @NotNull Class<?> type);
}
