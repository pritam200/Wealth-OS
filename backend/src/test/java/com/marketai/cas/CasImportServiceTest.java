package com.marketai.cas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.dataplatform.pipeline.RawRecord;
import com.marketai.dataplatform.service.IngestionPipeline;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CasImportServiceTest {

    private static byte[] pdf(String password) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
                float y = 780;
                for (String line : CasParserTest.CAS.split("\n")) {
                    cs.beginText();
                    cs.newLineAtOffset(20, y);
                    cs.showText(line);
                    cs.endText();
                    y -= 12;
                }
            }
            if (password != null) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy(password, password, new AccessPermission());
                policy.setEncryptionKeyLength(128);
                doc.protect(policy);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private final IngestionPipeline pipeline = mock(IngestionPipeline.class);
    private final CasImportService service = new CasImportService(pipeline, new ObjectMapper());

    @Test
    void opensTheProtectedPdfAndIngestsEveryTransaction() throws Exception {
        IngestionPipeline.Result res = new IngestionPipeline.Result();
        res.created = 4;
        when(pipeline.ingest(any())).thenReturn(res);

        CasImportService.Summary s = service.importPdf(1L, pdf("ABCDE1234F"), "ABCDE1234F", "My MF");

        assertThat(s.schemes()).isEqualTo(2);
        assertThat(s.rows()).isEqualTo(4);
        assertThat(s.created()).isEqualTo(4);
        assertThat(s.periodTo().toString()).isEqualTo("2022-12-31");
        assertThat(s.warnings()).anyMatch(w -> w.contains("Axis Bluechip") && w.contains("units don't add up"));

        ArgumentCaptor<IngestionPipeline.Request> req = ArgumentCaptor.forClass(IngestionPipeline.Request.class);
        verify(pipeline).ingest(req.capture());
        assertThat(req.getValue().records()).extracting(RawRecord::schemaVersion).containsOnly("statement-row-v1");
        assertThat(req.getValue().records().get(0).payload()).contains("INF179K01YZ4").contains("\"type\":\"SIP\"");
    }

    @Test
    void wrongOrMissingPasswordGivesAClearError() throws Exception {
        byte[] locked = pdf("RIGHT");
        assertThatThrownBy(() -> service.importPdf(1L, locked, "WRONG", "x"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("didn't open");
        assertThatThrownBy(() -> service.importPdf(1L, locked, "", "x"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("password-protected");
        verifyNoInteractions(pipeline);
    }
}
