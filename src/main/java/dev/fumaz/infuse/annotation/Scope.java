package dev.fumaz.infuse.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation used to mark custom scope annotations discoverable by package scanning.
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Scope {

    /**
     * Logical name for the scope. When left empty the annotation simple name is used.
     */
    String value() default "";

    /**
     * Hint whether the scope should be eagerly initialized when possible.
     */
    boolean eager() default false;
}
