
'use strict';
(function(root){
  class DebugStick {
    constructor(definitions){this.definitions=definitions;this.selected=new Map();}
    interact(hit,cycle,backward=false){
      const id=hit.id.replace(/^minecraft:/,''),definition=this.definitions[id]||{},keys=Object.keys(definition).filter(key=>definition[key]?.length).sort();
      if(!keys.length)return{changed:false,message:hit.id+' 没有可修改的状态'};
      const remembered=this.selected.get(hit.id),property=keys.includes(remembered)?remembered:undefined;
      if(!cycle){const index=property?keys.indexOf(property):(backward?0:-1),next=keys[(index+(backward?-1:1)+keys.length)%keys.length];this.selected.set(hit.id,next);return{changed:false,property:next,value:hit.states[next],message:'已选择 '+next+' = '+hit.states[next]};}
      const name=property||keys[0],values=definition[name],current=values.indexOf(String(hit.states[name])),index=current<0?(backward?0:-1):current,value=values[(index+(backward?-1:1)+values.length)%values.length];
      return{changed:value!==hit.states[name],property:name,value,states:{...hit.states,[name]:value},message:name+' = '+value};
    }
  }
  function adjacent(hit){if(!hit?.hit)return null;return hit.position.map((value,i)=>value+hit.face[i]);}
  function within(position,bounds){return !bounds||position.every((v,i)=>v>=bounds.min[i]&&v<bounds.max[i]);}
  const vectors={north:[0,0,-1],south:[0,0,1],east:[1,0,0],west:[-1,0,0],up:[0,1,0],down:[0,-1,0]};
  const opposite=name=>({north:'south',south:'north',east:'west',west:'east',up:'down',down:'up'})[name];
  const clockwise=name=>({north:'east',east:'south',south:'west',west:'north'})[name];
  function direction(look,vertical=false){const a=vertical&&Math.abs(look[1])>Math.max(Math.abs(look[0]),Math.abs(look[2]))?1:Math.abs(look[0])>Math.abs(look[2])?0:2;return a===1?(look[1]>0?'up':'down'):a===0?(look[0]>0?'east':'west'):(look[2]>0?'south':'north');}
  function container(id){return /^(minecraft:)?(chest|trapped_chest|ender_chest|barrel|hopper|dispenser|dropper|furnace|blast_furnace|smoker|brewing_stand|crafter|.*shulker_box)$/.test(id);}
  function placement(def,context){
    const states={...def.defaults},face=context.face||[0,1,0],look=context.look||[0,0,-1],id='minecraft:'+def.id,position=context.position;
    const faceName=direction(face,true),horizontal=direction(look),full=direction(look,true),properties=def.properties||{};
    // Rail orientation remains a deliberate debug-stick operation.
    if(!/(^|_)rail$/.test(def.id)){
      if(states.axis!==undefined)states.axis=Math.abs(face[0])?'x':Math.abs(face[2])?'z':'y';
      if(states.facing!==undefined){let facing=opposite(properties.facing?.includes('up')?full:horizontal);if(def.id==='observer'||def.id.endsWith('_stairs')||def.id.endsWith('_door')||def.id.endsWith('_fence_gate')||def.id.endsWith('_bed'))facing=def.id==='observer'?full:horizontal;if(def.id.endsWith('_trapdoor')&&face[1]===0)facing=faceName;if(def.id==='hopper'){facing=opposite(faceName);if(facing==='up')facing='down';}if(properties.facing?.includes(facing))states.facing=facing;}
      if(states.rotation!==undefined)states.rotation=String(Math.round((Math.atan2(-look[0],look[2])*8/Math.PI+(/(head|skull)$/.test(def.id)?0:8)+16)%16));
      if(states.half!==undefined&&['top','bottom'].includes(states.half))states.half=face[1]<0||face[1]===0&&context.hitHeight>.5?'top':'bottom';
      if(states.face!==undefined){states.face=face[1]>0?'floor':face[1]<0?'ceiling':'wall';if(states.face==='wall'&&properties.facing?.includes(faceName))states.facing=faceName;}
    }
    const changes=[],near=new Map((context.neighbors||[]).map(b=>[b.position.join(','),b])),at=(delta)=>near.get(position.map((v,i)=>v+delta[i]).join(','));
    if(['chest','trapped_chest'].includes(def.id)){
      states.type='single';let facing=states.facing,partner=null,type='single';
      const candidate=delta=>{const b=at(delta);return b?.id===id&&(b.states.type||'single')==='single'?b:null;};
      if(context.shift&&face[1]===0){const delta=face.map(v=>-v),b=candidate(delta);if(b){const f=b.states.facing||'north';if((Math.abs(vectors[f][0])>0)!==(Math.abs(face[0])>0)){facing=f;const ccw=opposite(clockwise(f));type=vectors[ccw].every((v,i)=>v===delta[i])?'right':'left';partner=b;}}}
      if(!context.shift){const right=clockwise(facing),left=opposite(right),b=candidate(vectors[right]),c=candidate(vectors[left]);if(b&&b.states.facing===facing){type='left';partner=b;}else if(c&&c.states.facing===facing){type='right';partner=c;}}
      states.facing=facing;states.type=type;if(partner)changes.push({position:partner.position,id,states:{...partner.states,facing,type:type==='left'?'right':'left'}});
    }
    changes.push({position,id,states});return changes;
  }
  function removal(hit,neighbors){
    const changes=[{position:hit.position,id:'minecraft:air',states:{}}];
    if(['minecraft:chest','minecraft:trapped_chest'].includes(hit.id)&&['left','right'].includes(hit.states.type)){
      const d=vectors[hit.states.type==='left'?clockwise(hit.states.facing):opposite(clockwise(hit.states.facing))];
      const p=hit.position.map((v,i)=>v+d[i]),other=neighbors.find(b=>b.position.join(',')===p.join(','));if(other?.id===hit.id)changes.push({position:p,id:other.id,states:{...other.states,type:'single'}});
    }return changes;
  }
  const api={DebugStick,adjacent,within,placement,removal,container,direction};if(typeof module!=='undefined')module.exports=api;else root.ProjectionEditModel=api;
})(typeof window!=='undefined'?window:globalThis);
