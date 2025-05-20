package specification;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface QueryTerm {
    String[] value();
    Operation operation() default Operation.LIKE;
}