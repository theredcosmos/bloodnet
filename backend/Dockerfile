# --- build stage: full JDK + Maven, dependencies cached in their own layer
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests run in CI (they need Docker for Testcontainers), not inside the image build.
RUN mvn -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/bloodnet-backend-*.jar extract --layers --launcher --destination /extracted

# --- runtime stage: JRE only, non-root user, layered jar so code changes don't re-copy dependencies
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
