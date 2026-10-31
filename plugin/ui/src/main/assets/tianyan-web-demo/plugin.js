'use strict';
const en = !navigator.language.toLowerCase().startsWith('zh');
const button = document.getElementById('read');
const output = document.getElementById('result');
if (en) {
  document.documentElement.lang = 'en';
  document.getElementById('title').textContent = 'Connect to app capabilities';
  document.getElementById('description').textContent = 'Request basic information after confirming in the app.';
  button.textContent = 'Read app information';
  output.textContent = 'Ready';
}
let pending = null;
let timer = null;
if (window.tianyanPlugin) {
  window.tianyanPlugin.onmessage = event => {
    let reply;
    try { reply = JSON.parse(event.data); } catch { return; }
    if (reply.id !== pending) return;
    clearTimeout(timer);
    pending = null;
    button.disabled = false;
    if (reply.success) {
      try { output.textContent = JSON.stringify(JSON.parse(reply.text), null, 2); }
      catch { output.textContent = reply.text; }
    } else output.textContent = (en ? 'Request denied: ' : '请求未完成：') + reply.text;
  };
}
button.addEventListener('click', () => {
  if (!window.tianyanPlugin || pending) return;
  pending = Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');
  button.disabled = true;
  output.textContent = en ? 'Waiting for app permission…' : '等待应用授权…';
  timer = setTimeout(() => {
    pending = null;
    button.disabled = false;
    output.textContent = en ? 'Request timed out. Try again.' : '请求已超时，请重试。';
  }, 72000);
  window.tianyanPlugin.postMessage(JSON.stringify({ id: pending, action: 'host.app-info', target: 'application' }));
});
