package io.github.litematicrender.nbt;

import io.github.litematicrender.nbt.io.NbtSizeTracker;
import java.io.DataInput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Immutable packed words: uniform pages cost one word; other pages stay compressed. */
public final class PackedLongArray {
    public static final int PAGE_WORDS = 4096;
    private static final int PAGE_BYTES = PAGE_WORDS * Long.BYTES;
    // Keys own only their page payload, never a schematic. Old schematics can be collected.
    private static final class DecodedPages extends LinkedHashMap<Page,long[]> {
        Page lastPage;
        long[] lastWords;
        DecodedPages(){super(64,.75f,true);}
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Page,long[]> entry){return size()>64;}
        long[] remember(Page page,long[] words){lastPage=page;lastWords=words;return words;}
    }
    private static final ThreadLocal<DecodedPages> DECODED=ThreadLocal.withInitial(DecodedPages::new);
    private static final class Page {
        final long uniform;
        final byte[] data;
        final boolean compressed;
        final int words;
        Page(long uniform, byte[] data, boolean compressed, int words) {
            this.uniform=uniform;this.data=data;this.compressed=compressed;this.words=words;
        }
        long[] decode() {
            var cache=DECODED.get();if(cache.lastPage==this)return cache.lastWords;
            long[] found=cache.get(this);if(found!=null)return cache.remember(this,found);
            byte[] raw=data;
            if(compressed) {
                raw=new byte[words*8];var inflater=new Inflater();
                try {
                    inflater.setInput(data);int offset=0;
                    while(offset<raw.length){int n=inflater.inflate(raw,offset,raw.length-offset);if(n==0)throw new IllegalStateException("方块数据页解压失败");offset+=n;}
                    if(!inflater.finished()||inflater.getRemaining()!=0)throw new IllegalStateException("方块数据页长度不符");
                }catch(DataFormatException e){throw new IllegalStateException("方块数据页损坏",e);}finally{inflater.end();}
            }
            long[] result=new long[words];ByteBuffer.wrap(raw).asLongBuffer().get(result);cache.put(this,result);return cache.remember(this,result);
        }
    }
    private final int length;
    private final Page[] pages;
    private final long[] dense;
    private final long retainedBytes;
    private PackedLongArray(int length,Page[] pages,long retainedBytes){this.length=length;this.pages=pages;this.dense=null;this.retainedBytes=retainedBytes;}
    private PackedLongArray(long[] dense){this.length=dense.length;this.dense=dense;this.pages=null;this.retainedBytes=8L*dense.length;}
    public static PackedLongArray wrap(long[] words){return new PackedLongArray(words);}
    private static PackedLongArray paged(long[] dense){
        Page[] pages=new Page[(dense.length+PAGE_WORDS-1)/PAGE_WORDS];long cost=32+pages.length*8L;
        for(int i=0;i<pages.length;i++){
            int begin=i*PAGE_WORDS,count=Math.min(PAGE_WORDS,dense.length-begin);long first=dense[begin];boolean uniform=true;for(int j=1;j<count;j++)if(dense[begin+j]!=first){uniform=false;break;}
            byte[] raw=null;if(!uniform){raw=new byte[count*8];ByteBuffer.wrap(raw).asLongBuffer().put(dense,begin,count);}pages[i]=new Page(first,raw,false,count);cost+=40+(raw==null?0:raw.length);
        }
        return new PackedLongArray(dense.length,pages,cost);
    }
    public int length(){return length;}
    public long retainedBytes(){return retainedBytes;}
    /** Copy only touched pages; edit history shares all other immutable page payloads. */
    public PackedLongArray withWords(java.util.Map<Integer,Long> changes){
        if(dense!=null)return paged(dense).withWords(changes);
        Page[] next=pages.clone();var grouped=new java.util.HashMap<Integer,long[]>();
        changes.forEach((i,v)->{
            if(i<0||i>=length)throw new IllegalArgumentException("方块索引越界");
            int p=i/PAGE_WORDS;long[] words=grouped.computeIfAbsent(p,k->{Page page=pages[k];if(page.data!=null)return page.decode().clone();long[] values=new long[page.words];Arrays.fill(values,page.uniform);return values;});words[i%PAGE_WORDS]=v;
        });
        long cost=retainedBytes;
        for(var e:grouped.entrySet()){Page old=next[e.getKey()];byte[] raw=new byte[e.getValue().length*8];ByteBuffer.wrap(raw).asLongBuffer().put(e.getValue());next[e.getKey()]=new Page(0,raw,false,e.getValue().length);cost+=raw.length-(old.data==null?0:old.data.length);}
        return new PackedLongArray(length,next,cost);
    }
    public long get(int index){
        if(index<0||index>=length)return 0;if(dense!=null)return dense[index];
        Page page=pages[index/PAGE_WORDS];return page.data==null?page.uniform:page.decode()[index%PAGE_WORDS];
    }
    public boolean zeroPage(int wordIndex){return dense==null&&pages[wordIndex/PAGE_WORDS].data==null&&pages[wordIndex/PAGE_WORDS].uniform==0;}
    public static PackedLongArray read(DataInput input,NbtSizeTracker tracker)throws IOException {
        int length=input.readInt();if(length<0)throw new IOException("方块数组长度为负数："+length);
        tracker.incrementPacked(4+8L*length);
        Page[] pages=new Page[(int)((length+(long)PAGE_WORDS-1)/PAGE_WORDS)];long retained=32+8L*pages.length;tracker.retainPacked(retained);
        byte[] raw=new byte[PAGE_BYTES],encoded=new byte[PAGE_BYTES+128];long[] words=new long[PAGE_WORDS];var deflater=new Deflater(1);
        try {
            for(int pageIndex=0;pageIndex<pages.length;pageIndex++) {
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("投影读取已取消");
                int count=Math.min(PAGE_WORDS,length-pageIndex*PAGE_WORDS),bytes=count*8;input.readFully(raw,0,bytes);ByteBuffer.wrap(raw,0,bytes).asLongBuffer().get(words,0,count);
                long first=words[0];boolean uniform=true;for(int i=1;i<count;i++)if(words[i]!=first){uniform=false;break;}
                Page page;
                if(uniform)page=new Page(first,null,false,count);
                else {
                    deflater.reset();deflater.setInput(raw,0,bytes);deflater.finish();int size=deflater.deflate(encoded);
                    boolean compressed=deflater.finished()&&size+32<bytes;
                    page=new Page(0,Arrays.copyOf(compressed?encoded:raw,compressed?size:bytes),compressed,count);
                }
                long cost=40+(page.data==null?0:16+page.data.length);tracker.retainPacked(cost);retained+=cost;pages[pageIndex]=page;
            }
        }finally{deflater.end();}
        return new PackedLongArray(length,pages,retained);
    }
}
