package tech.musxi.loginpoc;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KrcLyricsDecoderTest {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int[] KEY = {
        64, 71, 97, 119, 94, 50, 116, 71,
        81, 54, 49, 45, 206, 210, 110, 105
    };

    @Test public void acceptsSingleLeadingUtf8BomWithoutChangingRowBinding() throws Exception {
        KrcLyricsDecoder.Document doc=KrcLyricsDecoder.decode(encoded("\uFEFF"+source(translationMetadata("译"),"[1000,500]<0,500,0>Fixture")));
        assertEquals(KrcLyricsDecoder.Status.PRESENT,doc.status);
        assertEquals("Fixture",doc.rows.get(0).text);
        assertEquals("译",doc.rows.get(0).translation);
        assertStatus(KrcLyricsDecoder.Status.INVALID,encoded("\uFEFF\uFEFF[1000,500]Fixture"));
    }

    @Test public void decodesOrderedRowsOffsetsAndEmptyPlaceholder() throws Exception {
        String metadata = translationMetadata("译文一", "", "译文三");
        KrcLyricsDecoder.Document document = KrcLyricsDecoder.decode(encoded(sourceWithOffset(metadata,
            "[1000,500]<0,500,0>Original",
            "[2000,500]<0,300,0>Drift <300,200,0>",
            "[3000,500]<0,500,0>")));

        assertEquals(KrcLyricsDecoder.Status.PRESENT, document.status);
        assertEquals(3, document.rows.size());
        assertEquals(900L, document.rows.get(0).timeMs);
        assertEquals("Original", document.rows.get(0).text);
        assertEquals("译文一", document.rows.get(0).translation);
        assertEquals(1900L, document.rows.get(1).timeMs);
        assertEquals("Drift", document.rows.get(1).text);
        assertEquals("", document.rows.get(1).translation);
        assertEquals(2900L, document.rows.get(2).timeMs);
        assertEquals("", document.rows.get(2).text);
        assertEquals("译文三", document.rows.get(2).translation);
    }

    @Test public void romanizationOnlyIsIgnoredAndAbsent() throws Exception {
        String body = source(blockMetadata(0, 9,
                "romanization", "different language and row count", "extra ignored line"),
            "[10,1]原文一",
            "[20,1]原文二");

        KrcLyricsDecoder.Document document = KrcLyricsDecoder.decode(encoded(body));

        assertEquals(KrcLyricsDecoder.Status.ABSENT, document.status);
        assertEquals(2, document.rows.size());
        assertEquals("原文一", document.rows.get(0).text);
        assertEquals("原文二", document.rows.get(1).text);
        assertNull(document.rows.get(0).translation);
        assertNull(document.rows.get(1).translation);
    }

    @Test public void noLanguageOrLanguageWithoutTranslationIsAbsent() throws Exception {
        assertStatus(KrcLyricsDecoder.Status.ABSENT, encoded("[0,1]plain"));

        String noTranslation = source(metadataJson(1, "content", new JSONArray()), "[0,1]plain");
        assertStatus(KrcLyricsDecoder.Status.ABSENT, encoded(noTranslation));
    }

    @Test public void rejectsDuplicateLanguageTagsAndTranslationBlocks() throws Exception {
        String emptyMetadata = metadataJson(1, "content", new JSONArray());
        String duplicateTags = "[language:" + base64(emptyMetadata) + "]\n"
            + "[language:" + base64(emptyMetadata) + "]\n[0,1]原词";
        assertStatus(KrcLyricsDecoder.Status.INVALID, encoded(duplicateTags));

        JSONArray blocks = new JSONArray()
            .put(translationBlock("一"))
            .put(translationBlock("二"));
        String duplicateBlocks = source(metadataJson(1, "content", blocks), "[0,1]原词");
        assertStatus(KrcLyricsDecoder.Status.INVALID, encoded(duplicateBlocks));
    }

    @Test public void rejectsMalformedMetadataSchema() throws Exception {
        String[] malformed = {
            "[]",
            "{\"version\":1.0,\"content\":[]}",
            "{\"version\":\"1\",\"content\":[]}",
            "{\"version\":1,\"content\":{}}",
            "{\"version\":1,\"content\":[{\"type\":\"1\",\"language\":0,\"lyricContent\":[[\"译\"]]}]}",
            "{\"version\":1,\"content\":[{\"type\":1,\"language\":0,\"lyricContent\":[\"译\"]}]}",
            "{\"version\":1,\"content\":[{\"type\":1,\"language\":0,\"lyricContent\":[[7]]}]}",
            "{\"version\":1,\"content\":[{\"type\":1,\"language\":0,\"lyricContent\":[[\"译\",\"extra\"]]}]}",
            "{\"version\":1,\"content\":[{\"type\":1,\"language\":0}]}"
        };

        for (String metadata : malformed) {
            assertStatus(KrcLyricsDecoder.Status.INVALID,
                encoded(source(metadata, "[0,1]原词")));
        }
    }

    @Test public void rejectsNonStrictJsonSyntaxDuplicatesTrailingDataAndExcessDepth() throws Exception {
        String[] malformed = {
            "{'version':1,'content':[]}",
            "{version:1,content:[]}",
            "{\"version\":1,\"content\":[],}",
            "{\"version\":1,\"version\":1,\"content\":[]}",
            "{\"version\":1,\"content\":[]}{\"version\":1,\"content\":[]}",
            metadataWithIgnoredDepth(9)
        };

        for (String metadata : malformed) {
            assertStatus(KrcLyricsDecoder.Status.INVALID,
                encoded(source(metadata, "[0,1]原词")));
        }
    }

    @Test public void rejectsTranslationRowCountMismatch() throws Exception {
        String tooFew = translationMetadata("only one");
        String tooMany = translationMetadata("一", "二", "三");
        String twoRows = lines("[0,1]一", "[10,1]二");

        assertStatus(KrcLyricsDecoder.Status.INVALID, encoded(source(tooFew, "[0,1]一", "[10,1]二")));
        assertStatus(KrcLyricsDecoder.Status.INVALID, encoded(source(tooMany, "[0,1]一", "[10,1]二")));
        assertEquals(1, KrcLyricsDecoder.decode(encoded(source(translationMetadata("译"), "[0,1]一"))).rows.size());
    }

    @Test public void mapsUnknownNumericMetadataToUnsupported() throws Exception {
        assertStatus(KrcLyricsDecoder.Status.UNSUPPORTED,
            encoded(source(metadataJson(2, "content", new JSONArray()), "[0,1]原词")));
        assertStatus(KrcLyricsDecoder.Status.UNSUPPORTED,
            encoded(source(blockMetadata(2, 0, "译"), "[0,1]原词")));
        assertStatus(KrcLyricsDecoder.Status.UNSUPPORTED,
            encoded(source(blockMetadata(1, 7, "译"), "[0,1]原词")));
    }

    @Test public void enforcesMetadataDepthLimit() throws Exception {
        String atLimit = metadataWithIgnoredDepth(7); // Root object plus seven arrays.
        String tooDeep = metadataWithIgnoredDepth(8);

        assertStatus(KrcLyricsDecoder.Status.ABSENT, encoded(source(atLimit, "[0,1]原词")));
        assertStatus(KrcLyricsDecoder.Status.INVALID, encoded(source(tooDeep, "[0,1]原词")));
    }

    @Test public void enforcesMetadataBlockLimit() throws Exception {
        assertStatus(KrcLyricsDecoder.Status.PRESENT,
            encoded(source(blocksMetadata(16), "[0,1]原词")));
        assertStatus(KrcLyricsDecoder.Status.INVALID,
            encoded(source(blocksMetadata(17), "[0,1]原词")));
    }

    @Test public void rejectsHeaderAndBase64Corruption() throws Exception {
        assertStatus(KrcLyricsDecoder.Status.INVALID, null);
        assertStatus(KrcLyricsDecoder.Status.INVALID, "not base64");

        byte[] wrongHeader = containerBytes("[offset:0]".getBytes(StandardCharsets.UTF_8));
        wrongHeader[0] = 'x';
        assertStatus(KrcLyricsDecoder.Status.INVALID, Base64.getEncoder().encodeToString(wrongHeader));

        assertStatus(KrcLyricsDecoder.Status.INVALID, nonCanonicalBase64());
    }

    @Test public void rejectsTruncatedAndTrailingCompressedData() throws Exception {
        byte[] raw = containerBytes("[offset:0]".getBytes(StandardCharsets.UTF_8));
        byte[] truncated = new byte[raw.length - 1];
        System.arraycopy(raw, 0, truncated, 0, truncated.length);
        assertStatus(KrcLyricsDecoder.Status.INVALID, Base64.getEncoder().encodeToString(truncated));

        byte[] trailing = new byte[raw.length + 1];
        System.arraycopy(raw, 0, trailing, 0, raw.length);
        trailing[raw.length] = 0;
        assertStatus(KrcLyricsDecoder.Status.INVALID, Base64.getEncoder().encodeToString(trailing));
    }

    @Test public void rejectsInvalidUtf8AndControlCharacters() throws Exception {
        byte[] invalidUtf8 = {(byte) 0xc3};
        assertStatus(KrcLyricsDecoder.Status.INVALID,
            Base64.getEncoder().encodeToString(containerBytes(invalidUtf8)));

        byte[] withNul = "[offset:0]\n\0".getBytes(StandardCharsets.UTF_8);
        assertStatus(KrcLyricsDecoder.Status.INVALID,
            Base64.getEncoder().encodeToString(containerBytes(withNul)));
    }

    @Test public void enforcesInflatedAndEncodedByteLimits() throws Exception {
        String prefix = "[offset:0]\n";
        String exact = prefix + " ".repeat(MAX_BYTES - prefix.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(MAX_BYTES, exact.getBytes(StandardCharsets.UTF_8).length);
        assertStatus(KrcLyricsDecoder.Status.ABSENT, encoded(exact));

        assertStatus(KrcLyricsDecoder.Status.INVALID, encoded(exact + " "));

        byte[] oversizedContainer = new byte[MAX_BYTES + 1];
        byte[] header = "krc1".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(header, 0, oversizedContainer, 0, header.length);
        assertStatus(KrcLyricsDecoder.Status.INVALID,
            Base64.getEncoder().encodeToString(oversizedContainer));
    }

    @Test public void enforcesMetadataByteLimit() throws Exception {
        String atLimit = paddedMetadata(256 * 1024);
        String overLimit = paddedMetadata(256 * 1024 + 1);

        assertStatus(KrcLyricsDecoder.Status.ABSENT, encoded(source(atLimit, "[0,1]原词")));
        assertStatus(KrcLyricsDecoder.Status.INVALID, encoded(source(overLimit, "[0,1]原词")));
    }

    @Test public void enforcesTextCodePointLimit() throws Exception {
        String atLimit = "\uD83D\uDE00".repeat(4096);
        String overLimit = atLimit + "\uD83D\uDE00";

        KrcLyricsDecoder.Document document = KrcLyricsDecoder.decode(
            encoded(source(translationMetadata(atLimit), "[0,1]原词")));
        assertEquals(KrcLyricsDecoder.Status.PRESENT, document.status);
        assertEquals(atLimit, document.rows.get(0).translation);

        assertStatus(KrcLyricsDecoder.Status.INVALID,
            encoded(source(translationMetadata(overLimit), "[0,1]原词")));

        KrcLyricsDecoder.Document original = KrcLyricsDecoder.decode(
            encoded("[0,1]" + atLimit));
        assertEquals(KrcLyricsDecoder.Status.ABSENT, original.status);
        assertEquals(atLimit, original.rows.get(0).text);
        assertStatus(KrcLyricsDecoder.Status.INVALID,
            encoded("[0,1]" + overLimit));
    }

    @Test public void acceptsTenThousandSourceRowsAndRejectsMore() throws Exception {
        String atLimit = numberedRows(10000);
        String overLimit = numberedRows(10001);

        KrcLyricsDecoder.Document document = KrcLyricsDecoder.decode(encoded(atLimit));
        assertEquals(KrcLyricsDecoder.Status.ABSENT, document.status);
        assertEquals(10000, document.rows.size());
        assertEquals(9999L, document.rows.get(9999).timeMs);

        assertStatus(KrcLyricsDecoder.Status.INVALID, encoded(overLimit));
    }

    @Test public void keepsPcWordTimingsRelativeToTheRowStart() throws Exception {
        // Same payload as the PC fixture tests/lyrics-parser.cpp:24-26.
        KrcLyricsDecoder.Document document = KrcLyricsDecoder.decode(
            encoded("[1200,2000]<0,400,0>Hel<400,600,0>lo\n[3500,900]<0,900,0>World"));

        assertEquals(KrcLyricsDecoder.Status.ABSENT, document.status);
        assertEquals(2, document.rows.size());
        KrcLyricsDecoder.Row first = document.rows.get(0);
        assertEquals(1200L, first.timeMs);
        assertEquals("Hello", first.text);
        assertEquals(2, first.words.size());
        assertWord(first.words.get(0), 0, 3, 1200L, 1600L);
        assertWord(first.words.get(1), 3, 5, 1600L, 2200L);
        KrcLyricsDecoder.Row second = document.rows.get(1);
        assertEquals(1, second.words.size());
        assertWord(second.words.get(0), 0, 5, 3500L, 4400L);
    }

    @Test public void appliesDocumentOffsetToRowAndWordTimesWithAddition() throws Exception {
        // Android keeps the existing addition for [offset:]; words follow the row.
        KrcLyricsDecoder.Document ahead = KrcLyricsDecoder.decode(encoded(sourceWithOffset(
            translationMetadata("译"), "[5000,1000]<0,200,0>Ab<200,300,0>cd")));
        assertEquals(4900L, ahead.rows.get(0).timeMs);
        assertEquals("译", ahead.rows.get(0).translation);
        assertWord(ahead.rows.get(0).words.get(0), 0, 2, 4900L, 5100L);
        assertWord(ahead.rows.get(0).words.get(1), 2, 4, 5100L, 5400L);

        KrcLyricsDecoder.Document clamped = KrcLyricsDecoder.decode(encoded(
            "[offset:-7000]\n[100,1000]<0,100,0>a<100,100,0>b"));
        assertEquals(-6900L, clamped.rows.get(0).timeMs);
        assertWord(clamped.rows.get(0).words.get(0), 0, 1, 0L, 0L);
        assertWord(clamped.rows.get(0).words.get(1), 1, 2, 0L, 0L);
    }

    @Test public void trimsWordRangesAndNeverSplitsSurrogatePairs() throws Exception {
        KrcLyricsDecoder.Document document = KrcLyricsDecoder.decode(encoded(
            "[1000,500]  <0,100,0>\uD83D\uDE00<100,100,0>啊  "));

        KrcLyricsDecoder.Row row = document.rows.get(0);
        assertEquals("\uD83D\uDE00啊", row.text);
        assertEquals(2, row.words.size());
        assertWord(row.words.get(0), 0, 2, 1000L, 1100L);
        assertWord(row.words.get(1), 2, 3, 1100L, 1200L);
    }

    @Test public void skipsEmptyWordSegmentsButRequiresFullCoverage() throws Exception {
        KrcLyricsDecoder.Document adjacent = KrcLyricsDecoder.decode(encoded(
            "[1000,500]<0,0,0><100,100,0>abc"));
        assertEquals("abc", adjacent.rows.get(0).text);
        assertEquals(1, adjacent.rows.get(0).words.size());
        assertWord(adjacent.rows.get(0).words.get(0), 0, 3, 1100L, 1200L);

        KrcLyricsDecoder.Document prefix = KrcLyricsDecoder.decode(encoded(
            "[1000,500]pre<0,100,0>fix"));
        assertEquals("prefix", prefix.rows.get(0).text);
        assertTrue(prefix.rows.get(0).words.isEmpty());
    }

    @Test public void dropsOnlyWordTimingsWhenTheyLeaveTheRow() throws Exception {
        KrcLyricsDecoder.Document document = KrcLyricsDecoder.decode(encoded(source(
            translationMetadata("译", ""),
            "[1000,100]<0,500,0>Hello",
            "[2000,1000]<0,100,0>Ok")));

        assertEquals(KrcLyricsDecoder.Status.PRESENT, document.status);
        assertEquals("Hello", document.rows.get(0).text);
        assertEquals("译", document.rows.get(0).translation);
        assertTrue(document.rows.get(0).words.isEmpty());
        assertEquals(1, document.rows.get(1).words.size());
        assertWord(document.rows.get(1).words.get(0), 0, 2, 2000L, 2100L);

        // Non-monotonic and malformed tags stay INVALID instead of degrading.
        assertStatus(KrcLyricsDecoder.Status.INVALID, encoded("[0,1000]<500,1,0>a<100,1,0>b"));
        assertStatus(KrcLyricsDecoder.Status.INVALID, encoded("[0,1000]<0,1,0>a<0,1"));
        assertStatus(KrcLyricsDecoder.Status.INVALID, encoded("[0,1000]<a,1,0>a"));
    }

    @Test public void enforcesWordCountLimits() throws Exception {
        KrcLyricsDecoder.Document perRow = KrcLyricsDecoder.decode(encoded(wordRow(4096)));
        assertEquals(KrcLyricsDecoder.Status.ABSENT, perRow.status);
        assertEquals(4096, perRow.rows.get(0).words.size());

        // Overflow drops only this row's timings; text and translation survive.
        KrcLyricsDecoder.Document overRow = KrcLyricsDecoder.decode(encoded(source(
            translationMetadata("译", "二"), tagRow(4097), "[0,1]<0,1,0>tail")));
        assertEquals(KrcLyricsDecoder.Status.PRESENT, overRow.status);
        assertEquals(2, overRow.rows.size());
        assertEquals("译", overRow.rows.get(0).translation);
        assertEquals("x", overRow.rows.get(0).text);
        assertTrue(overRow.rows.get(0).words.isEmpty());
        assertEquals(1, overRow.rows.get(1).words.size());

        // Repeated <0,1,0> tags keep the document under the 1 MiB container limit
        // while exercising the document-wide count.
        KrcLyricsDecoder.Document limited = KrcLyricsDecoder.decode(encoded(document(24, 4096, 1696)));
        assertEquals(KrcLyricsDecoder.Status.ABSENT, limited.status);
        assertEquals(25, limited.rows.size());
        assertEquals(4096, limited.rows.get(0).words.size());
        assertEquals(1696, limited.rows.get(24).words.size());

        KrcLyricsDecoder.Document overDocument = KrcLyricsDecoder.decode(encoded(document(24, 4096, 1697)));
        assertEquals(KrcLyricsDecoder.Status.ABSENT, overDocument.status);
        assertEquals(25, overDocument.rows.size());
        assertEquals(4096, overDocument.rows.get(0).words.size());
        assertTrue(overDocument.rows.get(24).words.isEmpty());
    }

    @Test public void dropsOnlyWordTimingsThatExceedTheRowTimeLimit() throws Exception {
        // A positive document offset pushes absolute word times past 24 h while the
        // row itself stays legal, so only the timings degrade.
        KrcLyricsDecoder.Document document = KrcLyricsDecoder.decode(encoded(source(
            translationMetadata("译"), "[offset:1000000]\n[86000000,300000]<0,1000,0>ab")));

        assertEquals(KrcLyricsDecoder.Status.PRESENT, document.status);
        assertEquals(87000000L, document.rows.get(0).timeMs);
        assertEquals("ab", document.rows.get(0).text);
        assertEquals("译", document.rows.get(0).translation);
        assertTrue(document.rows.get(0).words.isEmpty());
    }

    private static void assertWord(KrcLyricsDecoder.Word word, int start, int end,
        long startTimeMs, long endTimeMs) {
        assertEquals(start, word.textStart);
        assertEquals(end, word.textEnd);
        assertEquals(startTimeMs, word.startTimeMs);
        assertEquals(endTimeMs, word.endTimeMs);
    }

    private static String wordRow(int count) {
        return "[0,1]" + "<0,1,0>x".repeat(count);
    }

    /** The tag count exceeds the per-row cap without exceeding the text limit. */
    private static String tagRow(int tags) {
        return "[0,1]" + "<0,1,0>".repeat(tags - 1) + "<0,1,0>x";
    }

    private static String document(int repeatedRows, int perRow, int tail) {
        StringBuilder body = new StringBuilder(repeatedRows * perRow * 8);
        for (int i = 0; i < repeatedRows; i++) body.append(wordRow(perRow)).append('\n');
        body.append(wordRow(tail));
        return body.toString();
    }

    private static String translationMetadata(String... translations) throws Exception {
        return metadataWithBlocks(new JSONArray().put(translationBlock(translations)));
    }

    private static JSONObject translationBlock(String... translations) throws Exception {
        JSONArray content = new JSONArray();
        for (String translation : translations) {
            content.put(new JSONArray().put(translation));
        }
        return new JSONObject()
            .put("type", 1)
            .put("language", 0)
            .put("lyricContent", content);
    }

    private static String blockMetadata(int type, int language, String... content) throws Exception {
        JSONArray rows = new JSONArray();
        for (String value : content) {
            rows.put(new JSONArray().put(value));
        }
        JSONObject block = new JSONObject()
            .put("type", type)
            .put("language", language)
            .put("lyricContent", rows);
        return metadataWithBlocks(new JSONArray().put(block));
    }

    private static String metadataWithBlocks(JSONArray blocks) throws Exception {
        return metadataJson(1, "content", blocks);
    }

    private static String metadataJson(int version, String key, Object value) throws Exception {
        return new JSONObject().put("version", version).put(key, value).toString();
    }

    private static String metadataWithIgnoredDepth(int depths) {
        return "{\"version\":1,\"content\":[],\"ignored\":"
            + "[".repeat(depths) + "0" + "]".repeat(depths) + "}";
    }

    private static String blocksMetadata(int count) throws Exception {
        JSONArray blocks = new JSONArray().put(translationBlock("译"));
        for (int i = 1; i < count; i++) {
            blocks.put(new JSONObject()
                .put("type", 0)
                .put("language", i)
                .put("lyricContent", new JSONArray().put(new JSONArray().put("romanization"))));
        }
        return metadataWithBlocks(blocks);
    }

    private static String paddedMetadata(int bytes) {
        String prefix = "{\"version\":1,\"content\":[]";
        int spaces = bytes - prefix.getBytes(StandardCharsets.UTF_8).length - 1;
        return prefix + " ".repeat(spaces) + "}";
    }

    private static String numberedRows(int count) {
        StringBuilder body = new StringBuilder(count * 10);
        for (int i = 0; i < count; i++) {
            if (i != 0) body.append('\n');
            body.append('[').append(i).append(",1]x");
        }
        return body.toString();
    }

    private static String sourceWithOffset(String metadata, String... sourceRows) {
        return lines("[language:" + base64Unchecked(metadata) + "]", "[offset:-100]")
            + "\n" + lines(sourceRows);
    }

    private static String source(String metadata, String... sourceRows) {
        return lines("[language:" + base64Unchecked(metadata) + "]", lines(sourceRows));
    }

    private static String lines(String... values) {
        return String.join("\n", values);
    }

    private static String base64(String value) {
        return base64Unchecked(value);
    }

    private static String base64Unchecked(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encoded(String body) throws Exception {
        return Base64.getEncoder().encodeToString(containerBytes(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] containerBytes(byte[] body) throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream output = new DeflaterOutputStream(compressed)) {
            output.write(body);
        }
        byte[] payload = compressed.toByteArray();
        byte[] result = new byte[4 + payload.length];
        result[0] = 'k';
        result[1] = 'r';
        result[2] = 'c';
        result[3] = '1';
        for (int i = 0; i < payload.length; i++) {
            result[i + 4] = (byte) (payload[i] ^ KEY[i % KEY.length]);
        }
        return result;
    }

    private static String nonCanonicalBase64() throws Exception {
        byte[] raw = null;
        for (int padding = 0; padding < 64; padding++) {
            byte[] candidate = containerBytes(("[offset:0]" + " ".repeat(padding))
                .getBytes(StandardCharsets.UTF_8));
            if (candidate.length % 3 == 1) {
                raw = candidate;
                break;
            }
        }
        assertTrue(raw != null);
        String canonical = Base64.getEncoder().encodeToString(raw);
        assertTrue(canonical.endsWith("=="));
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        int index = canonical.length() - 3;
        int value = alphabet.indexOf(canonical.charAt(index));
        char replacement = alphabet.charAt((value & 0x3c) | ((value + 1) & 3));
        return canonical.substring(0, index) + replacement + canonical.substring(index + 1);
    }

    private static void assertStatus(KrcLyricsDecoder.Status expected, String data) {
        KrcLyricsDecoder.Document document = KrcLyricsDecoder.decode(data);
        assertEquals(expected, document.status);
        if (expected != KrcLyricsDecoder.Status.PRESENT
            && expected != KrcLyricsDecoder.Status.ABSENT) {
            assertTrue(document.rows.isEmpty());
        }
    }
}
