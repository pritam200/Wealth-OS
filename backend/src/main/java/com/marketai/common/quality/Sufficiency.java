package com.marketai.common.quality;

import java.util.Optional;
import java.util.function.Function;

/**
 * A computed value together with an honest statement of whether it should be trusted.
 *
 * The rule this encodes: <em>every financial number must be able to decline.</em> A method
 * returning a bare {@code BigDecimal} has no way to say "I could not work this out" other than
 * returning null or zero, and both are indistinguishable from a real answer of null or zero.
 * That ambiguity is how a "no data" case becomes a confident-looking ₹0 on a screen.
 *
 * {@code whatWouldFixIt} is required on {@link #insufficient} on purpose. "Insufficient data" on
 * its own is a dead end for the user; "fetch price history for this symbol, then re-run" is an
 * action. If no such action exists, say so in that field — writing it out forces the question.
 *
 * @param <T> the value type, present only when the result is usable
 */
public record Sufficiency<T>(T value, DataQuality quality, String reason, String whatWouldFixIt) {

    /** Everything the calculation needed was present. */
    public static <T> Sufficiency<T> full(T value) {
        return new Sufficiency<>(value, DataQuality.FULL, null, null);
    }

    /** Usable, but incomplete. {@code reason} is shown to the user alongside the value. */
    public static <T> Sufficiency<T> partial(T value, String reason) {
        return new Sufficiency<>(value, DataQuality.PARTIAL, require(reason, "reason"), null);
    }

    /** Not computable. Carries no value — callers cannot accidentally read one. */
    public static <T> Sufficiency<T> insufficient(String reason, String whatWouldFixIt) {
        return new Sufficiency<>(null, DataQuality.INSUFFICIENT,
                require(reason, "reason"), require(whatWouldFixIt, "whatWouldFixIt"));
    }

    public boolean isUsable() {
        return quality.isUsable();
    }

    /** The value if usable, otherwise empty. The only supported way to read it. */
    public Optional<T> asOptional() {
        return isUsable() ? Optional.ofNullable(value) : Optional.empty();
    }

    /**
     * Transforms the value, preserving quality and reasoning. An insufficient result stays
     * insufficient and the mapper is never invoked — so downstream maths cannot run on a
     * value that was never computed.
     */
    public <R> Sufficiency<R> map(Function<? super T, ? extends R> mapper) {
        return isUsable()
                ? new Sufficiency<>(mapper.apply(value), quality, reason, whatWouldFixIt)
                : new Sufficiency<>(null, quality, reason, whatWouldFixIt);
    }

    /**
     * Combines with another result, taking the weaker quality of the two. A calculation is only
     * as trustworthy as its least trustworthy input.
     */
    public <O, R> Sufficiency<R> combine(Sufficiency<O> other,
                                         java.util.function.BiFunction<T, O, R> combiner) {
        if (!isUsable()) return new Sufficiency<>(null, quality, reason, whatWouldFixIt);
        if (!other.isUsable()) return new Sufficiency<>(null, other.quality, other.reason, other.whatWouldFixIt);
        DataQuality merged = (quality == DataQuality.PARTIAL || other.quality == DataQuality.PARTIAL)
                ? DataQuality.PARTIAL : DataQuality.FULL;
        String mergedReason = merged == DataQuality.PARTIAL
                ? java.util.stream.Stream.of(reason, other.reason)
                    .filter(java.util.Objects::nonNull)
                    .reduce((a, b) -> a + "; " + b).orElse(null)
                : null;
        return new Sufficiency<>(combiner.apply(value, other.value), merged, mergedReason, null);
    }

    private static String require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(
                    field + " is required — a result that declines must say why it declined");
        }
        return v;
    }
}
