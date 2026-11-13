package tech.musxi.loginpoc;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.Inflater;

/**
 * Bounded KRC1 container, line-level language metadata and optional word timings.
 * Word ranges index the final trimmed {@link Row#text}; no UI contract is implied.
 */
public final class KrcLyricsDecoder {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_WORDS_PER_ROW = 4096;
    private static final int MAX_WORDS = 100000;
    private static final int[] KEY = {64,71,97,119,94,50,116,71,81,54,49,45,206,210,110,105};
    private static final Pattern LINE = Pattern.compile("^\\[(\\d{1,9}),(\\d{1,9})](.*)$");
    private static final Pattern WORD = Pattern.compile("<(\\d{1,9}),(\\d{1,9}),(\\d{1,9})>");
    public enum Status { PRESENT, ABSENT, INVALID, UNSUPPORTED }
    /** Absolute track times; [textStart,textEnd) indexes Row.text in UTF-16 units. */
    public static final class Word {
        public final int textStart, textEnd;
        public final long startTimeMs, endTimeMs;
        Word(int textStart, int textEnd, long startTimeMs, long endTimeMs) {
            this.textStart=textStart; this.textEnd=textEnd;
            this.startTimeMs=startTimeMs; this.endTimeMs=endTimeMs;
        }
        @Override public String toString() { return "KrcWord(redacted)"; }
    }
    public static final class Row {
        public final long timeMs;
        public final String text, translation;
        public final List<Word> words;
        Row(long timeMs, String text, String translation) { this(timeMs,text,translation,Collections.emptyList()); }
        Row(long timeMs, String text, String translation, List<Word> words) {
            this.timeMs=timeMs; this.text=text; this.translation=translation;
            this.words=words==null || words.isEmpty()?Collections.emptyList()
                :Collections.unmodifiableList(new ArrayList<>(words));
        }
        @Override public String toString() { return "KrcRow(redacted)"; }
    }
    public static final class Document {
        public final List<Row> rows;
        public final Status status;
        public final String diagnostic;
        Document(List<Row> rows, Status status) { this(rows,status,"NONE"); }
        Document(List<Row> rows, Status status,String diagnostic) { this.rows=Collections.unmodifiableList(new ArrayList<>(rows)); this.status=status;this.diagnostic=diagnostic; }
        @Override public String toString() { return "KrcDocument(redacted)"; }
    }
    private KrcLyricsDecoder() {}
    public static Document decode(String encoded) {
        String text;
        try { text=inflate(encoded); }
        catch(Exception invalid) { return new Document(Collections.emptyList(),Status.INVALID,"CONTAINER"); }
        try { return parseText(text); }
        catch (Unsupported unsupported) { return new Document(Collections.emptyList(), Status.UNSUPPORTED); }
        catch (Invalid invalid) { return new Document(Collections.emptyList(),Status.INVALID,invalid.diagnostic); }
        catch (Exception invalid) { return new Document(Collections.emptyList(), Status.INVALID,"ROWS"); }
    }
    private static byte[] base64(String text, int maxBytes) throws Exception {
        if(text == null || text.length() > ((maxBytes + 2) / 3) * 4 || text.length()%4 != 0 ||
            !text.matches("[A-Za-z0-9+/]*={0,2}")) throw new Exception();
        byte[] bytes=Base64.getDecoder().decode(text);
        if(bytes.length > maxBytes || !Base64.getEncoder().encodeToString(bytes).equals(text))throw new Exception();
        return bytes;
    }
    private static String utf8(byte[] bytes) throws Exception {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static String inflate(String encoded) throws Exception {
        byte[] bytes=base64(encoded, MAX_BYTES);
        if(bytes.length<=4 || bytes[0]!='k' || bytes[1]!='r' || bytes[2]!='c' || bytes[3]!='1')throw new Exception();
        for(int i=4;i<bytes.length;i++)bytes[i]^=KEY[(i-4)%KEY.length];
        Inflater inflater=new Inflater();
        try {
            inflater.setInput(bytes,4,bytes.length-4);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buffer=new byte[4096];
            while(!inflater.finished()) {
                int count=inflater.inflate(buffer);
                if(count==0 || out.size()+count>MAX_BYTES)throw new Exception();
                out.write(buffer,0,count);
            }
            if(inflater.getRemaining()!=0)throw new Exception();
            String text=utf8(out.toByteArray());
            validateText(text,MAX_BYTES);
            if(text.startsWith("\uFEFF"))text=text.substring(1);
            if(text.trim().isEmpty())throw new Exception();
            return text;
        } finally { inflater.end(); }
    }
    private static void validateText(String text,int maxBytes) throws Exception {
        if(text.length()>maxBytes || text.getBytes(StandardCharsets.UTF_8).length>maxBytes)throw new Exception();
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t')throw new Exception();
            if(Character.isHighSurrogate(c)) { if(++i>=text.length() || !Character.isLowSurrogate(text.charAt(i)))throw new Exception(); }
            else if(Character.isLowSurrogate(c))throw new Exception();
        }
    }
    private static Document parseText(String text) throws Exception {
        String[] lines=text.replace("\r\n","\n").replace('\r','\n').split("\n",-1);
        if(lines.length>10000)throw new Exception();
        List<Row> rows=new ArrayList<>(); List<List<Span>> timings=new ArrayList<>();
        String language=null; long offset=0; boolean offsetSeen=false;
        int originalBytes=0,wordCount=0;
        for(String source:lines) {
            String line=source.trim();
            if(line.startsWith("[language:")) {
                if(language!=null || !line.endsWith("]"))throw new Invalid("LANGUAGE_TAG");
                language=line.substring(10,line.length()-1);
                if(language.length()>350*1024)throw new Exception();
                continue;
            }
            if(line.length()>64*1024)throw new Invalid("LINE_LIMIT");
            if(line.startsWith("[offset:")) {
                if(offsetSeen || !line.matches("\\[offset:[+-]?\\d{1,8}]"))throw new Invalid("OFFSET");
                offsetSeen=true;offset=Long.parseLong(line.substring(8,line.length()-1));
                if(Math.abs(offset)>86400000)throw new Exception();
                continue;
            }
            Matcher row=LINE.matcher(line);
            if(!row.matches()) {
                // Unknown numeric/timing syntax is damaged, never silently drop a translated row.
                if(line.startsWith("[") && line.length()>1 && Character.isDigit(line.charAt(1)))throw new Invalid("ROW_HEADER");
                if(!line.isEmpty() && !line.matches("\\[[A-Za-z][A-Za-z0-9_-]*:[^\\[\\]]*]"))
                    throw new Invalid(line.startsWith("\uFEFF")?"TEXT_BOM":
                        line.startsWith("[") && line.endsWith("]")?"BRACKET_METADATA":"UNTAGGED_TEXT");
                continue;
            }
            long start=Long.parseLong(row.group(1)),duration=Long.parseLong(row.group(2));
            if(start>86400000 || duration>86400000 || start+duration>86400000)throw new Invalid("ROW_TIME");
            String payload=row.group(3); Matcher word=WORD.matcher(payload); StringBuilder plain=new StringBuilder();
            // A tag starts a segment that runs to the next tag or the payload end;
            // its timing is relative to the row start.
            List<int[]> ranges=new ArrayList<>(); List<long[]> clock=new ArrayList<>();
            int cursor=0; long previous=-1; boolean wordOverflow=false;
            while(word.find()) {
                String between=payload.substring(cursor,word.start());
                if(between.indexOf('<')>=0 || between.indexOf('>')>=0)throw new Invalid("WORD_TAG");
                plain.append(between);
                long at=Long.parseLong(word.group(1)),length=Long.parseLong(word.group(2));
                if(at<previous || at>86400000 || length>86400000)throw new Invalid("WORD_TIME");
                // Keep validating past the per-row cap; overflowing rows lose only their timings.
                if(clock.size()<MAX_WORDS_PER_ROW)clock.add(new long[]{at,length,plain.length()});
                else wordOverflow=true;
                previous=at;cursor=word.end();
            }
            String tail=payload.substring(cursor);
            if(tail.indexOf('<')>=0 || tail.indexOf('>')>=0)throw new Invalid("WORD_TAIL");
            plain.append(tail);
            for(int i=0;i<clock.size();i++)
                ranges.add(new int[]{(int)clock.get(i)[2],
                    i+1<clock.size()?(int)clock.get(i+1)[2]:plain.length()});
            String raw=plain.toString();
            int begin=0,finish=raw.length();
            while(begin<finish && raw.charAt(begin)<=' ')begin++;
            while(finish>begin && raw.charAt(finish-1)<=' ')finish--;
            String original=raw.substring(begin,finish);
            if(original.codePointCount(0,original.length())>4096)throw new Exception();
            originalBytes+=original.getBytes(StandardCharsets.UTF_8).length;
            if(originalBytes>512*1024 || rows.size()>=10000)throw new Exception();
            // Word ranges index the trimmed text; a broken coverage or an out-of-row
            // timing drops only the timings and never the original/translation.
            List<Span> timed=null;
            if(!ranges.isEmpty() && !wordOverflow) {
                List<Span> clipped=new ArrayList<>(ranges.size());
                boolean cover=true,inRow=true,pairs=true;
                int atEnd=begin;
                for(int i=0;i<ranges.size();i++) {
                    int from=Math.max(ranges.get(i)[0],begin),to=Math.min(ranges.get(i)[1],finish);
                    long at=clock.get(i)[0],length=clock.get(i)[1];
                    if(at+length>duration)inRow=false;
                    if(to<=from)continue; // Empty segment between adjacent tags.
                    if(from!=atEnd)cover=false;
                    atEnd=to;
                    // A tag inside a surrogate pair cannot bound a valid UTF-16 range.
                    if(splitsPair(original,from-begin) || splitsPair(original,to-begin))pairs=false;
                    clipped.add(new Span(from-begin,to-begin,at,length));
                }
                // A gap before the first tag or after the last one leaves text untimed.
                if(cover && atEnd-begin!=original.length())cover=false;
                if(cover && inRow && pairs && !clipped.isEmpty()) {
                    wordCount+=clipped.size();
                    if(wordCount<=MAX_WORDS)timed=clipped;
                }
            }
            rows.add(new Row(start,original,null));
            timings.add(timed);
        }
        List<String> translations;
        try { translations=language==null?null:translations(language,rows.size()); }
        catch(Unsupported unsupported) { throw unsupported; }
        catch(Exception invalid) { throw new Invalid("METADATA"); }
        List<Row> result=new ArrayList<>();
        for(int i=0;i<rows.size();i++) {
            Row row=rows.get(i);
            long base=row.timeMs+offset;
            List<Word> words=Collections.emptyList(); List<Span> timed=timings.get(i);
            if(timed!=null) {
                words=new ArrayList<>(timed.size());
                boolean fits=true;
                for(Span span:timed) {
                    long from=Math.max(0,base+span.start),to=Math.max(0,base+span.start+span.duration);
                    // A positive document offset can push word times past the row limit;
                    // that drops only the timings and keeps the row and its translation.
                    if(from>86400000 || to>86400000)fits=false;
                    words.add(new Word(span.textStart,span.textEnd,from,to));
                }
                if(!fits)words=Collections.emptyList();
            }
            result.add(new Row(base,row.text,translations==null?null:translations.get(i),words));
        }
        return new Document(result,translations==null?Status.ABSENT:Status.PRESENT);
    }
    private static boolean splitsPair(String text,int index) {
        return index>0 && index<text.length() &&
            Character.isHighSurrogate(text.charAt(index-1)) && Character.isLowSurrogate(text.charAt(index));
    }
    /** Word timing relative to the row start, before the document offset is known. */
    private static final class Span {
        final int textStart,textEnd; final long start,duration;
        Span(int textStart,int textEnd,long start,long duration) {
            this.textStart=textStart; this.textEnd=textEnd; this.start=start; this.duration=duration;
        }
    }
    private static List<String> translations(String encoded,int count) throws Exception {
        String json=utf8(base64(encoded,256*1024));validateText(json,256*1024);
        // Android org.json accepts non-JSON syntax and duplicate keys. Reject both,
        // and cap depth before entering its recursive parser.
        new StrictJson(json).validate();
        org.json.JSONTokener tokener=new org.json.JSONTokener(json);
        Object value=tokener.nextValue();
        if(!(value instanceof JSONObject) || tokener.nextClean()!=0)throw new Exception();
        JSONObject root=(JSONObject)value;
        if(!isInteger(root.opt("version")))throw new Exception();
        if(!integer(root.opt("version"),1))throw new Unsupported();
        JSONArray blocks=root.optJSONArray("content");
        if(blocks==null || blocks.length()>16)throw new Exception();
        List<String> result=null;
        for(int b=0;b<blocks.length();b++) {
            JSONObject block=blocks.optJSONObject(b);
            if(block==null)throw new Exception();
            Object type=block.opt("type"),language=block.opt("language");
            if(!isInteger(type) || !isInteger(language))throw new Exception();
            if(integer(type,0))continue; // Romanization is not translated text or row evidence.
            if(!integer(language,0) || !integer(type,1))throw new Unsupported();
            JSONArray content=block.optJSONArray("lyricContent");
            if(content==null || content.length()!=count)throw new Exception();
            boolean translated=integer(type,1);
            if(translated && result!=null)throw new Exception();
            List<String> candidate=new ArrayList<>();int total=0;
            for(int r=0;r<content.length();r++) {
                JSONArray row=content.optJSONArray(r);
                if(row==null || row.length()>4096 || (translated && row.length()!=1))throw new Exception();
                for(int w=0;w<row.length();w++) {
                    Object item=row.opt(w);
                    if(!(item instanceof String))throw new Exception();
                    String s=(String)item;validateText(s,256*1024);
                    if(s.codePointCount(0,s.length())>4096 || s.indexOf('\n')>=0 || s.indexOf('\r')>=0)throw new Exception();
                    total+=s.getBytes(StandardCharsets.UTF_8).length;if(total>256*1024)throw new Exception();
                    if(translated)candidate.add(s);
                }
            }
            if(translated)result=candidate;
        }
        return result;
    }
    private static boolean integer(Object n,int expected) {
        return isInteger(n) && ((Number)n).longValue()==expected;
    }
    private static boolean isInteger(Object n) { return n instanceof Integer || n instanceof Long; }
    private static final class StrictJson {
        final String text; int cursor;
        StrictJson(String text) { this.text=text; }
        void validate() throws Exception { value(0);space();if(cursor!=text.length())throw new Exception(); }
        void space() { while(cursor<text.length() && " \r\n\t".indexOf(text.charAt(cursor))>=0)cursor++; }
        boolean take(char c) { space();if(cursor<text.length() && text.charAt(cursor)==c){cursor++;return true;}return false; }
        void need(char c) throws Exception { if(!take(c))throw new Exception(); }
        void value(int depth) throws Exception {
            space();if(depth>8 || cursor>=text.length())throw new Exception();
            char c=text.charAt(cursor);
            if((c=='{' || c=='[') && depth>=8)throw new Exception();
            if(c=='{') {
                cursor++;Set<String> keys=new HashSet<>();if(take('}'))return;
                do { space();String key=string();if(!keys.add(key))throw new Exception();need(':');value(depth+1); } while(take(','));
                need('}');
            } else if(c=='[') {
                cursor++;if(take(']'))return;
                do { value(depth+1); } while(take(','));need(']');
            } else if(c=='"')string();
            else {
                int start=cursor;
                while(cursor<text.length() && ",]} \r\n\t".indexOf(text.charAt(cursor))<0)cursor++;
                String token=text.substring(start,cursor);
                if(!token.equals("true") && !token.equals("false") && !token.equals("null") &&
                    !token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw new Exception();
            }
        }
        String string() throws Exception {
            if(cursor>=text.length() || text.charAt(cursor)!='"')throw new Exception();
            int start=cursor++;boolean closed=false;
            while(cursor<text.length()) {
                char c=text.charAt(cursor++);
                if(c=='"'){closed=true;break;}
                if(c<32)throw new Exception();
                if(c=='\\') {
                    if(cursor>=text.length())throw new Exception();char escape=text.charAt(cursor++);
                    if(escape=='u') {
                        for(int i=0;i<4;i++)if(cursor>=text.length() || Character.digit(text.charAt(cursor++),16)<0)throw new Exception();
                    } else if("\"\\/bfnrt".indexOf(escape)<0)throw new Exception();
                }
            }
            if(!closed)throw new Exception();
            Object decoded=new org.json.JSONTokener(text.substring(start,cursor)).nextValue();
            if(!(decoded instanceof String))throw new Exception();return (String)decoded;
        }
    }
    private static final class Unsupported extends Exception {}
    private static final class Invalid extends Exception {
        final String diagnostic;
        Invalid(String diagnostic) { this.diagnostic=diagnostic; }
    }
}
