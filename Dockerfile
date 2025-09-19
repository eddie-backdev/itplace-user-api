# --- 1단계: Builder ---
# Gradle과 JDK 17을 사용하여 애플리케이션을 빌드합니다.
FROM gradle:jdk17 AS builder
WORKDIR /app

# 1. 의존성 캐싱을 위해 빌드 파일만 먼저 복사합니다.
# 소스 코드가 변경되어도 이 레이어들은 캐시된 상태로 유지됩니다.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./

# 2. 의존성을 다운로드합니다. 이 단계는 build.gradle 파일이 변경될 때만 다시 실행됩니다.
RUN ./gradlew dependencies --no-daemon

# 3. 소스 코드를 복사합니다. (이 부분만 자주 변경됩니다)
COPY src ./src

# 4. 캐시된 의존성을 사용하여 애플리케이션을 빌드합니다.
RUN ./gradlew build -x test --no-daemon

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