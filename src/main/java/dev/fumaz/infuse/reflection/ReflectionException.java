package dev.fumaz.infuse.reflection;

import dev.fumaz.infuse.exception.ConfigurationException;

public class ReflectionException extends ConfigurationException {

    public ReflectionException(String message) {
        super(message);
    }

    public ReflectionException(String message, Throwable cause) {
        super(message, cause);
    }

}
