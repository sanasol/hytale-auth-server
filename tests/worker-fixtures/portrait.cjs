const {parentPort}=require('worker_threads');
let active=0;
parentPort.on('message',({id,skin})=>{
 if(skin.exit)process.exit(2);
 if(skin.hang)return;
 const concurrent=++active;
 setTimeout(()=>{active--;parentPort.postMessage({id,buffer:Buffer.from(String(skin.value)),timings:{concurrent},error:skin.fail?'fixture failure':undefined});},skin.delay||0);
});
