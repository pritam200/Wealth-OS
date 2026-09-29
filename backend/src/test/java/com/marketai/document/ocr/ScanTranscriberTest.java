package com.marketai.document.ocr;

import com.marketai.ai.llm.LlmCompletion;
import com.marketai.ai.llm.LlmService;
import com.marketai.ai.llm.LlmUnavailableException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScanTranscriberTest {

    @Test
    @DisplayName("every scanned page is read, in batches of the per-call limit; a scan past the total limit is refused, not read in part")
    void rendersPages() throws Exception {
        LlmService router = mock(LlmService.class);
        when(router.transcribe(any(), anyString(), anyList()))
            .thenReturn(LlmCompletion.builder().text("Amount Rs 5000 debited").build());
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < ScanTranscriber.MAX_PAGES + 2; i++) doc.addPage(new PDPage());

            String text = new ScanTranscriber(router).transcribePdf(doc);

            assertThat(text).isEqualTo("Amount Rs 5000 debited\n\nAmount Rs 5000 debited");
            verify(router).transcribe(any(), anyString(),
                argThat((List<com.marketai.ai.client.GeminiClient.InlineImage> images) -> images.size() == ScanTranscriber.MAX_PAGES));
            verify(router).transcribe(any(), anyString(),
                argThat((List<com.marketai.ai.client.GeminiClient.InlineImage> images) -> images.size() == 2));
        }
        try (PDDocument big = new PDDocument()) {
            for (int i = 0; i < ScanTranscriber.MAX_TOTAL_PAGES + 1; i++) big.addPage(new PDPage());
            assertThatThrownBy(() -> new ScanTranscriber(router).transcribePdf(big))
                .isInstanceOf(ScanTranscriber.ScanUnreadableException.class).hasMessageContaining("not read in part");
        }
    }

    @Test
    @DisplayName("no vision model, or an empty reading, is an explicit unreadable-scan error — never empty text")
    void unreadableIsExplicit() {
        LlmService router = mock(LlmService.class);
        when(router.transcribe(any(), anyString(), anyList()))
            .thenThrow(new LlmUnavailableException(LlmService.NO_VISION));
        assertThatThrownBy(() -> new ScanTranscriber(router).transcribeImage(new byte[]{1, 2, 3}, "receipt.jpg"))
            .isInstanceOf(ScanTranscriber.ScanUnreadableException.class)
            .hasMessageContaining("image-capable model");

        doReturn(LlmCompletion.builder().text("  ").build()).when(router).transcribe(any(), anyString(), anyList());
        assertThatThrownBy(() -> new ScanTranscriber(router).transcribeImage(new byte[]{1}, "a.png"))
            .isInstanceOf(ScanTranscriber.ScanUnreadableException.class);
    }

    @Test
    void recognisesImageAttachments() {
        assertThat(ScanTranscriber.isImageFile("Statement.JPG")).isTrue();
        assertThat(ScanTranscriber.isImageFile("note.pdf")).isFalse();
        assertThat(ScanTranscriber.mimeOf("x.webp")).isEqualTo("image/webp");
    }
}
