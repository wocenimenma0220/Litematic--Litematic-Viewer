'use strict';

/** Inclusive block coordinates; render bounds use the far edge of the last block. */
class RenderSelection {
  constructor(){this.enabled=false;this.renderEnabled=false;this.mode='region';this.reset();}
  reset(){this.corners=[null,null];this.active=0;this.layer=0;this.revision=(this.revision||0)+1;}
  setCorner(index,position){
    if(![0,1].includes(index)||!Array.isArray(position)||position.length!==3||!position.every(v=>Number.isSafeInteger(v)&&Math.abs(v)<=30000000))throw Error('角点需要三个 -30000000 到 30000000 之间的整数坐标');
    this.corners[index]=position.slice();this.active=index;this.layer=position[1];this.revision++;
  }
  bounds(world){
    return this.renderEnabled?this.range(world):null;
  }
  range(world){
    if(!world)return null;
    if(this.mode==='layer')return {min:[Math.floor(world.min[0]),this.layer,Math.floor(world.min[2])],max:[Math.ceil(world.max[0]),this.layer+1,Math.ceil(world.max[2])]};
    if(this.corners.some(p=>!p))return null;
    return {min:this.corners[0].map((v,i)=>Math.min(v,this.corners[1][i])),max:this.corners[0].map((v,i)=>Math.max(v,this.corners[1][i])+1)};
  }
  nudge(direction,amount){
    if(!Number.isFinite(amount)||!amount)return;
    amount=Math.sign(amount);
    if(this.mode==='layer'){this.layer+=amount;this.revision++;return;}
    const p=this.corners[this.active];if(!p)throw Error('请先用左右键选择角点');
    if(!Array.isArray(direction)||direction.length!==3||!direction.every(Number.isFinite)||!Math.hypot(...direction))throw Error('请先调整观察方向');
    // Litematica uses horizontal facing until the view tilts at least 60 degrees.
    const axis=Math.abs(direction[1])>=Math.hypot(...direction)*Math.sin(Math.PI/3)?1:Math.abs(direction[0])>Math.abs(direction[2])?0:2,next=p.slice();
    next[axis]+=Math.sign(direction[axis]||1)*amount;this.setCorner(this.active,next);
  }
  command(text){
    if(!this.enabled)throw Error('请先开启“选择渲染工具”');
    const args=text.trim().split(/\s+/);
    if(args[0]!=='//select'||!['1','2'].includes(args[1])||![2,5].includes(args.length))throw Error('用法：//select 1 或 //select 2；也可追加 X Y Z 整数坐标');
    const index=Number(args[1])-1;
    if(args.length===2)return {index};
    const position=args.slice(2).map(v=>/^-?\d+$/.test(v)?Number(v):NaN);this.setCorner(index,position);return {index,position};
  }
}
if(typeof module!=='undefined')module.exports={RenderSelection};
