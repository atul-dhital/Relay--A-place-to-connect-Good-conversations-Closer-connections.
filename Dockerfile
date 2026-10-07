FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -B

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --create-home chat && mkdir -p /data/images && chown -R chat:chat /app /data
COPY --from=build /app/target/real-time-chat-0.0.1-SNAPSHOT.jar /app/chat.jar
ENV FILE_UPLOAD_DIR=/data/images
VOLUME /data/images
USER chat
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/chat.jar"]
