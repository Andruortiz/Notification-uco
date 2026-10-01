# syntax=docker/dockerfile:1

# ---------- Etapa 1: compilar ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copiar solo los pom primero para aprovechar la cache de dependencias
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY utils/pom.xml utils/pom.xml
COPY core/pom.xml core/pom.xml
COPY infrastructure/pom.xml infrastructure/pom.xml

# Quitar fin de linea de Windows (CRLF) del wrapper de Maven
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN ./mvnw -B -ntp -q dependency:go-offline -pl infrastructure -am || true

COPY utils utils
COPY core core
COPY infrastructure infrastructure

# Las pruebas y las puertas de calidad ya corren en el CI
RUN ./mvnw -B -ntp package -pl infrastructure -am -DskipTests \
    -Dspotless.check.skip=true -Dspotbugs.skip=true -Djacoco.skip=true

# ---------- Etapa 2: ejecutar ----------
FROM eclipse-temurin:21-jre AS runtime

RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app

COPY --from=build /workspace/infrastructure/target/infrastructure-*.jar app.jar

USER app
EXPOSE 8060

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
