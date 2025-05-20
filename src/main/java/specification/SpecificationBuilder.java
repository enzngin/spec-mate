package specification;

import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import org.springframework.data.jpa.domain.Specification;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@SuppressWarnings("unchecked")
public class SpecificationBuilder<T> {

    public Specification<T> build(Object searchDto) {
        List<Specification<T>> specifications = new ArrayList<>();

        for (Field field : searchDto.getClass().getDeclaredFields()) {
            field.setAccessible(true);

            try {
                Object value = field.get(searchDto);
                if (value == null) continue;

                QueryTerm queryTerm = field.getAnnotation(QueryTerm.class);
                if (queryTerm != null && value instanceof String termValue && !termValue.isBlank()) {
                    List<Specification<T>> orSpecs = new ArrayList<>();
                    for (String path : queryTerm.value()) {
                        orSpecs.add(buildPredicate(path, termValue, queryTerm.operation()));
                    }
                    Specification<T> combinedOr = orSpecs.stream().reduce(Specification::or).orElse(null);
                    if (combinedOr != null) {
                        specifications.add(combinedOr);
                    }
                    continue;
                }

                QueryRange rangeAnnotation = field.getAnnotation(QueryRange.class);
                if (rangeAnnotation != null && value instanceof Range<?> range) {
                    String path = rangeAnnotation.value();
                    if (range.getFrom() != null && range.getTo() != null) {
                        specifications.add(buildBetweenPredicate(path, range.getFrom(), range.getTo()));
                    } else if (range.getFrom() != null) {
                        specifications.add(buildGreaterThanOrEqualToPredicate(path, range.getFrom()));
                    } else if (range.getTo() != null) {
                        specifications.add(buildLessThanOrEqualToPredicate(path, range.getTo()));
                    }
                    continue;
                }

                QueryPath annotation = field.getAnnotation(QueryPath.class);
                String path = (annotation != null) ? annotation.value() : field.getName();
                Operation operation = (annotation != null) ? annotation.operation() : Operation.EQUAL;

                if (value instanceof Collection<?> collection) {
                    specifications.add(buildInPredicate(path, collection));
                } else {
                    specifications.add(buildPredicate(path, value, operation));
                }

            } catch (IllegalAccessException e) {
                throw new RuntimeException("DTO alanına erişilemedi: " + field.getName(), e);
            }
        }

        return specifications.stream()
                .reduce(Specification::and)
                .orElse(null);
    }

    private Specification<T> buildPredicate(String pathExpression, Object value, Operation operation) {
        return (root, query, cb) -> {
            Path<?> targetPath = resolvePath(root, pathExpression);

            if (operation == Operation.LIKE && value instanceof String stringValue) {
                return cb.like(cb.lower(targetPath.as(String.class)), "%" + stringValue.toLowerCase() + "%");
            } else {
                return cb.equal(targetPath, value);
            }
        };
    }

    private Specification<T> buildBetweenPredicate(String pathExpression, Object from, Object to) {
        return (root, query, cb) -> {
            Path<Comparable<Object>> path = (Path<Comparable<Object>>) resolvePath(root, pathExpression);
            return cb.between(path, (Comparable<Object>) from, (Comparable<Object>) to);
        };
    }

    private Specification<T> buildGreaterThanOrEqualToPredicate(String pathExpression, Object from) {
        return (root, query, cb) -> {
            Path<Comparable<Object>> path = (Path<Comparable<Object>>) resolvePath(root, pathExpression);
            return cb.greaterThanOrEqualTo(path, (Comparable<Object>) from);
        };
    }

    private Specification<T> buildLessThanOrEqualToPredicate(String pathExpression, Object to) {
        return (root, query, cb) -> {
            Path<Comparable<Object>> path = (Path<Comparable<Object>>) resolvePath(root, pathExpression);
            return cb.lessThanOrEqualTo(path, (Comparable<Object>) to);
        };
    }

    private Specification<T> buildInPredicate(String pathExpression, Collection<?> values) {
        return (root, query, cb) -> {
            Path<Object> path = (Path<Object>) resolvePath(root, pathExpression);
            var inClause = cb.in(path);
            values.forEach(inClause::value);
            return inClause;
        };
    }

    private Path<?> resolvePath(From<?, ?> root, String pathExpression) {
        String[] parts = pathExpression.split("\\.");
        From<?, ?> join = root;
        for (int i = 0; i < parts.length - 1; i++) {
            join = join.join(parts[i], JoinType.LEFT);
        }
        return join.get(parts[parts.length - 1]);
    }
}
