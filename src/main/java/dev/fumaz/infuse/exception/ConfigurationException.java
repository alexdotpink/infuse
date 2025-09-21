package dev.fumaz.infuse.exception;

/**
 * Indicates a misconfiguration or invalid binding detected at runtime.
 */
public class ConfigurationException extends InfuseException {

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConfigurationException(Throwable cause) {
        super(cause);
    }
}
