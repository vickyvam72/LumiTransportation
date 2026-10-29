// Diagnosa koneksi feed armada dengan identitas Lumi sendiri (tanpa menyamar).
const WebSocket = require("ws");
const URL = "wss://mqtt.tj.co.id:8084/mqtt";
function str(s){const b=Buffer.from(s);return Buffer.concat([Buffer.from([b.length>>8,b.length&255]),b]);}
function rl(n){const o=[];do{let d=n%128;n=Math.floor(n/128);if(n>0)d|=128;o.push(d);}while(n>0);return Buffer.from(o);}
function pk(t,b){return Buffer.concat([Buffer.from([t]),rl(b.length),b]);}
function connect(level, cid, label){
  const proto = level===3?"MQIsdp":"MQTT";
  let vh = Buffer.concat([str(proto),Buffer.from([level,0x02,0,60])]);
  if(level===5) vh=Buffer.concat([vh,Buffer.from([0])]);
  return pk(0x10,Buffer.concat([vh,str(cid)]));
}
function attempt(label, level, cid, sub){ return new Promise(res=>{
  const t0=Date.now(); const log=[];
  const ws=new WebSocket(URL,["mqtt"],{headers:{"User-Agent":"LumiTransportation-diag/2.4"}});
  let bytes=0, msgs=0, topics=new Set();
  const done=(why)=>{try{ws.terminate();}catch(e){} console.log(`[${label}] ${why} | ${log.join(" ; ")} | msgs=${msgs} bytes=${bytes} topics=${[...topics].slice(0,5).join(",")}`); res();};
  ws.on("upgrade",r=>log.push("upgrade "+r.statusCode+" proto="+r.headers["sec-websocket-protocol"]+" server="+(r.headers.server||"")));
  ws.on("unexpected-response",(q,r)=>{log.push("http "+r.statusCode);done("unexpected");});
  ws.on("open",()=>{log.push("open");ws.send(connect(level,cid));});
  ws.on("message",d=>{bytes+=d.length; const t=d[0]>>4;
    if(t===2){log.push("CONNACK "+[...d].map(x=>x.toString(16)).join(" ")); if(d[3]===0||(level===5&&d[3]===0)){ws.send(pk(0x82,Buffer.concat([Buffer.from([0,1]),str(sub),Buffer.from([0])])));}}
    else if(t===9) log.push("SUBACK "+[...d].map(x=>x.toString(16)).join(" "));
    else if(t===3){msgs++; const tl=(d[2]<<8)|d[3]; topics.add(d.slice(4,4+tl).toString().split("/").slice(0,4).join("/"));}
    else log.push("pkt type "+t);
  });
  ws.on("close",(c,r)=>{log.push(`close ${c} "${r}" after ${Date.now()-t0}ms`);done("closed");});
  ws.on("error",e=>log.push("error "+e.message));
  setTimeout(()=>done("timeout 15s"),15000);
});}
(async()=>{
  const id=()=>Math.random().toString(16).slice(2,10);
  await attempt("v4 lumi_ route", 4, "lumi_diag_"+id(), "/mobile_armada/+/9/#");
  await attempt("v4 lumi_ all", 4, "lumi_diag_"+id(), "/mobile_armada/#");
  await attempt("v4 empty-id", 4, "", "/mobile_armada/#");
  await attempt("v3 lumi_", 3, "lumi_"+id(), "/mobile_armada/#");
  await attempt("v5 lumi_", 5, "lumi_diag_"+id(), "/mobile_armada/#");
})();
