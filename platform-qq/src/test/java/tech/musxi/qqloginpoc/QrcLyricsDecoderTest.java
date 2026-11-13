package tech.musxi.qqloginpoc;

import org.junit.Test;
import static org.junit.Assert.*;

public class QrcLyricsDecoderTest {
    // Self-written XML compressed with zlib and encrypted by pinned QQMusicApi tripledes.py,
    // not by the production decoder. No third-party song text or account material is used.
    private static final String GOLDEN="0c8d67dd3e549974b64ed2680459f138553fe2b7cdeb268e33263ce3ef74605977e46a86d6e15cc2c6f0f870f1de0b7d71157fdbbeff4a1b075abb3fa4a67bc575e2e690a4b8cb1f157ef7cce87ebee829d24323e222f087cf4b9b1f2d4ae77b3fd26bd4a36e8316b793f6d341adde903b742becfadf860bf0e7615af7e390885945d9f1dd7d9f31";

    @Test public void independentlyEncryptedXmlRetainsAbsoluteTimesOffsetTrimAndUtf16() {
        QrcLyricsDecoder.Document doc=QrcLyricsDecoder.decode(GOLDEN);
        assertEquals(QrcLyricsDecoder.Status.PRESENT,doc.status);
        assertEquals(2,doc.rows.size());
        QrcLyricsDecoder.Row row=doc.rows.get(0);
        assertEquals(900,row.timeMs); assertEquals("Hi 😀",row.text);
        assertEquals(2,row.words.size());
        assertWord(row.words.get(0),0,2,900,1100);
        assertWord(row.words.get(1),2,5,1100,1400);
        assertWord(doc.rows.get(1).words.get(0),0,3,1900,1900);
        assertFalse(doc.toString().contains("Hi"));
        try { row.words.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void xmlEntitiesAreDecodedOnceWithoutNormalizingNewlines() {
        String xml="<QrcInfos><Lyric_1 LyricContent='[1000,500]A&amp;B(1000,100)&#x1F600;(1100,200)\n[2000,1]&amp;lt;(2000,1)'/></QrcInfos>";
        QrcLyricsDecoder.Document doc=QrcLyricsDecoder.decode(xml);
        assertEquals(QrcLyricsDecoder.Status.PRESENT,doc.status);
        assertEquals("A&B😀",doc.rows.get(0).text);
        assertWord(doc.rows.get(0).words.get(1),3,5,1100,1300);
        assertEquals("&lt;",doc.rows.get(1).text);
    }

    @Test public void wordEndsMayOverlapLineEndButRelativeStartsAreNotGuessed() {
        QrcLyricsDecoder.Document doc=QrcLyricsDecoder.decode("[1000,100]A(1000,400)B(1100,500)");
        assertEquals(QrcLyricsDecoder.Status.PRESENT,doc.status);
        assertWord(doc.rows.get(0).words.get(1),1,2,1100,1600);
        invalid("[1000,100]A(0,100)");
        invalid("[1000,100]A(1100,100)B(1000,100)");
    }

    @Test public void japaneseKanaMetadataIsNotASeparateLyricRowOrWordTimeline() {
        QrcLyricsDecoder.Document doc=QrcLyricsDecoder.decode("[kana:1ゆ(1000,100)め(1100,100)]\n[1000,500]夢(1000,500)");
        assertEquals(QrcLyricsDecoder.Status.PRESENT,doc.status);
        assertEquals(1,doc.rows.size());
        assertEquals("夢",doc.rows.get(0).text);
        assertEquals(1,doc.rows.get(0).words.size());
    }

    @Test public void malformedContainersUnicodeAndXmlDeclarationsAreRejected() {
        for (String value:new String[]{"z".repeat(32),"0".repeat(15),"0".repeat(32),
            "00a8989609f44630a27b02aa779bf226", // encrypted invalid UTF-8
            "eb1fc01b2d9f5e830ec001fd1f34f3fc13a882ff709cadf0", // nonzero tail
            "<QrcInfos><!DOCTYPE a><Lyric_1 LyricContent='[0,1]X(0,1)'/></QrcInfos>",
            "<QrcInfos><Lyric_1 LyricContent='[0,1]&unknown;(0,1)'/></QrcInfos>",
            "<QrcInfos><Lyric_1 LyricContent='[0,1]X(0,1)' LyricContent='[0,1]Y(0,1)'/></QrcInfos>",
            "[0,1]\uD800(0,1)","[0,1]a\u0000(0,1)"}) invalid(value);
    }

    @Test public void missingAndUnsupportedAreDistinctAndBoundsAreEnforced() {
        assertEquals(QrcLyricsDecoder.Status.ABSENT,QrcLyricsDecoder.decode("").status);
        assertEquals(QrcLyricsDecoder.Status.UNSUPPORTED,QrcLyricsDecoder.decode("[00:01.00]Hello").status);
        assertEquals(QrcLyricsDecoder.Status.UNSUPPORTED,QrcLyricsDecoder.decode("[1000,100]Hello").status);
        invalid("[86400000,1]A(86400000,1)");
        invalid("[offset:86400001]\n[0,1]A(0,1)");
        invalid("[0,1]A(0,1)untimed");
        invalid("[0,1]"+"A(0,1)".repeat(4097));
        invalid("0".repeat(1024*1024+16));
        invalid("[0,1]A(0,1)\n".repeat(10_001));
    }

    @Test public void rejectsCompressedExpansionPastTheDecodedBudget() {
        // Reference encryption of zlib(524289 ASCII a bytes).
        invalid("31dc07d68f408bb2a891995cb541e2e950f91e3c0bd73607a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226a27b02aa779bf226e67d3929095ed6614257f81e2e4ee77b");
    }

    private static void invalid(String value) {
        assertEquals(QrcLyricsDecoder.Status.INVALID,QrcLyricsDecoder.decode(value).status);
    }
    private static void assertWord(QrcLyricsDecoder.Word word,int from,int to,long start,long end) {
        assertEquals(from,word.textStart);assertEquals(to,word.textEnd);
        assertEquals(start,word.startTimeMs);assertEquals(end,word.endTimeMs);
    }
}
