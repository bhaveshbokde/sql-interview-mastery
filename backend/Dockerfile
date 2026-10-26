# Multi-stage, so the shipped image carries a JRE and a jar rather than
# Maven, the sources and a full JDK.
#
# The dependency layer is separated from the source layer deliberately: a
# code change then re-resolves nothing, which turns a rebuild from minutes
# into seconds. It only works because the COPY of pom.xml comes before the
# COPY of src, which is easy to undo by tidying the file.

# ------------------------------------------------------------- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, cached as their own layer.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are skipped here on purpose. They need a database, and a build that
# silently starts one would hide whether the real suite passes. The tests are
# run by `./mvnw verify` against the provisioned test database — see
# docs/TESTING.md — and a container build is not the place to discover a
# failing test.
RUN mvn -B -q clean package -DskipTests

# ------------------------------------------------------------ runtime ----
FROM eclipse-temurin:21-jre-alpine AS runtime

# wget for the Compose healthcheck; the JRE image has no curl.
RUN apk add --no-cache wget \
 && addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=build /build/target/sql-interview-mastery-*.jar app.jar
USER app

EXPOSE 8085

# MaxRAMPercentage rather than a fixed -Xmx, so the JVM respects whatever
# limit the container is given instead of assuming the host's memory.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Djava.security.egd=file:/dev/urandom"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
