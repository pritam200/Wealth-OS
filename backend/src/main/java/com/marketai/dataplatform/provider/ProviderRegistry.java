package com.marketai.dataplatform.provider;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** The providers wired into this deployment. A provider that is not configured is simply absent. */
@Component
public class ProviderRegistry {

    private final List<FinancialDataProvider> providers;

    public ProviderRegistry(List<FinancialDataProvider> providers) { this.providers = providers; }

    public List<FinancialDataProvider> all() { return providers; }

    public Optional<FinancialDataProvider> find(String providerId) {
        return providers.stream().filter(p -> p.providerId().equalsIgnoreCase(providerId)).findFirst();
    }
}
