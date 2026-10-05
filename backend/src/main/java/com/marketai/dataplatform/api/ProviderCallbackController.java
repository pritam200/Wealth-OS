package com.marketai.dataplatform.api;

import com.marketai.dataplatform.provider.ConsentCapableProvider;
import com.marketai.dataplatform.provider.ProviderRegistry;
import com.marketai.dataplatform.service.ConsentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where a provider reports a consent outcome. It cannot carry a user's JWT, so it is public and
 * protected the only way such an endpoint can be: the provider adapter verifies the callback's
 * signature, and anything it does not verify is refused. A provider with no verification
 * implemented can never be accepted here. Nothing about the payload is logged.
 */
@RestController
@RequestMapping("/api/data/callbacks")
@RequiredArgsConstructor
public class ProviderCallbackController {

    private final ProviderRegistry providers;
    private final ConsentService consents;

    @PostMapping("/{providerId}")
    public ResponseEntity<Void> callback(@PathVariable String providerId, @RequestHeader Map<String, String> headers, @RequestBody(required = false) String body) {
        Optional<ConsentCapableProvider> p = providers.find(providerId).filter(ConsentCapableProvider.class::isInstance).map(ConsentCapableProvider.class::cast);
        if (p.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, String> h = new HashMap<>();
        headers.forEach((k, v) -> h.put(k.toLowerCase(), v));
        Optional<ConsentCapableProvider.ConsentEvent> event = p.get().parseCallback(Collections.unmodifiableMap(h), body);
        if (event.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        consents.applyEvent(providerId, event.get());
        return ResponseEntity.ok().build();
    }
}
