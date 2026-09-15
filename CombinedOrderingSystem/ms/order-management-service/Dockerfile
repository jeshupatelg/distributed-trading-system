# Multi-stage build
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Copy common schema and CombinedOrderingSystem modules
COPY config/grpc-proto-schema ./config/grpc-proto-schema
COPY CombinedOrderingSystem ./CombinedOrderingSystem

WORKDIR /build/CombinedOrderingSystem

# Build the specific module along with its local library dependencies
RUN mvn clean package -pl ms/order-management-service -am -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/CombinedOrderingSystem/ms/order-management-service/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

