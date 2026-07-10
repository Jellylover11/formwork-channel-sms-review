package one.formwork.channel.sms.api;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TenantSmsProviderRegistry {

    private final Map<UUID, String> tenantProviderMap = new ConcurrentHashMap<>();

    public Optional<String> getProviderFor(UUID tenantId) {
        return Optional.ofNullable(tenantProviderMap.get(tenantId));
    }

    public void registerTenant(UUID tenantId, String providerName) {
        tenantProviderMap.put(tenantId, providerName);
    }

    public void removeTenant(UUID tenantId) {
        tenantProviderMap.remove(tenantId);
    }
}