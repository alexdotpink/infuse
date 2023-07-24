package dev.fumaz.infuse.annotation;

import java.lang.annotation.*;

/**
 * This annotation is used to mark methods that should be called after the object has been constructed by the injector.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PostConstruct {
}
