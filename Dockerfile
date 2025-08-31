# ARM용 런타임 이미지만 사용
FROM eclipse-temurin:17-jre
WORKDIR /app

# GitHub Actions에서 빌드한 결과물만 복사
COPY build/libs/*.jar application.jar

COPY src/main/resources/prompt/ /app/prompt/

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]