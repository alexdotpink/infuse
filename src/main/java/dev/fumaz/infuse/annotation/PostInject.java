package dev.fumaz.infuse.annotation;

import java.lang.annotation.*;

/**
 * This annotation is used to mark methods that should be called when the injector is destroyed.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PostInject {
}
