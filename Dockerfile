FROM eclipse-temurin:11-jre as builder
WORKDIR application
ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=layertools -jar application.jar extract

################################

FROM eclipse-temurin:11-jre
LABEL maintainer="johnniang <johnniang@fastmail.com>"
WORKDIR application

# curl is required by the container HEALTHCHECK below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder application/dependencies/ ./
COPY --from=builder application/spring-boot-loader/ ./
COPY --from=builder application/snapshot-dependencies/ ./
COPY --from=builder application/application/ ./

# JAVA_OPTS is container-aware: the JVM sizes its heap as a percentage of the
# memory limit imposed by Docker / docker-compose (see `deploy.resources` there)
# instead of a fixed value, so memory limits are actually respected.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0" \
    TZ=Asia/Shanghai

RUN ln -sf /usr/share/zoneinfo/$TZ /etc/localtime \
    && echo $TZ > /etc/timezone

EXPOSE 8090

# Probe the actuator health endpoint. base-path is /api/admin/actuator and the
# 'health' endpoint is exposed in application.yaml. start-period covers the
# (potentially slow) Spring Boot + JPA/Flyway boot before failures are counted.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -f http://localhost:8090/api/admin/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Djava.security.egd=file:/dev/./urandom org.springframework.boot.loader.JarLauncher"]
