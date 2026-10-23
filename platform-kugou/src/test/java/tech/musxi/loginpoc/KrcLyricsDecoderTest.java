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
