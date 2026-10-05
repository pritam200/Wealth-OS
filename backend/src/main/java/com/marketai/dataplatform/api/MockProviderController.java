package com.marketai.dataplatform.api;

import com.marketai.dataplatform.provider.MockAccountAggregatorProvider;
import com.marketai.dataplatform.service.ConsentService;
import com.marketai.dataplatform.domain.ConsentStatus;
import com.marketai.dataplatform.provider.ConsentCapableProvider;
import com.marketai.auth.entity.User;
import com.marketai.dataplatform.domain.RecordKind;
import com.marketai.dataplatform.pipeline.RawRecord;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Development only: stands in for the user approving a consent at the mock provider's own screen. */
@RestController
@RequestMapping("/api/data/mock")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "wealthos.data.providers.mock.enabled", havingValue = "true")
public class MockProviderController {

    private final MockAccountAggregatorProvider mock;
    private final ConsentService consents;

    @PostMapping("/consents/{handle}/approve")
    public ResponseEntity<Void> approve(@PathVariable String handle) {
        mock.approve(handle);
        consents.applyEvent(MockAccountAggregatorProvider.ID, new ConsentCapableProvider.ConsentEvent(handle, ConsentStatus.APPROVED, "mock approval"));
        return ResponseEntity.ok().build();
    }

    @Data public static class FixtureRecord {
        private RecordKind kind = RecordKind.TRANSACTION;
        private String externalAccountId; private String externalRecordId; private String payload; private String schemaVersion = "aa-fi-v1";
    }

    /** Development only: sets what the calling user's next mock sync returns. Nothing is built in; this is how demo data is supplied. */
    @PutMapping("/fixtures")
    public ResponseEntity<Void> fixtures(@AuthenticationPrincipal User user, @RequestBody List<FixtureRecord> records) {
        mock.useFixtures(user.getId(), records.stream().map(r ->
            new RawRecord(r.getKind(), r.getExternalAccountId(), r.getExternalRecordId(), r.getPayload(), r.getSchemaVersion())).toList());
        return ResponseEntity.noContent().build();
    }
}
