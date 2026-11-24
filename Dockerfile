FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn --batch-mode clean verify

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/bateacapitalassistant-1.0.0.jar /app/bateacapitalassistant.jar
ENV TZ=Europe/Madrid
ENTRYPOINT ["java", "-jar", "/app/bateacapitalassistant.jar"]
