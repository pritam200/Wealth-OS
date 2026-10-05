package com.marketai.dataplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marketai.dataplatform.domain.ProviderMode;
import com.marketai.dataplatform.domain.RecordKind;
import com.marketai.dataplatform.domain.SourceType;
import com.marketai.dataplatform.pipeline.EmailEventNormalizer;
import com.marketai.dataplatform.pipeline.RawRecord;
import com.marketai.gmail.parser.ParsedEmail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The bridge from the Gmail importer into the canonical ledger. An email is a signal: it enters
 * the same pipeline as every other source, as a record from the EMAIL source, and waits for an
 * authoritative source to corroborate it. It never decides anything itself.
 *
 * <p>Must never disturb the import it observes: it runs in its own transaction and swallows and
 * logs every failure. The legacy tables remain what the existing screens read; this adds the
 * provenance-carrying record beside them.
 */
@Service
@Slf4j
public class EmailSignalRecorder {

    public static final String PROVIDER = "gmail";

    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{9,}");
    private static final Pattern PAN = Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b");

    private final ObjectMapper mapper;
    private final IngestionPipeline pipeline;
    private final TransactionTemplate isolated;

    public EmailSignalRecorder(ObjectMapper mapper, IngestionPipeline pipeline, PlatformTransactionManager tm) {
        this.mapper = mapper;
        this.pipeline = pipeline;
        this.isolated = new TransactionTemplate(tm);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @param lineFingerprint the importer's content fingerprint for this line: the same value
     *                        stored on the legacy row, so migrating that row later is recognised as the same record
     */
    public void record(Long userId, ParsedEmail pe, String gmailMessageId, String lineFingerprint) {
        try {
            if (pe == null || pe.getType() == null || !EmailEventNormalizer.SUPPORTED.contains(pe.getType().name())) return;
            String payload = mapper.writeValueAsString(payload(pe, gmailMessageId));
            String recordId = lineFingerprint != null ? lineFingerprint : gmailMessageId;
            RawRecord rec = new RawRecord(RecordKind.TRANSACTION, null, recordId, payload, EmailEventNormalizer.SCHEMA);
            isolated.executeWithoutResult(s -> pipeline.ingest(new IngestionPipeline.Request(userId, null, null,
                SourceType.EMAIL, PROVIDER, ProviderMode.LIVE, List.of(rec), null)));
        } catch (Exception e) {
            log.warn("event=email_signal_failed userId={} error={}", userId, e.getClass().getSimpleName());
        }
    }

    /** The extracted fields only — no email body, subject or sender, and long digit runs masked. */
    ObjectNode payload(ParsedEmail pe, String gmailMessageId) {
        ObjectNode n = mapper.createObjectNode();
        put(n, "type", pe.getType().name());
        put(n, "symbol", pe.getSymbol()); put(n, "exchange", pe.getExchange());
        if (pe.getQuantity() != null) n.put("quantity", pe.getQuantity());
        put(n, "price", pe.getPrice()); put(n, "tradeDate", pe.getTradeDate()); put(n, "startDate", pe.getStartDate());
        put(n, "maturityDate", pe.getMaturityDate());
        put(n, "bank", pe.getBank()); put(n, "principal", pe.getPrincipal()); put(n, "monthlyAmount", pe.getMonthlyAmount());
        put(n, "fundName", pe.getFundName()); put(n, "nav", pe.getNav()); put(n, "units", pe.getUnits()); put(n, "amount", pe.getAmount());
        put(n, "folio", mask(pe.getFolio())); put(n, "provider", pe.getProvider()); put(n, "isin", pe.getIsin());
        put(n, "dpId", pe.getDpId()); put(n, "clientId", mask(pe.getClientId()));
        put(n, "charges", pe.getCharges()); put(n, "tradeReference", pe.getTradeReference()); put(n, "linkGroup", pe.getLinkGroup());
        put(n, "newSymbol", pe.getNewSymbol()); put(n, "rate", pe.getRate()); put(n, "tds", pe.getTds()); put(n, "corporateAction", pe.getCorporateAction());
        put(n, "ratioFrom", pe.getRatioFrom()); put(n, "ratioTo", pe.getRatioTo()); put(n, "instrumentKind", pe.getInstrumentKind());
        n.put("idcwReinvest", pe.isIdcwReinvest());
        if (pe.getExtractionConfidence() != null) n.put("extractionConfidence", pe.getExtractionConfidence());
        put(n, "gmailMessageId", gmailMessageId);
        String d = pe.getSourceDescription();
        put(n, "sourceDescription", d == null ? null : mask(d.length() > 300 ? d.substring(0, 300) : d));
        return n;
    }

    private static void put(ObjectNode n, String k, Object v) { if (v != null) n.put(k, v.toString()); }

    static String mask(String s) {
        if (s == null) return null;
        String out = LONG_DIGITS.matcher(s).replaceAll(m -> "••••" + m.group().substring(m.group().length() - 4));
        return PAN.matcher(out).replaceAll("••••••••••");
    }
}
