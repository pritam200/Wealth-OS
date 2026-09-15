package com.marketai.document.extract;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks that every extracted field's source span genuinely occurs in the document.
 *
 * <p>This is the grounding check. Schema-constrained decoding guarantees an LLM returns
 * well-formed JSON; it guarantees nothing about whether the values were read from the document
 * or invented in a plausible shape. Verifying the span mechanically closes that gap without
 * trusting anything the model says about itself.
 *
 * <p>Whitespace is normalised before comparison because PDF text extraction routinely
 * introduces line breaks and runs of spaces inside a value that is visually contiguous.
 * Nothing else is relaxed: the characters must be present, in order, in the source.
 */
public final class SpanVerifier {

    private SpanVerifier() {}

    public record Result(boolean allFound, List<String> unfoundFields, int checked) {
        public double score() {
            if (checked == 0) return 0.0;
            return (double) (checked - unfoundFields.size()) / checked;
        }
    }

    /** Collapses runs of whitespace so extraction line-wrapping does not cause false negatives. */
    static String normalise(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    public static Result verify(String sourceText, List<ExtractedField> fields) {
        if (fields == null || fields.isEmpty()) {
            // No fields is not the same as all fields verified. A caller that extracted nothing
            // has nothing grounded, and scoring it 1.0 would read as perfect confidence.
            return new Result(false, List.of(), 0);
        }
        String haystack = normalise(sourceText);
        List<String> unfound = new ArrayList<>();

        for (ExtractedField f : fields) {
            if (!haystack.contains(normalise(f.sourceSpan()))) {
                unfound.add(f.name());
            }
        }
        return new Result(unfound.isEmpty(), List.copyOf(unfound), fields.size());
    }
}
