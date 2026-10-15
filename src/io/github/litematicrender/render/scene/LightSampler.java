package io.github.litematicrender.render.scene;

/** Shared sampling contract for dense and sparse offline lighting. */
public interface LightSampler {
    void compute();
    int cellLight(int x,int y,int z);
    /** Bound to a mesh section after compute(); implementations may cache its read-only neighbors. */
    default LightSampler forSection(int x,int y,int z){return this;}
    default int faceLight(int x,int y,int z,int dx,int dy,int dz){return cellLight(x+dx,y+dy,z+dz);}
}
