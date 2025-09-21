package dev.fumaz.infuse.provider;

import dev.fumaz.infuse.context.Context;
import dev.fumaz.infuse.context.ContextView;
import org.jetbrains.annotations.Nullable;

/**
 * An {@link ImmutableInstanceProvider} is a {@link Provider} that provides a specific instance without injecting it.
 *
 * @param <T> the type of the class
 */
public class ImmutableInstanceProvider<T> implements Provider<T>, Provider.ContextViewAware<T> {

    private final @Nullable T instance;

    public ImmutableInstanceProvider(@Nullable T instance) {
        this.instance = instance;
    }

    @Override
    public @Nullable T provide(Context<?> context) {
        return instance;
    }

    @Override
    public @Nullable T provide(ContextView<?> context) {
        return instance;
    }

}
