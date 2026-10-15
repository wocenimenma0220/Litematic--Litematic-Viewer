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
    private static final ThreadLocal<LinkedHashMap<Page,long[]>> DECODED = ThreadLocal.withInitial(
        () -> new LinkedHashMap<>(64, .75f, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<Page,long[]> entry) { return size() > 64; }
        });
    private static final class Page {
        final long uniform;
        final byte[] data;
        final boolean compressed;
        final int words;
        Page(long uniform, byte[] data, boolean compressed, int words) {
            this.uniform=uniform;this.data=data;this.compressed=compressed;this.words=words;
        }
        long[] decode() {
            var cache=DECODED.get();long[] found=cache.get(this);if(found!=null)return found;
            byte[] raw=data;
            if(compressed) {
                raw=new byte[words*8];var inflater=new Inflater();
                try {
                    inflater.setInput(data);int offset=0;
                    while(offset<raw.length){int n=inflater.inflate(raw,offset,raw.length-offset);if(n==0)throw new IllegalStateException("方块数据页解压失败");offset+=n;}
                    if(!inflater.finished()||inflater.getRemaining()!=0)throw new IllegalStateException("方块数据页长度不符");
                }catch(DataFormatException e){throw new IllegalStateException("方块数据页损坏",e);}finally{inflater.end();}
            }
            long[] result=new long[words];ByteBuffer.wrap(raw).asLongBuffer().get(result);cache.put(this,result);return result;
        }
    }
    private final int length;
    private final Page[] pages;
    private final long[] dense;
    private final long retainedBytes;
    private PackedLongArray(int length,Page[] pages,long retainedBytes){this.length=length;this.pages=pages;this.dense=null;this.retainedBytes=retainedBytes;}
    private PackedLongArray(long[] dense){this.length=dense.length;this.dense=dense;this.pages=null;this.retainedBytes=8L*dense.length;}
    public static PackedLongArray wrap(long[] words){return new PackedLongArray(words);}
    public int length(){return length;}
    public long retainedBytes(){return retainedBytes;}
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
