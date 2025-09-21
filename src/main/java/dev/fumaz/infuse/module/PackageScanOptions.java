package dev.fumaz.infuse.module;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Configuration object controlling how {@link InfuseModule#bindPackage(ClassLoader, String, PackageScanOptions)}
 * discovers and binds classes.
 */
public final class PackageScanOptions {

    private final boolean recursive;
    private final Predicate<Class<?>> filter;
    private final List<PackageBindingRule> rules;

    private PackageScanOptions(boolean recursive,
                               Predicate<Class<?>> filter,
                               List<PackageBindingRule> rules) {
        this.recursive = recursive;
        this.filter = filter;
        this.rules = rules;
    }

    public boolean isRecursive() {
        return recursive;
    }

    public @NotNull Predicate<Class<?>> getFilter() {
        return filter;
    }

    public @NotNull List<PackageBindingRule> getRules() {
        return rules;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static PackageScanOptions defaults() {
        return builder().build();
    }

    public static final class Builder {
        private boolean recursive = true;
        private Predicate<Class<?>> filter = clazz -> true;
        private final List<PackageBindingRule> additionalRules = new ArrayList<>();
        private boolean includeDefaultRules = true;
        private boolean applyDefaultFilter = true;

        public Builder recursive(boolean recursive) {
            this.recursive = recursive;
            return this;
        }

        public Builder filter(@NotNull Predicate<Class<?>> predicate) {
            this.filter = Objects.requireNonNull(predicate, "predicate");
            return this;
        }

        public Builder addFilter(@NotNull Predicate<Class<?>> predicate) {
            Objects.requireNonNull(predicate, "predicate");
            this.filter = this.filter.and(predicate);
            return this;
        }

        public Builder includeDefaultRules(boolean includeDefaultRules) {
            this.includeDefaultRules = includeDefaultRules;
            return this;
        }

        public Builder useDefaultClassFilter(boolean applyDefaultFilter) {
            this.applyDefaultFilter = applyDefaultFilter;
            return this;
        }

        public Builder clearRules() {
            this.additionalRules.clear();
            this.includeDefaultRules = false;
            return this;
        }

        public Builder addRule(@NotNull PackageBindingRule rule) {
            this.additionalRules.add(Objects.requireNonNull(rule, "rule"));
            return this;
        }

        public PackageScanOptions build() {
            Predicate<Class<?>> finalFilter = applyDefaultFilter
                    ? filter.and(PackageBindingRules.DEFAULT_CLASS_FILTER)
                    : filter;

            List<PackageBindingRule> finalRules = new ArrayList<>();

            if (includeDefaultRules) {
                finalRules.addAll(PackageBindingRules.defaultRules());
            }

            finalRules.addAll(additionalRules);

            return new PackageScanOptions(recursive, finalFilter, Collections.unmodifiableList(finalRules));
        }
    }
}
