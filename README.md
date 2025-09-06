# Outbox Harvester

## Overview
Outbox Harvester is a Spring Boot application designed to poll an outbox table in a PostgreSQL database and push the data to RabbitMQ. The application supports OpenTelemetry for distributed tracing and exposes Prometheus metrics for monitoring.

## Features
- **Database Polling**: Periodically polls the `outbox` table for new entries.
- **RabbitMQ Integration**: Sends messages to RabbitMQ queues.
- **Retry Mechanism**: Retries message processing up to a configurable limit.
- **OpenTelemetry Tracing**: Enables distributed tracing using the `traceparent` attribute.
- **Prometheus Metrics**: Exposes application metrics for monitoring.

## Prerequisites
- Java 17
- Maven
- PostgreSQL
- RabbitMQ
- Prometheus (optional, for metrics collection)

## Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/canberkturan/outbox-harvester.git
   cd outbox-harvester
   ```
2. Build the project:
   ```bash
   mvn clean install
   ```
3. Configure the application:
   - Update `src/main/resources/application.yaml` with your database and RabbitMQ credentials.

## Configuration
### `application.yaml`
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/outboxdb
    username: your_username
    password: your_password
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true

rabbitmq:
  host: localhost
  port: 5672
  username: guest
  password: guest

outbox:
  retry:
    limit: 3

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  metrics:
    export:
      prometheus:
        enabled: true

opentelemetry:
  enabled: true
```

## Usage
1. Start the application:
   ```bash
   mvn spring-boot:run
   ```
2. Access the following endpoints:
   - Prometheus Metrics: [http://localhost:8080/actuator/prometheus](http://localhost:8080/actuator/prometheus)
   - Health Check: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)

## Outbox Table Schema
The `outbox` table is defined as follows:
```sql
CREATE TABLE outbox (
    outbox_id UUID PRIMARY KEY,
    movie_json TEXT NOT NULL,
    action VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    traceparent VARCHAR(255),
    retry_count INT DEFAULT 0
);
```

## Monitoring
- **Prometheus**: Collect metrics from the `/actuator/prometheus` endpoint.
- **Grafana**: Visualize metrics by connecting to Prometheus.

## Tracing
- OpenTelemetry is enabled to trace the flow of messages. The `traceparent` attribute is used to propagate context.

## License
This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Contributing
Contributions are welcome! Please open an issue or submit a pull request.

## Contact
For questions or support, please contact [canberkturan](mailto:canberkturan@example.com).