package dev.fumaz.infuse.exception;

/**
 * Base unchecked exception for Infuse-specific failures.
 */
public class InfuseException extends RuntimeException {

    public InfuseException(String message) {
        super(message);
    }

    public InfuseException(String message, Throwable cause) {
        super(message, cause);
    }

    public InfuseException(Throwable cause) {
        super(cause);
    }
}
