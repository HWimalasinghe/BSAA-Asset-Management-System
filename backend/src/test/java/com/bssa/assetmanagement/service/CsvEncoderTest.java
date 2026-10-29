package com.bssa.assetmanagement.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The escaping that stands between a stored asset description and whatever a
 * spreadsheet decides to do with it when someone opens the export.
 */
class CsvEncoderTest {

    @ParameterizedTest
    @ValueSource(strings = {"LAP-0001", "Available", "ThinkPad T14", "2024-01-01"})
    void anOrdinaryValueIsWrittenAsItIs(String value) {
        assertEquals(value, CsvEncoder.escape(value));
    }

    @Test
    void aCommaInsideAValueKeepsItInItsOwnCell() {
        assertEquals("\"Desk, finance\"", CsvEncoder.escape("Desk, finance"));
    }

    @Test
    void anEmbeddedQuoteIsDoubledAndTheValueWrapped() {
        assertEquals("\"14\"\" laptop\"", CsvEncoder.escape("14\" laptop"));
    }

    @Test
    void aLineBreakStaysInsideTheCellInsteadOfStartingANewRow() {
        assertEquals("\"two\nlines\"", CsvEncoder.escape("two\nlines"));
    }

    @Test
    void aMissingValueBecomesAnEmptyCell() {
        assertEquals("", CsvEncoder.escape(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "=HYPERLINK(\"http://evil.example\",\"click\")",
            "+1+1",
            "-1+1",
            "@SUM(A1)",
            "\t=cmd"
    })
    void aValueThatASpreadsheetWouldExecuteIsDefanged(String value) {
        String escaped = CsvEncoder.escape(value);

        // The guard comes first; a value that also needs quoting is then
        // wrapped, so the apostrophe is either the very first character or the
        // character just inside the opening quote.
        assertTrue(escaped.startsWith("'") || escaped.startsWith("\"'"), escaped);
        assertFalse(escaped.startsWith("="), escaped);
        assertFalse(escaped.startsWith("+"), escaped);
        assertFalse(escaped.startsWith("-"), escaped);
        assertFalse(escaped.startsWith("@"), escaped);
    }

    @Test
    void aFormulaHiddenBehindLeadingSpacesIsStillDefanged() {
        assertTrue(CsvEncoder.escape("   =HYPERLINK(\"x\")").startsWith("\"'"));
    }

    @Test
    void anEqualsSignLaterInTheValueIsHarmlessAndLeftAlone() {
        assertEquals("Serial=SN-001", CsvEncoder.escape("Serial=SN-001"));
    }

    @Test
    void defangingStillQuotesAValueThatAlsoNeedsQuoting() {
        assertEquals("\"'=A,1\"", CsvEncoder.escape("=A,1"));
    }

    @Test
    void aBlankValueIsLeftAloneRatherThanTreatedAsAFormula() {
        assertEquals("   ", CsvEncoder.escape("   "));
    }

    @Test
    void aRecordIsTerminatedAsRFC4180Requires() {
        StringBuilder csv = new StringBuilder();
        CsvEncoder.appendLine(csv, List.of("LAP-0001", "Desk, finance", ""));

        assertEquals("LAP-0001,\"Desk, finance\",\r\n", csv.toString());
    }

    @Test
    void aDocumentStartsWithTheByteOrderMarkThenHeaderThenRows() {
        byte[] csv = CsvEncoder.toCsvBytes(
                List.of("Asset Code", "Description"),
                List.of(
                        List.of("LAP-0001", "Desk, finance"),
                        java.util.Arrays.asList("LAP-0002", null)));

        String text = new String(csv, StandardCharsets.UTF_8);

        assertTrue(text.startsWith("\uFEFF"), "Excel needs the mark to read UTF-8");
        assertEquals("\uFEFFAsset Code,Description\r\n"
                + "LAP-0001,\"Desk, finance\"\r\n"
                + "LAP-0002,\r\n", text);
    }

    @Test
    void noRowEverGainsOrLosesAColumn() {
        byte[] csv = CsvEncoder.toCsvBytes(
                List.of("A", "B"),
                List.of(List.of("with, comma", "with \"quote\"")));

        String[] lines = new String(csv, StandardCharsets.UTF_8).split("\r\n");

        assertEquals(2, lines.length);
        assertEquals("\"with, comma\",\"with \"\"quote\"\"\"", lines[1]);
    }
}
