package com.marketai.income.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists the same display-label strings the column already held pre-enum ("Salary",
 * "Dividend", "Capital Gain"), so existing rows keep reading correctly via
 * {@link IncomeSource#fromLabel} with no data migration, while new writes are constrained
 * to canonical values.
 */
@Converter(autoApply = true)
public class IncomeSourceConverter implements AttributeConverter<IncomeSource, String> {
    @Override
    public String convertToDatabaseColumn(IncomeSource source) {
        return source == null ? null : source.getLabel();
    }

    @Override
    public IncomeSource convertToEntityAttribute(String dbValue) {
        return IncomeSource.fromLabel(dbValue);
    }
}
