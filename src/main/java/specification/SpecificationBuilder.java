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

    /**
     * Cache for storing field metadata to avoid repeated reflection operations.
     * Key: DTO class type
     * Value: List of field metadata containing field info and annotations
     */
    private static final java.util.Map<Class<?>, List<FieldMetadata>> FIELD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Metadata holder for a single field containing all relevant annotation information.
     * This record is used to cache reflection results and avoid repeated field scanning.
     *
     * @param field The actual field from the DTO class
     * @param queryTerm QueryTerm annotation if present, null otherwise
     * @param queryRange QueryRange annotation if present, null otherwise
     * @param queryPath QueryPath annotation if present, null otherwise
     */
    private record FieldMetadata(
        Field field,
        QueryTerm queryTerm,
        QueryRange queryRange,
        QueryPath queryPath
    ) {}

    public Specification<T> build(Object searchDto) {
        // Get cached field metadata - reflection only happens once per class type
        List<FieldMetadata> fields = getFieldMetadata(searchDto.getClass());
        List<Specification<T>> specifications = new ArrayList<>();

        for (FieldMetadata metadata : fields) {
            try {
                Object value = metadata.field().get(searchDto);
                if (value == null) continue;

                // Process @QueryTerm annotation
                if (metadata.queryTerm() != null && value instanceof String termValue && !termValue.isBlank()) {
                    List<Specification<T>> orSpecs = new ArrayList<>();
                    for (String path : metadata.queryTerm().value()) {
                        orSpecs.add(buildPredicate(path, termValue, metadata.queryTerm().operation()));
                    }
                    Specification<T> combinedOr = orSpecs.stream().reduce(Specification::or).orElse(null);
                    if (combinedOr != null) {
                        specifications.add(combinedOr);
                    }
                    continue;
                }

                // Process @QueryRange annotation
                if (metadata.queryRange() != null && value instanceof Range<?> range) {
                    String path = metadata.queryRange().value();
                    if (range.getFrom() != null && range.getTo() != null) {
                        specifications.add(buildBetweenPredicate(path, range.getFrom(), range.getTo()));
                    } else if (range.getFrom() != null) {
                        specifications.add(buildGreaterThanOrEqualToPredicate(path, range.getFrom()));
                    } else if (range.getTo() != null) {
                        specifications.add(buildLessThanOrEqualToPredicate(path, range.getTo()));
                    }
                    continue;
                }

                // Process @QueryPath annotation or plain field
                String path = (metadata.queryPath() != null) ? metadata.queryPath().value() : metadata.field().getName();
                Operation operation = (metadata.queryPath() != null) ? metadata.queryPath().operation() : Operation.EQUAL;

                if (value instanceof Collection<?> collection) {
                    specifications.add(buildInPredicate(path, collection));
                } else {
                    specifications.add(buildPredicate(path, value, operation));
                }

            } catch (IllegalAccessException e) {
                throw new RuntimeException("DTO alanına erişilemedi: " + metadata.field().getName(), e);
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

    /**
     * Retrieves field metadata for the given DTO class.
     * Uses cache to avoid repeated reflection operations on the same class.
     *
     * @param clazz The DTO class to extract field metadata from
     * @return List of field metadata containing field info and annotations
     */
    private List<FieldMetadata> getFieldMetadata(Class<?> clazz) {
        return FIELD_CACHE.computeIfAbsent(clazz, this::extractFieldMetadata);
    }

    /**
     * Extracts field metadata from a DTO class using reflection.
     * This method is called only once per class type, results are cached.
     *
     * @param clazz The DTO class to scan
     * @return List of field metadata for all declared fields
     */
    private List<FieldMetadata> extractFieldMetadata(Class<?> clazz) {
        List<FieldMetadata> metadataList = new ArrayList<>();

        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);

            QueryTerm queryTerm = field.getAnnotation(QueryTerm.class);
            QueryRange queryRange = field.getAnnotation(QueryRange.class);
            QueryPath queryPath = field.getAnnotation(QueryPath.class);

            metadataList.add(new FieldMetadata(field, queryTerm, queryRange, queryPath));
        }

        return metadataList;
    }
}
