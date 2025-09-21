package dev.fumaz.infuse.scope;

/**
 * Represents an active scope context that can be closed to release scoped instances.
 */
public interface ScopeHandle extends AutoCloseable {

    /**
     * Closes the scope, triggering any registered destruction callbacks.
     */
    @Override
    void close();
}
