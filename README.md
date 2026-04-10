# CoreRouter Workers

CoreRouter Workers is a background processing repository for the CoreRouter platform.

It runs asynchronous workers for:
1. Email event processing and delivery (Redis list queue).
2. vLLM chat completion task processing (Redis stream consumer group).

The main goal is to keep your API fast and resilient by moving expensive work out of request/response paths.

## What This Repository Is For

This folder contains all infrastructure and code required to run long-running worker processes for CoreRouter.

It includes:
1. Worker entrypoints.
2. DTOs and service logic for queue payloads.
3. Email template rendering and provider integration.
4. Redis and API integration utilities.
5. Build, packaging, and deployment configuration.

## High-Level Architecture

### Email Pipeline
1. Backend publishes encrypted JSON into `queue:email`.
2. `EmailWorker` pops messages with blocking Redis pop (`BRPOP`).
3. Worker decrypts and parses payload.
4. Worker validates required envelope fields.
5. Worker routes by `templateKey`.
6. Worker renders HTML + text body from payload fields.
7. Worker sends email via Azure Communication Email.
8. Failures are retried with exponential backoff.
9. After max retries, payload goes to dead-letter queue: `queue:email:dlq`.

### vLLM Pipeline
1. Backend pushes tasks to Redis stream.
2. `VllmChatCompletionWorker` consumes using consumer groups.
3. Worker resolves model + billing config and executes task.
4. Worker updates task status through internal API.

## Repository Structure

```text
corerouter-workers/
	Dockerfile
	pom.xml
	README.md
	src/
		main/
			java/com/fleebug/
				config/      # Redis and mail client setup
				constants/   # Queue names, endpoints, worker constants
				dto/         # Queue and task payload models
				service/     # Business services (email, heartbeat, vLLM)
				utility/     # Env, encryption, HTTP API helper utilities
				workers/     # Worker entrypoints (EmailWorker, VllmChatCompletionWorker)
			resources/
				templates/   # Email HTML templates used by templateKey routing
				logback.xml  # Logging config
		test/
			java/         # Test classes
```

## Email Worker: Routing Model

Email messages use one queue (`queue:email`) and a common envelope.

### Required envelope fields
1. `schemaVersion`
2. `channel`
3. `category`
4. `templateKey`
5. `email`

### Supported `templateKey` values
1. `REGISTRATION_OTP`
2. `PASSWORD_RESET_OTP`
3. `PASSWORD_CHANGED_NOTIFICATION`
4. `USER_DELETED_NOTIFICATION`
5. `API_KEY_MONTHLY_USAGE_ALERT`

### Template files mapped in worker/service
1. `REGISTRATION_OTP` -> `verify-email.html`
2. `PASSWORD_RESET_OTP` -> `forgot-password.html`
3. `PASSWORD_CHANGED_NOTIFICATION` -> `password-changed-notification.html`
4. `USER_DELETED_NOTIFICATION` -> `user-deleted-notification.html`
5. `API_KEY_MONTHLY_USAGE_ALERT` -> `api-key-monthly-usage-alert.html`

## Email Payload Example

```json
{
	"schemaVersion": "v1",
	"channel": "EMAIL",
	"category": "BILLING",
	"templateKey": "API_KEY_MONTHLY_USAGE_ALERT",
	"type": "API_KEY_MONTHLY_USAGE_ALERT",
	"purpose": "MONTHLY_LIMIT_90",
	"email": "user@example.com",
	"fullName": "John Doe",
	"userId": 123,
	"apiKeyId": 45,
	"apiKeyName": "Production Key",
	"monthlyLimit": 10000,
	"consumed": 9100,
	"thresholdPercent": 91,
	"subject": "API key usage at 90% of monthly limit",
	"message": "...",
	"timestamp": 1775800000000,
	"eventTime": "2026-04-10T12:30:00Z"
}
```

## Retry and Dead-Letter Behavior

In `EmailWorker`:
1. Retry limit: 3 attempts.
2. Backoff: exponential from base 500ms (capped).
3. Requeue target: `queue:email`.
4. Final failure target: `queue:email:dlq`.

This gives at-least-once delivery behavior with a safe failure sink for debugging/replay.

## Heartbeats and Observability

Both workers send periodic heartbeats to the CoreRouter API and emit telemetry events.

Telemetry and status features include:
1. Success/failure traces.
2. Exception tracking with metadata.
3. Worker liveness heartbeat (`/api/v1/internal/worker/heartbeat`).

## Build and Run

### Prerequisites
1. Java 17
2. Maven Wrapper (`./mvnw`)
3. Redis reachable from worker runtime
4. Required environment variables (see below)

### Build

```bash
./mvnw clean package -Pshade -DskipTests
```

### Run Email Worker locally

```bash
export WORKER_CLASS=com.fleebug.workers.EmailWorker
java -cp target/corerouter-workers-1.0.0-SNAPSHOT.jar "$WORKER_CLASS"
```

### Run vLLM Worker locally

```bash
export WORKER_CLASS=com.fleebug.workers.VllmChatCompletionWorker
java -cp target/corerouter-workers-1.0.0-SNAPSHOT.jar "$WORKER_CLASS"
```

## Docker Runtime

The Docker image is generic and chooses worker entrypoint using `WORKER_CLASS`.

```dockerfile
ENTRYPOINT ["sh", "-c", "java -cp app.jar $WORKER_CLASS"]
```

That means one image can run different workers by changing environment only.

## Important Environment Variables

Core variables used across workers:
1. `WORKER_CLASS` (for example `com.fleebug.workers.EmailWorker`)
2. `REDIS_HOST`
3. `REDIS_PORT`
4. `REDIS_PASSWORD`
5. `ENCRYPTION_KEY`
6. `API_BASE_URL`
7. `APPLICATIONINSIGHTS_CONNECTION_STRING`
8. `WORKER_SECRET`

Email provider variables:
1. `AZURE_COMMUNICATION_CONNECTION_STRING`
2. `AZURE_COMMUNICATION_COREROUTER_ACCESS_KEY`
3. `AZURE_COMMUNICATION_COREROUTER_ENDPOINT`

Optional heartbeat override:
1. `API_HEARTBEAT_BASE_URL`

## Deployment

GitHub Actions deployment file:
1. Builds Docker image.
2. Starts `corerouter-email-worker` with `WORKER_CLASS=com.fleebug.workers.EmailWorker`.
3. Starts `corerouter-vllm-chat-completion-worker`.

See `.github/workflows/deploy.yml` for exact runtime flags and secret usage.

## Notes for Contributors

When adding new email events:
1. Add new `templateKey` handling in `EmailService.sendByTemplate`.
2. Add matching HTML template in `src/main/resources/templates`.
3. Ensure payload fields are covered in `EmailJobDto` (or ignored safely).
4. Keep rendering payload-driven (no DB lookup in worker).
5. Add tests for routing and template field substitution.

## Current Status Summary

This repository now supports:
1. A generic email worker with unified envelope routing.
2. Purpose/category-based email templates with consistent UI style.
3. Retry + dead-letter flow for resilient email processing.
4. Independent vLLM task worker in the same deployable artifact.