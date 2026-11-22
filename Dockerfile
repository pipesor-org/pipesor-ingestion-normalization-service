FROM eclipse-temurin:25-jre-noble@sha256:41a70a160a5a157126929c1901e8dcf17ea4fd88eca1e7b9abf3cb1bb04df6c2
ARG VCS_REF
LABEL org.opencontainers.image.source="https://github.com/pipesor-org/pipesor-ingestion-normalization-service" \
      org.opencontainers.image.revision="${VCS_REF}"
RUN test -n "$VCS_REF" \
    && groupadd --gid 10001 pipesor \
    && useradd --uid 10001 --gid pipesor --no-create-home --shell /usr/sbin/nologin pipesor
ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080
WORKDIR /app
COPY --chown=10001:10001 target/pipesor-ingestion-normalization-service-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
