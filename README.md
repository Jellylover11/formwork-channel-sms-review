# formwork-channel-sms

Spring Boot module for multi-tenant SMS delivery through five providers (Twilio, Vonage, MessageBird, BudgetSMS, AWS SNS) with per-tenant cost recording.

**Candidate:** Md. Shohidul Islam  
**Assignment:** Studio Butterfly Senior Java / Spring Boot Take-Home  
**Submitted:** 12 July 2026

---

## How to Build and Run

### Prerequisites

- Java 21+
- Maven 3.9+
- Docker (for Testcontainers in integration tests — not required, tests skip if Docker unavailable)

### Build and test

```bash
mvn clean verify
```

### Run tests only

```bash
mvn clean test
```

### Coverage report

```bash
mvn clean verify
# Report at: target/site/jacoco/index.html
```

All 168 tests pass. Coverage gate enforced via JaCoCo in `pom.xml`.

---

## Build Setup Changes

The module is part of a larger platform and depends on `formwork-parent` and `formwork-base-tenant` which were not provided. The following changes were made to make it build standalone:

- Replaced `formwork-parent` with `spring-boot-starter-parent 3.3.0`
- Added stub classes: `TenantScopedEntity`, `TenantBaseAutoConfiguration`, `FlywayModuleSupport`
- Fixed `VonageSmsGateway.getFirst()` to `get(0)` for Java 21 source compatibility
- Set `maven.compiler.source` and `target` to 21
- Added WireMock 3.5.4 and surefire plugin with `-XX:+EnableDynamicAgentLoading`

These changes are purely to enable standalone builds. In the real platform these would be provided by the parent project.

---

## What I Changed and Why

### Fix F-01 — Cost Recording Wired Into Send Path

`SmsCostService` was never called from `SmsChannelService`. Every SMS sent produced no cost record — silent financial data loss from day one. I injected `SmsCostService` and called `recordCost()` after every successful send. Failed sends produce no cost record. Two failing tests were written first to prove the bug.

### Fix F-02 and F-03 — AWS SNS POST and SigV4 Payload Hash

Two companion bugs in the same method. The gateway used `webClient.get()` for SNS Publish (a state-changing POST operation) and hashed an empty string as the payload (must be `sha256` of the form body). Both caused `SignatureDoesNotMatch` rejection from AWS on every send. Fixed by switching to POST with form body, correcting the canonical request, and computing the payload hash from the actual body. Also added `content-type` to canonical and signed headers as required by AWS SigV4 for POST requests.

### Fix F-04 — Tenant-Aware Provider Selection

`SmsMessage.tenantId()` was completely ignored in gateway resolution. All tenants shared one global provider. I introduced `TenantSmsProviderRegistry` as a `ConcurrentHashMap` mapping `UUID tenantId` to provider name. `resolveGateway()` now looks up the tenant-specific provider first and falls back to global config only if no tenant configuration exists. Two failing tests were written first.

### Fix F-06 — Register All Gateways Unconditionally

Only one gateway bean was ever created because each was annotated `@ConditionalOnProperty(havingValue = "TWILIO")` etc. Failover was structurally impossible — there was no secondary gateway in the Spring context. Removed the `havingValue` constraints so all five gateways register unconditionally.

### Fix F-07 — Vonage Segment Count

`messages.size()` always returned 1 for single-recipient sends regardless of actual segments. The real segment count is in the `message-parts` field of the Vonage response object. Fixed to read and parse `message-parts` with fallback to 1. A failing test was written first using a package-private constructor for `WebClient` injection.

### Fix F-08 — AWS SNS Credentials From Config

The gateway ignored the `AwsSnsProperties` config for credentials and called `System.getenv()` directly. Added `accessKey` and `secretKey` fields to `AwsSnsProperties`. Credentials are now read from config first with env var fallback. This enables per-tenant AWS accounts.

### Fix F-09 — Honest WireMock Integration Test

All files named `*WireMockTest.java` used Mockito mocks. No test verified the actual HTTP bytes sent. Added `TwilioSmsGatewayIntegrationTest` using a real `WireMockServer` on a dynamic port. The test asserts on the actual URL path, Authorization header format, Content-Type, and request body encoding. This test would immediately catch credential format changes or body encoding bugs.

### Fix F-10 — Retry With Exponential Backoff

`RetryProperties` had `maxAttempts` and `backoff` fields that were never read anywhere. Changed `backoff` from raw `String` to `Duration`. Implemented `sendWithRetry()` in `SmsChannelService` using these values with exponential backoff between attempts. Permanent errors (auth failures, invalid phone numbers) fail immediately without retry. Three failing tests were written first.

### Fix F-13 — Cost Recording Verification in Tests

`SmsChannelServiceTest` did not mock or verify `SmsCostService` at all. If cost recording was accidentally removed no test would catch it. Added `SmsCostService` as a mock and verified `recordCost()` is called with the correct arguments on success and never called on failure.

---

## What I Deliberately Did Not Fix and Why

### F-05 — BudgetSMS Credentials in URL

This is a straightforward fix — move credentials to an HTTP Basic Auth header and message parameters to a POST body. I chose not to implement it within the time box because it required a proper WireMock integration test to prove the fix (verifying the Authorization header and body encoding), and after completing the Twilio integration test I had used most of the available time. The bug is fully documented in REVIEW.md with the exact fix described.

### F-11 — maskRecipient GDPR Risk

The current mask retains too much of the phone number for GDPR compliance in the EU. The fix is clear — retain only country code prefix and last 2 digits. Not implemented because it is lower risk than the critical bugs and would require an ADR documenting the GDPR justification. Documented in REVIEW.md.

### F-12 — Missing Composite Database Index

A composite index on `(tenant_id, sent_at)` is needed for the cost query patterns. Not implemented because it requires a V2 Flyway migration and the existing tests do not run against a real database — there is no integration test to prove the performance impact. Documented in REVIEW.md.

---

## What I Would Do Next With Another Week

- Fix F-05: Move BudgetSMS credentials to Authorization header with a WireMock integration test proving the fix
- Fix F-11: More aggressive phone number masking with GDPR justification documented in ADR
- Fix F-12: Add V2 Flyway migration with composite index `(tenant_id, sent_at)`
- Add WireMock integration tests for all five providers — not just Twilio
- Add `@Validated` to `SmsChannelProperties` so misconfiguration fails at startup not at runtime
- Implement delivery status callbacks properly — `handleDeliveryCallback` is currently a no-op stub
- Add circuit breaker around provider HTTP calls to prevent slow providers from blocking threads
- Fix `ProviderRateRegistry` concurrent update — `setRate` and `getRate` are not atomic across the two operations
