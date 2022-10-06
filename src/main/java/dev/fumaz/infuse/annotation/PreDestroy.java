package dev.fumaz.infuse.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as a pre-destroy method.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PreDestroy {
}
