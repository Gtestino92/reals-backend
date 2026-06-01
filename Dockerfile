FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw

COPY src src
RUN ./mvnw -DskipTests package

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

RUN groupadd --system reals && useradd --system --gid reals reals

COPY --from=build /workspace/target/reals-backend-*.jar /app/app.jar

USER reals

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
