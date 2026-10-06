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

### Running with Docker Compose

> Docker Compose configuration will be added in a later phase. For now, start PostgreSQL manually or use a local instance.

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
