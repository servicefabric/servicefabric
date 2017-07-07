package io.scalecube.services.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

import io.scalecube.services.routing.Router;

import java.lang.annotation.ElementType;

@Target(value = {ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
@Retention(value = RetentionPolicy.RUNTIME)
@Documented
public @interface ServiceProxy {

  Class<? extends Router> router() default Router.class;

  int timeout() default 3;

  TimeUnit timeUnit() default TimeUnit.SECONDS;

}
