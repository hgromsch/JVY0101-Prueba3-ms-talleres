# syntax=docker/dockerfile:1.7
# ms-talleres — imagen multi-stage optimizada (EP3, IE1)
#   Etapa 1 (build):   compila con Maven + JDK y separa el jar en capas de Spring Boot.
#   Etapa 2 (runtime): solo JRE 21 sobre Alpine, usuario sin privilegios, sin Maven ni código fuente.
# Construir:  docker build -t rys-ms-talleres:local .
# Ejecutar:   docker run --rm -p 8084:8084 -e DB_HOST=host.docker.internal rys-ms-talleres:local

ARG JAVA_VERSION=21

############################ Etapa 1: build ############################
FROM maven:3.9.9-eclipse-temurin-${JAVA_VERSION}-alpine AS build
WORKDIR /build

# 1) Primero solo el pom: la capa de dependencias queda en caché mientras el pom no cambie
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

# 2) Luego el código. Las pruebas corren en la etapa "test" del pipeline CI/CD, no aquí.
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
 && cp target/ms-talleres-*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extraido

############################ Etapa 2: runtime ##########################
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine AS runtime

ARG APP_VERSION=local
LABEL org.opencontainers.image.title="ms-talleres" \
      org.opencontainers.image.description="Respirar y Soltar — microservicio ms-talleres" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.vendor="Heinrich Gromsch — JVY0101 EP3"

# Usuario y grupo sin privilegios (UID fijo para que los volúmenes/secretos tengan permisos predecibles)
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app -h /app app
WORKDIR /app

# Capas de menor a mayor frecuencia de cambio: al cambiar solo el código se reemplaza la última capa
COPY --from=build --chown=app:app /build/extraido/dependencies/ ./
COPY --from=build --chown=app:app /build/extraido/spring-boot-loader/ ./
COPY --from=build --chown=app:app /build/extraido/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /build/extraido/application/ ./

USER app
EXPOSE 8084

# JVM consciente del límite de memoria del contenedor (deploy.resources.limits en Swarm)
ENV APP_VERSION=${APP_VERSION} \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

# Docker/Swarm reemplazan la tarea si deja de responder
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8084/actuator/health/liveness >/dev/null || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
