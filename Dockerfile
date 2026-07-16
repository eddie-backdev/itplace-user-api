FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd -r appgroup && useradd --no-log-init -r -g appgroup appuser
USER appuser

COPY build/libs/*.jar application.jar

EXPOSE 8080 9090

ENTRYPOINT ["java", "-jar", "application.jar"]
