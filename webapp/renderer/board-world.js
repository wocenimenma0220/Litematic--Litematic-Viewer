'use strict';
/* Pure simulation model, shared by the editor and regression checks. */
(function(root) {
  const key=(x,y)=>`${x},${y}`, LIMIT=192;
  class BoardWorld {
    constructor(catalog,view='side') { this.catalog=new Map(catalog.map(b=>[b.id,b]));this.cells=new Map();this.water=new Map();this.tickCount=0;this.view=view; }
    normalize(cell) {
      const def=this.catalog.get(cell?.id);if(!def)return null;
      const states={...def.defaults};
      for(const [name,value] of Object.entries(cell.states||{}))if(def.properties?.[name]?.includes(String(value)))states[name]=String(value);
      // Read previously saved boards without losing their chosen states.
      if(!cell.states){if(def.facing.includes(cell.facing))states[def.axis?'axis':'facing']=cell.facing;if(def.waterlogged)states.waterlogged=String(!!cell.waterlogged);if(def.activation&&cell.active!==undefined)states[def.activation]=def.activation==='enabled'?String(!cell.active):def.activation==='sculk_sensor_phase'?(cell.active?'active':'inactive'):def.activation==='power'?(cell.active?'15':'0'):String(!!cell.active);}
      if(cell.id.endsWith('_slab')&&states.type==='double')states.waterlogged='false';
      return {id:cell.id,states,facing:states.facing||states.axis,waterlogged:states.waterlogged==='true',active:def.activation==='enabled'?states.enabled==='false':def.activation==='power'?Number(states.power)>0:def.activation==='sculk_sensor_phase'?states.sculk_sensor_phase==='active':states[def.activation]==='true'};
    }
    get(x,y) { return this.cells.get(key(x,y)); }
    set(x,y,cell) {
      if(Math.abs(x)>LIMIT||Math.abs(y)>LIMIT)return;
      const k=key(x,y), old=this.cells.get(k);
      if(cell?.id==='water'&&old&&this.catalog.get(old.id)?.waterlogged&&old.states?.type!=='double'){this.cells.set(k,this.normalize({...old,states:{...old.states,waterlogged:'true'}}));return;}
      if(cell?.id==='water'&&old&&!this.catalog.get(old.id)?.replaceable)return;
      if(cell){const normalized=this.normalize(cell);if(normalized)this.cells.set(k,normalized);}else this.cells.delete(k);
    }
    move(from,to) {
      if(Math.abs(to.x)>LIMIT||Math.abs(to.y)>LIMIT)return false;
      const a=key(from.x,from.y),b=key(to.x,to.y);if(a===b||!this.cells.has(a)||this.cells.has(b))return false;
      this.cells.set(b,this.cells.get(a));this.cells.delete(a);this.water.clear();return true;
    }
    passable(x,y) {
      if(Math.abs(x)>LIMIT||Math.abs(y)>LIMIT)return false;
      const b=this.get(x,y);return !b||b.id==='water'||this.catalog.get(b.id)?.replaceable||b.waterlogged;
    }
    source(x,y) { const b=this.get(x,y);return b?.id==='water'||b?.waterlogged; }
    tick() {
      // Synchronous updates: no update-order bias; only sources persist without supply.
      const candidates=new Set(this.water.keys());
      for(const [k,b] of this.cells)if(b.id==='water'||b.waterlogged)candidates.add(k);
      const directions=this.view==='top'?[[0,-1],[0,1],[-1,0],[1,0]]:[[0,1],[-1,0],[1,0]];
      for(const k of [...candidates]) {const [x,y]=k.split(',').map(Number);for(const [dx,dy] of directions)if(Math.abs(x+dx)<=LIMIT&&Math.abs(y+dy)<=LIMIT)candidates.add(key(x+dx,y+dy));}
      const next=new Map();
      const at=(x,y)=>this.source(x,y)?{level:0,falling:false}:this.water.get(key(x,y));
      for(const k of candidates){
        const [x,y]=k.split(',').map(Number);
        if(!this.passable(x,y))continue;
        if(this.source(x,y)){next.set(k,{level:0,falling:false});continue;}
        const above=this.view==='side'?at(x,y-1):null;
        if(above){next.set(k,{level:0,falling:true});continue;}
        let level=8;
        for(const [dx,dy] of (this.view==='top'?directions:[[-1,0],[1,0]])){
          const neighbor=at(x+dx,y+dy);
          // Prefer falling; horizontal distance resets after a fall reaches a supporting surface.
          if(neighbor&&(this.view==='top'||!this.passable(x+dx,y+1)))level=Math.min(level,neighbor.level+1);
        }
        if(level<=7)next.set(k,{level,falling:false});
      }
      // Water breaks washable components and waterlogs compatible blocks.
      for(const [k] of next){const b=this.cells.get(k);if(!b||b.id==='water'||b.waterlogged)continue;const def=this.catalog.get(b.id);if(def?.replaceable)this.cells.delete(k);}
      // Contact with lava follows source/flow distinction (lava is a static palette block).
      for(const [k,b] of [...this.cells])if(b.id==='lava'){
        const [x,y]=k.split(',').map(Number);
        if([[0,-1],[0,1],[-1,0],[1,0]].some(([dx,dy])=>next.has(key(x+dx,y+dy))))this.cells.set(k,{id:'obsidian'});
      }
      this.water=next;this.tickCount++;
    }
    snapshot() {return [...this.cells].map(([k,v])=>[k,{...v,states:{...v.states}}]);}
    restore(data) {
      if(!Array.isArray(data)||data.length>150000)throw new Error('画布数据无效');
      const result=new Map();
      for(const entry of data){if(!Array.isArray(entry)||entry.length!==2)throw new Error('画布数据无效');const [k,b]=entry;if(!/^-?\d+,-?\d+$/.test(k)||!this.catalog.has(b?.id))continue;const [x,y]=k.split(',').map(Number);if(Math.abs(x)>LIMIT||Math.abs(y)>LIMIT)continue;result.set(k,this.normalize(b));}
      this.cells=result;this.water.clear();this.tickCount=0;
    }
  }
  const api={BoardWorld,key,LIMIT};if(typeof module!=='undefined')module.exports=api;else root.BoardModel=api;
})(typeof window!=='undefined'?window:globalThis);
