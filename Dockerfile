# syntax=docker/dockerfile:1.7

FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./

RUN --mount=type=cache,id=s/93c82963-37c3-414d-9a3c-6a1239f75087-/root/.m2,target=/root/.m2 \
    mvn -B -DskipTests dependency:go-offline

COPY src src
COPY legal-documents legal-documents

RUN --mount=type=cache,id=s/93c82963-37c3-414d-9a3c-6a1239f75087-/root/.m2,target=/root/.m2 \
    mvn -B -DskipTests package

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
