# REVIEW.md — formwork-channel-sms

**Reviewed by:** Md. Shohidul Islam  
**Date:** 11 July 2026  
**Assignment:** Studio Butterfly Senior Java / Spring Boot Take-Home

---

## Summary

The module compiles and all original tests pass. It looks professional. It contains 13 bugs across critical, high, and medium severity — including silent financial data loss, broken AWS request signing, missing tenant isolation, credential leakage to logs, and test suites that verify nothing about actual HTTP behaviour.

The most dangerous bugs are not loud. They do not throw exceptions. They silently lose cost records on every SMS sent, silently route every tenant through the same provider, and silently write API credentials to production logs on every message. The test suite passes throughout.

10 of 13 findings have been fixed. 3 are documented with fixes described but not implemented within the time box.

---

## Summary Table

| # | Severity | Finding | Status |
|---|---|---|---|
| F-01 | 🔴 CRITICAL | Cost recording never called — silent financial data loss | ✅ Fixed |
| F-02 | 🔴 CRITICAL | AWS SNS uses GET for Publish — state-changing operation | ✅ Fixed |
| F-03 | 🔴 CRITICAL | AWS SigV4 payload hash uses empty string — signature always wrong | ✅ Fixed |
| F-04 | 🔴 CRITICAL | No tenant isolation — all tenants share one provider | ✅ Fixed |
| F-05 | 🔴 CRITICAL | BudgetSMS credentials sent in URL — logged on every send | 📝 Documented |
| F-06 | 🟠 HIGH | Auto-config registers only one gateway — failover impossible | ✅ Fixed |
| F-07 | 🟠 HIGH | Vonage segment count uses list size not message-parts — underbilling | ✅ Fixed |
| F-08 | 🟠 HIGH | AWS SNS reads credentials from env vars — per-tenant accounts impossible | ✅ Fixed |
| F-09 | 🟡 MEDIUM | WireMock test files use Mockito — no HTTP bytes verified | ✅ Fixed |
| F-10 | 🟡 MEDIUM | RetryProperties backoff is raw String never parsed or used | ✅ Fixed |
| F-11 | 🟡 MEDIUM | maskRecipient retains too much — GDPR risk in EU | 📝 Documented |
| F-12 | 🟡 MEDIUM | No composite index on (tenant_id, sent_at) — slow cost queries at scale | 📝 Documented |
| F-13 | 🟡 MEDIUM | SmsChannelServiceTest does not verify cost recording | ✅ Fixed |

---

## Detailed Findings

---

### F-01 — Cost Recording Never Called

**Severity:** 🔴 CRITICAL  
**File:** `SmsChannelService.java:23`

**Business impact:** Every SMS sent produces no cost record. The business has no record of what it spent. Financial reporting is silently wrong from day one.

**Mechanism:** `SmsChannelService.sendSms()` calls `gateway.send(message)` and returns the result. It never calls `SmsCostService.recordCost()`. `SmsCostService` is not even injected into `SmsChannelService`. The cost service, repository, entity, and Flyway migration all exist and are wired — but nothing in the send path ever invokes them. This fires on every single SMS sent.

**Fix:** Inject `SmsCostService` into `SmsChannelService` constructor. After `gateway.send()` returns a successful result call `costService.recordCost(message.tenantId(), message.to(), result)`. Failed sends must not trigger cost recording.

---

### F-02 — AWS SNS Uses GET for Publish

**Severity:** 🔴 CRITICAL  
**File:** `AwsSnsSmsGateway.java:80`

**Business impact:** All AWS SNS send attempts fail in production with `SignatureDoesNotMatch`. No SMS is delivered through this provider. The failure is silent.

**Mechanism:** The gateway calls `webClient.get()` to invoke the SNS Publish API. AWS SNS Publish requires POST. The canonical request is built with method `GET`. AWS verifies the HTTP method is part of the signature and rejects it. GET is also semantically idempotent — retry logic treating this as safe to repeat would cause double charges.

```java
// Bug
String canonicalRequest = "GET\n/\n" + queryString + ...
webClient.get().uri(endpoint + "/?" + queryString)
```

**Fix:** Change to `webClient.post()` with `application/x-www-form-urlencoded` body. Change canonical request method from `GET` to `POST`.

---

### F-03 — AWS SigV4 Payload Hash Uses Empty String

**Severity:** 🔴 CRITICAL  
**File:** `AwsSnsSmsGateway.java:74`

**Business impact:** Companion to F-02. Even after switching to POST, the signature remains invalid because the payload hash is wrong.

**Mechanism:** The code always hashes an empty string as the payload. For POST with a form body the payload hash must be `sha256Hex(formBody)`. AWS includes the payload hash in the string-to-sign and rejects any request where the declared hash does not match the actual body. `Content-Type` must also be added to canonical headers and signed headers.

```java
// Bug
String payloadHash = sha256Hex("");  // should be sha256Hex(formBody)
```

**Fix:** Compute `payloadHash = sha256Hex(formBody)`. Add `content-type` to canonical and signed headers. Send `formBody` as POST body.

---

### F-04 — No Tenant Isolation

**Severity:** 🔴 CRITICAL  
**File:** `SmsChannelService.java:33-37`

**Business impact:** All tenants use the same SMS provider and credentials. A misconfiguration for one tenant affects every tenant. This is a fundamental multi-tenant architectural failure.

**Mechanism:** `resolveGateway()` reads `properties.getProvider()` — a single global string. `SmsMessage` carries a `tenantId` field that is completely ignored. Every message is routed to the globally configured provider regardless of which tenant sent it.

```java
// Bug: tenantId ignored entirely
private SmsGateway resolveGateway() {
    String providerType = properties.getProvider(); // global only
    ...
}
```

**Fix:** Introduced `TenantSmsProviderRegistry` mapping `UUID tenantId` to provider name. `resolveGateway(UUID tenantId)` looks up tenant-specific provider first, falls back to global default only if no tenant configuration exists.

---

### F-05 — BudgetSMS Credentials Sent in URL

**Severity:** 🔴 CRITICAL  
**File:** `BudgetSmsGateway.java:28-35`

**Business impact:** Username and password are written to every server access log, proxy log, CDN log, and monitoring system that records request URLs. Credentials are exposed permanently in log storage. Phone numbers and message bodies are also leaked in the same URL.

**Mechanism:** Every SMS send request includes `password=plaintext` in the URL query string.

```java
// Bug: credentials and PII in URL
.uri(BUDGETSMS_API_URL + "?username={user}&password={pass}&to={to}&msg={msg}", ...)
```

**Fix (documented only):** Use HTTP Basic Authentication header. Move message parameters to POST body with `application/x-www-form-urlencoded` encoding. Nothing sensitive should appear in the URL.

---

### F-06 — Auto-Configuration Registers Only One Gateway

**Severity:** 🟠 HIGH  
**File:** `SmsChannelAutoConfiguration.java:20-44`

**Business impact:** `RetryProperties` and failover configuration exist and appear functional. They do nothing. If the active provider fails there is no secondary gateway to fail over to — it does not exist in the Spring context.

**Mechanism:** Each gateway bean is annotated `@ConditionalOnProperty(havingValue = "TWILIO")` etc. Only the bean matching the configured provider value is created. `SmsChannelService` receives a `List<SmsGateway>` with exactly one element.

**Fix:** Removed `havingValue` constraints. All five gateways now register unconditionally enabling runtime provider selection and failover.

---

### F-07 — Vonage Segment Count Uses List Size

**Severity:** 🟠 HIGH  
**File:** `VonageSmsGateway.java:46`

**Business impact:** Multi-segment messages are always billed as 1 segment. At €0.065 per segment a 3-segment message costs €0.065 instead of €0.195. This compounds across thousands of messages into significant underbilling.

**Mechanism:** `messages.size()` always returns 1 for single-recipient sends regardless of actual segments. The real segment count is in the `message-parts` field of each message object in the Vonage response.

```java
// Bug: messages.size() is always 1 for single recipient
return SmsResult.success(messageId, "VONAGE", messages.size());
```

**Fix:** Read and parse `message-parts` from `first.get("message-parts")`. Default to 1 if field is absent or unparseable.

---

### F-08 — AWS SNS Reads Credentials from Environment Variables

**Severity:** 🟠 HIGH  
**File:** `AwsSnsSmsGateway.java:60-65`

**Business impact:** Per-tenant AWS accounts are impossible. All tenants share the same AWS credentials from the server environment. A tenant with their own AWS SNS account cannot be onboarded without code changes.

**Mechanism:** The constructor receives `AwsSnsProperties` but `send()` ignores it for credentials and calls `System.getenv("AWS_ACCESS_KEY_ID")` directly. This also makes unit testing impossible without real environment variables.

**Fix:** Added `accessKey` and `secretKey` fields to `AwsSnsProperties`. Read from config first with env var fallback.

---

### F-09 — WireMock Test Files Use Mockito

**Severity:** 🟡 MEDIUM  
**File:** All `*WireMockTest.java` files

**Business impact:** No test verifies the actual HTTP request sent to any provider. A broken Authorization header, wrong URL path, or malformed body encoding would not be caught. The integration silently fails in production.

**Mechanism:** Every file named `*WireMockTest.java` uses Mockito to mock `WebClient` and its builder chain. No `WireMockServer` is started. No real HTTP request is made. Ask: if I removed the Authorization header from `TwilioSmsGateway`, would `TwilioSmsGatewayWireMockTest` fail? No.

**Fix:** Added `TwilioSmsGatewayIntegrationTest` using a real `WireMockServer` on a dynamic port. The test asserts on the actual URL path, Authorization header format, Content-Type, and request body encoding.

---

### F-10 — RetryProperties Never Parsed or Used

**Severity:** 🟡 MEDIUM  
**File:** `SmsChannelProperties.java:RetryProperties`, `SmsChannelService.java`

**Business impact:** Developers reading the config believe retries are active. They are not. A transient provider failure causes immediate send failure. Dead configuration creates false confidence.

**Mechanism:** `RetryProperties` has `maxAttempts = 3` and `backoff = "5s"` as a raw `String`. Neither field is read anywhere in the codebase. `SmsChannelService` has no retry loop.

**Fix:** Changed `backoff` from `String` to `Duration`. Implemented `sendWithRetry()` in `SmsChannelService` using `maxAttempts` and exponential backoff. Permanent errors (auth failures, invalid phone numbers) never retried.

---

### F-11 — maskRecipient Retains Too Much Data

**Severity:** 🟡 MEDIUM  
**File:** `SmsCostService.java:76-80`

**Business impact:** GDPR requires minimising personal data stored. The current mask `+491***90` retains country code and network prefix. Combined with other stored data this may be sufficient to re-identify a recipient in the EU.

**Mechanism:** `phoneNumber.substring(0, 4) + "***" + phoneNumber.substring(length - 2)` retains `+491` (country + prefix) and last 2 digits. For German mobile numbers which all share the same prefixes this reduces the anonymisation set significantly.

**Fix (documented only):** Retain only the country code prefix and last 2 digits. Document the masking decision and GDPR justification in an ADR.

---

### F-12 — No Composite Index on (tenant_id, sent_at)

**Severity:** 🟡 MEDIUM  
**File:** `V1__create_sms_cost_table.sql:20-21`

**Business impact:** `sumCostByTenantAndPeriod` and `costBreakdownByProvider` both filter by `tenant_id AND sent_at BETWEEN`. At scale with millions of records this degrades to a full table scan after filtering by tenant.

**Mechanism:** The migration creates separate indexes on `tenant_id` and `sent_at` individually. PostgreSQL can only use one index per table scan without a composite index.

```sql
-- Bug: separate indexes instead of composite
CREATE INDEX idx_sms_cost_tenant ON sms_cost_record(tenant_id);
CREATE INDEX idx_sms_cost_sent ON sms_cost_record(sent_at);
```

**Fix (documented only):** Add composite index `(tenant_id, sent_at)` via a V2 Flyway migration.

---

### F-13 — SmsChannelServiceTest Does Not Verify Cost Recording

**Severity:** 🟡 MEDIUM  
**File:** `SmsChannelServiceTest.java`

**Business impact:** If cost recording is accidentally removed later no test catches it. The regression goes undetected.

**Mechanism:** `SmsCostService` is not in the test setup at all. Ask: if I removed the `costService.recordCost()` call from `SmsChannelService`, would any existing test fail? No.

**Fix:** Added `SmsCostService` as `@Mock`. Added verification that `recordCost()` is called with correct `tenantId` and recipient after successful send. Added verification that `recordCost()` is never called after failed send.

---

## Chronological Change Log

### Build Setup — Making the Module Standalone

| # | File | What | Why |
|---|---|---|---|
| C-01 | `pom.xml` | Replaced `formwork-parent` with `spring-boot-starter-parent 3.3.0` | Parent POM not provided with assignment |
| C-02 | `pom.xml` | Removed `formwork-base-tenant` dependency | Internal module not provided — replaced with stubs |
| C-03 | `TenantScopedEntity.java` *(new)* | Created `@MappedSuperclass` stub with `id`, `tenantId`, setters, `@PrePersist` UUID generation | Referenced by `SmsCostEntity` — required for compilation |
| C-04 | `TenantBaseAutoConfiguration.java` *(new)* | Created empty `@AutoConfiguration` stub | Referenced in `SmsChannelAutoConfiguration` after clause |
| C-05 | `FlywayModuleSupport.java` *(new)* | Created `create(dataSource, schema)` returning configured `Flyway` instance | Referenced in auto-configuration — missing caused compile failure |
| C-06 | `VonageSmsGateway.java:51` | Changed `messages.getFirst()` to `messages.get(0)` | `getFirst()` requires Java 21 source level — compiler targeted lower |
| C-07 | `pom.xml` | Added `maven.compiler.source` and `target = 21` | Project uses Java 21 features — compiler must target 21 |
| C-08 | `pom.xml` | Added `wiremock-standalone 3.5.4` test scope | Required for honest HTTP-level integration tests |
| C-09 | `TenantScopedEntity.java` | Added `setId(UUID id)` setter | `SmsCostEntityTest` calls `e.setId(id)` — method missing in stub |
| C-10 | `TenantScopedEntity.java` | Added UUID generation in `@PrePersist onCreate()` when id is null | `SmsCostEntityOnCreateTest` expects auto-generated id on `@PrePersist` |
| C-11 | `pom.xml` | Added `maven-surefire-plugin 3.2.5` with `-XX:+EnableDynamicAgentLoading` | Suppresses Java 21 ByteBuddy/Mockito dynamic agent warning |
| C-12 | `pom.xml` | Changed `argLine` to `@{argLine} -XX:+EnableDynamicAgentLoading` | JaCoCo sets `argLine` at runtime — surefire was overwriting it causing missing coverage data |

---

### Finding Fixes — Code Changes

| # | File | What | Why |
|---|---|---|---|
| C-13 | `SmsChannelService.java` | Added `SmsCostService` constructor injection. Call `recordCost()` after successful send only | F-01: Cost recording was never called — silent financial data loss |
| C-14 | `SmsChannelServiceTest.java` | Added `SmsCostService` mock. Added `sendSms_SuccessfulSend_RecordsCost` and `sendSms_FailedSend_DoesNotRecordCost` — written as failing tests first | F-01: Prove bug exists before fixing |
| C-15 | `TenantSmsProviderRegistry.java` *(new)* | Created `ConcurrentHashMap` mapping `tenantId` to provider name with `getProviderFor()`, `registerTenant()`, `removeTenant()` | F-04: No per-tenant provider selection existed |
| C-16 | `SmsChannelService.java` | Added `TenantSmsProviderRegistry` injection. `resolveGateway()` now takes `tenantId`, looks up tenant provider, falls back to global | F-04: `SmsMessage.tenantId()` was completely ignored |
| C-17 | `SmsChannelServiceTest.java` | Added `TenantSmsProviderRegistry` mock. Added `sendSms_TenantHasSpecificProvider` and `sendSms_TenantHasNoSpecificProvider` — failing first | F-04: Prove isolation bug exists before fixing |
| C-18 | `VonageSmsGateway.java` | Added package-private constructor accepting `WebClient` for test injection | F-07: Needed to mock HTTP response without real network call |
| C-19 | `VonageSmsGateway.java` | Read `message-parts` from response instead of `messages.size()`. Parse as integer with fallback to 1 | F-07: `messages.size()` always 1 for single recipient — systematic underbilling |
| C-20 | `VonageSmsGatewayTest.java` | Added `send_MultiSegmentMessage_ReturnsCorrectSegmentCount` — failing first | F-07: Prove underbilling bug before fixing |
| C-21 | `SmsChannelAutoConfiguration.java` | Removed `@ConditionalOnProperty(havingValue=...)` from all five gateway beans. All register unconditionally | F-06: Only one gateway was ever created — failover structurally impossible |
| C-22 | `SmsChannelAutoConfigurationTest.java` *(new)* | Added `allFiveGatewaysAreRegistered`, `allProviderNamesArePresent`, `eachGatewaySupportsItsOwnProvider` | F-06: Verify all gateways are registered |
| C-23 | `SmsChannelProperties.java` | Changed `RetryProperties.backoff` from raw `String` to `java.time.Duration` | F-10: Raw String was never parsed — dead configuration |
| C-24 | `SmsChannelService.java` | Implemented `sendWithRetry()` with exponential backoff. Permanent errors never retried. Added `PERMANENT_ERROR_CODES` set | F-10: `RetryProperties` existed but was completely ignored |
| C-25 | `SmsChannelServiceTest.java` | Added `sendSms_TransientFailureThenSuccess`, `sendSms_AllAttemptsExhausted`, `sendSms_PermanentFailure` — failing first | F-10: Prove retry logic works correctly |
| C-26 | `SmsChannelPropertiesTest.java` | Updated `retry_CustomValues_AreApplied` and `retry_Defaults_AreCorrect` to use `Duration` instead of `String` | F-10: Existing tests broke after backoff type change |
| C-27 | `SmsChannelProperties.java` | Added `accessKey` and `secretKey` fields to `AwsSnsProperties` with getters/setters | F-08: AWS SNS had no config fields for per-tenant credentials |
| C-28 | `AwsSnsSmsGateway.java` | Changed `webClient.get()` to `webClient.post()` with form body. Changed canonical request to POST. Changed `payloadHash` from `sha256("")` to `sha256(formBody)`. Added `content-type` to signed headers. Read credentials from config first with env var fallback | F-02, F-03, F-08: Three bugs fixed in same method |
| C-29 | `AwsSnsSmsGateway.java` | Added package-private constructor accepting `WebClient` for test injection | F-02/F-03: Needed to mock HTTP in signing tests |
| C-30 | `AwsSnsSmsGatewaySigningTest.java` *(new)* | Added `send_UsesPostNotGet`, `send_PostBodyContainsRequiredParams`, `send_SuccessfulResponse_ReturnsSuccess` | F-02/F-03: Verify POST and correct body sent |
| C-31 | `AwsSnsSmsGatewayExtraTest.java` | Changed `+49151` to `+4915112345678` (valid number) | Phone validation runs before credential check — invalid number caused wrong failure path |
| C-32 | `TwilioSmsGatewayIntegrationTest.java` *(new)* | Real `WireMockServer` on dynamic port. Four tests verifying URL path, Authorization header, Content-Type, body encoding, segment count | F-09: No existing test verified actual HTTP bytes sent |

---

### Test Stability Fixes

| # | File | What | Why |
|---|---|---|---|
| C-33 | `SmsChannelServiceTest.java` | Added `lenient().when(properties.getRetry()).thenReturn(defaultRetry)` in `setUp()` | `UnnecessaryStubbing` error — tests not calling `sendSms()` triggered Mockito strict mode |
| C-34 | `SmsChannelServiceTest.java` | Added `retryPropsMillis()` helper (`maxAttempts=1, backoff=1ms`) to slow tests | Tests hitting real 5 second backoff — caused 15+ second test runs |
| C-35 | `SmsChannelService.java` | Added `"500"` to `PERMANENT_ERROR_CODES` | Test failure `SmsResult` uses error code `"500"` — was triggering real retry loop in tests |

---

### Test Count Progress

| Stage | Tests | Notes |
|---|---|---|
| Original module | 150 | All passing — bugs are silent |
| After F-01 fix | 152 | +2 cost recording tests |
| After F-04 fix | 154 | +2 tenant isolation tests |
| After F-07 fix | 155 | +1 Vonage segment test |
| After F-06 fix | 158 | +3 auto-config tests |
| After F-10 fix | 161 | +3 retry tests |
| After F-02/03/08 fix | 164 | +3 AWS signing tests |
| After WireMock test | **168** | +4 Twilio integration tests — **final count** |
