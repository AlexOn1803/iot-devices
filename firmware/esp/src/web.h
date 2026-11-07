#pragma once
#include <Arduino.h>

// Сторінка налаштування Wi-Fi (точка доступу IoT-Setup-xxxx, адреса 192.168.4.1)
static const char PORTAL_HTML[] PROGMEM = R"HTML(<!doctype html>
<html lang="uk"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>IoT Setup</title>
<style>
:root{--bg:#18120c;--card:#241b12;--fg:#f3e9dc;--mut:#b8a891;--acc:#ffb952}
*{box-sizing:border-box}body{margin:0;font-family:system-ui,sans-serif;background:var(--bg);color:var(--fg);padding:16px}
.card{background:var(--card);border-radius:16px;padding:16px;max-width:420px;margin:0 auto}
h1{font-size:20px;margin:0 0 4px}p{color:var(--mut);font-size:14px;margin:0 0 16px}
label{display:block;font-size:13px;color:var(--mut);margin:12px 0 4px}
input,select{width:100%;padding:12px;border-radius:10px;border:1px solid #4a3c2c;background:#140f0a;color:var(--fg);font-size:16px}
button{width:100%;margin-top:16px;padding:14px;border:0;border-radius:12px;background:var(--acc);color:#2b1a07;font-size:16px;font-weight:600}
.net{display:flex;justify-content:space-between;padding:10px 12px;border-radius:10px;background:#140f0a;margin:6px 0;cursor:pointer}
.ok{color:#7fd18b}
</style></head><body><div class="card">
<h1>Підключення до Wi-Fi</h1>
<p>Вибери домашню мережу (2.4 ГГц). Після збереження плата перезапуститься, а цей Wi-Fi зникне — поверни телефон у домашню мережу й натисни «Знайти ESP» у додатку.</p>
<div id="nets"><p>Пошук мереж…</p></div>
<label>Назва мережі</label><input id="ssid" autocomplete="off">
<label>Пароль</label><input id="pass" type="password">
<button onclick="save()">Зберегти й підключитися</button>
<p id="msg" style="margin-top:12px"></p>
</div><script>
function pick(s){document.getElementById('ssid').value=s;document.getElementById('pass').focus()}
async function scan(){try{const r=await fetch('/api/scan');if(r.status==202){setTimeout(scan,1500);return}
const j=await r.json();const el=document.getElementById('nets');el.innerHTML='';
j.nets.sort((a,b)=>b.rssi-a.rssi).forEach(n=>{const d=document.createElement('div');d.className='net';
d.innerHTML='<span>'+n.ssid.replace(/</g,'&lt;')+'</span><span>'+(n.open?'':'🔒 ')+n.rssi+' dBm</span>';d.onclick=()=>pick(n.ssid);el.appendChild(d)})}catch(e){setTimeout(scan,2000)}}
async function save(){const ssid=document.getElementById('ssid').value.trim();if(!ssid)return;
const r=await fetch('/api/wifi',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({ssid,pass:document.getElementById('pass').value})});
document.getElementById('msg').innerHTML=r.ok?'<span class="ok">Збережено. Плата перезапускається…</span>':'Помилка '+r.status}
scan();
</script></body></html>)HTML";

// Проста сторінка керування в домашній мережі (http://iot-xxxxxx.local/)
static const char CONTROL_HTML[] PROGMEM = R"HTML(<!doctype html>
<html lang="uk"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>IoT Device</title>
<style>
:root{--bg:#18120c;--card:#241b12;--fg:#f3e9dc;--mut:#b8a891;--acc:#ffb952;--on:#2e9e5b}
*{box-sizing:border-box}body{margin:0;font-family:system-ui,sans-serif;background:var(--bg);color:var(--fg);padding:16px}
.wrap{max-width:460px;margin:0 auto}h1{font-size:20px;margin:0}small{color:var(--mut)}
.c{background:var(--card);border-radius:16px;padding:14px 16px;margin-top:12px;display:flex;align-items:center;gap:12px}
.c .n{flex:1}.v{font-size:22px;font-weight:600}
.t{width:56px;height:32px;border-radius:16px;border:0;background:#4a3c2c;position:relative}
.t.on{background:var(--on)}.t:after{content:'';position:absolute;top:4px;left:4px;width:24px;height:24px;border-radius:50%;background:#fff;transition:.15s}
.t.on:after{left:28px}input[type=range]{width:100%}
</style></head><body><div class="wrap">
<h1 id="title">IoT Device</h1><small id="sub"></small><div id="list"></div>
<p><small>Повне налаштування — у додатку IoT Devices.</small></p></div><script>
let key=localStorage.getItem('k')||'';
async function api(p,b){const o={headers:{'X-Key':key}};if(b){o.method='POST';o.headers['Content-Type']='application/json';o.body=JSON.stringify(b)}
const r=await fetch(p,o);if(r.status==401){key=prompt('Ключ доступу (є в додатку → налаштування пристрою)')||'';localStorage.setItem('k',key);return null}return r.json()}
function esc(s){return String(s).replace(/</g,'&lt;')}
function render(s){const L=document.getElementById('list');L.innerHTML='';s.comps.forEach(c=>{if(c.t=='button')return;
const d=document.createElement('div');d.className='c';let h='<div class="n"><small>'+esc(c.name)+'</small><div class="v">';
if(c.temp!==undefined)h+=(c.temp==null?'—':c.temp+' °C')+(c.hum!=null&&c.hum!==undefined?' · '+c.hum+' %':'');
else if(c.pct!==undefined)h+=c.pct+' %';else if(c.active!==undefined)h+=c.active?'Активно':'—';
else if(c.on!==undefined)h+=c.on?'Увімкнено':'Вимкнено';h+='</div>';
if(c.bri!==undefined)h+='<input type="range" min="1" max="255" value="'+c.bri+'" onchange="api(\'/api/set\',{i:'+c.i+',bri:+this.value})">';
h+='</div>';if(c.on!==undefined)h+='<button class="t'+(c.on?' on':'')+'" onclick="api(\'/api/set\',{i:'+c.i+',toggle:true}).then(x=>x&&render(x))"></button>';
d.innerHTML=h;L.appendChild(d)})}
async function tick(){try{const i=await (await fetch('/api/info')).json();document.getElementById('title').textContent=i.name;
document.getElementById('sub').textContent=i.id+' · '+i.fw+' · '+i.rssi+' dBm';const s=await api('/api/state');if(s)render(s)}catch(e){}
setTimeout(tick,2000)}tick();
</script></body></html>)HTML";
