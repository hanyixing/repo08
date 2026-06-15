FROM eclipse-temurin:21-jre AS builder

WORKDIR /application
ARG JAR_FILE=/application/build/libs/*.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre
LABEL maintainer="johnniang <johnniang@foxmail.com>"
WORKDIR /application
COPY --from=builder /application/extracted/dependencies/ ./
COPY --from=builder /application/extracted/spring-boot-loader/ ./
COPY --from=builder /application/extracted/snapshot-dependencies/ ./
COPY --from=builder /application/extracted/application/ ./

# Install curl for health check
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

ENV JVM_OPTS="" \
    JAVA_MEMORY_OPTS="-Xms256m -Xmx512m" \
    HALO_WORK_DIR="/root/.halo2" \
    SPRING_CONFIG_LOCATION="optional:classpath:/;optional:file:/root/.halo2/" \
    TZ=Asia/Shanghai

RUN ln -sf /usr/share/zoneinfo/$TZ /etc/localtime \
    && echo $TZ > /etc/timezone

RUN java -XX:ArchiveClassesAtExit=application.jsa -Dspring.context.exit=onRefresh -jar application.jar --halo.work-dir=/tmp/halo2 \
    && rm -rf /tmp/halo2

# Health check configuration
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8090/actuator/health/readiness || exit 1

EXPOSE 8090

ENTRYPOINT ["sh", "-c", "exec java ${JVM_OPTS} ${JAVA_MEMORY_OPTS} -XX:SharedArchiveFile=application.jsa -XX:+ExitOnOutOfMemoryError -jar application.jar \"$@\"", "--"]
