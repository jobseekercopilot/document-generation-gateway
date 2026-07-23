FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY libs ./libs
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/document-generation-gateway-1.0.0.jar app.jar
RUN apk add --no-cache curl
EXPOSE 8092
ENTRYPOINT ["java", "-jar", "app.jar"]
