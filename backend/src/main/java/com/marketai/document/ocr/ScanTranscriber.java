package com.marketai.document.ocr;

import com.marketai.ai.prompt.PromptLibrary;

import com.marketai.ai.llm.LlmService;

import com.marketai.ai.client.GeminiClient;
import com.marketai.ai.llm.LlmUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a scanned statement or a photographed document into text.
 *
 * <p>The vision model only transcribes — it is asked for the page's text, verbatim, and nothing
 * else. Reading transactions out of that text is left to the ordinary extractor, so a scan goes
 * through exactly the same checks as a text PDF: every amount and date must be found in the
 * line it was read from. A model that "helpfully" summarised a page instead of transcribing it
 * would fail those checks rather than be believed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScanTranscriber {

    /** Pages beyond this are not sent — a statement's transactions are on its first pages, and
     *  every page is one image in the request. */
    static final int MAX_PAGES = 8;
    /** Longer scans are refused rather than read in part. */
    static final int MAX_TOTAL_PAGES = 40;
    private static final float DPI = 150f;

    
    private final LlmService router;

    /** Why a scan could not be read — shown to the user instead of a silent empty result. */
    public static class ScanUnreadableException extends Exception {
        public ScanUnreadableException(String message) { super(message); }
    }

    /** True when some configured provider can read images. */
    public boolean available() {
        return router.canReadImages();
    }

    public static boolean isImageFile(String filename) {
        if (filename == null) return false;
        String f = filename.toLowerCase(Locale.ROOT);
        return f.endsWith(".png") || f.endsWith(".jpg") || f.endsWith(".jpeg") || f.endsWith(".webp");
    }

    static String mimeOf(String filename) {
        String f = filename.toLowerCase(Locale.ROOT);
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    /**
     * Transcribes every page of an (already decrypted) PDF with no text layer, {@link #MAX_PAGES}
     * pages per model call. A scan longer than {@link #MAX_TOTAL_PAGES} is refused with the
     * reason rather than read in part: a statement with its last pages unread would look
     * complete while missing their lines.
     */
    public String transcribePdf(PDDocument doc) throws ScanUnreadableException {
        int total = doc.getNumberOfPages();
        if (total > MAX_TOTAL_PAGES) {
            throw new ScanUnreadableException("The scan has " + total + " pages; at most " + MAX_TOTAL_PAGES
                + " are transcribed, so it was not read in part. Import it as a text PDF or split it.");
        }
        StringBuilder text = new StringBuilder();
        PDFRenderer renderer = new PDFRenderer(doc);
        for (int from = 0; from < total; from += MAX_PAGES) {
            List<GeminiClient.InlineImage> pages = new ArrayList<>();
            try {
                for (int i = from; i < Math.min(total, from + MAX_PAGES); i++) {
                    BufferedImage img = renderer.renderImageWithDPI(i, DPI, ImageType.GRAY);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ImageIO.write(img, "png", out);
                    pages.add(new GeminiClient.InlineImage("image/png", out.toByteArray()));
                }
            } catch (Exception e) {
                throw new ScanUnreadableException("The scanned pages could not be rendered: " + e.getMessage());
            }
            if (text.length() > 0) text.append("\n\n");
            text.append(transcribe(pages));
        }
        if (text.length() == 0) throw new ScanUnreadableException("The document has no pages.");
        return text.toString();
    }

    public String transcribeImage(byte[] bytes, String filename) throws ScanUnreadableException {
        return transcribe(List.of(new GeminiClient.InlineImage(mimeOf(filename), bytes)));
    }

    private String transcribe(List<GeminiClient.InlineImage> images) throws ScanUnreadableException {
        if (images.isEmpty()) throw new ScanUnreadableException("The document has no pages.");
        try {
            String text = router.transcribe(PromptLibrary.SCAN_TRANSCRIPTION, "Transcribe this document.", images).getText();
            if (text == null || text.isBlank()) throw new ScanUnreadableException("No text could be read from the scan.");
            return text;
        } catch (LlmUnavailableException e) {
            throw new ScanUnreadableException(e.getMessage());
        }
    }
}
