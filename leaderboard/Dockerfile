FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -B -DskipTests
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /build/target/leaderboard-1.0.0.jar /app/app.jar
USER app
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
