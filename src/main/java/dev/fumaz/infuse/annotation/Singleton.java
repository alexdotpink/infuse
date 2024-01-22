package dev.fumaz.infuse.annotation;

import java.lang.annotation.*;

/**
 * Marks a class as a singleton.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Singleton {

    boolean lazy() default false;

}
