# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml,required=true \
    --mount=type=secret,id=package_token,required=true \
    JSC_PACKAGE_READ_TOKEN="$(cat /run/secrets/package_token)" \
    mvn -B --no-transfer-progress clean verify

FROM eclipse-temurin:17-jre-alpine
# Refresh runtime packages to Alpine's CVE-fixed builds: OpenSSL (CVE-2026-14456)
# and libexpat 2.8.4 (CVE-2026-76956/76641/76957/66046 - hash-flooding DoS).
RUN apk add --no-cache --upgrade \
    libcrypto3=3.5.8-r0 \
    libssl3=3.5.8-r0 \
    expat=2.8.4-r0 \
    openssl=3.5.8-r0
WORKDIR /app
COPY --from=build /app/target/document-generation-gateway-1.0.0.jar app.jar
RUN apk add --no-cache curl
EXPOSE 8092
ENTRYPOINT ["java", "-jar", "app.jar"]
