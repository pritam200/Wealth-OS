package com.marketai.tax.controller;

import com.marketai.auth.entity.User;
import com.marketai.tax.dto.CapitalGainsExportRow;
import com.marketai.tax.dto.TaxResponse;
import com.marketai.tax.service.TaxService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/tax")
@RequiredArgsConstructor
public class TaxController {

    private final TaxService service;

    @GetMapping("/summary")
    public ResponseEntity<TaxResponse> summary(@AuthenticationPrincipal User user,
                                               @RequestParam(required = false) Integer fyStartYear) {
        return ResponseEntity.ok(service.summary(user.getId(), fyStartYear));
    }

    /**
     * Read-only capital-gains dump for ClearTax/Quicko-style ITR-filing tools — a correctly
     * formatted export of numbers already computed by {@link TaxService}, not a filer. See
     * {@link TaxService#capitalGainsExport} for what's in/out of scope.
     */
    @GetMapping("/export")
    public ResponseEntity<?> export(@AuthenticationPrincipal User user,
                                    @RequestParam(required = false) String financialYear,
                                    @RequestParam(defaultValue = "csv") String format) {
        Integer fyStartYear = parseFyStartYear(financialYear);
        List<CapitalGainsExportRow> rows = service.capitalGainsExport(user.getId(), fyStartYear);

        if ("json".equalsIgnoreCase(format)) {
            return ResponseEntity.ok(rows);
        }

        byte[] csv = toCsv(rows).getBytes(StandardCharsets.UTF_8);
        String filename = "capital-gains-" + (financialYear != null ? financialYear : "current") + ".csv";
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString())
            .body(csv);
    }

    /** Accepts "2025-26" (the FY label format used across the app) or a bare start year. */
    private Integer parseFyStartYear(String financialYear) {
        if (financialYear == null || financialYear.isBlank()) return null;
        String startPart = financialYear.split("-")[0].trim();
        return Integer.parseInt(startPart);
    }

    private String toCsv(List<CapitalGainsExportRow> rows) {
        StringBuilder sb = new StringBuilder(
            "Asset Symbol,Asset Name,ISIN,Acquisition Date,Sale Date,Quantity,"
                + "Acquisition Value,Sale Value,Gain Type,Gain/Loss,Exemption Applied\n");
        for (CapitalGainsExportRow r : rows) {
            sb.append(csvField(r.assetSymbol())).append(',')
                .append(csvField(r.assetName())).append(',')
                .append(csvField(r.isin())).append(',')
                .append(csvField(r.acquisitionDate())).append(',')
                .append(csvField(r.saleDate())).append(',')
                .append(csvField(r.quantity())).append(',')
                .append(csvField(r.acquisitionValue())).append(',')
                .append(csvField(r.saleValue())).append(',')
                .append(csvField(r.gainType())).append(',')
                .append(csvField(r.gainOrLoss())).append(',')
                .append(csvField(r.exemptionApplied())).append('\n');
        }
        return sb.toString();
    }

    private String csvField(Object value) {
        if (value == null) return "";
        String s = value.toString();
        return (s.contains(",") || s.contains("\""))
            ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
