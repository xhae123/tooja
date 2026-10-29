// Progressive enhancement only: every page is complete without this file.
(function () {
  'use strict';
  var FMT = { pct: function (v) { return v.toFixed(1) + '%'; }, bytes: function (v) { return v >= 1e9 ? (v / 1e9).toFixed(2) + ' GB' : v >= 1e6 ? (v / 1e6).toFixed(1) + ' MB' : (v / 1e3).toFixed(0) + ' KB'; }, num: function (v) { return Math.abs(v % 1) < 1e-9 ? v.toLocaleString('ko-KR') : v.toLocaleString('ko-KR', { maximumFractionDigits: 1 }); } };
  function fmtFor(metric) { return /percent$|availability$/.test(metric) ? FMT.pct : /heap|bytes/.test(metric) ? FMT.bytes : FMT.num; }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function kst(ms) { var d = new Date(ms); var p = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).formatToParts(d); var o = {}; p.forEach(function (x) { o[x.type] = x.value; }); return o.month + '-' + o.day + ' ' + o.hour + ':' + o.minute + ':' + o.second; }

  // Relative times: the server renders the absolute KST value; we add "n분 전" and keep it fresh.
  function relative(ms) { var s = Math.max(0, Math.round((Date.now() - ms) / 1000)); if (s < 60) return s + '초 전'; if (s < 3600) return Math.floor(s / 60) + '분 전'; if (s < 86400) return Math.floor(s / 3600) + '시간 전'; return Math.floor(s / 86400) + '일 전'; }
  function tickTimes() { document.querySelectorAll('time[data-ms]').forEach(function (t) { var ms = +t.getAttribute('data-ms'); if (!ms) return; if (t.hasAttribute('data-rel')) t.textContent = relative(ms); t.title = kst(ms) + ' KST · ' + new Date(ms).toISOString(); }); }
  tickTimes(); setInterval(tickTimes, 15000);

  // Charts: crosshair + tooltip from the embedded bucket data.
  document.querySelectorAll('.chart[data-points]').forEach(function (fig) {
    var points; try { points = JSON.parse(fig.getAttribute('data-points')); } catch (e) { return; }
    if (!points.length) return;
    var svg = fig.querySelector('svg'), tip = fig.querySelector('.tip'), cross = fig.querySelector('.cross'), focus = fig.querySelector('.focus');
    var fmt = fmtFor(fig.getAttribute('data-metric') || '');
    var vb = svg.viewBox.baseVal;
    function show(evt) {
      var rect = svg.getBoundingClientRect(); var x = (evt.clientX - rect.left) / rect.width * vb.width;
      var best = points[0], bd = Infinity; points.forEach(function (p) { var d = Math.abs(p.x - x); if (d < bd) { bd = d; best = p; } });
      cross.setAttribute('x1', best.x); cross.setAttribute('x2', best.x); cross.style.display = 'block';
      focus.setAttribute('cx', best.x); focus.setAttribute('cy', best.y); focus.style.display = 'block';
      tip.innerHTML = '<b>' + fmt(best.avg) + '</b> <span style="opacity:.7">평균</span><br>' + fmt(best.min) + ' ~ ' + fmt(best.max) + '<br><span style="opacity:.7">' + kst(best.ts) + '</span>';
      tip.style.left = (best.x / vb.width * rect.width) + 'px'; tip.style.top = (best.y / vb.height * rect.height) + 'px'; tip.style.display = 'block';
    }
    function hide() { cross.style.display = 'none'; focus.style.display = 'none'; tip.style.display = 'none'; }
    svg.addEventListener('mousemove', show); svg.addEventListener('mouseleave', hide);
    svg.addEventListener('touchstart', function (e) { show(e.touches[0]); }, { passive: true });
  });

  // Auto refresh with a pause switch; the preference is a per-viewer convenience.
  var sw = document.getElementById('autorefresh');
  if (sw) {
    var key = 'owner.autorefresh', seconds = +sw.getAttribute('data-seconds') || 30, timer;
    try { if (localStorage.getItem(key) === 'off') sw.checked = false; } catch (e) {}
    function arm() { clearTimeout(timer); if (sw.checked) timer = setTimeout(function () { location.reload(); }, seconds * 1000); }
    sw.addEventListener('change', function () { try { localStorage.setItem(key, sw.checked ? 'on' : 'off'); } catch (e) {} arm(); });
    arm();
    var count = document.getElementById('autorefresh-count'); if (count) { var left = seconds; setInterval(function () { if (!sw.checked) { count.textContent = ''; return; } left = left > 0 ? left - 1 : seconds; count.textContent = left + 's'; }, 1000); }
  }

  // Mobile sidebar.
  var menu = document.getElementById('menu'), side = document.querySelector('.sidebar');
  if (menu && side) { menu.addEventListener('click', function () { side.classList.toggle('open'); }); document.addEventListener('click', function (e) { if (side.classList.contains('open') && !side.contains(e.target) && e.target !== menu) side.classList.remove('open'); }); }

  // "/" focuses the first filter input, Esc clears it.
  document.addEventListener('keydown', function (e) {
    if (e.key === '/' && !/input|select|textarea/i.test(document.activeElement.tagName)) { var f = document.querySelector('.filterbar input, .filterbar select'); if (f) { e.preventDefault(); f.focus(); } }
  });
})();
