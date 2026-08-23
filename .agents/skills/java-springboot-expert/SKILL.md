---
name: "java-springboot-expert"
description: "Specialized skill in Java 21 development and Spring Boot enterprise architectures."
---
# Java & Spring Boot Expert Skill

## Section 1: Java (Core Concepts & Best Practices)
1. Use modern Java features (Java 17/21) such as records, pattern matching, and sealed classes where appropriate.
2. Implement concurrency using Java 21 Virtual Threads (Project Loom) for lightweight I/O blocking tasks (REST, SQL queries) to prevent thread pool starvation.
3. Ensure proper resource management using try-with-resources.

## Section 2: Spring Boot (Enterprise Application Framework)
1. Follow standard controller-service-repository patterns.
2. Configure dedicated thread pools for task executors and scheduler components.
3. Use Spring Kafka for message consumption and production, ensuring proper serialization and idempotency checks.
4. Rely on Spring Boot Starter Web gRPC configurations to manage incoming RPC schemas.
5. **Constructor Injection**: Always use constructor-based dependency injection (avoid field-level `@Autowired` to ensure easy unit testing and state validation).
6. **Strict DTO Mapping**: Always use dedicated DTOs or Models (Java records/classes) for request/response serialization; never parse raw JSON strings, `Map<String, Object>`, or raw JSON payloads.
