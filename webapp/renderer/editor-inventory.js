'use strict';
(() => {
  const SLOT_COUNT=36,HOTBAR_START=27,HOTBAR_SIZE=9;
  function cleanInventory(input){
    // The old catalog pages were automatically filled; start the new personal backpack empty.
    const current=input?.version===2;
    const result={version:2,slot:current&&Number.isInteger(input.slot)&&input.slot>=0&&input.slot<HOTBAR_SIZE?input.slot:0,items:Array(SLOT_COUNT).fill(null)};
    if(current&&Array.isArray(input.items))for(let index=0;index<SLOT_COUNT;index++){
      const id=input.items[index];if(typeof id==='string'&&/^[a-z0-9_]+$/.test(id)&&id.length<=128)result.items[index]=id;
    }
    return result;
  }
  class EditorInventory{
    constructor(ids,saved){this.ids=new Set(ids);this.restore(saved);}
    restore(saved){
      this.state=cleanInventory(saved);
      this.state.items=this.state.items.map(id=>this.ids.has(id)?id:null);
    }
    get slot(){return this.state.slot;}
    get selected(){return this.get(HOTBAR_START+this.slot);}
    get(index){return this.state.items[index]??null;}
    set(index,id){
      if(!Number.isInteger(index)||index<0||index>=SLOT_COUNT||id!==null&&!this.ids.has(id))throw Error('未知的背包物品或槽位');
      this.state.items[index]=id;
    }
    select(slot){if(!Number.isInteger(slot)||slot<0||slot>=HOTBAR_SIZE)throw Error('未知的快捷栏槽位');this.state.slot=slot;}
    cycle(direction){this.select((this.slot+direction%HOTBAR_SIZE+HOTBAR_SIZE)%HOTBAR_SIZE);}
    equip(id){this.set(HOTBAR_START+this.slot,id);}
    move(from,to){
      if(![from,to].every(index=>Number.isInteger(index)&&index>=0&&index<SLOT_COUNT))throw Error('未知的背包槽位');
      const item=this.get(from);this.set(from,this.get(to));this.set(to,item);
    }
    swap(index){if(index<0||index>=HOTBAR_START||!Number.isInteger(index))throw Error('未知的背包槽位');this.move(index,HOTBAR_START+this.slot);}
    snapshot(){return cleanInventory(this.state);}
  }
  const api={EditorInventory,cleanInventory,SLOT_COUNT,HOTBAR_START,HOTBAR_SIZE};
  if(typeof module!=='undefined')module.exports=api;else window.EditorInventoryModel=api;
})();
