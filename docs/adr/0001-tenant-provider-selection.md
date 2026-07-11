# ADR 0001 — Tenant-Aware Provider Selection

**Date:** 11 July 2026  
**Status:** Accepted  
**Author:** Md. Shohidul Islam

---

## Context

The original `SmsChannelService.resolveGateway()` read a single global provider string from `SmsChannelProperties`. The `SmsMessage` record carries a `tenantId` field that was completely ignored. In a multi-tenant SaaS platform this means:

- All tenants are forced onto the same SMS provider
- One tenant's provider configuration affects every other tenant
- Per-tenant pricing contracts with providers are impossible
- A tenant with their own AWS SNS account or Twilio sub-account cannot be onboarded

This is Finding F-04 in REVIEW.md and represents a fundamental architectural gap.

---

## Decision

Introduce a `TenantSmsProviderRegistry` — a runtime registry mapping `UUID tenantId` to a provider name string.

`SmsChannelService.resolveGateway(UUID tenantId)` now:

1. Looks up the tenant-specific provider from `TenantSmsProviderRegistry`
2. Falls back to the global `SmsChannelProperties.provider` if no tenant-specific config exists

```java
private SmsGateway resolveGateway(UUID tenantId) {
    String providerType = tenantRegistry.getProviderFor(tenantId)
            .orElse(properties.getProvider());
    return gateways.stream()
            .filter(g -> g.supports(providerType))
            .findFirst()
            .orElseThrow(...);
}
```

The registry is a `ConcurrentHashMap<UUID, String>` with three operations:

- `getProviderFor(UUID tenantId)` — returns `Optional<String>`
- `registerTenant(UUID tenantId, String providerName)` — adds or updates a mapping
- `removeTenant(UUID tenantId)` — removes a mapping on tenant offboarding

---

## Alternatives Considered

### Alternative 1 — Per-tenant SmsChannelProperties in config file

Map each tenant ID to a full `SmsChannelProperties` block in `application.yml`. Rejected because:

- Config files are static — adding a new tenant requires redeployment
- Tenant IDs are UUIDs — not suitable as config keys in most formats
- Does not support runtime tenant onboarding

### Alternative 2 — Tenant context via ThreadLocal

Some multi-tenant frameworks propagate tenant context via a `ThreadLocal` set by a request filter. Rejected because:

- `SmsMessage` already carries `tenantId` explicitly — ThreadLocal adds hidden state
- Async and reactive contexts lose ThreadLocal state across thread boundaries
- Explicit parameter passing is easier to test and reason about

### Alternative 3 — Database-backed registry

Store provider mappings in the database and load them on startup or per-request. Not implemented within the time box but this is the correct long-term approach:

- Supports runtime changes without redeployment
- Survives application restarts
- Can be managed via an admin API

The current `ConcurrentHashMap` implementation is a stepping stone toward this. The interface (`getProviderFor`, `registerTenant`, `removeTenant`) would remain the same — only the backing store would change.

---

## Consequences

**Positive:**
- Each tenant can now use a different SMS provider
- Provider selection is explicit and traceable — `tenantId` flows from `SmsMessage` through `resolveGateway()` to the registry lookup
- Fallback to global config means existing single-tenant deployments continue to work without any configuration change
- The registry is thread-safe via `ConcurrentHashMap`

**Negative:**
- The current in-memory registry does not survive application restarts — tenant registrations are lost on restart
- No persistence layer means tenant configurations must be re-registered after every deployment
- No admin API to manage registrations — currently requires calling `registerTenant()` directly in code or startup configuration

**Remaining risk:**
- Provider credentials are still global per provider — tenant A and tenant B on Twilio share the same Twilio account SID and auth token. True credential isolation per tenant requires extending `SmsChannelProperties` to support per-tenant credential blocks, which is the next step after this ADR.

---

## What This ADR Does Not Cover

- Per-tenant provider credentials (separate from provider selection)
- Failover order per tenant — which provider to try second if the primary fails
- Persistence of tenant registry entries across restarts
- Admin API for managing tenant provider assignments at runtime

These are natural follow-on decisions once the registry pattern is established and working in production.
