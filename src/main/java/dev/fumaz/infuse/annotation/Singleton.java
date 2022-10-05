package dev.fumaz.infuse.annotation;

import java.lang.annotation.*;

/**
 * Marks a class as a singleton.
 *
 * TODO: Make this work
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Singleton {
    boolean lazy() default true;
}
