const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

// Isolate connection lifecycle tests from real time and the browser's WebSocket transport.
function consoleHarness() {
  const nodes = new Map();
  const timers = new Map();
  let nextTimer = 0;
  const harness = {nodes, timers};
  function node(id) {
    if (!nodes.has(id)) nodes.set(id, {
      textContent: '', value: '', dataset: {}, disabled: false,
      addEventListener(name, handler) { this[name] = handler; }
    });
    return nodes.get(id);
  }
  class Socket {
    constructor() { harness.socket = this; }
    close(code, reason) { this.closeRequest = {code, reason}; }
  }
  class Source {
    constructor() { harness.source = this; }
    addEventListener(name, handler) { this[name] = handler; }
    close() { this.closed = true; }
  }
  const context = vm.createContext({
    document: {getElementById: node},
    location: new URL('http://localhost:8080/'),
    window: {addEventListener() {}},
    fetch: async () => ({ok: true, json: async () => ({httpPort: 8080, httpsPort: 8443})}),
    URL, Headers, AbortSignal, AbortController, WebSocket: Socket, EventSource: Source,
    performance: {now: () => 0, getEntriesByName: () => []},
    setTimeout(callback, delay) { timers.set(++nextTimer, {callback, delay}); return nextTimer; },
    clearTimeout(id) { timers.delete(id); }
  });
  vm.runInContext(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/test-console.js'), 'utf8'), context);
  harness.click = id => node(id).click();
  harness.status = () => node('ws-status');
  harness.serverClose = () => harness.socket.onclose({code: 1000, wasClean: true, reason: ''});
  return harness;
}

test('WebSocket timeout stays an error even when the closing handshake returns 1000', () => {
  const page = consoleHarness();
  page.click('ws-start');
  [...page.timers.values()].find(timer => timer.delay === 15000).callback();
  page.serverClose();
  assert.equal(page.status().dataset.state, 'error');
  assert.equal(page.status().textContent, '시간 초과');
});

test('manual close is neutral and cancels the receive timeout', () => {
  const page = consoleHarness();
  page.click('ws-start'); page.click('ws-stop'); page.serverClose();
  assert.equal(page.status().dataset.state, '');
  assert.equal(page.timers.size, 0);
  assert.equal(page.nodes.get('ws-start').disabled, false);
});

test('normal server close succeeds only after ten messages', () => {
  for (const count of [1, 10]) {
    const page = consoleHarness(); page.click('ws-start');
    for (let i = 1; i <= count; i++) page.socket.onmessage({data: JSON.stringify({sequence: i})});
    page.serverClose();
    assert.equal(page.status().dataset.state, count === 10 ? 'success' : 'error');
  }
});

test('SSE closes after ten events and ignores subsequent errors without reconnecting', () => {
  const page = consoleHarness(); page.click('sse-start');
  for (let i = 1; i <= 10; i++) page.source.time({data: JSON.stringify({sequence: i}), lastEventId: String(i)});
  page.source.onerror();
  assert.equal(page.source.closed, true);
  assert.equal(page.nodes.get('sse-status').textContent, '10회 수신 완료');
  assert.equal(page.nodes.get('sse-start').disabled, false);
  assert.equal(page.timers.size, 0);
});
