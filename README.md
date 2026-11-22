# ms-talleres

Talleres grupales y control de cupos. Cubre **RF-06 y RF-07**. El cupo se descuenta de
forma atómica en Redis (`DECR`, mismo patrón que la reserva de horario de ms-agenda); al
agotarse, la inscripción pasa a **lista de espera** (`EN_ESPERA`) en vez de perderse.

**Puerto:** 8084 · **Base de datos:** `talleres_db` · **Swagger:** http://localhost:8084/swagger-ui.html

## Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/talleres` | Crea un taller (201) |
| GET | `/api/talleres?proximos=` | Lista talleres (filtro opcional de próximos) |
| GET | `/api/talleres/{id}` | Obtiene un taller (404 si no existe) |
| PUT | `/api/talleres/{id}` | Actualiza el taller |
| DELETE | `/api/talleres/{id}` | Elimina el taller (204) |
| POST | `/api/talleres/{id}/inscripciones` | Inscribe paciente (201; CONFIRMADA o EN_ESPERA) |
| GET | `/api/talleres/{id}/inscripciones` | Lista inscripciones del taller |
| DELETE | `/api/talleres/{id}/inscripciones/{inscId}` | Cancela la inscripción y devuelve el cupo |

## Cómo correr localmente

```bash
# Opción A — todo el sistema en contenedores (repo rys-infraestructura, clonado al lado de este)
cd ../rys-infraestructura && docker compose up -d --build
curl http://localhost:8084/api/talleres

# Opción B — solo este servicio con Maven (requiere PostgreSQL y Redis locales)
mvn spring-boot:run

# Opción C — solo la imagen Docker de este servicio
docker build -t rys/ms-talleres:local .
docker run --rm -p 8084:8084 -e DB_HOST=host.docker.internal rys/ms-talleres:local
curl http://localhost:8084/actuator/health
```

## Arquitectura

Capas `api` → `service` → `repository` → `domain`. Reglas de negocio: no duplicar
inscripción activa del mismo paciente (409) y cupo total nunca menor que los ya ocupados (409).
Relación JPA destacada: `Taller` **OneToMany** `Inscripcion` (y **ManyToOne** inversa).

## Imagen Docker (EP3)

`Dockerfile` **multi-stage**: la etapa `build` (Maven + JDK 21) descarga dependencias en una capa aparte (caché)
y separa el jar en las capas de Spring Boot; la etapa `runtime` usa solo **JRE 21 sobre Alpine**, corre con un
**usuario sin privilegios** (UID 10001), expone el puerto 8084, define un `HEALTHCHECK` y ajusta la JVM al
límite de memoria del contenedor (`MaxRAMPercentage`). `.dockerignore` deja fuera `target/`, `.git/` y el resto.
Tamaño aproximado: ~145 MB comprimida (≈400 MB descomprimida, de los cuales ~180 MB son el JRE compartido).

## Despliegue en la nube (EP3)

Corre en el clúster **Docker Swarm** de AWS como el servicio `rys_ms-talleres` (definido en
`rys-infraestructura/docker-compose.yml`), detrás del **API Gateway**:
`https://<api>.execute-api.us-east-1.amazonaws.com/prod/talleres/api/talleres` (cabecera `x-api-key`).

### Pipeline CI/CD (`.github/workflows/ci-cd.yml`)

| Etapa | Cuándo | Qué hace |
|---|---|---|
| 1 · Build y pruebas | push y pull request a `main`/`develop` | `mvn verify` + reporte de pruebas |
| 2 · Recursos cloud | push a `main` | Crea/verifica el repositorio ECR `rys/ms-talleres` (análisis de vulnerabilidades al subir) |
| 3 · Imagen | push a `main` | `docker build` multi-stage y push a Amazon ECR con etiqueta = commit (+ `latest`) |
| 4 · Despliegue | push a `main` | `docker service update --image …` por SSH al manager: rolling update `start-first` con rollback automático |
| 5 · Prueba de humo | push a `main` | Por el API Gateway: `/actuator/info` muestra la versión del commit, `/actuator/health` = UP, `GET /api/talleres` = 200 y sin API key = 403 |

Secrets del repositorio: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` (Learner Lab, se renuevan
en cada sesión), `SWARM_HOST`, `SWARM_SSH_KEY`, `API_URL`, `API_KEY`.

## Correcciones aplicadas sobre la EP2

- **Configuración externa:** la conexión a la base de datos (y a Redis) estaba fija en `localhost`, lo que impedía
  ejecutar el servicio en contenedores; ahora se toma de `DB_HOST`, `DB_PORT`, `DB_NAME`, `REDIS_HOST` (con
  valores por defecto para desarrollo).
- **Salud del servicio:** se agregó Spring Boot Actuator (`/actuator/health/liveness`, `/readiness`, `/info` con la
  versión desplegada), que usan el `HEALTHCHECK` de Docker, Swarm y la prueba de humo del pipeline.
- **Apagado ordenado** (`server.shutdown: graceful`): al escalar hacia abajo o actualizar, termina las peticiones en curso.
- **Secretos fuera del código:** la clave de la base de datos llega como secreto de Docker Swarm
  (`/run/secrets/spring.datasource.password`, leído con `spring.config.import: configtree`).
- `spring.jpa.open-in-view: false` y pool de conexiones acotado (`DB_POOL_SIZE`, 4 por réplica) para que
  varias réplicas compartan la misma instancia RDS.
- **La lista de espera no avanzaba:** al cancelar una inscripción confirmada el cupo volvía al taller aunque
  hubiera personas `EN_ESPERA`. Ahora el cupo pasa a la primera persona de la lista (orden de llegada) y solo si no
  hay nadie esperando vuelve al contador. Pruebas nuevas en `TallerServiceTest`.
