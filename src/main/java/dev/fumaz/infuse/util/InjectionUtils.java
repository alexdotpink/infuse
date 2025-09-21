package dev.fumaz.infuse.util;

import dev.fumaz.infuse.annotation.Inject;
import dev.fumaz.infuse.annotation.Qualifier;
import dev.fumaz.infuse.bind.BindingQualifier;

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

    public static BindingQualifier resolveQualifier(Annotation[] annotations) {
        if (annotations == null || annotations.length == 0) {
            return BindingQualifier.none();
        }

        BindingQualifier qualifier = BindingQualifier.none();

        for (Annotation annotation : annotations) {
            Class<? extends Annotation> type = annotation.annotationType();

            if (type.isAnnotationPresent(Qualifier.class)) {
                if (!qualifier.isDefault()) {
                    throw new IllegalStateException("Multiple qualifier annotations found on injection point");
                }

                qualifier = BindingQualifier.from(annotation);
            }
        }

        return qualifier;
    }
}
