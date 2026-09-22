package com.marketai.planner.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** The PDF's "MONTH-END REVIEW → Monthly Reflection & Insights" free-text notes plus the final
 * status the user confirms (the PDF's three checkboxes). Purely user-authored narrative — never
 * generated or overwritten by any automated process. */
@Entity
@Table(name = "monthly_reflections",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "year_month"}))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class MonthlyReflection {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;

    @Column(name = "biggest_expense_note", length = 300)
    private String biggestExpenseNote;

    @Column(name = "overspend_note", length = 500)
    private String overspendNote;

    @Column(name = "underspend_note", length = 500)
    private String underspendNote;

    @Column(name = "one_off_note", length = 500)
    private String oneOffNote;

    @Column(name = "adjustments_note", length = 500)
    private String adjustmentsNote;

    /** UNDER / EXACT / OVER — the PDF's three checkboxes. User-confirmed, not auto-set, though
     * the UI pre-selects the box matching the computed total as a default. */
    @Column(name = "final_status", length = 20)
    private String finalStatus;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
