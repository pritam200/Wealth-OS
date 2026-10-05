package com.marketai.dataplatform.service;

import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.dataplatform.api.ProviderCallbackController;
import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.pipeline.RawRecord;
import com.marketai.dataplatform.provider.MockAccountAggregatorProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderSyncFamilyIntegrationTest extends DataPlatformTestSupport {

    @Autowired ConsentService consentService;
    @Autowired FinancialDataSyncService sync;
    @Autowired MockAccountAggregatorProvider mock;
    @Autowired ProviderCallbackController callbacks;
    @Autowired CsvImportService csv;
    @Autowired FamilyAccessService family;
    @Autowired UserRepository users;
    @Autowired DataConnectionService connectionsView;

    @org.junit.jupiter.api.BeforeEach
    void cleanUsers() { users.deleteAllInBatch(); }

    private ConsentService.Started connect() {
        return consentService.start(USER, MockAccountAggregatorProvider.ID, "HDFC Mutual Fund");
    }

    private void approve(ConsentService.Started s) {
        mock.approve(s.consent().getProviderConsentHandle());
        consentService.refresh(USER, s.consent().getId());
    }

    @Test @DisplayName("consent lifecycle: a new connection waits for approval and cannot sync until the user approves")
    void consentGatesSync() {
        var s = connect();
        assertThat(s.connection().getStatus()).isEqualTo(ConnectionStatus.PENDING_CONSENT);
        assertThat(s.connection().getMode()).isEqualTo(ProviderMode.MOCK);
        assertThatThrownBy(() -> sync.initialSync(USER, s.connection().getId()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not active");

        approve(s);
        assertThat(connectionRepo.findById(s.connection().getId()).orElseThrow().getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(consentRepo.findById(s.consent().getId()).orElseThrow().getApprovedAt()).isNotNull();
    }

    @Test @DisplayName("a rejected or revoked consent disconnects, and a revoked connection can no longer sync")
    void consentRevoked() {
        var s = connect();
        approve(s);
        consentService.revoke(USER, s.connection().getId());
        assertThat(connectionRepo.findById(s.connection().getId()).orElseThrow().getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(consentRepo.findById(s.consent().getId()).orElseThrow().getRevokedAt()).isNotNull();
        assertThatThrownBy(() -> sync.incrementalSync(USER, s.connection().getId())).isInstanceOf(ResponseStatusException.class);
    }

    @Test @DisplayName("sync: records flow raw → ledger with counters; everything from the mock is labelled MOCK, never LIVE")
    void syncFlowsAndLabelsMock() {
        var s = connect(); approve(s);
        mock.useFixtures(USER, List.of(
            aaTxn("AA-1", "SIP", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "50", "10000"),
            aaTxn("AA-2", "SIP", "2026-09-15", ISIN_A, "HDFC Flexi Cap", "50", "10000"),
            aaHolding(ISIN_A, "HDFC Flexi Cap", "100", "2026-10-05")));

        FinancialSyncRun run = sync.initialSync(USER, s.connection().getId());

        assertThat(run.getStatus()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.getRecordsFetched()).isEqualTo(3);
        assertThat(run.getRecordsCreated()).isGreaterThanOrEqualTo(2);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(raws.findAll()).allSatisfy(r -> assertThat(r.getProviderMode()).isEqualTo(ProviderMode.MOCK));
        assertThat(all()).hasSize(2);
        assertThat(issueRepo.findAll()).isEmpty();                     // holding 100 == ledger 100
        var conn = connectionRepo.findById(s.connection().getId()).orElseThrow();
        assertThat(conn.getSyncStatus()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(conn.getLastSuccessfulSyncAt()).isNotNull();
        assertThat(sync.recent(USER)).hasSize(1);
    }

    @Test @DisplayName("sync idempotency: re-running a sync over the same data creates nothing new")
    void syncIdempotent() {
        var s = connect(); approve(s);
        mock.useFixtures(USER, List.of(aaTxn("AA-1", "SIP", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "50", "10000")));
        sync.initialSync(USER, s.connection().getId());
        FinancialSyncRun again = sync.fullReconciliation(USER, s.connection().getId());
        assertThat(again.getRecordsCreated()).isZero();
        assertThat(again.getRecordsDuplicated()).isEqualTo(1);
        assertThat(all()).hasSize(1);
    }

    @Test @DisplayName("a bad record fails visibly (PARTIAL sync with the error), the good ones still land, and retry reprocesses only the failed")
    void partialSyncAndRetry() {
        var s = connect(); approve(s);
        mock.useFixtures(USER, List.of(
            aaTxn("AA-1", "SIP", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "50", "10000"),
            new RawRecord(RecordKind.TRANSACTION, "FOLIO1234", "BAD", "{}", "mystery-v9")));

        FinancialSyncRun run = sync.initialSync(USER, s.connection().getId());
        assertThat(run.getStatus()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(run.getErrors()).isNotBlank();
        assertThat(all()).hasSize(1);
        assertThat(raws.findAll().stream().filter(r -> r.getProcessingStatus() == ProcessingStatus.FAILED)).hasSize(1);

        FinancialSyncRun retry = sync.retryFailedSync(USER, s.connection().getId());
        assertThat(retry.getKind()).isEqualTo(SyncKind.RETRY_FAILED);
        assertThat(retry.getRecordsFetched()).isEqualTo(1);           // only the failed row, not the good one
        assertThat(all()).hasSize(1);                                  // and nothing duplicated
    }

    @Test @DisplayName("sync errors from the provider fail the run without leaking a stack trace or touching the ledger")
    void providerFailure() {
        var s = connect(); approve(s);
        mock.revokeConsent(s.consent().getProviderConsentHandle());     // provider-side revoke the platform has not yet heard about
        FinancialSyncRun run = sync.initialSync(USER, s.connection().getId());
        assertThat(run.getStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.getErrors()).doesNotContain("\tat ");
        assertThat(all()).isEmpty();
    }

    @Test @DisplayName("callback: a well-formed event updates the consent; an unverifiable one is rejected with 401 and changes nothing")
    void callbackVerification() {
        var s = connect();
        String handle = s.consent().getProviderConsentHandle();

        var bad = callbacks.callback(MockAccountAggregatorProvider.ID, Map.of(), "garbage");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(consentRepo.findById(s.consent().getId()).orElseThrow().getStatus()).isEqualTo(ConsentStatus.PENDING_APPROVAL);

        var ok = callbacks.callback(MockAccountAggregatorProvider.ID, Map.of(), handle + "|APPROVED");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(consentRepo.findById(s.consent().getId()).orElseThrow().getStatus()).isEqualTo(ConsentStatus.APPROVED);

        assertThat(callbacks.callback("no-such-provider", Map.of(), "x").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // an unknown handle is ignored, not an error that reveals which handles exist
        assertThat(callbacks.callback(MockAccountAggregatorProvider.ID, Map.of(), "other|APPROVED").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test @DisplayName("another user cannot refresh, sync or revoke someone else's connection")
    void connectionsArePrivate() {
        var s = connect(); approve(s);
        assertThatThrownBy(() -> sync.initialSync(999L, s.connection().getId())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> consentService.refresh(999L, s.consent().getId())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> consentService.revoke(999L, s.connection().getId())).isInstanceOf(ResponseStatusException.class);
    }

    @Test @DisplayName("the connections overview lists the Gmail signal honestly: email is a signal, not an institution source")
    void overviewExplainsEmail() {
        var overview = connectionsView.overview(USER);
        assertThat(overview.institutionSourceConnected()).isFalse();
        assertThat(overview.toString()).containsIgnoringCase("signal");
        var s = connect(); approve(s);
        // a MOCK connection is never counted as a real institution source
        assertThat(connectionsView.overview(USER).institutionSourceConnected()).isFalse();
        assertThat(connectionsView.overview(USER).connections()).anySatisfy(c -> assertThat(c.toString()).contains("MOCK"));
    }

    /* ───────── file import ───────── */

    private static final String HEADER = "Date,Type,Scheme,ISIN,Units,NAV,Amount,Reference\n";

    @Test @DisplayName("CSV import: rows enter through the same pipeline as STATEMENT source, and a second upload of the same file adds nothing")
    void csvImport() {
        String file = HEADER
            + "2026-01-10,SIP," + "Fund A," + ISIN_A + ",100,10,1000,\n"
            + "2026-02-10,SIP," + "Fund A," + ISIN_A + ",90,11.11,1000,\n"
            + "2026-03-10,Redemption," + "Fund A," + ISIN_A + ",50,12,600,\n";
        var opt = new CsvImportService.Options("HDFC Mutual Fund", "FOLIO1234", SourceType.STATEMENT, AssetClass.MUTUAL_FUND, "csv", false);

        var first = csv.importCsv(USER, file, opt);
        assertThat(first.created()).isEqualTo(3);
        assertThat(all()).allSatisfy(t -> assertThat(t.getSourceType()).isEqualTo(SourceType.STATEMENT));
        assertThat(all()).allSatisfy(t -> assertThat(t.getReconciliationStatus()).isEqualTo(ReconStatus.VERIFIED));

        var second = csv.importCsv(USER, file, opt);
        assertThat(second.created()).isZero();
        assertThat(second.duplicated()).isEqualTo(3);
        assertThat(all()).hasSize(3);
    }

    @Test @DisplayName("CSV import: two genuinely identical rows in one file are two transactions, not collapsed into one")
    void csvIdenticalRowsDistinct() {
        String file = HEADER
            + "2026-01-10,BUY,Fund A," + ISIN_A + ",10,10,100,\n"
            + "2026-01-10,BUY,Fund A," + ISIN_A + ",10,10,100,\n";
        var opt = new CsvImportService.Options("HDFC Mutual Fund", "FOLIO1234", SourceType.STATEMENT, AssetClass.MUTUAL_FUND, "csv", false);
        csv.importCsv(USER, file, opt);
        csv.importCsv(USER, file, opt);
        assertThat(all().stream().filter(t -> t.getStatus().counts() && t.getReconciliationStatus() != ReconStatus.DUPLICATE)).hasSize(2);
    }

    @Test @DisplayName("CSV import: bad rows are rejected with reasons and do not stop the good ones; formula-injection cells are not executed")
    void csvBadRows() {
        String file = HEADER
            + "2026-01-10,BUY,=HYPERLINK(\"http://evil\")," + ISIN_A + ",10,10,100,\n"
            + "not-a-date,BUY,Fund A," + ISIN_A + ",10,10,100,\n"
            + "2026-01-12,BUY,Fund A," + ISIN_A + ",10,10,100,\n";
        var opt = new CsvImportService.Options("HDFC Mutual Fund", "FOLIO1234", SourceType.STATEMENT, AssetClass.MUTUAL_FUND, "csv", false);
        var res = csv.importCsv(USER, file, opt);
        assertThat(res.rejected()).isGreaterThanOrEqualTo(1);
        assertThat(res.errors()).isNotEmpty();
        assertThat(res.created()).isGreaterThanOrEqualTo(1);
        assertThat(assetRepo.findAll()).noneMatch(a -> a.getName() != null && a.getName().startsWith("="));
    }

    @Test @DisplayName("CSV import refuses an unrecognised file type and a file with no recognisable columns")
    void csvValidation() {
        var opt = new CsvImportService.Options("HDFC", "X", SourceType.STATEMENT, AssetClass.MUTUAL_FUND, "csv", false);
        assertThatThrownBy(() -> csv.importCsv(USER, "foo,bar\n1,2\n", opt)).isInstanceOf(ResponseStatusException.class);
        var badSource = new CsvImportService.Options("HDFC", "X", SourceType.EMAIL, AssetClass.MUTUAL_FUND, "csv", false);
        assertThatThrownBy(() -> csv.importCsv(USER, HEADER + "2026-01-10,BUY,A,,1,1,1,\n", badSource)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> csv.importCsv(USER, HEADER, opt)).isInstanceOf(ResponseStatusException.class);
    }

    @Test @DisplayName("a complete statement that omits an email-reported transaction marks it UNCONFIRMED")
    void completeStatementCoverage() {
        emailRecorder.record(USER, emailSip(java.time.LocalDate.now().minusDays(10).toString(), ISIN_A, "Fund A", "5000"), "m1", "fp1");
        String file = HEADER + java.time.LocalDate.now().minusDays(40) + ",SIP,Fund A," + ISIN_A + ",10,10,100,\n";
        var opt = new CsvImportService.Options("HDFC Mutual Fund", "FOLIO1234", SourceType.STATEMENT, AssetClass.MUTUAL_FUND, "csv", true);
        csv.importCsv(USER, file, opt);
        assertThat(all().stream().filter(t -> t.getSourceType() == SourceType.EMAIL).findFirst().orElseThrow().getReconciliationStatus())
            .isNotEqualTo(ReconStatus.VERIFIED);
    }

    /* ───────── family ───────── */

    private User user(String email) {
        return users.save(User.builder().name(email).email(email).password("x").build());
    }

    private FinancialAccount account(Long owner, String key, Ownership o, Long familyId) {
        return accountRepo.save(FinancialAccount.builder().ownerUserId(owner).institution("HDFC").accountKey(key).displayName(key)
            .accountType(AccountType.MF_FOLIO).ownership(o).familyId(familyId).build());
    }

    @Test @DisplayName("family: INDIVIDUAL stays private, FAMILY is visible to sharing members only, JOINT is visible to co-owners")
    void familyAccess() {
        User a = user("a@x.in"), b = user("b@x.in"), c = user("c@x.in");
        Family f = family.create(a.getId(), "Singh family");
        family.addMember(a.getId(), f.getId(), "b@x.in", "MEMBER");

        FinancialAccount priv = account(a.getId(), "HDFC:PRIVATE", Ownership.INDIVIDUAL, null);
        FinancialAccount shared = account(a.getId(), "HDFC:SHARED", Ownership.INDIVIDUAL, null);
        FinancialAccount joint = account(a.getId(), "HDFC:JOINT", Ownership.INDIVIDUAL, null);
        family.setOwnership(a.getId(), shared.getId(), Ownership.FAMILY, f.getId(), null);
        family.setOwnership(a.getId(), joint.getId(), Ownership.JOINT, null, List.of(c.getId()));

        assertThat(family.accessibleAccountIds(a.getId(), true)).contains(priv.getId(), shared.getId(), joint.getId());
        // b is in the family but a has not turned sharing on yet
        assertThat(family.accessibleAccountIds(b.getId(), true)).doesNotContain(priv.getId(), shared.getId());
        family.setSharing(a.getId(), f.getId(), true);
        Set<Long> bSees = family.accessibleAccountIds(b.getId(), true);
        assertThat(bSees).contains(shared.getId()).doesNotContain(priv.getId(), joint.getId());
        assertThat(family.accessibleAccountIds(b.getId(), false)).isEmpty();                 // the individual view never includes family accounts
        // a co-owner sees and may change the joint account, but sees nothing else
        assertThat(family.accessibleAccountIds(c.getId(), false)).containsExactly(joint.getId());
        assertThat(family.canModify(c.getId(), joint.getId())).isTrue();
        assertThat(family.canModify(b.getId(), shared.getId())).isFalse();                   // viewing a family account is not editing it
        assertThat(family.canAccess(c.getId(), priv.getId())).isFalse();
    }

    @Test @DisplayName("family: only the owner can change an account's ownership; sharing into a family you are not in is refused")
    void familyOwnershipGuards() {
        User a = user("a@x.in"), b = user("b@x.in");
        Family f = family.create(a.getId(), "A");
        FinancialAccount acc = account(a.getId(), "HDFC:1", Ownership.INDIVIDUAL, null);
        assertThatThrownBy(() -> family.setOwnership(b.getId(), acc.getId(), Ownership.JOINT, null, List.of(b.getId())))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> family.setOwnership(b.getId(), acc.getId(), Ownership.FAMILY, f.getId(), null))
            .isInstanceOf(ResponseStatusException.class);
        FinancialAccount b1 = account(b.getId(), "HDFC:B", Ownership.INDIVIDUAL, null);
        assertThatThrownBy(() -> family.setOwnership(b.getId(), b1.getId(), Ownership.FAMILY, f.getId(), null))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("family you belong to");
    }

    @Test @DisplayName("a joint account can be shared with a co-owner by email, and an unknown email is refused")
    void jointByEmail() {
        User a = user("a@x.in"), c = user("c@x.in");
        FinancialAccount acc = account(a.getId(), "HDFC:J", Ownership.INDIVIDUAL, null);
        assertThatThrownBy(() -> family.setOwnershipByEmail(a.getId(), acc.getId(), Ownership.JOINT, null, List.of("nobody@x.in")))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("nobody@x.in");
        family.setOwnershipByEmail(a.getId(), acc.getId(), Ownership.JOINT, null, List.of("c@x.in"));
        assertThat(family.accessibleAccountIds(c.getId(), false)).containsExactly(acc.getId());
    }

    @Test @DisplayName("data health respects the family boundary: another member's unshared transactions never appear in my score")
    void healthRespectsOwnership() {
        emailRecorder.record(USER, emailSip("2026-08-15", ISIN_A, "Fund A", "1000"), "m1", "fp1");
        var other = quality().health(777L, false);
        assertThat(other.considered()).isZero();
        assertThat(quality().health(USER, false).considered()).isEqualTo(1);
    }

    @Autowired DataQualityService qualityService;
    private DataQualityService quality() { return qualityService; }
}
