package com.marketai.onboarding;

import com.marketai.cas.CasImportService;
import com.marketai.dataplatform.domain.SourceType;
import com.marketai.dataplatform.service.CsvImportService;
import com.marketai.dataplatform.service.ExcelCsvConverter;
import com.marketai.gmail.repository.GmailTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The setup checklist: which sources a user has, how to get each one's data, and how far each
 * has been imported ("synced through"). A source is NOT_STARTED until it has a date, CURRENT
 * while that date is within its refresh window, and STALE after.
 */
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final long MAX_FILE_BYTES = 5_000_000;

    private final ImportSourceRepository sources;
    private final CsvImportService csvImport;
    private final GmailTokenRepository gmailTokens;
    private final CasImportService casImport;

    public record SourceView(Long id, String kind, String name, LocalDate syncedThrough, LocalDateTime lastImportAt,
                             String lastImportNote, String status, Long daysBehind, LocalDate nextUpdateDue,
                             boolean fileImportable) {}

    public record Guide(String kind, String title, String best, List<String> steps, String ongoing, boolean fileImportable) {}

    public record GmailView(boolean connected, String email, LocalDateTime lastSyncAt) {}

    public record Checklist(List<SourceView> sources, List<Guide> guides, GmailView gmail, int total, int current) {}

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public Checklist checklist(Long userId) {
        LocalDate today = LocalDate.now(IST);
        List<SourceView> views = sources.findByUserIdOrderByKindAscNameAsc(userId).stream()
            .map(s -> view(s, today)).toList();
        GmailView gmail = gmailTokens.findByUserId(userId)
            .map(t -> new GmailView(true, t.getConnectedEmail(), t.getLastSyncAt()))
            .orElse(new GmailView(false, null, null));
        int current = (int) views.stream().filter(v -> "CURRENT".equals(v.status())).count();
        return new Checklist(views, guides(), gmail, views.size(), current);
    }

    private static SourceView view(ImportSource s, LocalDate today) {
        LocalDate through = s.getSyncedThrough();
        String status;
        Long behind = null;
        LocalDate due = null;
        if (through == null) {
            status = "NOT_STARTED";
        } else {
            behind = ChronoUnit.DAYS.between(through, today);
            due = through.plusDays(s.getKind().refreshEveryDays());
            status = behind > s.getKind().refreshEveryDays() ? "STALE" : "CURRENT";
        }
        return new SourceView(s.getId(), s.getKind().name(), s.getName(), through, s.getLastImportAt(),
            s.getLastImportNote(), status, behind, due, s.getKind().fileImportable());
    }

    // ------------------------------------------------------------------ change

    @Transactional
    public SourceView add(Long userId, String kindName, String rawName) {
        SourceKind kind = parseKind(kindName);
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 80) throw bad("Enter a name of up to 80 characters, e.g. Zerodha or HDFC Bank.");
        if (sources.existsByUserIdAndKindAndNameIgnoreCase(userId, kind, name))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already added " + name + " under " + kind.label() + ".");
        ImportSource saved = sources.save(ImportSource.builder()
            .userId(userId).kind(kind).name(name).createdAt(LocalDateTime.now()).build());
        return view(saved, LocalDate.now(IST));
    }

    @Transactional
    public void remove(Long userId, Long id) {
        sources.delete(owned(userId, id));
    }

    /** For sources entered by hand: the user states how far their entries go. Can move either way — a correction is allowed. */
    @Transactional
    public SourceView setSyncedThrough(Long userId, Long id, LocalDate date) {
        ImportSource s = owned(userId, id);
        if (date == null) throw bad("Pick the date your data is up to.");
        if (date.isAfter(LocalDate.now(IST))) throw bad("That date is in the future.");
        s.setSyncedThrough(date);
        s.setLastImportAt(LocalDateTime.now());
        s.setLastImportNote("Marked up to date by you");
        return view(sources.save(s), LocalDate.now(IST));
    }

    /**
     * Imports a statement/holdings file for this source and moves its watermark to the latest
     * date in the file. The watermark only moves forward here: an older file never makes data look
     * less current than it is.
     */
    @Transactional
    public ImportResult importFile(Long userId, Long id, MultipartFile file, boolean completeStatement) throws IOException {
        ImportSource s = owned(userId, id);
        if (!s.getKind().fileImportable())
            throw bad("Files can't be imported for " + s.getKind().label().toLowerCase()
                + " yet. Add them by hand, then mark the date you are up to.");
        if (file == null || file.isEmpty()) throw bad("The file is empty.");
        if (file.getSize() > MAX_FILE_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The file is too large (limit 5 MB).");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String csv;
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) csv = ExcelCsvConverter.toCsv(file.getBytes());
        else if (name.endsWith(".csv") || name.endsWith(".txt")) csv = new String(file.getBytes(), StandardCharsets.UTF_8);
        else throw bad("Upload a .csv, .xlsx or .xls file.");

        CsvImportService.Summary summary = csvImport.importCsv(userId, csv, new CsvImportService.Options(
            s.getName(), null, SourceType.STATEMENT, s.getKind().assetClass(), "setup-checklist", completeStatement));

        if (summary.latest() != null && summary.created() + summary.duplicated() > 0
                && (s.getSyncedThrough() == null || summary.latest().isAfter(s.getSyncedThrough()))) {
            s.setSyncedThrough(summary.latest());
        }
        s.setLastImportAt(LocalDateTime.now());
        s.setLastImportNote(summary.created() + " new, " + summary.duplicated() + " already present"
            + (summary.rejected() > 0 ? ", " + summary.rejected() + " rejected" : ""));
        return new ImportResult(view(sources.save(s), LocalDate.now(IST)), summary);
    }

    /**
     * Imports a CAMS/KFintech mutual-fund CAS PDF against a mutual-fund source. The watermark
     * becomes the statement's end date — the date it is complete through, which can be later
     * than its last transaction — and, as with files, only moves forward.
     */
    @Transactional
    public CasResult importCas(Long userId, Long id, MultipartFile file, String password) throws IOException {
        ImportSource s = owned(userId, id);
        if (s.getKind() != SourceKind.MUTUAL_FUNDS && s.getKind() != SourceKind.STOCKS)
            throw bad("A CAS PDF imports into the Mutual funds or Stocks section.");
        if (file == null || file.isEmpty()) throw bad("The file is empty.");
        if (file.getSize() > 15_000_000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The file is too large (limit 15 MB).");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".pdf")) throw bad("Upload the CAS as a PDF.");

        CasImportService.Summary summary = casImport.importPdf(userId, file.getBytes(), password, s.getName(),
            s.getKind() == SourceKind.STOCKS ? CasImportService.Kind.DEMAT : CasImportService.Kind.MUTUAL_FUND);
        if (summary.periodTo() != null && (s.getSyncedThrough() == null || summary.periodTo().isAfter(s.getSyncedThrough()))) {
            s.setSyncedThrough(summary.periodTo());
        }
        s.setLastImportAt(LocalDateTime.now());
 s.setLastImportNote("CAS: " + summary.schemes() + (s.getKind() == SourceKind.STOCKS ? " holdings, " : " schemes, ") + summary.created() + " new, "
            + summary.duplicated() + " already present");
        return new CasResult(view(sources.save(s), LocalDate.now(IST)), summary);
    }

    /**
     * A CAS that arrived without the user choosing a section (forwarded email): the kind is read
     * from the document and it is filed under the user's first source of that kind, created if the
     * user has none yet. Same watermark rule as an upload.
     */
    @Transactional
    public CasImportService.Summary importForwardedCas(Long userId, byte[] pdf, String password) {
        CasImportService.Summary summary = casImport.importPdf(userId, pdf, password, "Forwarded CAS", null);
        SourceKind kind = summary.kind() == CasImportService.Kind.DEMAT ? SourceKind.STOCKS : SourceKind.MUTUAL_FUNDS;
        ImportSource s = sources.findByUserIdOrderByKindAscNameAsc(userId).stream()
            .filter(x -> x.getKind() == kind).findFirst()
            .orElseGet(() -> sources.save(ImportSource.builder().userId(userId).kind(kind)
                .name(kind == SourceKind.STOCKS ? "Demat (CAS)" : "Mutual funds (CAS)").createdAt(LocalDateTime.now()).build()));
        if (summary.periodTo() != null && (s.getSyncedThrough() == null || summary.periodTo().isAfter(s.getSyncedThrough()))) {
            s.setSyncedThrough(summary.periodTo());
        }
        s.setLastImportAt(LocalDateTime.now());
        s.setLastImportNote("Forwarded CAS: " + summary.schemes() + (kind == SourceKind.STOCKS ? " holdings, " : " schemes, ")
            + summary.created() + " new, " + summary.duplicated() + " already present");
        sources.save(s);
        return summary;
    }

    public record CasResult(SourceView source, CasImportService.Summary summary) {}

    public record ImportResult(SourceView source, CsvImportService.Summary summary) {}

    // ------------------------------------------------------------------ helpers

    private ImportSource owned(Long userId, Long id) {
        return sources.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found"));
    }

    private static SourceKind parseKind(String s) {
        try {
            return SourceKind.valueOf(s);
        } catch (Exception e) {
            throw bad("Unknown source type.");
        }
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    /** How to get each kind's data. Static text: the screen shows it beside the upload box. */
    static List<Guide> guides() {
        return List.of(
            new Guide("STOCKS", SourceKind.STOCKS.label(),
                "Your NSDL/CDSL demat statement shows every holding across all brokers; your broker's export adds buy prices.",
                List.of("Positions across all brokers: NSDL/CDSL send a monthly demat CAS by email, or request one at nsdl.co.in / cdslindia.com (e-CAS) with your PAN. Upload the PDF below, with its password, against any broker here.",
                    "Cost basis per broker: open the broker's web portal (e.g. Zerodha Console, Groww, Upstox).",
                    "Go to Reports / Holdings or Tradebook and download the CSV or Excel file.",
                    "Add the broker below, then upload the file against it.",
                    "For a trade history, pick the full financial year(s) you want, and tick 'complete statement' so gaps are flagged."),
                "Re-download the tradebook once a month (or after big trades) and upload it. Only dates after 'synced through' are new.",
                true),
            new Guide("MUTUAL_FUNDS", SourceKind.MUTUAL_FUNDS.label(),
                "One CAS (Consolidated Account Statement) covers every fund house and platform you've ever used.",
                List.of("Go to camsonline.com (or mykarvy.com) → Investor Services → Mailback → Consolidated Account Statement.",
                    "Choose 'Detailed (transaction listing)', period 'Since inception' (or the range you need), and 'with zero balance folios'. Enter your PAN and email, and set a password.",
                    "The PDF arrives by email within minutes. Upload it below with the password you set.",
                    "Don't have it handy? Upload a CSV/Excel statement from your platform instead."),
                "Request a fresh CAS each month. Re-uploading overlapping periods is safe: duplicates are skipped.",
                true),
            new Guide("FIXED_DEPOSITS", SourceKind.FIXED_DEPOSITS.label(),
                "A deposit list from each bank (netbanking → Deposits), as CSV/Excel, or add them by hand.",
                List.of("Log in to the bank's netbanking and open Deposits / Fixed Deposits.",
                    "Download the list if the bank offers it, otherwise note bank, amount, rate, start and maturity date.",
                    "Upload the file here, or add each FD under Financial Planning, then set the date you're up to."),
                "FDs change rarely. Check again when one matures or you open a new one.",
                true),
            new Guide("RECURRING_DEPOSITS", SourceKind.RECURRING_DEPOSITS.label(),
                "Your RD installments per bank, as a file or entered by hand.",
                List.of("Open netbanking → Deposits → Recurring Deposits.",
                    "Download the passbook or statement, or note the monthly amount, rate and start date.",
                    "Upload it here or enter it by hand, then set the date you're up to."),
                "Update monthly, after each installment.",
                true),
            new Guide("CREDIT_CARDS", SourceKind.CREDIT_CARDS.label(),
                "The latest statement of each card (balance, due date, limit).",
                List.of("Open the card issuer's app or net banking and download the latest statement PDF.",
                    "Add the card in Cards & Rewards, using the statement for the limit and due date.",
                    "Add the card here, then set the date of the latest statement you've entered."),
                "Each month when the new statement arrives. Connecting Gmail can pick card statements up automatically.",
                false));
    }
}
