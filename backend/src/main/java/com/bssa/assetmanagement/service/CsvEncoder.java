package com.bssa.assetmanagement.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds CSV documents for export without any external dependency.
 *
 * Two things here are security work rather than formatting: values are quoted
 * so that a comma, quote or newline inside an asset description cannot move a
 * cell into another column or row, and values that a spreadsheet would read as
 * a formula are defanged so an attacker-controlled field cannot execute
 * something when the export is opened.
 */
public final class CsvEncoder {

    /**
     * Characters that make Excel, Google Sheets or LibreOffice treat the cell
     * as a formula. A prefix quote keeps the text visible and stops evaluation.
     */
    private static final char[] FORMULA_PREFIXES = {'=', '+', '-', '@', '\t'};

    /** Byte-order mark, so Excel opens a UTF-8 export with the right encoding. */
    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final String CRLF = "\r\n";

    private CsvEncoder() {
    }

    /** Escapes one cell value for CSV. Null becomes an empty cell. */
    public static String escape(Object value) {
        if (value == null) {
            return "";
        }

        String text = String.valueOf(value);
        if (looksLikeFormula(text)) {
            text = "'" + text;
        }

        boolean needsQuotes = text.contains(",")
                || text.contains("\"")
                || text.contains("\n")
                || text.contains("\r");

        if (needsQuotes) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    /**
     * A value is defanged when its first non-space character would start a
     * formula. Leading spaces are ignored because that is how "=cmd" hides
     * itself from a casual reader.
     *
     * This also affects a genuine negative number, which would then be exported
     * as text; none of the exported registers hold negative numeric columns.
     */
    private static boolean looksLikeFormula(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        char first = trimmed.charAt(0);
        for (char formulaStart : FORMULA_PREFIXES) {
            if (first == formulaStart) {
                return true;
            }
        }
        return false;
    }

    /** Writes a single record, terminated by CRLF as RFC 4180 requires. */
    public static void appendLine(StringBuilder csv, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escape(values.get(i)));
        }
        csv.append(CRLF);
    }

    /**
     * Assembles a whole document: byte-order mark, header row, data rows.
     * Returned as bytes because the response is a file download rather than a
     * rendered page.
     */
    public static byte[] toCsvBytes(List<String> headers, List<List<String>> rows) {
        StringBuilder csv = new StringBuilder();
        appendLine(csv, headers);
        for (List<String> row : rows) {
            appendLine(csv, row);
        }

        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + UTF_8_BOM.length);
        out.writeBytes(UTF_8_BOM);
        out.writeBytes(body);
        return out.toByteArray();
    }
}
