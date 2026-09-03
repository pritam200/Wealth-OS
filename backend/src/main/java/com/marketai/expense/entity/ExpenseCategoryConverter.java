package com.marketai.expense.entity;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/**
 * Converts to/from the same display-label strings the column already contained
 * pre-enum (e.g. "Food", "Shopping") — so existing rows keep working via
 * {@link ExpenseCategory#fromLabel} without a data migration, while new writes
 * are guaranteed to be one of the canonical categories.
 */
@Converter(autoApply = true)
public class ExpenseCategoryConverter implements AttributeConverter<ExpenseCategory, String> {
    @Override
    public String convertToDatabaseColumn(ExpenseCategory category) {
        return category == null ? null : category.getLabel();
    }

    @Override
    public ExpenseCategory convertToEntityAttribute(String dbValue) {
        return ExpenseCategory.fromLabel(dbValue);
    }
}
