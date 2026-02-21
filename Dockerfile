# Stage 1: Build JAR
FROM maven:3.9.3-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY src ./src
RUN ./mvnw clean package -Pshade -DskipTests

# Stage 2: Run JAR
FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY --from=build /app/target/corerouter-email-worker-1.0.0-SNAPSHOT.jar app.jar
ENTRYPOINT ["sh", "-c", "java -cp app.jar $WORKER_CLASS"]