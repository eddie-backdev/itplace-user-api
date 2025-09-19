# --- 1단계: Builder ---
FROM gradle:jdk17 AS builder
WORKDIR /app

# (선택) Gradle 캐시 디렉터리 지정 → 레이어 캐시 효율↑
ENV GRADLE_USER_HOME=/home/gradle/.gradle

# Gradle Wrapper & 스크립트 먼저 복사 (레이어 캐시 활용)
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
# Windows용 wrapper가 있으면 함께 복사 (없으면 생략 가능)
# COPY gradlew.bat ./

# gradlew 실행 권한 부여
RUN chmod +x ./gradlew

# 의존성만 먼저 다운로드 (캐시 전용 단계)
RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew dependencies --no-daemon || true

# 애플리케이션 소스 복사
COPY src ./src

# 테스트 제외 빌드 (필요시 -x test 제거)
RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew build -x test --no-daemon


# --- 2단계: Layer Extractor ---
# 빌드된 fat JAR에서 Spring Boot layertools로 계층 추출
FROM eclipse-temurin:17-jdk-jammy AS extractor
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar application.jar
RUN java -Djarmode=layertools -jar application.jar extract


# --- 3단계: Final Image ---
# 경량 JRE에 계층만 복사해 최종 실행 이미지 구성
FROM eclipse-temurin:17-jre
WORKDIR /app

# 보안을 위해 비-루트 사용자로 실행
RUN groupadd -r appgroup && useradd --no-log-init -r -g appgroup appuser
USER appuser

# 레이어 순서: 의존성 → 로더 → 스냅샷 의존성 → 앱
COPY --from=extractor /app/dependencies/            ./dependencies/
COPY --from=extractor /app/spring-boot-loader/      ./spring-boot-loader/
COPY --from=extractor /app/snapshot-dependencies/   ./snapshot-dependencies/
COPY --from=extractor /app/application/             ./application/

# (선택) 포트 노출
# EXPOSE 8080

# (선택) JVM 옵션 주입용
ENV JAVA_OPTS=""

# Spring Boot Loader로 실행
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp .:spring-boot-loader/* org.springframework.boot.loader.launch.JarLauncher"]
