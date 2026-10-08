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
    private final com.marketai.identity.service.FinancialIdentityService identity;

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
        fileUnderSource(userId, summary, "Forwarded CAS: ");
        return summary;
    }

    /** Password guesses tried automatically, from the PAN and date of birth the user already gave. */
    private static final List<com.marketai.identity.service.PasswordStrategy> AUTO_PASSWORDS = List.of(
        com.marketai.identity.service.PasswordStrategy.PAN_UPPERCASE,
        com.marketai.identity.service.PasswordStrategy.PAN_FIRST4_PLUS_DOB_DDMMYYYY,
        com.marketai.identity.service.PasswordStrategy.PAN_UPPERCASE_PLUS_DOB_DDMMYYYY,
        com.marketai.identity.service.PasswordStrategy.DOB_DDMMYYYY);

    /**
     * The one-box upload: any mutual-fund or demat CAS PDF. The kind is read from the document,
     * and a locked PDF is opened with the password the user typed, else with passwords derived from
     * their stored PAN / date of birth, so most people never have to type one.
     */
    @Transactional
    public CasResult importCasAuto(Long userId, MultipartFile file, String typed) throws IOException {
        if (file == null || file.isEmpty()) throw bad("The file is empty.");
        if (file.getSize() > 15_000_000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The file is too large (limit 15 MB).");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".pdf"))
            throw bad("This box takes the PDF statement (CAS). For a CSV or Excel file, use the bank or broker section below.");
        byte[] pdf = file.getBytes();

        List<String> candidates = new ArrayList<>();
        if (typed != null && !typed.isBlank()) candidates.add(typed.trim());
        if (casImport.needsPassword(pdf)) {
            for (var st : AUTO_PASSWORDS) identity.derivePassword(userId, st).ifPresent(pw -> { if (!candidates.contains(pw)) candidates.add(pw); });
        } else if (candidates.isEmpty()) {
            candidates.add(null);
        }
        if (candidates.isEmpty())
            throw bad("This PDF is locked and we don't have your PAN or date of birth saved. Type its password (for a depository statement it is your PAN in capitals).");

        ResponseStatusException last = null;
        for (String pw : candidates) {
            try {
                CasImportService.Summary summary = casImport.importPdf(userId, pdf, pw, "Statement upload", null);
                ImportSource src = fileUnderSource(userId, summary, "CAS: ");
                return new CasResult(view(src, LocalDate.now(IST)), summary);
            } catch (ResponseStatusException e) {
                String m = e.getReason() == null ? "" : e.getReason();
                if (!m.contains("password")) throw e;   // a real parse problem: stop guessing
                last = e;
            }
        }
        throw bad(typed != null && !typed.isBlank()
            ? "That password didn't open the PDF, and neither did the ones we can work out from your saved PAN and date of birth."
            : "This PDF is locked and none of the passwords we can work out from your saved details opened it. Type its password.");
    }

    private ImportSource fileUnderSource(Long userId, CasImportService.Summary summary, String notePrefix) {
        SourceKind kind = summary.kind() == CasImportService.Kind.DEMAT ? SourceKind.STOCKS : SourceKind.MUTUAL_FUNDS;
        ImportSource s = sources.findByUserIdOrderByKindAscNameAsc(userId).stream()
            .filter(x -> x.getKind() == kind).findFirst()
            .orElseGet(() -> sources.save(ImportSource.builder().userId(userId).kind(kind)
                .name(kind == SourceKind.STOCKS ? "Demat (CAS)" : "Mutual funds (CAS)").createdAt(LocalDateTime.now()).build()));
        if (summary.periodTo() != null && (s.getSyncedThrough() == null || summary.periodTo().isAfter(s.getSyncedThrough()))) {
            s.setSyncedThrough(summary.periodTo());
        }
        s.setLastImportAt(LocalDateTime.now());
        s.setLastImportNote(notePrefix + summary.schemes() + (kind == SourceKind.STOCKS ? " holdings, " : " schemes, ")
            + summary.created() + " new, " + summary.duplicated() + " already present");
        return sources.save(s);
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
                "A depository statement (CAS) lists your shares at every broker. A broker's own file adds what you paid.",
                List.of("Easiest: drop the statement in the box at the top of this page. Check your email for a message from NSDL or CDSL with a CAS attached, they send one regularly.",
                    "Locked PDFs usually open with your PAN in capital letters. If your PAN and date of birth are saved in Settings, we try that for you.",
                    "To include what you paid: download the holdings or tradebook file from your broker's website (look under Reports or Portfolio) and upload it against that broker here. Menu names differ by broker."),
                "Add a fresh statement every month or two. Only data after 'synced through' is new.",
                true),
            new Guide("MUTUAL_FUNDS", SourceKind.MUTUAL_FUNDS.label(),
                "One statement (CAS) covers every fund house, so you do not set up each fund separately.",
                List.of("Easiest: drop the statement in the box at the top of this page. Check your email for a message with a 'Consolidated Account Statement' attached, the registrars send one regularly.",
                    "No email? Request one free on mfcentral.com, camsonline.com or kfintech.com. Look for the consolidated statement (CAS), choose the detailed type so transactions are included, and note the password you set.",
                    "Funds held inside your demat account show up in the demat statement instead."),
                "Add a fresh statement every month or so. Overlapping periods are fine, repeats are skipped.",
                true),
            new Guide("FIXED_DEPOSITS", SourceKind.FIXED_DEPOSITS.label(),
                "Banks do not offer a combined statement, so this is mostly typing a few details per deposit.",
                List.of("Open your bank's netbanking or app and find your deposits (usually Deposits, or Accounts then Fixed Deposits).",
                    "For each one note the amount, interest rate, start date and maturity date. If the bank offers a deposit advice or list to download, keep that file too.",
                    "Add each FD under Financial Planning, then set the date you are up to here."),
                "FDs change rarely. Check again when one matures or you open a new one.",
                true),
            new Guide("RECURRING_DEPOSITS", SourceKind.RECURRING_DEPOSITS.label(),
                "Same as fixed deposits: a few details per RD.",
                List.of("Open netbanking or the app and find Recurring Deposits.",
                    "Note the monthly amount, interest rate, start date and tenure.",
                    "Add it here or under Financial Planning, then set the date you are up to."),
                "Update monthly, after each installment.",
                true),
            new Guide("CREDIT_CARDS", SourceKind.CREDIT_CARDS.label(),
                "The latest statement of each card (balance, due date, limit).",
                List.of("Open the card issuer's app or netbanking and download the latest statement PDF (some banks also offer Excel).",
                    "Statement PDFs are often locked. Banks usually build the password from your name and date of birth or the last 4 digits of the card, and the rule differs by bank, so check the email that carried the statement.",
                    "Add the card here, then set the date of the latest statement you have entered."),
                "Each month when the new statement arrives. Connecting Gmail can pick card statements up automatically.",
                false));
    }
}
