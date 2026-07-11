package one.formwork.channel.sms.api;

import one.formwork.channel.sms.cost.SmsCostService;
import one.formwork.channel.sms.validation.PhoneNumberValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SmsChannelService {

    private static final Logger log = LoggerFactory.getLogger(SmsChannelService.class);

    // Error codes that are permanent — never retry these
    private static final Set<String> PERMANENT_ERROR_CODES = Set.of(
            "21211", // Twilio: invalid phone number
            "21214", // Twilio: phone number not mobile
            "401",   // Auth failure
            "403",   // Forbidden
            "404",
            "500"// Not found
    );

    private final List<SmsGateway> gateways;
    private final SmsChannelProperties properties;
    private final SmsCostService costService;
    private final TenantSmsProviderRegistry tenantRegistry;

    public SmsChannelService(List<SmsGateway> gateways,
                             SmsChannelProperties properties,
                             SmsCostService costService,
                             TenantSmsProviderRegistry tenantRegistry) {
        this.gateways = gateways;
        this.properties = properties;
        this.costService = costService;
        this.tenantRegistry = tenantRegistry;
    }

    public SmsResult sendSms(SmsMessage message) {
        PhoneNumberValidator.validate(message.to());
        SmsGateway gateway = resolveGateway(message.tenantId());
        SmsResult result = sendWithRetry(gateway, message);
        if (result.isSuccess()) {
            costService.recordCost(message.tenantId(), message.to(), result);
        }
        return result;
    }

    public List<SmsResult> sendBulk(List<SmsMessage> messages) {
        return messages.stream().map(this::sendSms).toList();
    }

    public void handleDeliveryCallback(String provider, Map<String, String> params) {
        // Provider-specific callback handling
    }

    private SmsResult sendWithRetry(SmsGateway gateway, SmsMessage message) {
        SmsChannelProperties.RetryProperties retry = properties.getRetry();
        int maxAttempts = retry.getMaxAttempts();
        long backoffMillis = retry.getBackoff().toMillis();

        SmsResult result = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            result = gateway.send(message);

            if (result.isSuccess()) {
                return result;
            }

            // Do not retry permanent errors
            if (isPermanentError(result)) {
                log.warn("Permanent error on attempt {}/{} — not retrying: code={}",
                        attempt, maxAttempts, result.errorCode());
                return result;
            }

            if (attempt < maxAttempts) {
                long sleepMs = backoffMillis * (1L << (attempt - 1)); // exponential backoff
                log.warn("Transient error on attempt {}/{}, retrying in {}ms: code={}",
                        attempt, maxAttempts, sleepMs, result.errorCode());
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return result;
                }
            }
        }

        log.error("All {} attempts exhausted for message to {}", maxAttempts, message.to());
        return result;
    }

    private boolean isPermanentError(SmsResult result) {
        return result.errorCode() != null && PERMANENT_ERROR_CODES.contains(result.errorCode());
    }

    private SmsGateway resolveGateway(UUID tenantId) {
        String providerType = tenantRegistry.getProviderFor(tenantId)
                .orElse(properties.getProvider());
        return gateways.stream()
                .filter(g -> g.supports(providerType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No SmsGateway for provider: " + providerType));
    }
}