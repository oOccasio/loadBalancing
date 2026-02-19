# 빌드 스테이지
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Gradle 래퍼와 빌드 스크립트만 먼저 복사 (의존성 캐싱을 위해)
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# 실행 권한 부여 및 의존성 다운로드
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon

# 소스 코드 복사 및 빌드
COPY src src
RUN ./gradlew clean build -x test --no-daemon

# 런타임 스테이지
FROM eclipse-temurin:21-jre

WORKDIR /app

# 빌드 스테이지에서 JAR 파일만 복사
COPY --from=builder /app/build/libs/*.jar app.jar

# 애플리케이션용 사용자 생성 (보안 강화)
RUN addgroup --system spring && adduser --system spring --ingroup spring
USER spring:spring

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]