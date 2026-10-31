'use strict';
const en = !navigator.language.toLowerCase().startsWith('zh');
const button = document.getElementById('copy');
const input = document.getElementById('text');
const output = document.getElementById('result');
if (en) {
  document.documentElement.lang = 'en';
  document.getElementById('title').textContent = 'Copy text';
  document.getElementById('description').textContent = 'The app shows the submitted text and asks each time. It does not read the clipboard.';
  document.getElementById('label').textContent = 'Text to copy (up to 1024 characters)';
  button.textContent = 'Request copy';
  output.textContent = 'Ready';
}
let pending = null;
let timer = null;
function finish(message) {
  clearTimeout(timer);
  pending = null;
  button.disabled = false;
  input.disabled = false;
  output.textContent = message;
}
if (window.tianyanPlugin) window.tianyanPlugin.onmessage = event => {
  let reply;
  try { reply = JSON.parse(event.data); } catch { return; }
  if (!pending || reply.id !== pending) return;
  finish(reply.success ? (en ? 'Copied' : '已复制') : (en ? 'Request denied: ' : '请求未完成：') + reply.text);
};
button.addEventListener('click', () => {
  if (!window.tianyanPlugin || pending || !input.value || input.value.length > 1024) return;
  const id = Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');
  const message = JSON.stringify({ id, action: 'host.clipboard-write', target: 'clipboard', text: input.value });
  if (message.length > 4096) {
    output.textContent = en ? 'Text encoding is too large. Shorten it and try again.' : '编码后的文本过长，请缩短后重试。';
    return;
  }
  pending = id;
  button.disabled = true;
  input.disabled = true;
  output.textContent = en ? 'Waiting for app permission…' : '等待应用授权…';
  timer = setTimeout(() => finish(en ? 'Request timed out. Try again.' : '请求已超时，请重试。'), 72000);
  window.tianyanPlugin.postMessage(message);
});
