package dev.fumaz.infuse.exception;

/**
 * Signals a failure while provisioning or injecting a dependency.
 */
public class ProvisionException extends InfuseException {

    public ProvisionException(String message) {
        super(message);
    }

    public ProvisionException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProvisionException(Throwable cause) {
        super(cause);
    }
}
