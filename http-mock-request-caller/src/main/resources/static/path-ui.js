(function(root){
  'use strict';
  function create({getScenario,setScenario,onError}){
    const model=root.CallerPathModel,$=id=>document.getElementById(id),dialog=$('path-dialog');
    let currentIndex=null,draft=null;
    const choices=[['path-types','contentType',model.contentTypes],['path-encodings','contentEncoding',model.contentEncodings],['path-framing','chunked',[false,true]]];
    for(const [id,key,values]of choices){
      for(const value of values){const label=document.createElement('label'),input=document.createElement('input'),text=document.createElement('span');
        input.type='checkbox';input.name=key;input.value=String(value);text.textContent=key==='chunked'?(value?'chunked':'fixed-length'):value;label.append(input,text);$(id).append(label);}
    }
    for(const method of model.methods){const option=document.createElement('option');option.value=method;option.textContent=method;$('path-method').append(option);}
    function error(message){$('path-error').textContent=message;$('path-error').hidden=!message;}
    function selected(id,boolean){return [...$(id).querySelectorAll('input:checked')].map(input=>boolean?input.value==='true':input.value);}
    function delayFields(){const mode=$('path-delay-mode').value;for(const [id,active] of [['path-delay-fixed',mode==='FIXED'],['path-delay-min',mode==='RANDOM'],['path-delay-max',mode==='RANDOM']])$(id).disabled=!active;}
    function readForm(){
      const path=JSON.parse(JSON.stringify(draft));
      path.name=$('path-name').value;path.method=$('path-method').value;path.requestPath=$('path-request-path').value;path.requestUrl=$('path-url').value.trim()||null;
      path.delay={mode:$('path-delay-mode').value,fixedMillis:Number($('path-delay-fixed').value),minMillis:Number($('path-delay-min').value),maxMillis:Number($('path-delay-max').value)};
      path.headers=model.parseHeaders($('path-headers').value);path.successStatusMin=Number($('path-status-min').value);path.successStatusMax=Number($('path-status-max').value);
      path.payload={...path.payload,chunked:model.mergeSelections(draft.payload.chunked,selected('path-framing',true)),contentType:model.mergeSelections(draft.payload.contentType,selected('path-types',false)),contentEncoding:model.mergeSelections(draft.payload.contentEncoding,selected('path-encodings',false)),contentLength:model.parseNumbers($('path-lengths').value,true),blockSize:model.parseNumbers($('path-blocks').value,false)};
      path.pathHooks={pre:$('path-pre').value,post:$('path-post').value};path.requestHooks={pre:$('path-request-pre').value,post:$('path-request-post').value};return path;
    }
    function updateCount(){try{$('path-combinations').textContent=model.combinationCount(readForm()).toLocaleString()+'개 조합 / worker / loop';}catch{$('path-combinations').textContent='payload 배열 입력을 확인하세요.';}}
    function open(index,copied){
      try{
        const scenario=getScenario();
        if(index===null&&scenario.paths.length>=100)throw Error('path는 최대 100개입니다.');
        if(index===null){draft=copied||model.newPath(crypto.randomUUID());if(!copied)draft.name='request '+(scenario.paths.length+1);}
        else{const saved=scenario.paths[index];if(!saved||typeof saved!=='object'||Array.isArray(saved))throw Error('전문보기에서 잘못된 path 설정을 수정하세요.');draft=JSON.parse(JSON.stringify(saved));
          const defaults=model.newPath(draft.id||crypto.randomUUID());draft={...defaults,...draft,id:draft.id||defaults.id,delay:{...defaults.delay,...draft.delay},payload:{...defaults.payload,...draft.payload},pathHooks:{...defaults.pathHooks,...draft.pathHooks},requestHooks:{...defaults.requestHooks,...draft.requestHooks}};
        }
        currentIndex=index;$('path-form').reset();error('');$('path-dialog-title').textContent=index===null?'Path 추가':'Path 상세 · 편집';$('path-apply').textContent=index===null?'추가':'적용';$('path-record-id').textContent=index===null?'새 레코드':draft.id;
        $('path-copy').hidden=index===null;
        $('path-name').value=draft.name;$('path-method').value=draft.method;$('path-request-path').value=draft.requestPath;$('path-url').value=draft.requestUrl||'';
        $('path-url').placeholder=scenario.endpointBaseUrl+' (기본 endpoint 사용)';$('path-headers').value=model.formatHeaders(draft.headers);
        $('path-delay-mode').value=draft.delay.mode;$('path-delay-fixed').value=draft.delay.fixedMillis;$('path-delay-min').value=draft.delay.minMillis;$('path-delay-max').value=draft.delay.maxMillis;delayFields();
        $('path-status-min').value=draft.successStatusMin;$('path-status-max').value=draft.successStatusMax;
        $('path-lengths').value=Array.isArray(draft.payload.contentLength)?draft.payload.contentLength.join(', '):'';$('path-blocks').value=Array.isArray(draft.payload.blockSize)?draft.payload.blockSize.join(', '):'';
        for(const [id,key]of choices)for(const input of $(id).querySelectorAll('input'))input.checked=Array.isArray(draft.payload[key])&&draft.payload[key].includes(key==='chunked'?input.value==='true':input.value);
        $('path-pre').value=draft.pathHooks.pre;$('path-post').value=draft.pathHooks.post;$('path-request-pre').value=draft.requestHooks.pre;$('path-request-post').value=draft.requestHooks.post;
        $('path-hook-section').open=!!(draft.pathHooks.pre||draft.pathHooks.post||draft.requestHooks.pre||draft.requestHooks.post);
        updateCount();dialog.showModal();$('path-form-body').scrollTop=0;$('path-name').focus();
      }catch(failure){onError(failure.message);}
    }
    function render(scenario){
      const rows=$('path-rows');rows.replaceChildren();const paths=Array.isArray(scenario.paths)?scenario.paths:[];
      $('path-count').textContent=paths.length+' / 100';$('path-add').disabled=paths.length>=100;
      if(!paths.length){const row=document.createElement('tr'),cell=document.createElement('td');cell.colSpan=8;cell.className='path-empty';cell.textContent='등록된 path가 없습니다. 하단의 추가 버튼으로 요청을 등록하세요.';row.append(cell);rows.append(row);return;}
      paths.forEach((path,index)=>{
        const p=path||{},row=document.createElement('tr');row.className='clickable';
        const cell=text=>{const td=document.createElement('td');td.textContent=text;row.append(td);return td;};
        cell(String(index+1));cell(p.name||'(이름 없음)');const method=cell('');const badge=document.createElement('span');badge.className='method-badge';badge.textContent=p.method||'—';method.append(badge);
        const target=cell(p.requestUrl||'기본 endpoint');if(!p.requestUrl){const base=document.createElement('small');base.textContent=scenario.endpointBaseUrl||'';target.append(base);}
        cell(p.requestPath??'—');try{cell(model.combinationCount(p).toLocaleString());}catch{cell('설정 확인');}
        try{cell(model.delaySummary(p));}catch{cell('설정 확인');}
        const action=cell(''),button=document.createElement('button');button.type='button';button.className='subtle';button.textContent='조회 / 편집';button.setAttribute('aria-label',(p.name||'path '+(index+1))+' 조회 / 편집');button.addEventListener('click',event=>{event.stopPropagation();open(index);});action.append(button);
        row.addEventListener('click',()=>open(index));rows.append(row);
      });
    }
    $('path-delay-mode').addEventListener('change',delayFields);
    $('path-copy').addEventListener('click',()=>{try{if(getScenario().paths.length>=100)throw Error('path는 최대 100개입니다.');const copied=model.copyPath(readForm(),crypto.randomUUID());model.validatePath(copied);open(null,copied);}catch(failure){error(failure.message);}});
    $('path-add').addEventListener('click',()=>open(null));$('path-close').addEventListener('click',()=>dialog.close());$('path-cancel').addEventListener('click',()=>dialog.close());
    $('path-form').addEventListener('input',()=>{error('');updateCount();});$('path-form').addEventListener('change',updateCount);
    $('path-form').addEventListener('submit',event=>{
      event.preventDefault();try{const updated=model.upsertPath(getScenario(),currentIndex,readForm());setScenario(updated);dialog.close();}catch(failure){error(failure.message);}
    });
    return {render};
  }
  root.CallerPathUI={create};
})(globalThis);
