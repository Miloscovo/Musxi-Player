package tech.musxi.neteaseloginpoc;

import org.junit.Test;
import static org.junit.Assert.*;

public class YrcLyricsDecoderTest {
    @Test public void absoluteWordsOwnLineClockAndUtf16RangesWithMetadataExcluded() {
        String source="{\"t\":0,\"c\":[{\"tx\":\"作词: somebody\",\"li\":\"https://example.invalid/credit\"}]}\n"
            +"[1000,100](1000,200,0)  Hi(1200,300,1) 😀 \n[2000,0](2000,0,0)Bye";
        YrcLyricsDecoder.Document doc=YrcLyricsDecoder.decode(source);
        assertEquals(YrcLyricsDecoder.Status.PRESENT,doc.status);
        assertEquals(2,doc.rows.size());
        YrcLyricsDecoder.Row row=doc.rows.get(0);
        assertEquals("Hi 😀",row.text);assertEquals(1000,row.timeMs);
        word(row.words.get(0),0,2,1000,1200);word(row.words.get(1),2,5,1200,1500);
        word(doc.rows.get(1).words.get(0),0,3,2000,2000);
        assertTrue(doc.original.contains("[00:01.000]Hi 😀"));
        assertFalse(doc.original.contains("somebody"));assertFalse(doc.original.contains("https"));
        assertFalse(doc.toString().contains("Hi"));
        try{row.words.clear();fail();}catch(UnsupportedOperationException expected){}
    }

    @Test public void fileOffsetIsAppliedOnceAndDuplicateLineTimesArePreserved() {
        YrcLyricsDecoder.Document doc=YrcLyricsDecoder.decode("[1000,500](1000,500,0)A\n[1000,500](1200,300,0)B\n[offset:-1200]");
        assertEquals(YrcLyricsDecoder.Status.PRESENT,doc.status);
        assertEquals(-200,doc.rows.get(0).timeMs);assertEquals(-200,doc.rows.get(1).timeMs);
        word(doc.rows.get(0).words.get(0),0,1,0,300);
        word(doc.rows.get(1).words.get(0),0,1,0,300);
        assertTrue(doc.original.startsWith("[offset:-1200]"));
    }

    @Test public void rejectsRelativeTimesBackwardsTimesBrokenRangesAndUnicode() {
        for(String bad:new String[]{"[1000,500](0,100,0)A","[1000,500](1200,10,0)A(1100,10,0)B",
            "[1000,500]untimed(1000,100,0)A","[1000,500](1000,100,0)(1100,100,0)A",
            "[1000,500](1000,100,0)A(1100,100,0)","[0,100](0,50,0)\uD83D(50,50,0)\uDE00",
            "[1000,500](1000,100,0)A(1100,-1,0)B","[1000,500](1000,100,0)A(1100,100)B",
            "[0,1](0,1,0)\uD800","[0,1](0,1,0)A\u0000","[86400000,1](86400000,1,0)A",
            "[offset:86400001]\n[0,1](0,1,0)A","[0,1](0,1,0)[00:01.000]literal"}) invalid(bad);
    }

    @Test public void absentAndUnsupportedHaveNoInventedWords() {
        assertEquals(YrcLyricsDecoder.Status.ABSENT,YrcLyricsDecoder.decode("").status);
        for(String input:new String[]{"[1000,500]plain","[00:01.000]plain","[bg:(0,1,0)A]", "{\"t\":0,\"c\":[{\"tx\":\"credits\"}]}"})
            assertEquals(YrcLyricsDecoder.Status.UNSUPPORTED,YrcLyricsDecoder.decode(input).status);
    }

    @Test public void sourceRowWordAndTimeBudgetsAreBounded() {
        invalid("[0,1](0,1,0)"+"a".repeat(4097));
        invalid("[0,1]"+"(0,1,0)a".repeat(4097));
        invalid("[0,1](0,1,0)A\n".repeat(10_001));
        invalid("a".repeat(512*1024+1));
        invalid("[0,1](0,1,0)"+"😀".repeat(140_000));
        invalid("[offset:1000]\n[86399999,1](86399999,1,0)A");
        invalid(("[0,1]"+"(0,1,0)a".repeat(4096)+"\n").repeat(25));
    }

    @Test public void rowsWithoutWordTagsCanCoexistWithoutFabricatedTiming() {
        YrcLyricsDecoder.Document doc=YrcLyricsDecoder.decode("[0,100]Title\n[1000,500](1000,500,0)Singing");
        assertEquals(YrcLyricsDecoder.Status.PRESENT,doc.status);
        assertEquals(2,doc.rows.size());assertTrue(doc.rows.get(0).words.isEmpty());
        assertEquals(1,doc.rows.get(1).words.size());
    }
    private static void invalid(String text){assertEquals(YrcLyricsDecoder.Status.INVALID,YrcLyricsDecoder.decode(text).status);}
    private static void word(YrcLyricsDecoder.Word w,int from,int to,long start,long end){
        assertEquals(from,w.textStart);assertEquals(to,w.textEnd);assertEquals(start,w.startTimeMs);assertEquals(end,w.endTimeMs);
    }
}
