# AI-USAGE.md

## Overview

This file documents how AI tools were used during this assignment, where they helped, where they were wrong, and what was written or decided independently.

---

## Tools Used

- **Claude (claude.ai chat)** — used throughout via browser chat, not Claude Code in the IDE
- **No IDE-integrated AI tools were used** — GitHub Copilot and Claude Code were not active during this assignment

---

## How AI Was Used

### Code review and bug finding

I used Claude to help reason through the code after reading each file myself. I described what I saw, asked questions about specific patterns, and used the conversation to pressure-test my own findings. The analysis in REVIEW.md reflects my own reading of the code — Claude helped articulate and structure what I had already identified, not discover bugs I had missed.

### Scaffolding new code

For new classes like `TenantSmsProviderRegistry` and the retry logic in `SmsChannelService`, I used Claude to generate initial scaffolding which I then reviewed, modified, and adapted. The retry implementation — exponential backoff, permanent error classification, the `PERMANENT_ERROR_CODES` set — was shaped by my own judgment about what the production system needed, not taken directly from AI output.

### Test structure

Claude suggested test structures for the new failing tests. I reviewed every test before committing and modified several. The `retryPropsMillis()` helper and the `lenient()` stub fix were written independently to resolve stability issues Claude did not catch initially.

### Documentation

Claude helped draft the structure of REVIEW.md, README.md, this file, and the ADR. The content — findings, mechanisms, triage decisions — reflects my own engineering judgment. Claude organized and formatted it.

---

## Where the AI Was Wrong

### AWS SNS test count regression

After fixing the AWS SNS gateway (Findings 2, 3, 8), the test count dropped from 161 to 159 instead of increasing. Claude initially did not notice this regression. I identified it independently by checking the test output and raised it. Claude then diagnosed the two failing tests.

### Wrong mock type in AWS signing test

Claude's first version of `AwsSnsSmsGatewaySigningTest` used `bodyToMono(Map.class)` in the mock chain. The fixed gateway returns XML as a `String`, not a JSON `Map`. The mismatch caused test failures. I identified the type mismatch and Claude corrected it.

### File naming confusion

Claude gave instructions to create `AwsSnsSmsGatewaySigningTest` as a new file, but the content was accidentally placed inside `AwsSnsSmsGatewayTest.java` under the wrong class name. This caused confusion across several exchanges. I resolved the file structure independently.

### Mock chain setup errors

The initial WireMock mock chain for the AWS SNS signing tests used `any(String.class)` for the URI stub but the actual call used a plain string argument. This required multiple rounds of correction. I adjusted the mock setup in the test to match the actual gateway call pattern.

### Slow test not caught

After implementing retry with exponential backoff, one test (`sendBulk_MultipleMessages`) became very slow — hitting a real 5-second backoff delay. Claude did not anticipate this. I identified the slow test from the build output and independently wrote the `retryPropsMillis()` helper to fix it.

---

## What Was Written Independently

- Identifying the AWS SNS test count regression (161 → 159)
- Adjusting several test stubs and assertions to match actual runtime behaviour
- The `retryPropsMillis()` test helper for fast retry configuration in tests
- `lenient().when(properties.getRetry())` fix for Mockito strict mode
- Adding `"500"` to `PERMANENT_ERROR_CODES` after identifying slow test cause
- Resolving the `AwsSnsSmsGatewaySigningTest` file structure confusion
- All triage decisions — what to fix, what to document, what to cut and why

---

## Summary

AI was used as a thinking partner and drafting tool throughout. It accelerated scaffolding, documentation, and test structure. It made several mistakes that I caught and corrected. Every line of code committed to this repository was reviewed and understood before it was pushed. I can explain any line in the submission.
