'use strict';
const os=require('node:os');
function memoryPolicy(total=os.totalmem(),free=os.freemem()) {
  const mb=1048576,override=Number(process.env.LITEMATIC_RENDER_HEAP_MB);
  const heapMB=Number.isInteger(override)&&override>=512&&override<=32768?override:
    Math.max(512,Math.min(8192,Math.floor(Math.min(total*.25,free*.6)/mb/256)*256));
  return {heapMB,geometryBudgetMB:Math.max(128,Math.min(512,Math.floor(total/mb/64/64)*64))};
}
module.exports={memoryPolicy};
