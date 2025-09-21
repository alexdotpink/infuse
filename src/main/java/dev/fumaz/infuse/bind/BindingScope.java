package dev.fumaz.infuse.bind;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Objects;

/**
 * Represents the lifecycle scope of a binding.
 */
public final class BindingScope {

    public static final BindingScope ANY = new BindingScope("*");
    public static final BindingScope UNSCOPED = new BindingScope("unscoped");
    public static final BindingScope INSTANCE = new BindingScope("instance");
    public static final BindingScope IMMUTABLE_INSTANCE = new BindingScope("immutable_instance");
    public static final BindingScope SINGLETON = new BindingScope("singleton");
    public static final BindingScope EAGER_SINGLETON = new BindingScope("eager_singleton");
    public static final BindingScope REQUEST = new BindingScope("request");
    public static final BindingScope SESSION = new BindingScope("session");

    private final String name;

    private BindingScope(@NotNull String name) {
        this.name = Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
    }

    public static BindingScope custom(@NotNull String name) {
        switch (Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT)) {
            case "*":
                return ANY;
            case "unscoped":
                return UNSCOPED;
            case "instance":
                return INSTANCE;
            case "immutable_instance":
                return IMMUTABLE_INSTANCE;
            case "singleton":
                return SINGLETON;
            case "eager_singleton":
                return EAGER_SINGLETON;
            case "request":
                return REQUEST;
            case "session":
                return SESSION;
            default:
                return new BindingScope(name);
        }
    }

    public @NotNull String getName() {
        return name;
    }

    public boolean isAny() {
        return this == ANY;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof BindingScope)) {
            return false;
        }

        BindingScope that = (BindingScope) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
