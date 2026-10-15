package io.github.litematicrender.render.scene;

/** Flat dense address space backed by lazy uniform/nibble pages; no full byte channels. */
final class LightGrid {
    final int length;
    private final LightPage[] pages;
    LightGrid(int length){this.length=length;pages=new LightPage[(int)(((long)length+4095)/4096)];}
    int get(int index){LightPage page=pages[index>>>12];return page==null?0:page.get(index&4095);}
    void set(int index,int value){int slot=index>>>12;LightPage page=pages[slot];if(page==null){if(value==0)return;pages[slot]=page=new LightPage();}page.set(index&4095,value);}
    void compact(){for(LightPage page:pages)if(page!=null)page.compact();}
    void fill(int value){for(int i=0;i<pages.length;i++){if(value==0){pages[i]=null;continue;}if(pages[i]==null)pages[i]=new LightPage();pages[i].fill(value);}}
    long payloadBytes(){long bytes=0;for(LightPage page:pages)if(page!=null)bytes+=page.payloadBytes();return bytes;}
}
