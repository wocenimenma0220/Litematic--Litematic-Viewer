package io.github.litematicrender.schematic;

import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.*;
import io.github.litematicrender.serve.RenderEngine;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

public final class LargeNbtRegression {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),out=root.resolve("tools/verify/large-nbt-output");Files.createDirectories(out);
        Random random=new Random(17);long[] reference=new long[PackedLongArray.PAGE_WORDS*4+13];
        Arrays.fill(reference,PackedLongArray.PAGE_WORDS,PackedLongArray.PAGE_WORDS*2,0x5555555555555555L);
        for(int i=PackedLongArray.PAGE_WORDS*2;i<PackedLongArray.PAGE_WORDS*3;i++)reference[i]=i%73==0?random.nextLong():0x0123456789abcdefL;
        for(int i=PackedLongArray.PAGE_WORDS*3;i<reference.length;i++)reference[i]=random.nextLong();
        var bytes=new ByteArrayOutputStream();try(var writer=new DataOutputStream(bytes)){writer.writeInt(reference.length);for(long word:reference)writer.writeLong(word);}
        var packed=PackedLongArray.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),new NbtSizeTracker(NbtSizeTracker.SCHEMATIC_MAX_BYTES,true));
        for(int pass=0;pass<3;pass++)for(int i=0;i<reference.length;i++)check(reference[i]==packed.get(i),"decoded word differs at "+i);
        for(int bits=2;bits<=16;bits++)for(int i=0;i<50000;i++){int cell=random.nextInt((reference.length-1)*64/bits);check(LitematicaSchematic.getAt(reference,bits,cell)==LitematicaSchematic.getAt(packed,bits,cell),"palette index crosses page incorrectly");}
        var tag=new NbtCompound().putInt("Version",7).putString("Name","原始与压缩");Path gzip=out.resolve("valid.nbt.gz"),raw=out.resolve("valid.nbt");NbtFileUtils.writeCompressed(gzip,tag);NbtFileUtils.writeRaw(raw,tag);
        for(Path file:List.of(raw,gzip)){check(NbtFileUtils.readCompressedOrRaw(file).getIntOrDefault("Version",0)==7,"file header detection failed");try(var input=Files.newInputStream(file)){check(NbtFileUtils.readCompressedOrRawStream(input).getIntOrDefault("Version",0)==7,"stream header detection failed");}}
        Path truncated=out.resolve("truncated.litematic");Files.write(truncated,Arrays.copyOf(Files.readAllBytes(gzip),11));boolean failed=false;try{NbtFileUtils.readSchematic(truncated);}catch(IOException e){failed=true;check(!e.toString().contains("Unknown(31)")&&e.getCause()!=null,"gzip error was masked");}check(failed,"truncated gzip accepted");
        Path invalid=out.resolve("negative.nbt");try(var writer=new DataOutputStream(Files.newOutputStream(invalid))){writer.writeByte(10);writer.writeUTF("");writer.writeByte(12);writer.writeUTF("bad");writer.writeInt(-1);}
        failed=false;try{NbtFileUtils.readCompressedOrRaw(invalid);}catch(IOException e){failed=true;}check(failed,"negative NBT array was allocated");

        Path huge=out.resolve("over-512MiB.litematic");int wordCount=67_108_864;
        try(var writer=new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(huge)),65536))){
            writer.writeByte(10);writer.writeUTF("");writer.writeByte(3);writer.writeUTF("Version");writer.writeInt(7);
            writer.writeByte(10);writer.writeUTF("Regions");writer.writeByte(10);writer.writeUTF("big");writer.writeByte(12);writer.writeUTF("BlockStates");writer.writeInt(wordCount);
            byte[] zero=new byte[65536];for(int offset=0;offset<wordCount*8;offset+=zero.length)writer.write(zero);writer.writeByte(0);writer.writeByte(0);writer.writeByte(0);
        }
        var large=NbtFileUtils.readSchematic(huge);var largeWords=((NbtPackedLongArray)large.getCompound("Regions").orElseThrow().getCompound("big").orElseThrow().get("BlockStates").orElseThrow()).value;
        check(largeWords.length()==wordCount&&largeWords.get(wordCount-1)==0&&largeWords.retainedBytes()<2*1024*1024,"large empty data stayed dense");
        failed=false;try{NbtFileUtils.readCompressedOrRaw(huge);}catch(IOException e){failed=true;check(!e.toString().contains("Unknown(31)"),"quota failure was masked");}check(failed,"generic NBT allocation cap was bypassed");
        System.out.println("PASS: raw/GZIP detection, original truncated error, malformed lengths, exact random words and cross-page palette entries, >512 MiB schematic with <2 MiB backing");
        if(args.length>1){
            System.setProperty("litematicrender.skins.offline","true");long start=System.nanoTime();
            try(var engine=new RenderEngine(root)){
                var result=engine.open(Path.of(args[1]));System.out.println("REAL FILE OPEN: regions="+result.get("regionCount")+"; blocks="+result.get("totalBlocks")+"; seconds="+(System.nanoTime()-start)/1e9);
                var field=RenderEngine.class.getDeclaredField("schematic");field.setAccessible(true);var schematic=(LitematicaSchematic)field.get(engine);long retained=0,logical=0;
                for(var region:schematic.regions().values()){retained+=region.blocks.storage.retainedBytes();logical+=8L*region.blocks.storage.length();}
                System.out.println("REAL PACKED DATA: logical="+logical+"; retained="+retained+"; heapUsed="+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()));
                engine.open(root.resolve("../../曼波mis_(咸鱼lite_v2+)_v2.4.2.litematic").normalize());System.out.println("PASS: real large -> small replacement in same process");
            }
        }
    }
}
