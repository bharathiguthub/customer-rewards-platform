# Customer Rewards Platform

A production-ready RESTful backend service for managing customer loyalty accounts and reward point transactions. Built as a portfolio project for a Forward Deployed Engineer role, demonstrating Java/Spring Boot microservice design, clean layered architecture, transactional integrity, API design best practices, and production-grade engineering concerns.

---

## Technology Stack

| Technology | Version | Purpose |
|---|---|---|
| Java | 21 | Core language (LTS) |
| Spring Boot | 3.4.x | Application framework |
| Gradle | 8.x (Kotlin DSL) | Build tool |
| Spring Data JPA | Managed by Spring Boot | ORM / data access |
| PostgreSQL | 16 | Primary relational database |
| Flyway | Managed by Spring Boot | Database schema migrations |
| Bean Validation | Managed by Spring Boot | Input validation |
| Spring Boot Actuator | Managed by Spring Boot | Health checks and metrics |
| springdoc-openapi | 2.8.8 | OpenAPI 3 / Swagger UI |
| JUnit 5 | Managed by Spring Boot | Unit and integration testing |
| Mockito | Managed by Spring Boot | Test mocking |
| Testcontainers | 1.20.4 | Real PostgreSQL in tests |
| Docker | Latest | Containerization |

---

## Architecture Overview

The application follows a strict **clean layered architecture**:

```
Controller → Service → Repository → Entity (Database)
               ↕
             Mapper
               ↕
              DTO
```

| Layer | Package | Responsibility |
|---|---|---|
| **Controller** | `controller` | HTTP request handling, input validation, HTTP status codes. No business logic. |
| **Service** | `service` | All business rules, transactional coordination, idempotency logic. Returns DTOs. |
| **Repository** | `repository` | Spring Data JPA interfaces for data access. No business logic. |
| **Entity** | `entity` | JPA-annotated domain objects. Never exposed directly via API. |
| **DTO** | `dto` | Plain Java records for request/response payloads. No JPA annotations. |
| **Mapper** | `mapper` | Stateless converters between entities and DTOs. |
| **Exception** | `exception` | Custom exceptions and global `@RestControllerAdvice` handler. |
| **Config** | `config` | Spring bean configuration: OpenAPI, Jackson, CORS. |

Clean layering keeps concerns separated, makes each layer independently testable, and ensures the database schema never leaks through to API consumers.

---

## Local Prerequisites

- **Java 21** — [Download](https://adoptium.net/)
- **Docker Desktop** — Required for Testcontainers (integration tests) and docker-compose local development
- **Gradle** — Or use the included `./gradlew` wrapper (no installation required)

---

## Local Development Setup

### Environment Variables

The application uses environment variables for all database credentials. Never hardcode secrets in source code.

| Variable | Description | Example |
|---|---|---|
| `DB_URL` | JDBC connection URL | `jdbc:postgresql://localhost:5432/customerrewards` |
| `DB_USERNAME` | Database username | `rewards_user` |
| `DB_PASSWORD` | Database password | `rewards_pass` |

Create a `.env` file (excluded from git) or export variables in your shell:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/customerrewards
export DB_USERNAME=rewards_user
export DB_PASSWORD=rewards_pass
```

### Running with Docker

#### Prerequisites

- **Docker** — [Download Docker Desktop](https://www.docker.com/products/docker-desktop)
- **PostgreSQL in Docker** — A running PostgreSQL container (see setup below)

#### PostgreSQL Container Setup

The application connects to a PostgreSQL container running on the Docker network. First, create the database container:

```bash
docker run -d \
  --name fde-rewards-postgres \
  -e POSTGRES_DB=customer_rewards \
  -e POSTGRES_USER=rewards_user \
  -e POSTGRES_PASSWORD=rewards_pass \
  -p 5433:5432 \
  postgres:16
```

#### Create Docker Network

Create a bridge network for inter-container communication:

```bash
docker network create rewards-network
```

Connect the PostgreSQL container to the network:

```bash
docker network connect rewards-network fde-rewards-postgres
```

#### Build and Run the Spring Boot Application

The application is packaged as a Docker image. Build it:

```bash
docker build -t customer-rewards:latest .
```

Run the container on the rewards network with environment variables for database credentials:

```bash
docker run -d \
  --name customer-rewards-app \
  --network rewards-network \
  -p 8080:8080 \
  -p 8081:8081 \
  -e DB_URL=jdbc:postgresql://fde-rewards-postgres:5432/customer_rewards \
  -e DB_USERNAME=rewards_user \
  -e DB_PASSWORD=rewards_pass \
  customer-rewards:latest
```

**Key points:**
- The application runs as a non-root user (`appuser`) for security.
- Database credentials are supplied via environment variables — **never hardcoded**.
- When running inside Docker, the application connects to the PostgreSQL container via the container name (`fde-rewards-postgres:5432`) on the Docker network, not `localhost`.
- Port `8080` = REST API
- Port `8081` = Management/Actuator endpoints

#### Verify the Application is Running

Check container status:

```bash
docker ps | grep customer-rewards
```

View application logs:

```bash
docker logs -f customer-rewards-app
```

#### Test Health and Actuator

From your host machine (or another container on the network):

```bash
curl http://localhost:8081/actuator/health
```

Expected response:

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP"
    },
    "diskSpace": {
      "status": "UP"
    },
    "ping": {
      "status": "UP"
    }
  }
}
```

#### Test Swagger UI

Open your browser and navigate to:

```
http://localhost:8080/swagger-ui.html
```

You should see the interactive API documentation for all endpoints.

#### Stop Containers

```bash
docker stop customer-rewards-app fde-rewards-postgres
docker rm customer-rewards-app fde-rewards-postgres
```

#### Docker Image Details

| Component | Details |
|---|---|
| Build stage | Uses `eclipse-temurin:21-jdk-jammy` to compile and build with Gradle wrapper |
| Runtime stage | Uses lightweight `eclipse-temurin:21-jre-jammy` for production |
| Non-root user | Application runs as `appuser` (not root) for security |
| JAR | Copied from builder stage to runtime stage |
| Size | ~637 MB (multi-stage keeps runtime image lean) |
| Ports | 8080 (app), 8081 (management) |
| Entrypoint | `java -jar app.jar` |

### Docker Compose (Planned)

> Docker Compose configuration will be added in a later phase for local development convenience.

---

## Running Tests

Tests use Testcontainers to spin up a real PostgreSQL instance automatically — no manual database setup required.

```bash
./gradlew test
```

Or on Windows PowerShell:

```powershell
.\gradlew.bat test
```

---

## Building

```bash
./gradlew build
```

---

## API Documentation

Once the application is running:

- **Swagger UI**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **OpenAPI JSON**: [http://localhost:8080/api-docs](http://localhost:8080/api-docs)

---

## Actuator (Health & Metrics)

Spring Boot Actuator is exposed on a separate management port:

- **Health**: [http://localhost:8081/actuator/health](http://localhost:8081/actuator/health)
- **Info**: [http://localhost:8081/actuator/info](http://localhost:8081/actuator/info)
- **Metrics**: [http://localhost:8081/actuator/metrics](http://localhost:8081/actuator/metrics)

---

## Future AWS Deployment

The application is designed for cloud-native deployment on AWS:

- **Docker** — The application is packaged as a Docker image using a multi-stage `Dockerfile`.
- **Amazon ECR** — The Docker image is pushed to a private Elastic Container Registry.
- **Amazon ECS Fargate** — The image runs as a serverless container task inside our assigned VPC — no EC2 instances to manage.
- **Amazon RDS PostgreSQL** — The managed PostgreSQL database, deployed in a private subnet.
- **AWS Secrets Manager** — Database credentials and other secrets are injected at runtime via Secrets Manager; no secrets exist in source code or container images.
- **Application Load Balancer** — Handles HTTPS termination and routes traffic to ECS tasks.
- **Amazon CloudWatch** — Application logs stream to CloudWatch Logs; metrics are published for dashboards and alarms.

The same Docker image runs locally (with docker-compose) and on AWS (on ECS). The only difference is how credentials are supplied: environment variables locally, Secrets Manager on AWS.

---

## Future AI Capability

A future phase will integrate **Amazon Bedrock** to provide an AI-powered customer rewards assistant. This assistant will allow customers to query their rewards history, get personalized redemption recommendations, and interact with the platform using natural language. The rewards service's clean API design ensures Bedrock integration can be added as a new layer without modifying existing business logic.
