package dev.fumaz.infuse.annotation;

import java.lang.annotation.*;

/**
 * Marks a constructor, field, method or parameter as injectable.
 */
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Inject {
    boolean optional() default false;
}
