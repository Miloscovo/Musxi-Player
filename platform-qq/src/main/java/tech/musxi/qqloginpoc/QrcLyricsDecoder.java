package tech.musxi.qqloginpoc;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.Inflater;

/** Bounded online QRC container and absolute word times. No XML parser or entity resolver is used. */
public final class QrcLyricsDecoder {
    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_WORDS = 100_000;
    private static final long MAX_TIME = 86_400_000L;
    private static final Pattern LINE = Pattern.compile("^\\[(\\d{1,10}),(\\d{1,10})](.*)$");
    private static final Pattern WORD = Pattern.compile("\\((\\d{1,10}),(\\d{1,10})\\)");
    private static final Pattern CONTENT = Pattern.compile("\\bLyricContent\\s*=\\s*([\"'])");
    private static final Pattern OFFSET = Pattern.compile("(?i)^\\[offset:([+-]?\\d{1,10})]$");
    private static final Pattern META = Pattern.compile("(?i)^\\[(ti|ar|al|by|re|ve|length|kana):.*]$");
    public enum Status { PRESENT, ABSENT, INVALID, UNSUPPORTED, NETWORK, TIMEOUT, PLATFORM_ERROR }
    public static final class Word {
        public final int textStart, textEnd;
        public final long startTimeMs, endTimeMs;
        Word(int start, int end, long time, long endTime) {
            textStart=start; textEnd=end; startTimeMs=time; endTimeMs=endTime;
        }
        @Override public String toString() { return "QrcWord(redacted)"; }
    }
    public static final class Row {
        public final long timeMs;
        public final String text;
        public final List<Word> words;
        Row(long time, String text, List<Word> words) {
            timeMs=time; this.text=text; this.words=Collections.unmodifiableList(new ArrayList<>(words));
        }
        @Override public String toString() { return "QrcRow(redacted)"; }
    }
    public static final class Document {
        public final List<Row> rows;
        public final Status status;
        Document(List<Row> rows, Status status) {
            this.rows=Collections.unmodifiableList(new ArrayList<>(rows)); this.status=status;
        }
        @Override public String toString() { return "QrcDocument(redacted)"; }
    }
    private QrcLyricsDecoder() {}
    static Document unavailable(Status status) { return new Document(Collections.emptyList(), status); }

    public static Document decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return unavailable(Status.ABSENT);
        try {
            String text;
            if (encoded.startsWith("[") || encoded.startsWith("<") || encoded.startsWith("\uFEFF")) {
                text=encoded;
            } else {
                if (encoded.length()>MAX_BYTES*2 || encoded.length()%16!=0) invalid();
                byte[] bytes=new byte[encoded.length()/2];
                for (int i=0; i<bytes.length; i++) {
                    int high=Character.digit(encoded.charAt(i*2),16), low=Character.digit(encoded.charAt(i*2+1),16);
                    if (high<0 || low<0 || encoded.charAt(i*2)>127 || encoded.charAt(i*2+1)>127) invalid();
                    bytes[i]=(byte)((high<<4)|low);
                }
                text=inflate(QrcCipher.decrypt(bytes));
            }
            validateText(text);
            if (text.startsWith("\uFEFF")) text=text.substring(1);
            text=text.trim();
            if (text.startsWith("<")) text=extractContent(text);
            return parseText(text);
        } catch (UnsupportedOperationException unsupported) { return unavailable(Status.UNSUPPORTED); }
        catch (Exception invalid) { return unavailable(Status.INVALID); }
    }

    private static String inflate(byte[] bytes) throws Exception {
        Inflater inflater=new Inflater();
        try {
            inflater.setInput(bytes);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buffer=new byte[4096];
            while (!inflater.finished()) {
                int count=inflater.inflate(buffer);
                if (count==0 || out.size()+count>MAX_BYTES) invalid();
                out.write(buffer,0,count);
            }
            // Online QRC is padded to an eight-byte cipher block, not a second zlib stream.
            int remaining=inflater.getRemaining();
            if (remaining>7) invalid();
            for (int i=bytes.length-remaining; i<bytes.length; i++) if (bytes[i]!=0) invalid();
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();
        } finally { inflater.end(); }
    }

    private static String extractContent(String xml) {
        // Read only the single literal attribute, preserving embedded newlines. DTDs and
        // arbitrary entities are rejected, and no part of the input is executed as XML.
        if (!xml.contains("<QrcInfos") || !xml.endsWith("</QrcInfos>") || xml.contains("<!")) invalid();
        Matcher matcher=CONTENT.matcher(xml);
        if (!matcher.find()) invalid();
        char quote=matcher.group(1).charAt(0);
        int start=matcher.end(), end=xml.indexOf(quote,start);
        if (end<0 || matcher.find(end+1)) invalid();
        return unescape(xml.substring(start,end));
    }

    private static String unescape(String value) {
        StringBuilder result=new StringBuilder(value.length());
        for (int i=0; i<value.length(); i++) {
            char c=value.charAt(i);
            if (c!='&') { result.append(c); continue; }
            int end=value.indexOf(';',i+1);
            if (end<0 || end-i>12) invalid();
            String name=value.substring(i+1,end);
            switch (name) {
                case "amp": result.append('&'); break;
                case "lt": result.append('<'); break;
                case "gt": result.append('>'); break;
                case "quot": result.append('"'); break;
                case "apos": result.append('\''); break;
                default:
                    if (!name.matches("#(?:[0-9]{1,7}|x[0-9a-fA-F]{1,6})")) invalid();
                    int code=name.startsWith("#x")?Integer.parseInt(name.substring(2),16):Integer.parseInt(name.substring(1));
                    if (!Character.isValidCodePoint(code) || code>=0xD800 && code<=0xDFFF) invalid();
                    result.appendCodePoint(code);
            }
            i=end;
        }
        return result.toString();
    }

    private static Document parseText(String text) {
        validateText(text);
        String[] lines=text.replace("\r\n","\n").replace('\r','\n').split("\n",-1);
        if (lines.length>10_000) invalid();
        long offset=0;
        for (String source:lines) {
            Matcher metadata=OFFSET.matcher(source.trim());
            if (metadata.matches()) {
                offset=Long.parseLong(metadata.group(1));
                if (offset < -MAX_TIME || offset>MAX_TIME) invalid();
            }
        }
        ArrayList<Row> rows=new ArrayList<>();
        int wordCount=0;
        for (String source:lines) {
            String line=source.trim();
            if (line.isEmpty() || META.matcher(line).matches() || OFFSET.matcher(line).matches()) continue;
            Matcher header=LINE.matcher(line);
            if (!header.matches()) throw new UnsupportedOperationException();
            long start=number(header.group(1)), duration=number(header.group(2));
            if (start+duration>MAX_TIME) invalid();
            String body=header.group(3);
            StringBuilder raw=new StringBuilder();
            ArrayList<Word> words=new ArrayList<>();
            Matcher token=WORD.matcher(body);
            int cursor=0;
            long lastTime=-1;
            while (token.find()) {
                int from=raw.length();
                raw.append(body,cursor,token.start());
                long at=number(token.group(1)), length=number(token.group(2));
                // QRC word starts are absolute; do not guess a relative-time dialect.
                // Provider line durations may overlap or end before their last word; retain
                // the independently supplied word end rather than clipping it to the line.
                if (at<start || at<lastTime || at+length>MAX_TIME || at+length+offset>MAX_TIME ||
                    raw.length()==from || words.size()>=4096 || ++wordCount>MAX_WORDS) invalid();
                words.add(new Word(from,raw.length(),Math.max(0,at+offset),Math.max(0,at+length+offset)));
                lastTime=at;
                cursor=token.end();
            }
            raw.append(body,cursor,body.length());
            String full=raw.toString();
            if (full.codePointCount(0,full.length())>4096) invalid();
            int left=0,right=full.length();
            while (left<right && space(full.charAt(left))) left++;
            while (right>left && space(full.charAt(right-1))) right--;
            if (left==right) continue;
            ArrayList<Word> trimmed=new ArrayList<>(words.size());
            int covered=left;
            for (Word word:words) {
                int from=Math.max(left,word.textStart), to=Math.min(right,word.textEnd);
                if (from>=to) continue;
                if (from!=covered || splitsSurrogate(full,from) || splitsSurrogate(full,to)) invalid();
                trimmed.add(new Word(from-left,to-left,word.startTimeMs,word.endTimeMs));
                covered=to;
            }
            if (!words.isEmpty() && covered!=right) invalid();
            rows.add(new Row(start+offset,full.substring(left,right),trimmed));
        }
        return new Document(rows,wordCount==0?Status.UNSUPPORTED:Status.PRESENT);
    }

    private static long number(String value) {
        long number=Long.parseLong(value);
        if (number>MAX_TIME) invalid();
        return number;
    }
    private static boolean space(char c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }
    private static boolean splitsSurrogate(String text,int index) {
        return index>0 && index<text.length() && Character.isHighSurrogate(text.charAt(index-1)) && Character.isLowSurrogate(text.charAt(index));
    }
    private static void validateText(String text) {
        if (text.length()>MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) invalid();
        for (int i=0; i<text.length(); i++) {
            char c=text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i>=text.length() || !Character.isLowSurrogate(text.charAt(i))) invalid();
            } else if (Character.isLowSurrogate(c) || Character.isISOControl(c) && c!='\t' && c!='\r' && c!='\n') invalid();
        }
    }
    private static void invalid() { throw new IllegalArgumentException("Invalid QRC"); }
}
