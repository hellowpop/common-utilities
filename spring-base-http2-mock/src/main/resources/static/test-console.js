'use strict';

(() => {
  const $ = id => document.getElementById(id);
  const secure = location.protocol === 'https:';
  const activeRequests = new Set();
  let eventSource = null;
  let sseTimer = null;
  let socket = null;
  let socketTimer = null;
  let socketCloseTimer = null;
  let socketCloseCause = null;

  function status(id, text, state = '') {
    $(id).textContent = text;
    $(id).dataset.state = state;
  }
  function pretty(text) {
    try { return JSON.stringify(JSON.parse(text), null, 2); } catch { return text; }
  }
  function append(id, text) {
    const output = $(id);
    output.textContent += `[${new Date().toLocaleTimeString()}] ${text}\n`;
    output.scrollTop = output.scrollHeight;
  }
  function progress(prefix, count) {
    $(`${prefix}-progress`).value = count;
    $(`${prefix}-count`).textContent = `${count} / 10`;
  }
  function protocolFor(url) {
    const timings = performance.getEntriesByName(url, 'resource');
    return timings.length ? timings[timings.length - 1].nextHopProtocol || '브라우저 미제공' : '브라우저 미제공';
  }

  async function send(prefix, path, options = {}) {
    const button = $(`${prefix}-send`);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 15000);
    activeRequests.add(controller);
    button.disabled = true;
    status(`${prefix}-status`, '요청 중', 'busy');
    $(`${prefix}-result`).textContent = '서버 응답을 기다리는 중…';
    const start = performance.now();
    try {
      const response = await fetch(path, {...options, cache: 'no-store', mode: 'same-origin', signal: controller.signal});
      const body = await response.text();
      const protocol = protocolFor(response.url);
      $(`${prefix}-meta`).textContent = `${Math.round(performance.now() - start)} ms · ${protocol}`;
      const headers = [...response.headers].map(([name, value]) => `${name}: ${value}`).join('\n');
      $(`${prefix}-result`).textContent = `${response.status} ${response.statusText}\n${headers}\n\n${pretty(body)}`;
      status(`${prefix}-status`, `${response.status} ${response.ok ? '응답 완료' : '요청 거부'}`, response.ok ? 'success' : 'error');
    } catch (error) {
      status(`${prefix}-status`, '요청 실패', 'error');
      $(`${prefix}-meta`).textContent = `${Math.round(performance.now() - start)} ms`;
      $(`${prefix}-result`).textContent = error.name === 'AbortError'
        ? '요청 시간이 초과되었거나 페이지를 이동했습니다.'
        : `네트워크 오류: ${error.message}\n서버 연결 및 HTTPS 인증서 수락 여부를 확인하세요.`;
    } finally {
      clearTimeout(timer);
      activeRequests.delete(controller);
      button.disabled = prefix === 'h2' && !secure;
    }
  }

  $('current-origin').textContent = location.origin;
  $('rest-origin').textContent = location.origin;
  $('origin-badge').textContent = secure ? '● HTTPS · TLS 연결' : '● HTTP · 평문 연결';
  $('ws-scheme').textContent = secure ? 'WSS' : 'WS';
  $('h2-send').disabled = !secure;
  if (!secure) $('h2-note').textContent = 'HTTP/2는 HTTPS 전용입니다. 상단의 HTTPS 콘솔을 열고 인증서 경고를 수락한 뒤 Ping을 보내세요.';

  async function loadConnections() {
    try {
      const response = await fetch('/test/config', {cache: 'no-store', mode: 'same-origin', signal: AbortSignal.timeout(5000)});
      if (!response.ok) throw new Error(`포트 정보 응답 ${response.status}`);
      const config = await response.json();
      for (const scheme of ['http', 'https']) {
        const port = config[`${scheme}Port`];
        if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('유효하지 않은 커넥터 포트');
        const url = new URL(location.href);
        url.protocol = `${scheme}:`;
        url.port = String(port);
        url.pathname = '/'; url.search = ''; url.hash = '';
        $(`${scheme}-link`).href = url.href;
      }
      $('connection-note').textContent = 'HTTP와 HTTPS는 별도 탭에서 테스트합니다. 현재 페이지와 같은 출처로 호출하므로 CORS·혼합 콘텐츠 문제 없이 결과를 확인할 수 있습니다. HTTPS 최초 접속 시 self-signed 인증서 경고를 수락하세요.';
    } catch (error) {
      $('connection-note').textContent = `연결 주소를 불러오지 못했습니다: ${error.message}. 페이지를 새로 고침하거나 서버 설정의 HTTP/HTTPS 주소로 직접 접속하세요.`;
    }
  }
  loadConnections();

  let serviceBusy = false;
  const serviceButtons = [];
  function setServiceBusy(busy) {
    serviceBusy = busy;
    $('services-refresh').disabled = busy;
    for (const button of serviceButtons) button.disabled = busy || button.dataset.blocked === 'true';
  }
  function renderServices(services) {
    if (!Array.isArray(services)) throw new Error('잘못된 서비스 정보');
    const list = $('services-list');
    list.textContent = '';
    serviceButtons.length = 0;
    for (const service of services) {
      const item = document.createElement('article');
      item.dataset.engine = service.engine;
      const name = document.createElement('strong');
      name.textContent = service.engine === 'netty' ? 'Netty HTTP Mock' : 'HTTP Mock Response';
      const state = document.createElement('span');
      state.className = 'service-state';
      state.dataset.state = service.state;
      state.textContent = service.state;
      item.append(name, state);
      for (const endpoint of service.endpoints) {
        const address = document.createElement('code');
        const host = endpoint.host.includes(':') ? `[${endpoint.host}]` : endpoint.host;
        address.textContent = `${endpoint.name} · ${host}:${endpoint.port || 'OS 자동 배정'}`;
        item.append(address);
      }
      const note = document.createElement('p');
      note.textContent = !service.enabled ? '설정에서 비활성화되어 있습니다.'
        : service.engine === 'response' ? '모든 response 바인딩을 함께 제어합니다.' : '중지 후 시작하면 서버를 새로 생성합니다.';
      item.append(note);
      if (service.error) {
        const error = document.createElement('p'); error.className = 'service-error';
        error.textContent = service.error; item.append(error);
      }
      const actions = document.createElement('div'); actions.className = 'service-actions';
      for (const action of ['start', 'stop']) {
        const button = document.createElement('button');
        button.type = 'button'; button.className = action === 'stop' ? 'secondary' : '';
        button.textContent = action === 'start' ? '시작' : '중지';
        button.dataset.action = action;
        button.setAttribute('aria-label', `${service.engine} ${button.textContent}`);
        const transition = ['STARTING', 'STOPPING'].includes(service.state);
        button.dataset.blocked = String(!service.enabled || transition || (action === 'start' ? service.state === 'RUNNING' : service.state === 'STOPPED'));
        button.disabled = serviceBusy || button.dataset.blocked === 'true';
        button.addEventListener('click', () => controlService(service.engine, action));
        serviceButtons.push(button); actions.append(button);
      }
      item.append(actions); list.append(item);
    }
  }
  async function fetchServiceStates() {
    const response = await fetch('/test/service-engines', {cache: 'no-store', mode: 'same-origin', signal: AbortSignal.timeout(10000)});
    if (!response.ok) throw new Error(`상태 응답 ${response.status}`);
    renderServices(await response.json());
  }
  async function loadServices() {
    if (serviceBusy) return;
    setServiceBusy(true);
    try { await fetchServiceStates(); }
    catch (error) { $('services-message').textContent = `상태 조회 실패: ${error.message}`; }
    finally { setServiceBusy(false); }
  }
  async function controlService(engine, action) {
    if (serviceBusy) return;
    setServiceBusy(true);
    const label = action === 'start' ? '시작' : '중지';
    $('services-message').textContent = `${engine} ${label} 처리 중… 연결과 스레드 정리를 기다립니다.`;
    let outcome;
    try {
      const response = await fetch(`/test/services/${encodeURIComponent(engine)}/${action}`, {
        method: 'POST', mode: 'same-origin', cache: 'no-store', headers: {'Content-Type': 'application/json'}, body: '{}'
      });
      const result = await response.json();
      if (!response.ok) throw new Error(`${response.status} · ${result.message || '서비스 제어 실패'}`);
      renderServices(result);
      outcome = `${engine} ${label} 완료`;
    } catch (error) { outcome = `${engine} ${label} 실패: ${error.message}`; }
    finally {
      try { await fetchServiceStates(); }
      catch (error) { outcome += ` · 상태 재조회 실패: ${error.message}. 새로 고침으로 확인하세요.`; }
      $('services-message').textContent = outcome;
      setServiceBusy(false);
    }
  }
  $('services-refresh').addEventListener('click', loadServices);
  loadServices();

  $('rest-method').addEventListener('change', () => {
    $('rest-body').disabled = $('rest-method').value === 'GET';
  });
  $('rest-form').addEventListener('submit', event => {
    event.preventDefault();
    if ($('rest-send').disabled) return;
    try {
      const values = JSON.parse($('rest-headers').value || '{}');
      if (values === null || Array.isArray(values) || typeof values !== 'object') throw new Error('헤더는 JSON 객체로 입력하세요.');
      const headers = new Headers();
      for (const [name, value] of Object.entries(values)) {
        if (typeof value !== 'string') throw new Error('헤더 값은 문자열이어야 합니다.');
        headers.append(name, value);
      }
      const method = $('rest-method').value;
      const options = {method, headers};
      if (method !== 'GET') options.body = $('rest-body').value;
      const url = new URL('/api/echo', location.origin);
      url.search = $('rest-query').value.trim().replace(/^\?/, '');
      send('rest', url, options);
    } catch (error) {
      status('rest-status', '입력 오류', 'error');
      $('rest-result').textContent = error.message;
    }
  });
  $('h2-send').addEventListener('click', () => {
    if (secure && !$('h2-send').disabled) send('h2', '/http2/ping');
  });
  for (const prefix of ['rest', 'h2']) {
    $(`${prefix}-clear`).addEventListener('click', () => {
      if ($(`${prefix}-send`).disabled && (prefix !== 'h2' || secure)) return;
      $(`${prefix}-result`).textContent = '새 요청을 기다립니다.';
      $(`${prefix}-meta`).textContent = '대기';
      status(`${prefix}-status`, '대기');
    });
  }

  function stopSse(message, state = '') {
    if (eventSource) eventSource.close();
    eventSource = null;
    clearTimeout(sseTimer);
    $('sse-start').disabled = false;
    $('sse-stop').disabled = true;
    status('sse-status', message, state);
  }
  $('sse-start').addEventListener('click', () => {
    if (eventSource) return;
    let count = 0;
    $('sse-result').textContent = '';
    progress('sse', 0);
    $('sse-start').disabled = true;
    $('sse-stop').disabled = false;
    status('sse-status', '연결 중', 'busy');
    const source = new EventSource('/sse/call');
    eventSource = source;
    append('sse-result', `${location.origin}/sse/call 연결 요청`);
    source.onopen = () => {
      if (eventSource !== source) return;
      status('sse-status', '수신 중', 'busy');
      append('sse-result', '연결됨 · time 이벤트 대기');
    };
    source.addEventListener('time', event => {
      if (eventSource !== source) return;
      count++;
      progress('sse', count);
      append('sse-result', `#${count} · id ${event.lastEventId}\n${pretty(event.data)}`);
      if (count >= 10) {
        stopSse('10회 수신 완료', 'success');
        append('sse-result', '완료 · 자동 재연결을 막기 위해 EventSource를 닫았습니다.');
      }
    });
    source.onerror = () => {
      if (eventSource !== source) return;
      stopSse('스트림 오류', 'error');
      append('sse-result', '연결이 끊겼거나 요청이 거부되었습니다. 서버와 인증서를 확인하세요. 자동 재연결하지 않습니다.');
    };
    sseTimer = setTimeout(() => {
      if (eventSource !== source) return;
      stopSse('시간 초과', 'error'); append('sse-result', '15초 수신 제한 시간 초과 · 연결 종료');
    }, 15000);
  });
  $('sse-stop').addEventListener('click', () => {
    stopSse('사용자 중지'); append('sse-result', '사용자가 스트림을 중지했습니다.');
  });

  function resetSocket(connection) {
    if (socket !== connection) return;
    socket = null;
    clearTimeout(socketTimer); clearTimeout(socketCloseTimer);
    $('ws-start').disabled = false;
    $('ws-stop').disabled = true;
  }
  function closeSocket(reason, cause = 'manual') {
    const connection = socket;
    if (!connection) return;
    socketCloseCause = cause;
    clearTimeout(socketTimer);
    $('ws-stop').disabled = true;
    status('ws-status', '종료 중', 'busy');
    append('ws-result', reason);
    try { connection.close(1000, reason); } catch (error) { append('ws-result', error.message); }
    socketCloseTimer = setTimeout(() => {
      if (socket !== connection) return;
      resetSocket(connection);
      status('ws-status', '종료 확인 시간 초과', 'error');
      append('ws-result', '브라우저가 종료 이벤트를 제공하지 않았습니다.');
    }, 2500);
  }
  $('ws-start').addEventListener('click', () => {
    if (socket) return;
    const url = new URL('/ws/call', location.href);
    url.protocol = secure ? 'wss:' : 'ws:';
    let count = 0;
    $('ws-result').textContent = '';
    $('ws-meta').textContent = '서버 종료 대기';
    progress('ws', 0);
    status('ws-status', '연결 중', 'busy');
    let connection;
    try { connection = new WebSocket(url); } catch (error) {
      status('ws-status', '연결 실패', 'error'); append('ws-result', error.message); return;
    }
    socket = connection;
    socketCloseCause = null;
    $('ws-start').disabled = true;
    $('ws-stop').disabled = false;
    append('ws-result', `${url.href} 연결 요청`);
    connection.onopen = () => {
      if (socket !== connection) return;
      status('ws-status', '연결됨', 'busy'); append('ws-result', '연결됨 · 서버 메시지 대기');
    };
    connection.onmessage = event => {
      if (socket !== connection) return;
      progress('ws', ++count); append('ws-result', `#${count}\n${pretty(event.data)}`);
    };
    connection.onerror = () => {
      if (socket !== connection) return;
      status('ws-status', '연결 오류', 'error'); append('ws-result', '전송 오류 · 서버, 인증서 및 동일 Origin 여부를 확인하세요.');
    };
    connection.onclose = event => {
      if (socket !== connection) return;
      resetSocket(connection);
      const normal = event.code === 1000;
      const timedOut = socketCloseCause === 'timeout';
      const manual = socketCloseCause === 'manual';
      const completed = normal && count === 10 && !timedOut && !manual;
      status('ws-status', timedOut ? '시간 초과' : completed ? '10회 수신 완료' : `연결 종료 ${event.code}`,
        timedOut ? 'error' : completed ? 'success' : manual && normal ? '' : 'error');
      $('ws-meta').textContent = `close ${event.code} · ${count}개 수신`;
      append('ws-result', `종료 코드 ${event.code} · clean=${event.wasClean}${event.reason ? ` · ${event.reason}` : ''}`);
    };
    socketTimer = setTimeout(() => {
      if (socket === connection) closeSocket('15초 연결 제한 시간 초과', 'timeout');
    }, 15000);
  });
  $('ws-stop').addEventListener('click', () => closeSocket('사용자가 연결을 종료했습니다.'));
  window.addEventListener('pagehide', () => {
    if (eventSource) stopSse('페이지 이동');
    clearTimeout(socketTimer); clearTimeout(socketCloseTimer);
    if (socket) socket.close(1000, 'page closed');
    for (const controller of activeRequests) controller.abort();
  });
})();
