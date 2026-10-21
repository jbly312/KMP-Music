# Сервер синхронизации KMP Music.
# Собирается только сервер (KMP_SERVER_ONLY): desktop-модули с Compose в образ не попадают.

# JDK того же вендора, что указан в gradle/gradle-daemon-jvm.properties, — Gradle не будет скачивать свой
FROM azul/zulu-openjdk:21 AS build
WORKDIR /src
ENV KMP_SERVER_ONLY=1
COPY . .
RUN chmod +x gradlew && ./gradlew :server:installDist --no-daemon --console=plain -x test

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S kmp && adduser -S kmp -G kmp \
    && mkdir /data && chown kmp:kmp /data
COPY --from=build /src/server/build/install/server /app
USER kmp
ENV DATA_DIR=/data \
    PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s CMD wget -qO- http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["/app/bin/server"]
