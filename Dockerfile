# Use a multi-stage build to generate the JAR file and then package it into the Docker image
FROM amazoncorretto:25.0.5-alpine3.24 AS build
WORKDIR /app
COPY build.gradle settings.gradle gradle.properties gradle.lockfile gradlew ./
COPY gradle ./gradle
COPY src ./src
RUN ./gradlew bootJar copyPyroscopeAgent --no-daemon --no-watch-fs

# Use the same patched Java distribution for build and runtime.
FROM amazoncorretto:25.0.5-alpine3.24
ARG JAR_FILE=/app/build/libs/*.jar
RUN addgroup -S -g 10001 popcorn \
    && adduser -S -D -H -u 10001 -G popcorn popcorn
WORKDIR /app
COPY --from=build --chown=10001:10001 ${JAR_FILE} app.jar
COPY --from=build --chown=10001:10001 /app/build/pyroscope/pyroscope.jar /opt/pyroscope/pyroscope.jar
COPY --chmod=0555 scripts/backend-entrypoint /usr/local/bin/backend-entrypoint
USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["/usr/local/bin/backend-entrypoint"]
