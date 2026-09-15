package com.marketai.scoring.redflag;

import java.util.List;

/**
 * Every red flag found, and whether any of them vetoes a recommendation.
 *
 * <p>The flags are reported even when none blocks: a user is entitled to know their holding has
 * a promoter pledge, whether or not that changes the rating.
 */
public record RedFlagAssessment(List<RedFlag> flags) {

    public RedFlagAssessment {
        flags = flags == null ? List.of() : List.copyOf(flags);
    }

    public static RedFlagAssessment clean() { return new RedFlagAssessment(List.of()); }

    /** True when at least one flag vetoes. No score can override this. */
    public boolean vetoes() {
        return flags.stream().anyMatch(RedFlag::blocks);
    }

    public List<RedFlag> blocking() {
        return flags.stream().filter(RedFlag::blocks).toList();
    }

    /** Why a recommendation was withheld, in the user's terms. */
    public String vetoReason() {
        if (!vetoes()) return null;
        return "No recommendation is shown because: " + String.join("; ",
            blocking().stream().map(f -> f.type() + (f.detail() == null ? "" : " (" + f.detail() + ")")).toList());
    }
}
