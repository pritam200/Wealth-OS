package com.marketai.research.model;

import java.util.List;

/**
 * What validation removed or corrected in the model's output.
 *
 * @param removedFigures numbers the model wrote that are not in the verified facts or evidence —
 *                       replaced in the text, listed here with where they appeared
 * @param droppedCitations cited ids that do not exist in the context
 */
public record GuardReport(List<String> removedFigures, List<String> droppedCitations, List<String> notes) {
    public boolean clean() { return removedFigures.isEmpty() && droppedCitations.isEmpty(); }
}
