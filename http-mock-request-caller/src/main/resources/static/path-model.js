(function(root){
  'use strict';
  const contentTypes=['TEXT_PLAIN','APPLICATION_JSON','APPLICATION_XML','TEXT_HTML','APPLICATION_FORM_URLENCODED','APPLICATION_OCTET_STREAM','MULTIPART_FORM_DATA'];
  const contentEncodings=['IDENTITY','GZIP','DEFLATE','BR','ZSTD','SNAPPY'];
  const methods=['POST','GET','HEAD','PUT','PATCH','DELETE','OPTIONS'];
  const managedHeaders=new Set(['host','content-length','transfer-encoding','content-type','content-encoding','connection','upgrade','expect']);
  const axes=['chunked','contentType','contentEncoding','contentLength','blockSize'];
  const clone=value=>JSON.parse(JSON.stringify(value));
  function requireValue(ok,message){if(!ok)throw Error(message);}
  function newPath(id){return {id,delay:defaultDelay(),name:'request',requestUrl:null,requestPath:'/',method:'POST',headers:{},payload:{chunked:[false],contentType:['APPLICATION_JSON'],contentEncoding:['IDENTITY'],contentLength:[4096],blockSize:[128]},pathHooks:{pre:'',post:''},requestHooks:{pre:'',post:''},successStatusMin:200,successStatusMax:299};}
  function defaultDelay(){return {mode:'NONE',fixedMillis:0,minMillis:0,maxMillis:0};}
  function normalizeScenario(value){
    const s=clone(value);s.executionMode??='LOOPS';s.durationSeconds??=60;
    if(Array.isArray(s.paths))for(const p of s.paths)if(p&&typeof p==='object')p.delay={...defaultDelay(),...p.delay};return s;
  }
  function validateExecution(s){
    const mode=s.executionMode??'LOOPS';requireValue(['LOOPS','DURATION'].includes(mode),'실행 기준을 확인하세요.');
    if(mode==='DURATION'){
      const duration=s.durationSeconds??60;requireValue(Number.isInteger(duration)&&duration>=1&&duration<=86400,'실행 시간은 1..86400초 정수여야 합니다.');
      requireValue(duration*1000<(s.runTimeoutMillis??900000),'실행 시간은 runTimeoutMillis보다 짧아야 합니다.');
      requireValue((s.rampUpSeconds??0)<duration,'Ramp-up은 실행 시간보다 짧아야 합니다.');
    }else requireValue(Number.isInteger(s.loopCount)&&s.loopCount>=1,'Loop count는 양의 정수여야 합니다.');
  }
  function executionEstimate(s){
    validateExecution(s);if(s.executionMode==='DURATION')return '시간 기준 · '+(s.durationSeconds??60)+'초 · 요청 수 미정';
    const n=s.paths.reduce((sum,p)=>sum+combinationCount(p),0)*s.maxWorkers*s.loopCount;return '예상 '+n.toLocaleString()+' 요청';
  }
  function validateDelay(value){
    const d={...defaultDelay(),...value};requireValue(['NONE','FIXED','RANDOM'].includes(d.mode),'요청 간격 모드를 확인하세요.');
    const valid=n=>Number.isInteger(n)&&n>=0&&n<=600000;
    if(d.mode==='FIXED')requireValue(valid(d.fixedMillis),'고정 간격은 0..600000 ms 정수여야 합니다.');
    if(d.mode==='RANDOM')requireValue(valid(d.minMillis)&&valid(d.maxMillis)&&d.minMillis<=d.maxMillis,'랜덤 간격은 0..600000 ms 정수이며 최소 ≤ 최대여야 합니다.');return d;
  }
  function delaySummary(path){const d=validateDelay(path?.delay);return d.mode==='NONE'?'없음':d.mode==='FIXED'?d.fixedMillis+' ms':d.minMillis+'–'+d.maxMillis+' ms (랜덤)';}
  function runCountSummary(r){return (r.completed??0).toLocaleString()+' / '+(r.executionMode==='DURATION'?'시간 기준':r.planned==null?'미정':r.planned.toLocaleString());}
  function mergeSelections(original,selected){
    const values=Array.isArray(original)?original:[];
    return [...values.filter(v=>selected.includes(v)),...selected.filter(v=>!values.includes(v))];
  }
  function parseNumbers(text,allowZero){
    requireValue(typeof text==='string'&&text.trim().length>0,'길이와 block size 배열에 숫자를 입력하세요.');
    const values=text.trim().split(',').flatMap(part=>{requireValue(part.trim().length>0,'쉼표 사이에 숫자를 입력하세요.');return part.trim().split(/\s+/);}).map(Number);
    requireValue(values.every(n=>Number.isSafeInteger(n)&&n>=(allowZero?0:1)&&n<=67108864),'크기는 '+(allowZero?'0':'1')+'..67108864 범위의 정수로 입력하세요.');return values;
  }
  function parseHeaders(text){
    const entries=new Map(),names=new Map();
    for(const line of text.split('\n')){
      if(!line.trim())continue;
      const colon=line.indexOf(':');requireValue(colon>0,'헤더는 이름: 값 형식으로 한 줄씩 입력하세요.');
      const name=line.slice(0,colon).trim(),value=line.slice(colon+1).trim(),lower=name.toLowerCase();
      requireValue(/^[!#$%&'*+.^_`|~0-9a-zA-Z-]+$/.test(name)&&!managedHeaders.has(lower),'직접 지정할 수 없는 헤더: '+name);
      requireValue(!/[\r\n\0]/.test(value),'헤더 값에 제어 문자를 사용할 수 없습니다.');
      const key=names.get(lower)||name;names.set(lower,key);if(!entries.has(key))entries.set(key,[]);entries.get(key).push(value);
    }
    requireValue(entries.size<=64,'추가 헤더는 최대 64개입니다.');
    let bytes=0;for(const [name,values]of entries)for(const value of values)bytes+=name.length+value.length;
    requireValue(bytes<=32768,'헤더 전체 크기는 32KiB 이하여야 합니다.');return Object.fromEntries(entries);
  }
  function formatHeaders(headers){
    requireValue(headers&&typeof headers==='object'&&!Array.isArray(headers),'headers는 객체여야 합니다.');
    return Object.entries(headers).flatMap(([name,values])=>{requireValue(Array.isArray(values)&&values.every(v=>typeof v==='string'),'헤더 값은 문자열 배열이어야 합니다.');return values.map(v=>name+': '+v);}).join('\n');
  }
  function combinationCount(path){
    let n=1;for(const key of axes){const values=path?.payload?.[key];requireValue(Array.isArray(values)&&values.length>0&&values.every(v=>v!==null),'payload의 다섯 배열은 비어 있을 수 없습니다.');n*=values.length;requireValue(Number.isSafeInteger(n)&&n<=100000,'path 조합 수는 100000개 이하여야 합니다.');}return n;
  }
  function validateBase(value){
    requireValue(!/[\s\\]/.test(value),'URL에 공백이나 역슬래시를 사용할 수 없습니다.');
    let url;try{url=new URL(value);}catch{throw Error('올바른 HTTP/HTTPS base URL을 입력하세요.');}
    requireValue(['http:','https:'].includes(url.protocol)&&url.hostname&&!url.username&&!url.password&&!url.search&&!url.hash&&!/[?#]/.test(value)&&url.port!=='0','base URL은 HTTP/HTTPS 주소이며 query, fragment, 사용자 정보를 포함할 수 없습니다.');
  }
  function validatePath(p){
    requireValue(p&&typeof p==='object'&&!Array.isArray(p),'path 설정은 객체여야 합니다.');
    requireValue(typeof p.id==='string'&&p.id.length>0,'path ID가 필요합니다.');
    requireValue(typeof p.name==='string'&&p.name.length<=200,'path 이름은 200자 이하여야 합니다.');
    requireValue(methods.includes(p.method),'지원하지 않는 HTTP 메서드입니다.');
    if(p.requestUrl!==null&&p.requestUrl!==undefined&&p.requestUrl!=='')validateBase(p.requestUrl);
    requireValue(typeof p.requestPath==='string'&&(p.requestPath===''||p.requestPath.startsWith('/'))&&!p.requestPath.startsWith('//')&&!/[\s\\#\0]/.test(p.requestPath),'요청 path는 /로 시작하며 공백·authority·fragment를 포함할 수 없습니다.');
    let decoded;try{decoded=decodeURIComponent(p.requestPath.split('?')[0]);decodeURIComponent(p.requestPath);}catch{throw Error('path의 percent encoding을 확인하세요.');}
    requireValue(!decoded.split('/').some(v=>v==='.'||v==='..'),'path에 . 또는 .. 세그먼트를 사용할 수 없습니다.');
    requireValue(Number.isInteger(p.successStatusMin)&&Number.isInteger(p.successStatusMax)&&p.successStatusMin>=100&&p.successStatusMax<=599&&p.successStatusMin<=p.successStatusMax,'성공 상태 코드 범위는 100..599에서 시작 ≤ 종료여야 합니다.');
    validateDelay(p.delay);parseHeaders(formatHeaders(p.headers));combinationCount(p);
    const payload=p.payload;
    requireValue(payload.chunked.every(v=>typeof v==='boolean')&&payload.contentType.every(v=>contentTypes.includes(v))&&payload.contentEncoding.every(v=>contentEncodings.includes(v)),'payload 타입·인코딩·chunked 값을 확인하세요.');
    requireValue(payload.contentLength.every(v=>Number.isSafeInteger(v)&&v>=0&&v<=67108864)&&payload.blockSize.every(v=>Number.isSafeInteger(v)&&v>0&&v<=67108864),'payload 길이/block size는 최대 64MiB 범위의 정수여야 합니다.');
    const minimum=Math.max(...payload.contentType.map(v=>({APPLICATION_JSON:2,APPLICATION_XML:13,TEXT_HTML:26,MULTIPART_FORM_DATA:233})[v]||1));
    requireValue(payload.contentLength.every(v=>v===0||v>=minimum),'선택한 Content-Type의 최소 양수 길이는 '+minimum+' bytes입니다.');
    if(['GET','HEAD'].includes(p.method))requireValue(payload.contentLength.every(v=>v===0)&&payload.chunked.every(v=>v===false)&&payload.contentEncoding.every(v=>v==='IDENTITY'),'GET/HEAD는 길이 0, IDENTITY, fixed-length만 사용할 수 있습니다.');
    for(const hooks of [p.pathHooks,p.requestHooks])requireValue(hooks&&typeof hooks.pre==='string'&&typeof hooks.post==='string'&&hooks.pre.length<=32768&&hooks.post.length<=32768,'각 pre/post script는 32768자 이하여야 합니다.');
    return p;
  }
  function upsertPath(scenario,index,path){
    requireValue(Array.isArray(scenario.paths),'시나리오 paths 배열이 필요합니다.');validatePath(path);
    const result=clone(scenario);
    if(index===null){requireValue(result.paths.length<100,'path는 최대 100개입니다.');requireValue(!result.paths.some(p=>p?.id===path.id),'중복 path ID입니다.');result.paths.push(clone(path));}
    else{requireValue(Number.isInteger(index)&&index>=0&&index<result.paths.length,'편집할 path가 없습니다.');requireValue(result.paths[index]?.id==null||result.paths[index].id===path.id,'편집 중 path ID가 변경되었습니다.');requireValue(!result.paths.some((p,i)=>i!==index&&p?.id===path.id),'중복 path ID입니다.');result.paths[index]=clone(path);}
    return result;
  }
  function copyPath(path,id){const result=clone(path);result.id=id;result.name=(String(path.name||'request').slice(0,195))+' (복사)';return result;}
  const api={copyPath,defaultDelay,normalizeScenario,validateExecution,executionEstimate,validateDelay,delaySummary,runCountSummary,contentTypes,contentEncodings,methods,newPath,mergeSelections,parseNumbers,parseHeaders,formatHeaders,validatePath,upsertPath,combinationCount};
  if(typeof module==='object'&&module.exports)module.exports=api;else root.CallerPathModel=api;
})(typeof globalThis==='object'?globalThis:this);
