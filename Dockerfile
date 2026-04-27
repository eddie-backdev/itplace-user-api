FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd -r appgroup && useradd --no-log-init -r -g appgroup appuser
USER appuser

COPY build/libs/*.jar application.jar

ENTRYPOINT ["java", "-jar", "application.jar"]
