FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn --batch-mode clean verify

FROM eclipse-temurin:21-jre-alpine AS java-runtime

FROM ghcr.io/anomalyco/opencode:2.4.1
USER root
RUN apk add --no-cache bash su-exec tzdata \
    && addgroup -g 4196 assistant-ai \
    && adduser -D -u 4196 -G assistant-ai assistant-ai
COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
WORKDIR /app
COPY --from=build /build/target/bateacapitalassistant-1.0.0.jar /app/bateacapitalassistant.jar
COPY opencode.json /etc/batea-opencode.json
COPY scripts/run-assistant.sh /app/run-assistant.sh
ENV TZ=Europe/Madrid \
    BATEA_OPENCODE_URL=http://127.0.0.1:4196 \
    OPENCODE_CONFIG=/etc/batea-opencode.json \
    OPENCODE_ENABLE_EXA=1 \
    XDG_DATA_HOME=/opencode-data
RUN /opt/java/openjdk/bin/java -version && opencode --version \
    && install -d -o 4196 -g 4196 /opencode-data /workspace
ENTRYPOINT ["/bin/bash", "/app/run-assistant.sh"]
CMD []
