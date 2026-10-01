
FROM eclipse-temurin:21-jdk AS build
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


FROM eclipse-temurin:21-jre AS runtime

RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app

COPY --from=build /workspace/infrastructure/target/infrastructure-*.jar app.jar

USER app
EXPOSE 8060

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]