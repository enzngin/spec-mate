package specification;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface QueryPath {
    String value();

    Operation operation() default Operation.EQUAL;
}
