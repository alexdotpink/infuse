package dev.fumaz.infuse.annotation;

import java.lang.annotation.*;

/**
 * This annotation is used to mark methods that should be called after all fields have been injected.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PostConstruct {
}
