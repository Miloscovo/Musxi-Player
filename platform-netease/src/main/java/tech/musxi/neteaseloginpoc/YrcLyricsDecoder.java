package tech.musxi.neteaseloginpoc;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Bounded primary YRC timeline. Word starts are absolute track milliseconds, never inferred. */
public final class YrcLyricsDecoder {
    private static final int MAX_BYTES=512*1024, MAX_WORDS=100_000;
    private static final long MAX_TIME=86_400_000L;
    private static final Pattern LINE=Pattern.compile("^\\[(\\d{1,10}),(\\d{1,10})](.*)$");
    private static final Pattern WORD=Pattern.compile("\\((\\d{1,10}),(\\d{1,10}),(\\d{1,10})\\)");
    private static final Pattern BROKEN_TAG=Pattern.compile("\\([+-]?\\d+\\s*,");
    private static final Pattern OFFSET=Pattern.compile("(?i)^\\[offset:([+-]?\\d{1,10})]$");
    private static final Pattern META=Pattern.compile("(?i)^\\[(ti|ar|al|by|re|ve|length):.*]$");
    public enum Status { PRESENT, ABSENT, INVALID, UNSUPPORTED, NETWORK, PLATFORM_ERROR }
    public static final class Word {
        public final int textStart,textEnd;
        public final long startTimeMs,endTimeMs;
        Word(int from,int to,long start,long end){textStart=from;textEnd=to;startTimeMs=start;endTimeMs=end;}
        @Override public String toString(){return "YrcWord(redacted)";}
    }
    public static final class Row {
        public final long timeMs;
        public final String text;
        public final List<Word> words;
        Row(long time,String text,List<Word> words){
            timeMs=time;this.text=text;this.words=Collections.unmodifiableList(new ArrayList<>(words));
        }
        @Override public String toString(){return "YrcRow(redacted)";}
    }
    public static final class Document {
        /** Canonical LRC of these primary rows, with the YRC file offset retained once. */
        public final String original;
        public final List<Row> rows;
        public final Status status;
        Document(String original,List<Row> rows,Status status){
            this.original=original;this.rows=Collections.unmodifiableList(new ArrayList<>(rows));this.status=status;
        }
        @Override public String toString(){return "YrcDocument(redacted)";}
    }
    private YrcLyricsDecoder(){}
    static Document unavailable(Status status){return new Document("",Collections.emptyList(),status);}
    public static Document decode(String source){
        if(source==null || source.isEmpty())return unavailable(Status.ABSENT);
        try{return parse(source);}
        catch(UnsupportedOperationException unsupported){return unavailable(Status.UNSUPPORTED);}
        catch(IllegalArgumentException invalid){return unavailable(Status.INVALID);}
    }
    private static Document parse(String source){
        validate(source);
        if(source.startsWith("\uFEFF"))source=source.substring(1);
        String[] lines=source.replace("\r\n","\n").replace('\r','\n').split("\n",-1);
        if(lines.length>10_000)invalid();
        long offset=0;
        for(String line:lines){
            Matcher metadata=OFFSET.matcher(line.trim());
            if(metadata.matches()){
                offset=Long.parseLong(metadata.group(1));
                if(offset < -MAX_TIME || offset>MAX_TIME)invalid();
            }
        }
        ArrayList<Row> rows=new ArrayList<>();
        StringBuilder original=new StringBuilder("[offset:"+offset+"]\n");
        int totalWords=0;
        for(String raw:lines){
            String line=raw.trim();
            if(line.isEmpty() || META.matcher(line).matches() || OFFSET.matcher(line).matches())continue;
            // Native JSON credit annotations contain names/links, not singing word times.
            // Ignore them without parsing JSON or copying links into display/cache models.
            if(line.startsWith("{") && line.endsWith("}"))continue;
            Matcher header=LINE.matcher(line);
            if(!header.matches())throw new UnsupportedOperationException();
            long start=number(header.group(1)),duration=number(header.group(2));
            if(start+duration>MAX_TIME || start+offset>MAX_TIME)invalid();
            String body=header.group(3);
            Matcher tag=WORD.matcher(body);
            StringBuilder text=new StringBuilder();
            ArrayList<Word> words=new ArrayList<>();
            int cursor=0;
            long time=-1,endTime=-1,lastStart=-1;
            boolean found=false;
            while(tag.find()){
                String part=body.substring(cursor,tag.start());
                if(BROKEN_TAG.matcher(part).find())invalid();
                if(found){
                    if(part.isEmpty())invalid();
                    int from=text.length();text.append(part);
                    if(words.size()>=4096 || ++totalWords>MAX_WORDS)invalid();
                    words.add(new Word(from,text.length(),Math.max(0,time+offset),Math.max(0,endTime+offset)));
                }else if(!part.isEmpty())invalid();
                time=number(tag.group(1));long length=number(tag.group(2));
                // A line duration can end before its last word. Keep the provider's actual end.
                if(time<start || time<lastStart || time+length>MAX_TIME || time+length+offset>MAX_TIME)invalid();
                endTime=time+length;lastStart=time;found=true;cursor=tag.end();
            }
            String tail=body.substring(cursor);
            if(BROKEN_TAG.matcher(tail).find())invalid();
            int from=text.length();text.append(tail);
            if(found){
                if(tail.isEmpty() || words.size()>=4096 || ++totalWords>MAX_WORDS)invalid();
                words.add(new Word(from,text.length(),Math.max(0,time+offset),Math.max(0,endTime+offset)));
            }
            String full=text.toString();
            if(full.codePointCount(0,full.length())>4096)invalid();
            int left=0,right=full.length();
            while(left<right && Character.isWhitespace(full.charAt(left)))left++;
            while(right>left && Character.isWhitespace(full.charAt(right-1)))right--;
            if(left==right)continue;
            ArrayList<Word> trimmed=new ArrayList<>();
            for(Word word:words){
                int a=Math.max(left,word.textStart),b=Math.min(right,word.textEnd);
                if(a>=b)continue;
                if(split(full,a) || split(full,b))invalid();
                trimmed.add(new Word(a-left,b-left,word.startTimeMs,word.endTimeMs));
            }
            String displayed=full.substring(left,right);
            // A leading LRC timestamp in literal YRC text would be consumed as a second
            // timestamp by the shared parser. Refuse that conversion instead of changing text.
            if(displayed.matches("^\\[\\d+:[0-5]\\d(?:\\.\\d{1,3})?].*"))invalid();
            rows.add(new Row(start+offset,displayed,trimmed));
            original.append(String.format(Locale.ROOT,"[%02d:%02d.%03d]",start/60000,(start/1000)%60,start%1000))
                .append(displayed).append('\n');
        }
        if(rows.isEmpty() || rows.stream().noneMatch(row->!row.words.isEmpty()))return unavailable(Status.UNSUPPORTED);
        validate(original.toString());
        return new Document(original.toString(),rows,Status.PRESENT);
    }
    private static long number(String value){long parsed=Long.parseLong(value);if(parsed>MAX_TIME)invalid();return parsed;}
    private static boolean split(String value,int index){
        return index>0 && index<value.length() && Character.isHighSurrogate(value.charAt(index-1)) && Character.isLowSurrogate(value.charAt(index));
    }
    private static void validate(String text){
        if(text.length()>MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)invalid();
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)){
                if(++i>=text.length() || !Character.isLowSurrogate(text.charAt(i)))invalid();
            }else if(Character.isLowSurrogate(c) || Character.isISOControl(c) && c!='\t' && c!='\r' && c!='\n')invalid();
        }
    }
    private static void invalid(){throw new IllegalArgumentException("Invalid YRC");}
}
