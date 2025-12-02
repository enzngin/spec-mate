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

        // Build all predicates in a single lambda to share JOIN cache
        return (root, query, cb) -> {
            // JOIN cache: key = path (e.g., "author"), value = Join instance
            java.util.Map<String, jakarta.persistence.criteria.Join<?, ?>> joinCache = new java.util.HashMap<>();

            // Collect paths that need FETCH JOIN to prevent N+1
            java.util.Set<String> fetchPaths = new java.util.HashSet<>();
            collectFetchPaths(fields, searchDto, fetchPaths);

            // Apply FETCH JOINs to prevent N+1 problem
            for (String fetchPath : fetchPaths) {
                try {
                    root.fetch(fetchPath, JoinType.LEFT);
                } catch (Exception e) {
                    // If fetch fails, ignore (might be already fetched or unsupported)
                }
            }

            // Make query distinct to avoid duplicate results from JOINs
            if (!fetchPaths.isEmpty() && query != null) {
                query.distinct(true);
            }

            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            for (FieldMetadata metadata : fields) {
                try {
                    Object value = metadata.field().get(searchDto);
                    if (value == null) continue;

                    // Process @QueryTerm annotation
                    if (metadata.queryTerm() != null && value instanceof String termValue && !termValue.isBlank()) {
                        List<jakarta.persistence.criteria.Predicate> orPredicates = new ArrayList<>();
                        for (String path : metadata.queryTerm().value()) {
                            Path<?> targetPath = resolvePathWithJoinCache(root, path, joinCache);
                            if (metadata.queryTerm().operation() == Operation.LIKE) {
                                orPredicates.add(cb.like(cb.lower(targetPath.as(String.class)), "%" + termValue.toLowerCase() + "%"));
                            } else {
                                orPredicates.add(cb.equal(targetPath, termValue));
                            }
                        }
                        if (!orPredicates.isEmpty()) {
                            predicates.add(cb.or(orPredicates.toArray(new jakarta.persistence.criteria.Predicate[0])));
                        }
                        continue;
                    }

                    // Process @QueryRange annotation
                    if (metadata.queryRange() != null && value instanceof Range<?> range) {
                        String path = metadata.queryRange().value();
                        Path<Comparable<Object>> targetPath = (Path<Comparable<Object>>) resolvePathWithJoinCache(root, path, joinCache);

                        if (range.getFrom() != null && range.getTo() != null) {
                            predicates.add(cb.between(targetPath, (Comparable<Object>) range.getFrom(), (Comparable<Object>) range.getTo()));
                        } else if (range.getFrom() != null) {
                            predicates.add(cb.greaterThanOrEqualTo(targetPath, (Comparable<Object>) range.getFrom()));
                        } else if (range.getTo() != null) {
                            predicates.add(cb.lessThanOrEqualTo(targetPath, (Comparable<Object>) range.getTo()));
                        }
                        continue;
                    }

                    // Process @QueryPath annotation or plain field
                    String path = (metadata.queryPath() != null) ? metadata.queryPath().value() : metadata.field().getName();
                    Operation operation = (metadata.queryPath() != null) ? metadata.queryPath().operation() : Operation.EQUAL;

                    Path<?> targetPath = resolvePathWithJoinCache(root, path, joinCache);

                    if (value instanceof Collection<?> collection) {
                        if (!collection.isEmpty()) {
                            var inClause = cb.in((jakarta.persistence.criteria.Expression<Object>) targetPath);
                            for (Object item : collection) {
                                inClause.value(item);
                            }
                            predicates.add(inClause);
                        }
                    } else {
                        if (operation == Operation.LIKE && value instanceof String stringValue) {
                            predicates.add(cb.like(cb.lower(targetPath.as(String.class)), "%" + stringValue.toLowerCase() + "%"));
                        } else {
                            predicates.add(cb.equal(targetPath, value));
                        }
                    }

                } catch (IllegalAccessException e) {
                    throw new RuntimeException("DTO alanına erişilemedi: " + metadata.field().getName(), e);
                }
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    /**
     * Resolves a path expression with JOIN deduplication.
     * Uses a cache to ensure that the same relationship is only joined once,
     * preventing duplicate JOINs in the generated SQL.
     *
     * @param root The root entity
     * @param pathExpression Path expression (e.g., "author.firstName")
     * @param joinCache Cache to store and reuse JOIN instances
     * @return Resolved path
     */
    private Path<?> resolvePathWithJoinCache(From<?, ?> root, String pathExpression,
                                             java.util.Map<String, jakarta.persistence.criteria.Join<?, ?>> joinCache) {
        String[] parts = pathExpression.split("\\.");
        From<?, ?> current = root;

        // Navigate through the path, reusing existing JOINs
        StringBuilder pathBuilder = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (i > 0) pathBuilder.append(".");
            pathBuilder.append(parts[i]);

            String joinPath = pathBuilder.toString();

            // Check if JOIN already exists in cache
            jakarta.persistence.criteria.Join<?, ?> join = joinCache.get(joinPath);
            if (join == null) {
                // Create new JOIN and cache it
                join = current.join(parts[i], JoinType.LEFT);
                joinCache.put(joinPath, join);
            }
            current = join;
        }

        // Return the final field path
        return current.get(parts[parts.length - 1]);
    }

    /**
     * Collects all root paths that need FETCH JOIN to prevent N+1 problem.
     * Detects nested paths (e.g., "author.firstName") and extracts root path ("author").
     *
     * @param fields Field metadata list
     * @param searchDto Search DTO instance
     * @param fetchPaths Set to collect fetch paths
     */
    private void collectFetchPaths(List<FieldMetadata> fields, Object searchDto, java.util.Set<String> fetchPaths) {
        for (FieldMetadata metadata : fields) {
            try {
                Object value = metadata.field().get(searchDto);
                if (value == null) continue;

                // Collect from @QueryTerm
                if (metadata.queryTerm() != null && value instanceof String && !((String) value).isBlank()) {
                    for (String path : metadata.queryTerm().value()) {
                        extractRootPath(path, fetchPaths);
                    }
                }

                // Collect from @QueryRange
                if (metadata.queryRange() != null && value instanceof Range<?>) {
                    extractRootPath(metadata.queryRange().value(), fetchPaths);
                }

                // Collect from @QueryPath
                if (metadata.queryPath() != null) {
                    extractRootPath(metadata.queryPath().value(), fetchPaths);
                }

            } catch (IllegalAccessException e) {
                // Ignore
            }
        }
    }

    /**
     * Extracts root path from a potentially nested path.
     * Example: "author.firstName" → adds "author" to fetchPaths
     *
     * @param path Path expression
     * @param fetchPaths Set to add root path
     */
    private void extractRootPath(String path, java.util.Set<String> fetchPaths) {
        if (path.contains(".")) {
            String rootPath = path.substring(0, path.indexOf("."));
            fetchPaths.add(rootPath);
        }
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
