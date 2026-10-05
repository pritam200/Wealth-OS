package com.marketai.dataplatform.service;

import org.apache.poi.ss.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.time.ZoneId;

/**
 * Turns the first sheet of an Excel statement into CSV for the normal import path. Formulas are never
 * evaluated: only the value the file already holds is read, so a crafted workbook cannot make the
 * server compute anything. Dates become ISO yyyy-MM-dd so day-first/month-first ambiguity cannot
 * creep in. The CSV text then goes through the same cleaning, validation and idempotency as any file.
 */
public final class ExcelCsvConverter {

    static final int MAX_ROWS = 5001, MAX_COLS = 60;

    private ExcelCsvConverter() {}

    public static String toCsv(byte[] bytes) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (wb.getNumberOfSheets() == 0) throw bad("The workbook has no sheets");
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            StringBuilder out = new StringBuilder();
            int rows = 0;
            for (Row row : sheet) {
                if (++rows > MAX_ROWS) throw bad("Too many rows (limit 5000)");
                int last = Math.min(Math.max(row.getLastCellNum(), 0), MAX_COLS);
                for (int c = 0; c < last; c++) {
                    if (c > 0) out.append(',');
                    out.append(quote(text(row.getCell(c), fmt)));
                }
                out.append('\n');
            }
            return out.toString();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            // The library's message can echo file content; keep the reply generic.
            throw bad("That file could not be read as an Excel workbook");
        }
    }

    private static String text(Cell cell, DataFormatter fmt) {
        if (cell == null) return "";
        CellType t = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (t) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                ? cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString()
                : plain(cell.getNumericCellValue());
            case STRING -> cell.getCellType() == CellType.FORMULA ? cell.getStringCellValue() : fmt.formatCellValue(cell);
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    private static String plain(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : new java.math.BigDecimal(String.valueOf(d)).toPlainString();
    }

    private static String quote(String v) {
        return v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
}
