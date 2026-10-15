package io.github.litematicrender.render.scene;

import java.util.*;

/** Conservative 16-cube empty-space connectivity, independent of lighting. */
public final class SectionVisibility {
    public record Position(int x,int y,int z) {}
    public static final class Section {
        public final byte[] solid=new byte[512];
        /** For each entry face, a six-bit set of connected exit faces. */
        public final byte[] exits=new byte[6];
    }
    public final Map<Position,Section> sections=new LinkedHashMap<>();
    public void setSolid(int x,int y,int z) {
        var key=new Position(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16));
        var section=sections.computeIfAbsent(key,ignored->new Section());
        int index=(x&15)|((z&15)<<4)|((y&15)<<8);
        section.solid[index>>3]|=(byte)(1<<(index&7));
    }
    public void compile() {
        int[] queue=new int[4096];var seen=new BitSet(4096);
        for(var section:sections.values()) {
            seen.clear();Arrays.fill(section.exits,(byte)0);
            for(int start=0;start<4096;start++) {
                if(seen.get(start)||solid(section,start))continue;
                int head=0,tail=1,faces=0;queue[0]=start;seen.set(start);
                while(head<tail) {
                    int cell=queue[head++],x=cell&15,z=(cell>>4)&15,y=cell>>8;
                    if(y==0)faces|=1;if(y==15)faces|=2;if(z==0)faces|=4;
                    if(z==15)faces|=8;if(x==0)faces|=16;if(x==15)faces|=32;
                    for(int direction=0;direction<6;direction++) {
                        if(direction==0&&y==0||direction==1&&y==15||direction==2&&z==0
                            ||direction==3&&z==15||direction==4&&x==0||direction==5&&x==15)continue;
                        int next=cell+switch(direction){case 0->-256;case 1->256;case 2->-16;case 3->16;case 4->-1;default->1;};
                        if(!seen.get(next)&&!solid(section,next)){seen.set(next);queue[tail++]=next;}
                    }
                }
                for(int face=0;face<6;face++)if((faces&(1<<face))!=0)section.exits[face]|=(byte)faces;
            }
        }
    }
    private static boolean solid(Section section,int index){return (section.solid[index>>3]&(1<<(index&7)))!=0;}
}
