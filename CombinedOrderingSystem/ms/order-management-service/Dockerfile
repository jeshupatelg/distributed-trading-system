# Multi-stage build
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Copy root pom and modules required for compilation
COPY pom.xml .
COPY libs ./libs
COPY ms/order-management-service ./ms/order-management-service

# Build the specific module along with its local library dependencies
RUN mvn clean package -pl ms/order-management-service -am -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/ms/order-management-service/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
