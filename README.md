# SpecMate 🔍

**Dynamic JPA Specification Builder** - Annotation-driven solution for building complex JPA queries dynamically

## Overview

SpecMate is a powerful, annotation-driven library that simplifies the creation of dynamic JPA Specification queries. Instead of writing repetitive specification code, you can use simple annotations on your DTO fields to automatically generate complex queries with support for:

- ✅ Multi-field text search
- ✅ Range queries (between, greater than, less than)
- ✅ Collection filtering (IN queries)
- ✅ Nested entity relationships
- ✅ Multiple search operations (EQUAL, LIKE)
- ✅ Automatic AND/OR combinations

## Features

### 🔍 Text Search with `@QueryTerm`
Search across multiple fields simultaneously with a single search term.

```java
public class ProductSearchDto {
    @QueryTerm({"name", "description", "category.name"})
    private String searchKeyword;
}
```
**Generated Query**: `WHERE (name LIKE '%keyword%' OR description LIKE '%keyword%' OR category.name LIKE '%keyword%')`

### 📊 Range Queries with `@QueryRange`
Handle date ranges, price ranges, and any comparable values.

```java
public class ProductSearchDto {
    @QueryRange("price")
    private Range<BigDecimal> priceRange;
    
    @QueryRange("createdAt")
    private Range<LocalDateTime> dateRange;
}
```
**Generated Queries**:
- Both values: `WHERE price BETWEEN ? AND ?`
- From only: `WHERE price >= ?`
- To only: `WHERE price <= ?`

### 🎯 Path-based Queries with `@QueryPath`
Query specific fields with custom operations and support for nested relationships.

```java
public class UserSearchDto {
    @QueryPath(value = "email", operation = Operation.LIKE)
    private String email;
    
    @QueryPath("department.manager.name")
    private String managerName;
    
    @QueryPath("status")
    private List<UserStatus> statusList; // Automatic IN query
}
```

### 🔗 Relationship Navigation
Navigate through entity relationships using dot notation:

```java
@QueryPath("user.profile.address.city")
private String cityName;

@QueryTerm({"customer.firstName", "customer.lastName", "customer.company.name"})
private String customerSearch;
```

## Quick Start

### 1. Add Dependencies

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
    <version>3.1.4</version>
</dependency>
<dependency>
    <groupId>jakarta.persistence</groupId>
    <artifactId>jakarta.persistence-api</artifactId>
    <version>3.1.0</version>
</dependency>
```

### 2. Create Your Search DTO

```java
public class ProductSearchDto {
    // Multi-field search
    @QueryTerm({"name", "description", "brand.name"})
    private String searchTerm;
    
    // Range filtering
    @QueryRange("price")
    private Range<BigDecimal> priceRange;
    
    // Exact match on nested property
    @QueryPath("category.id")
    private Long categoryId;
    
    // Collection filtering (IN query)
    @QueryPath("status")
    private List<ProductStatus> statusList;
    
    // LIKE operation
    @QueryPath(value = "sku", operation = Operation.LIKE)
    private String skuPattern;
    
    // Getters and setters...
}
```

### 3. Use in Your Repository

```java
@RestController
public class ProductController {
    
    @Autowired
    private ProductRepository productRepository;
    
    private final SpecificationBuilder<Product> specBuilder = new SpecificationBuilder<>();
    
    @PostMapping("/products/search")
    public Page<Product> searchProducts(
            @RequestBody ProductSearchDto searchDto,
            Pageable pageable) {
        
        Specification<Product> spec = specBuilder.build(searchDto);
        return productRepository.findAll(spec, pageable);
    }
}
```

## Advanced Usage Examples

### Complex E-commerce Search

```java
public class OrderSearchDto {
    // Search in customer details
    @QueryTerm({"customer.firstName", "customer.lastName", "customer.email"})
    private String customerSearch;
    
    // Date range filtering
    @QueryRange("orderDate")
    private Range<LocalDateTime> orderDateRange;
    
    // Multiple status filtering
    @QueryPath("status")
    private List<OrderStatus> statusList;
    
    // Price range
    @QueryRange("totalAmount")
    private Range<BigDecimal> amountRange;
    
    // Shipping address search
    @QueryTerm({"shippingAddress.city", "shippingAddress.state"})
    private String locationSearch;
    
    // Product search in order items
    @QueryTerm({"orderItems.product.name", "orderItems.product.sku"})
    private String productSearch;
}
```

### User Management Search

```java
public class UserSearchDto {
    // Basic user info search
    @QueryTerm({"firstName", "lastName", "username", "email"})
    private String userSearch;
    
    // Department filtering
    @QueryPath(value = "department.name", operation = Operation.LIKE)
    private String departmentName;
    
    // Role-based filtering
    @QueryPath("roles.name")
    private List<String> roleNames;
    
    // Registration date range
    @QueryRange("createdAt")
    private Range<LocalDateTime> registrationPeriod;
    
    // Active/inactive status
    @QueryPath("isActive")
    private Boolean activeStatus;
    
    // Manager search
    @QueryPath(value = "manager.firstName", operation = Operation.LIKE)
    private String managerName;
}
```

## Annotation Reference

### `@QueryTerm`
Multi-field text search with LIKE operation.

```java
@QueryTerm(value = {"field1", "field2", "nested.field"}, operation = Operation.LIKE)
```

**Parameters:**
- `value`: Array of field paths to search
- `operation`: Search operation (default: `LIKE`)

**Behavior:** Creates OR conditions between specified fields

### `@QueryRange`
Range-based filtering for comparable values.

```java
@QueryRange("fieldName")
private Range<T> rangeValue;
```

**Parameters:**
- `value`: Field path for range query

**Supported Operations:**
- Both from/to: `BETWEEN`
- From only: `>=`
- To only: `<=`

### `@QueryPath`
Single field query with customizable operation.

```java
@QueryPath(value = "fieldPath", operation = Operation.EQUAL)
```

**Parameters:**
- `value`: Field path (supports nested properties)
- `operation`: Query operation (default: `EQUAL`)

**Auto-detection:**
- `Collection` values → `IN` query
- `String` with `LIKE` → Case-insensitive contains
- Other types → Exact match

## Supported Operations

| Operation | Description | Example |
|-----------|-------------|---------|
| `EQUAL` | Exact match | `WHERE field = value` |
| `LIKE` | Case-insensitive contains | `WHERE LOWER(field) LIKE '%value%'` |
| `BETWEEN` | Range query | `WHERE field BETWEEN from AND to` |
| `IN` | Collection match | `WHERE field IN (value1, value2, ...)` |

## Path Resolution

SpecMate automatically handles JPA joins for nested properties:

```java
"user.profile.address.city" 
// Generates: LEFT JOIN user.profile LEFT JOIN profile.address
```

**Supported Patterns:**
- Simple fields: `name`, `email`
- One level: `category.name`
- Multiple levels: `user.profile.address.city`
- Collections: `orderItems.product.name`

## Best Practices

### 1. DTO Design
```java
public class SearchDto {
    // Group related searches
    @QueryTerm({"title", "description"})
    private String contentSearch;
    
    // Use meaningful field names
    @QueryRange("publishedDate")
    private Range<LocalDate> publicationPeriod;
    
    // Leverage auto-detection
    @QueryPath("tags") // Auto IN query for List<String>
    private List<String> tagList;
}
```

### 2. Performance Considerations
```java
// Index your searchable fields
@Entity
@Table(indexes = {
    @Index(columnList = "name"),
    @Index(columnList = "category_id"),
    @Index(columnList = "created_at")
})
public class Product {
    // Entity definition
}
```

### 3. Validation
```java
@Valid
public class ProductSearchDto {
    @Size(min = 2, message = "Search term must be at least 2 characters")
    @QueryTerm({"name", "description"})
    private String searchTerm;
    
    @Valid
    @QueryRange("price")
    private Range<BigDecimal> priceRange;
}
```

## Error Handling

The library provides clear error messages for common issues:

```java
try {
    Specification<Product> spec = specBuilder.build(searchDto);
    return repository.findAll(spec, pageable);
} catch (RuntimeException e) {
    // Handle "DTO alanına erişilemedi" errors
    log.error("Failed to build specification", e);
    throw new BadRequestException("Invalid search criteria");
}
```

## Technical Details

### Architecture
- **Reflection-based**: Automatically processes DTO fields
- **Type-safe**: Generic `SpecificationBuilder<T>`
- **Spring Integration**: Native JPA Specification support
- **Lazy Evaluation**: Specifications built on-demand

### Requirements
- Java 17+
- Spring Boot 3.1.4+
- Jakarta Persistence 3.1.0+

## License

This project is licensed under the MIT License.

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

**Made with ❤️ for the Spring Boot community**