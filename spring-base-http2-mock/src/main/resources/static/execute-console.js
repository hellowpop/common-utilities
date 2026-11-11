'use strict';
(() => {
  class SseJsonDecoder {
    constructor(receive) { this.receive = receive; this.buffer = ''; }
    push(text) {
      this.buffer += text;
      let boundary;
      while ((boundary = /\r?\n\r?\n/.exec(this.buffer)) !== null) {
        const frame = this.buffer.slice(0, boundary.index);
        this.buffer = this.buffer.slice(boundary.index + boundary[0].length);
        let event = 'message'; const data = [];
        for (const line of frame.split(/\r?\n/)) {
          if (line.startsWith(':')) continue;
          const colon = line.indexOf(':');
          const name = colon === -1 ? line : line.slice(0, colon);
          let value = colon === -1 ? '' : line.slice(colon + 1);
          if (value.startsWith(' ')) value = value.slice(1);
          if (name === 'event') event = value;
          if (name === 'data') data.push(value);
        }
        if (data.length) this.receive(event, JSON.parse(data.join('\n')));
      }
    }
    finish() { if (this.buffer.trim()) throw new Error('완료되지 않은 SSE 이벤트입니다.'); }
  }
  if (typeof module !== 'undefined' && module.exports) module.exports = {SseJsonDecoder};
  if (typeof document === 'undefined') return;
  const $ = id => document.getElementById(id);
  let active = null, edited = false, lines = [];
  const secure = location.protocol === 'https:';
  const example = {
    threadCount: 2, loopCount: 2, endpointUrl: 'http://127.0.0.1:28080/', chunked: [true, false],
    contentType: ['TEXT_PLAIN'], contentEncoding: ['IDENTITY', 'GZIP'], contentLength: [128], blockSize: [16],
    headers: {'x-response-action': 'copy-and-send'}
  };
  $('execution-spec').value = JSON.stringify(example, null, 2);
  $('execution-spec').addEventListener('input', () => { edited = true; });
  $('execution-start').disabled = secure;
  if (secure) $('execution-note').textContent = '/api/*는 HTTP/1.1 전용입니다. 브라우저에서는 상단의 HTTP 콘솔을 열어 실행하세요. HTTPS 직접 호출은 HTTP/1.1 클라이언트를 사용합니다.';
  fetch('/test/services', {cache:'no-store', mode:'same-origin', signal:AbortSignal.timeout(5000)})
    .then(response => response.ok ? response.json() : [])
    .then(services => {
      const netty = Array.isArray(services) && services.find(service => service.engine === 'netty');
      if (!netty || edited) return;
      const url = new URL('http://localhost/');
      let host = ['0.0.0.0', '::'].includes(netty.host) ? location.hostname : netty.host;
      host = host.replace(/^\[|\]$/g, ''); url.hostname = host.includes(':') ? `[${host}]` : host;
      url.port = String(netty.port); example.endpointUrl = url.href;
      $('execution-spec').value = JSON.stringify(example, null, 2);
    }).catch(() => {});

  function status(text, state = '') { $('execution-status').textContent = text; $('execution-status').dataset.state = state; }
  function log(event, value) {
    lines.push(`[${new Date().toLocaleTimeString()}] ${event}\n${JSON.stringify(value, null, 2)}`);
    if (lines.length > 40) lines.shift();
    $('execution-log').textContent = lines.join('\n\n'); $('execution-log').scrollTop = $('execution-log').scrollHeight;
  }
  $('execution-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (active || secure) return;
    let spec;
    try { spec = JSON.parse($('execution-spec').value); }
    catch (error) { status('JSON 입력 오류', 'error'); $('execution-summary').textContent = error.message; return; }
    const controller = new AbortController(); active = controller;
    $('execution-start').disabled = true; $('execution-cancel').disabled = false;
    lines = []; $('execution-log').textContent = ''; $('execution-summary').textContent = '최종 요약을 기다리는 중입니다.';
    $('execution-count').textContent = '0회 완료'; status('실행 요청 중', 'busy');
    let receivedSummary = false, completed = 0;
    const parser = new SseJsonDecoder((name, value) => {
      if (name === 'started') { status(`${value.combinationCount}개 조합 백그라운드 실행 중`, 'busy'); $('execution-count').textContent = `0 / ${value.totalCount}회`; }
      if (name === 'response') { completed++; $('execution-count').textContent = `${completed}회 결과 수신`; }
      if (name === 'summary') {
        receivedSummary = true;
        $('execution-summary').textContent = JSON.stringify(value, null, 2);
        $('execution-count').textContent = `${value.completedCount} / ${value.totalCount}회 완료`;
        status(value.cancelled ? '일부 수행 후 취소됨' : `전체 수행 완료${value.failureCount ? ` · 실패 ${value.failureCount}건` : ''}`,
          value.cancelled || value.failureCount ? 'error' : 'success');
      }
      log(name, value);
    });
    let reader;
    try {
      const response = await fetch('/api/execute-mock-request', {method:'POST', mode:'same-origin', cache:'no-store',
        headers:{'Content-Type':'application/json', Accept:'text/event-stream'}, body:JSON.stringify(spec), signal:controller.signal});
      if (!response.ok) {
        const body = await response.text(); let message = body;
        try { message = JSON.parse(body).error || body; } catch {}
        throw new Error(`${response.status} · ${message.slice(0, 2048)}`);
      }
      if (!response.body) throw new Error('이 브라우저는 응답 스트림을 지원하지 않습니다.');
      reader = response.body.getReader(); const decoder = new TextDecoder('utf-8');
      while (true) {
        const {value, done} = await reader.read();
        if (done) break;
        parser.push(decoder.decode(value, {stream:true}));
      }
      parser.push(decoder.decode()); parser.finish();
      if (!receivedSummary) throw new Error('최종 요약 없이 연결이 종료되었습니다.');
    } catch (error) {
      if (controller.signal.aborted) {
        status('사용자 중지'); $('execution-summary').textContent = '클라이언트 연결을 닫았습니다. 서버가 남은 작업을 취소합니다. 최종 요약은 수신하지 않습니다.';
      } else {
        status('실행 실패', 'error'); $('execution-summary').textContent = error.message;
        controller.abort();
      }
    } finally {
      if (reader) reader.releaseLock();
      active = null; $('execution-start').disabled = secure; $('execution-cancel').disabled = true;
    }
  });
  $('execution-cancel').addEventListener('click', () => { if (active) active.abort(); });
  window.addEventListener('pagehide', () => { if (active) active.abort(); });
})();
