package dev.fumaz.infuse.util;

import dev.fumaz.infuse.annotation.Inject;

import java.lang.annotation.Annotation;

public final class InjectionUtils {

    private InjectionUtils() {
    }

    public static boolean isOptional(Annotation[] annotations) {
        if (annotations == null) {
            return false;
        }

        for (Annotation annotation : annotations) {
            if (annotation instanceof Inject) {
                return ((Inject) annotation).optional();
            }
        }

        return false;
    }
}
