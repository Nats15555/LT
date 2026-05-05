FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /workspace

COPY gradle gradle
COPY gradlew gradlew.bat settings.gradle.kts ./
COPY app-module app-module
COPY execution-module execution-module
COPY metrics-collector-module metrics-collector-module
COPY summarization-module summarization-module

RUN chmod +x gradlew \
    && ./gradlew :app-module:bootJar :execution-module:bootJar :metrics-collector-module:bootJar :summarization-module:bootJar --no-daemon --parallel


FROM eclipse-temurin:17-jre-jammy AS app
COPY --from=builder /workspace/app-module/build/libs/*.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]

FROM eclipse-temurin:17-jre-jammy AS execution
COPY --from=builder /workspace/execution-module/build/libs/*.jar /app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app.jar"]

FROM eclipse-temurin:17-jre-jammy AS metrics-collector
COPY --from=builder /workspace/metrics-collector-module/build/libs/*.jar /app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app.jar"]

FROM eclipse-temurin:17-jre-jammy AS summarization
COPY --from=builder /workspace/summarization-module/build/libs/*.jar /app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "/app.jar"]
