package com.marketai.document.extract;

/**
 * One value pulled out of a document, together with the exact text it came from.
 *
 * <p>{@code sourceSpan} is what turns "every financial number must be traceable" from an
 * aspiration into a mechanically checkable property. Email-level provenance answers "which
 * message produced this holding"; it cannot answer "which words in it said ₹47,300". Only the
 * second is enough to settle a disagreement with the user about a number on their screen.
 *
 * <p>It is also the cheapest hallucination defence available. A span that cannot be found in
 * the source was not read out of the document, whatever the model reported about it.
 *
 * @param name       canonical field name, e.g. {@code quantity}, {@code netAmount}
 * @param rawValue   exactly as it appeared, before normalisation
 * @param sourceSpan verbatim substring of the source text this was read from
 * @param page       1-based page for PDFs, null for email bodies
 * @param bbox       optional bounding box, when the extractor can supply one
 */
public record ExtractedField(String name, String rawValue, String sourceSpan,
                             Integer page, String bbox) {

    public ExtractedField {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Extracted field must be named");
        }
        if (sourceSpan == null || sourceSpan.isBlank()) {
            // Refusing at construction is deliberate. A field with no span is untraceable, and
            // allowing one would let the guarantee erode silently, one extractor at a time.
            throw new IllegalArgumentException(
                "Field '" + name + "' has no source span — every extracted value must cite the "
                    + "text it came from");
        }
    }

    public static ExtractedField ofEmail(String name, String rawValue, String sourceSpan) {
        return new ExtractedField(name, rawValue, sourceSpan, null, null);
    }

    public static ExtractedField ofPdf(String name, String rawValue, String sourceSpan, int page) {
        return new ExtractedField(name, rawValue, sourceSpan, page, null);
    }
}
