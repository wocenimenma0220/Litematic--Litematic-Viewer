const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('meshTest',{
  open:name=>ipcRenderer.invoke('mesh-test:open',name),
  read:(id,offset,length)=>ipcRenderer.invoke('mesh-test:read',{id,offset,length}),
});
