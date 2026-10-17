'use strict';
/* Pure simulation model, shared by the editor and regression checks. */
(function(root) {
  const key=(x,y)=>`${x},${y}`, LIMIT=192;
  const piston=cell=>cell?.id==='piston'||cell?.id==='sticky_piston';
  const headDefinition={id:'piston_head',name:'活塞头',defaults:{facing:'north',type:'normal',short:'false'},properties:{facing:['north','south','east','west','up','down'],type:['normal','sticky'],short:['false']},facing:['north','south','east','west','up','down'],replaceable:false};
  function pistonOffset(cell,view='side') {
    const facing=cell?.states?.facing;
    return facing==='east'?[1,0]:facing==='west'?[-1,0]:view==='top'?(facing==='north'?[0,-1]:facing==='south'?[0,1]:[0,0]):(facing==='up'?[0,-1]:facing==='down'?[0,1]:[0,0]);
  }
  const inside=(x,y)=>Number.isInteger(x)&&Number.isInteger(y)&&Math.abs(x)<=LIMIT&&Math.abs(y)<=LIMIT;
  class BoardWorld {
    constructor(catalog,view='side') { this.catalog=new Map(catalog.map(b=>[b.id,b]));this.catalog.set(headDefinition.id,headDefinition);this.cells=new Map();this.water=new Map();this.tickCount=0;this.view=view; }
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
    pistonHead(cell) {return piston(cell)&&cell.states?.extended==='true'?this.normalize({id:'piston_head',states:{facing:cell.states.facing,type:cell.id==='sticky_piston'?'sticky':'normal'}}):null;}
    assembly(x,y,cell) {
      const parts=[{x,y,cell}],head=this.pistonHead(cell),[dx,dy]=pistonOffset(cell,this.view);
      if(head&&(dx||dy))parts.push({x:x+dx,y:y+dy,cell:head});
      return parts;
    }
    assemblyAt(x,y) {
      let cell=this.get(x,y);if(!cell)return [];
      if(cell.id==='piston_head'){
        const [dx,dy]=pistonOffset(cell,this.view),base=this.get(x-dx,y-dy),head=this.pistonHead(base);
        if((dx||dy)&&head&&head.states.facing===cell.states.facing&&head.states.type===cell.states.type){x-=dx;y-=dy;cell=base;}
      }
      return this.assembly(x,y,cell);
    }
    planSet(x,y,cell) {
      if(!inside(x,y)||cell?.id==='piston_head')return null;
      const k=key(x,y), old=this.cells.get(k);
      if(cell?.id==='water'&&old&&this.catalog.get(old.id)?.waterlogged&&old.states?.type!=='double')return {remove:new Set(),add:[{x,y,cell:this.normalize({...old,states:{...old.states,waterlogged:'true'}})}]};
      if(cell?.id==='water'&&old&&!this.catalog.get(old.id)?.replaceable)return null;
      const normalized=cell?this.normalize(cell):null;if(cell&&!normalized)return null;
      const remove=new Set(this.assemblyAt(x,y).map(p=>key(p.x,p.y))),add=normalized?this.assembly(x,y,normalized):[];
      if(add.some((p,i)=>!inside(p.x,p.y)||i>0&&this.get(p.x,p.y)&&!remove.has(key(p.x,p.y))))return null;
      return {remove,add};
    }
    apply(plan) {
      if(!plan)return false;
      for(const k of plan.remove)this.cells.delete(k);
      for(const p of plan.add)this.cells.set(key(p.x,p.y),p.cell);
      return true;
    }
    canSet(x,y,cell) {return !!this.planSet(x,y,cell);}
    set(x,y,cell) {return this.apply(this.planSet(x,y,cell));}
    planMove(from,to) {
      if(!inside(to.x,to.y)||from.x===to.x&&from.y===to.y)return null;
      const parts=this.assemblyAt(from.x,from.y);if(!parts.length)return null;
      const remove=new Set(parts.map(p=>key(p.x,p.y))),add=parts.map(p=>({...p,x:p.x+to.x-from.x,y:p.y+to.y-from.y}));
      if(add.some(p=>!inside(p.x,p.y)||this.get(p.x,p.y)&&!remove.has(key(p.x,p.y))))return null;
      return {remove,add};
    }
    canMove(from,to) {return !!this.planMove(from,to);}
    move(from,to) {const moved=this.apply(this.planMove(from,to));if(moved)this.water.clear();return moved;}
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
      for(const entry of data){if(!Array.isArray(entry)||entry.length!==2)throw new Error('画布数据无效');const [k,b]=entry;if(!/^-?\d+,-?\d+$/.test(k)||!this.catalog.has(b?.id)||b.id==='piston_head')continue;const [x,y]=k.split(',').map(Number);if(!inside(x,y))continue;result.set(key(x,y),this.normalize(b));}
      // Rebuild linked heads, including old saves that only contained extended bases.
      for(const [k,cell] of [...result]){
        const head=this.pistonHead(cell),[dx,dy]=pistonOffset(cell,this.view);if(!head||!dx&&!dy)continue;
        const [x,y]=k.split(',').map(Number),hx=x+dx,hy=y+dy;
        if(!inside(hx,hy)||result.has(key(hx,hy)))result.set(k,this.normalize({...cell,states:{...cell.states,extended:'false'}}));
        else result.set(key(hx,hy),head);
      }
      this.cells=result;this.water.clear();this.tickCount=0;
    }
  }
  const api={BoardWorld,key,LIMIT,pistonOffset,headDefinition};if(typeof module!=='undefined')module.exports=api;else root.BoardModel=api;
})(typeof window!=='undefined'?window:globalThis);
