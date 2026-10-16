
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY utils/pom.xml utils/pom.xml
COPY core/pom.xml core/pom.xml
COPY infrastructure/pom.xml infrastructure/pom.xml

RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN ./mvnw -B -ntp -q dependency:go-offline -pl infrastructure -am || true

COPY utils utils
COPY core core
COPY infrastructure infrastructure

RUN ./mvnw -B -ntp package -pl infrastructure -am -DskipTests \
    -Dspotless.check.skip=true -Dspotbugs.skip=true -Djacoco.skip=true


FROM eclipse-temurin:25-jre AS runtime

ARG VERSION=dev
ARG REVISION=unknown
ARG SOURCE_URL=unknown

LABEL org.opencontainers.image.title="notification-uco" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.source="${SOURCE_URL}"

RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app

COPY --from=build /workspace/infrastructure/target/infrastructure-*.jar app.jar

USER app
EXPOSE 8060 8061

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]