# syntax=docker/dockerfile:1.7

FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace

ENV MAVEN_OPTS="-Dmaven.repo.local=/opt/maven-repository"

COPY pom.xml ./

# Keep prefetched dependencies in a layer that CI can export.
RUN mvn -B -DskipTests dependency:go-offline

COPY src src
COPY legal-documents legal-documents

RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy

ARG IMAGE_REPOSITORY="ghcr.io/gtestino92/reals-backend"
ARG IMAGE_TAG="local"
ARG IMAGE_REVISION="unknown"

LABEL org.opencontainers.image.source="https://github.com/Gtestino92/reals-backend"
LABEL org.opencontainers.image.description="Reals backend service"
LABEL org.opencontainers.image.revision="${IMAGE_REVISION}"

ENV IMAGE_REPOSITORY="${IMAGE_REPOSITORY}" \
    IMAGE_TAG="${IMAGE_TAG}" \
    IMAGE_REVISION="${IMAGE_REVISION}"

WORKDIR /app

RUN groupadd --system reals && useradd --system --gid reals reals

COPY --from=build /workspace/target/reals-backend-*.jar /app/app.jar

USER reals

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
