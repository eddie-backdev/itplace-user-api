# --- 1단계: Builder ---
# Gradle과 JDK 17을 사용하여 애플리케이션을 빌드합니다.
FROM gradle:jdk17 AS builder
WORKDIR /app
COPY . .
# Gradle 빌드 실행 (테스트는 제외)
RUN ./gradlew build -x test

# --- 2단계: Final Image ---
# 경량 JRE 이미지에 빌드된 JAR 파일만 복사합니다.
FROM eclipse-temurin:17-jre
WORKDIR /app

# 보안을 위해 비-루트 사용자로 실행합니다.
RUN groupadd -r appgroup && useradd --no-log-init -r -g appgroup appuser
USER appuser

# builder 단계에서 생성된 JAR 파일을 복사합니다.
COPY --from=builder /app/build/libs/*.jar application.jar

# (선택) JVM 옵션 주입용
ENV JAVA_OPTS=""

# 단순 java -jar 명령으로 애플리케이션을 실행합니다.
ENTRYPOINT ["java", "-jar", "application.jar"]