'use strict';
(function(root){
  class BoardAudio {
    constructor(manifest,{AudioClass=root.Audio,random=Math.random,onError=()=>{}}={}){this.manifest=manifest;this.AudioClass=AudioClass;this.random=random;this.onError=onError;this.playing=new Set();this.failed=new Set();}
    play(id){
      const sound=this.manifest[id];if(!sound?.samples.length)return false;
      let roll=this.random()*sound.samples.reduce((sum,s)=>sum+(s.weight||1),0),sample=sound.samples.at(-1);
      for(const entry of sound.samples){roll-=entry.weight||1;if(roll<0){sample=entry;break;}}
      while(this.playing.size>=16){const oldest=this.playing.values().next().value;oldest.pause();this.playing.delete(oldest);}
      const audio=new this.AudioClass(sample.file);audio.volume=Math.max(0,Math.min(1,sound.volume*sample.volume));audio.playbackRate=Math.max(.25,Math.min(4,sound.pitch*sample.pitch));audio.preservesPitch=false;this.playing.add(audio);
      const done=()=>this.playing.delete(audio),fail=()=>{done();if(!this.failed.has(sample.file)){this.failed.add(sample.file);this.onError(sample.file);}};
      audio.addEventListener('ended',done,{once:true});audio.addEventListener('error',fail,{once:true});
      try{const result=audio.play();result?.catch(fail);}catch{fail();}
      return true;
    }
  }
  if(typeof module!=='undefined')module.exports={BoardAudio};else root.BoardAudio=BoardAudio;
})(typeof window!=='undefined'?window:globalThis);
