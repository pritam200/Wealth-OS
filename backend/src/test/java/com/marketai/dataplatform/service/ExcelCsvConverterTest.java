package com.marketai.dataplatform.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExcelCsvConverterTest {

    @Test
    void convertsDatesNumbersAndQuotesAndIgnoresFormulas() throws Exception {
        byte[] bytes;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet();
            Row h = s.createRow(0);
            String[] heads = {"Date", "Type", "Scheme", "Units", "Amount"};
            for (int i = 0; i < heads.length; i++) h.createCell(i).setCellValue(heads[i]);
            Row r = s.createRow(1);
            Cell d = r.createCell(0);
            d.setCellValue(java.sql.Date.valueOf(LocalDate.of(2026, 3, 5)));
            CellStyle st = wb.createCellStyle();
            st.setDataFormat(wb.createDataFormat().getFormat("dd-mm-yyyy"));
            d.setCellStyle(st);
            r.createCell(1).setCellValue("Purchase");
            r.createCell(2).setCellValue("Fund, \"Growth\"");
            r.createCell(3).setCellValue(10.5);
            r.createCell(4).setCellFormula("1000+500");
            wb.write(out);
            bytes = out.toByteArray();
        }
        String csv = ExcelCsvConverter.toCsv(bytes);
        List<List<String>> t = CsvImportService.parse(csv);
        assertThat(t.get(0)).containsExactly("Date", "Type", "Scheme", "Units", "Amount");
        assertThat(t.get(1).subList(0, 4)).containsExactly("2026-03-05", "Purchase", "Fund, \"Growth\"", "10.5");
        assertThat(t.get(1).get(4)).isNotEqualTo("1500"); // only the stored value is read, never computed
    }

    @Test
    void garbageIsRejectedWithGenericMessage() {
        assertThatThrownBy(() -> ExcelCsvConverter.toCsv("not a workbook".getBytes()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("could not be read");
    }
}
