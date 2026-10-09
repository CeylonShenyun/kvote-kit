/* kvote 卡片：把 /api/usage 渲染成一张深海玻璃卡，每个额度一行海，小帆船开到哪 = 用了多少。
   用法：<link rel="stylesheet" href="kvote-card.css"> <div id="kv"></div> <script src="kvote-card.js"></script>
         KvoteCard.mount(document.getElementById("kv"), { endpoint: "/api/usage", refreshSeconds: 60 }) */
(function () {
  "use strict";

  // 窗口名 → 卡片上的字。seven_day_overage_included 是 Claude Code 里写的"按模型分的每周额度"，
  // 只有用到那个模型的那一轮才带回来；我们测下来是用 Fable 那轮出现，所以标 Fable，不对就改这里
  var LABELS = { five_hour: "本轮会话", seven_day: "每周 · 所有模型", seven_day_overage_included: "每周 · Fable" };
  var ORDER = ["five_hour", "seven_day", "seven_day_overage_included"];
  var HOT = 80;   // 用到这么多（%）开始变暖光
  var WEEK = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

  function esc(s) { return String(s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function pad(n) { return (n < 10 ? "0" : "") + n; }

  // ── 像素画：一行一串字符，字符查调色板，同色连着的合成一个 rect ──
  function pix(rows, pal, k, cls) {
    var w = rows[0].length, h = rows.length;
    var out = '<svg' + (cls ? ' class="' + cls + '"' : '') + ' viewBox="0 0 ' + w + ' ' + h + '" width="' + (w * k) + '" height="' + (h * k) + '" shape-rendering="crispEdges">';
    for (var y = 0; y < h; y++) {
      var r = rows[y], x = 0;
      while (x < w) {
        var ch = r.charAt(x), c = pal[ch];
        if (!c) { x++; continue; }
        var x0 = x; while (x < w && r.charAt(x) === ch) x++;
        out += '<rect x="' + x0 + '" y="' + y + '" width="' + (x - x0) + '" height="1" fill="' + c + '"/>';
      }
    }
    return out + '</svg>';
  }
  // 小帆船 22×18：木头船身、奶白船舷、三个亮着暖灯的圆窗；白主帆、背光的前帆、一道蓝帆条；桅杆顶小红旗两帧飘
  var HULL = [
    '..........MW..........', '..........MWW.........', '.........wMWWW........', '........wwMWWWW.......',
    '.......wwwMWWWWW......', '......wwwwMWWWWWW.....', '.....bbbbbMbbbbbbb....', '....wwwwwwMWWWWWWWW...',
    '...wwwwwwwMWWWWWWWWW..', '..wwwwwwwwMWWWWWWWWW..', 'G.........M.........GG', 'GGGGGGGGGGGGGGGGGGGGGG',
    'HHHHoHHHHoHHHHoHHHHHHH', '.hhhhhhhhhhhhhhhhhhhh.', '..HHHHHHHHHHHHHHHHHH..', '....KKKKKKKKKKKKKK....'];
  var FLAG_A = ['..........MRRR........', '..........MRr.........'];
  var FLAG_B = ['..........MRR.........', '..........MRRr........'];
  var PAL = { M: '#5a3a28', R: '#d4483c', r: '#a8352c', W: '#f2f5f9', w: '#c6d4e3', b: '#6C90B6',
    G: '#eadfca', H: '#8a5a3c', h: '#6e4630', K: '#3f2a1e', o: '#ffe9a8' };
  var BW = 33;   // 船宽（22 格 × 1.5）
  var BOAT = '<i class="kv-boat">' + pix(FLAG_A.concat(HULL), PAL, 1.5, 'f1') + pix(FLAG_B.concat(HULL), PAL, 1.5, 'f2') + '</i>';
  // 尽头的红白浮标 5×8，顶上一盏灯一闪一闪（到这就满了）
  var BUOY = pix(['..y..', '.kkk.', '.RRR.', '.WWW.', '.RRR.', '.WWW.', 'kkkkk', '.kkk.'],
    { y: '#ffe9a8', k: '#27324a', R: '#c9473e', W: '#e6ecf4' }, 1.6);

  // 浪的两层花纹：前浪（光晕 + 浅蓝浪线 + 浪尖白高光 + 一粒浪花），后浪更长更淡
  function svgUrl(s) { return 'url("data:image/svg+xml,' + encodeURIComponent(s) + '")'; }
  function front(glow, line) {
    return svgUrl('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 26 10">' +
      '<path d="M0 4.5Q6.5 0.8 13 4.5T26 4.5" fill="none" stroke="' + glow + '" stroke-width="4.5" stroke-linecap="round" opacity=".2"/>' +
      '<path d="M0 4.5Q6.5 0.8 13 4.5T26 4.5" fill="none" stroke="' + line + '" stroke-width="1.8" stroke-linecap="round"/>' +
      '<path d="M3.6 3.1Q6.5 1.9 9.4 3.1" fill="none" stroke="#fff" stroke-width=".9" stroke-linecap="round" opacity=".9"/>' +
      '<circle cx="11.2" cy="2.7" r=".55" fill="#fff" opacity=".8"/></svg>');
  }
  var W1 = front('#a9c6e6', '#dbe8f6'), W1H = front('#f2c46d', '#f6dcae');
  var W2 = svgUrl('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 38 12"><path d="M0 9.6Q9.5 7 19 9.6T38 9.6" fill="none" stroke="#8FACCB" stroke-width="1.1" stroke-linecap="round" opacity=".62"/></svg>');
  var STARS = '<b style="left:9%;animation-delay:-.3s"></b><b style="left:24%;animation-delay:-1.4s;animation-duration:2.6s"></b>' +
    '<b style="left:41%;animation-delay:-.8s"></b><b style="left:57%;animation-delay:-1.9s;animation-duration:1.9s"></b>' +
    '<b style="left:72%;animation-delay:-.1s;animation-duration:2.5s"></b><b style="left:88%;animation-delay:-1.1s"></b>';

  function label(k) {
    if (LABELS[k]) return LABELS[k];
    return "每周 · " + k.replace(/^seven_day_/, "").replace(/_/g, " ").replace(/\b\w/g, function (c) { return c.toUpperCase(); });
  }
  function order(k) { var i = ORDER.indexOf(k); return i < 0 ? 99 : i; }
  function resetText(ts) {
    var ms = ts * 1000 - Date.now();
    if (ms <= 0) return "已重置";
    if (ms < 24 * 3600e3) { var t = Math.round(ms / 60e3), h = Math.floor(t / 60), m = t % 60; return (h ? h + " 小时 " : "") + m + " 分后重置"; }
    var d = new Date(ts * 1000); return WEEK[d.getDay()] + " " + pad(d.getHours()) + ":" + pad(d.getMinutes()) + " 重置";
  }
  function updatedText(u) {
    if (!u) return "还没有数据";
    var min = Math.max(0, Math.floor((Date.now() / 1000 - u) / 60));
    return min < 1 ? "刚刚更新" : min < 60 ? min + " 分钟前更新" : min < 1440 ? Math.floor(min / 60) + " 小时前更新" : Math.floor(min / 1440) + " 天前更新";
  }

  function render(el, data) {
    var w = (data && data.windows) || {};
    var keys = Object.keys(w).filter(function (k) { return k === "five_hour" || k.indexOf("seven_day") === 0; })
      .sort(function (a, b) { return order(a) - order(b) || (a < b ? -1 : 1); });
    var html = '<div class="kv-head"><b>额度<i>Kvote</i></b><small>' + esc(updatedText(data && data.updated)) + '</small></div>';
    if (!keys.length) html += '<p class="kv-empty">还没有用量数据，跟 Claude 说句话就有了</p>';
    keys.forEach(function (k) {
      var p = Math.max(0, Math.min(1, Number(w[k].utilization) || 0)), pct = Math.round(p * 100);
      html += '<div class="kv-row' + (pct >= HOT ? ' hot' : '') + '" data-p="' + p + '">' +
        '<div class="kv-top"><span>' + esc(label(k)) + '</span><em>' + pct + '<small>%</small></em></div>' +
        '<div class="kv-sea"><div class="kv-rt kv-rt0"></div><div class="kv-rt kv-rt1">' + STARS + '</div>' +
        '<i class="kv-buoy">' + BUOY + '</i><div class="kv-wk"><div class="kv-ride">' + BOAT + '</div></div></div>' +
        '<div class="kv-reset" data-ts="' + (Number(w[k].resetsAt) || 0) + '">' + esc(resetText(w[k].resetsAt)) + '</div></div>';
    });
    for (var i = 0; i < 7; i++) html += '<i class="kv-snow" style="left:' + (8 + i * 13) + '%;animation-duration:' + (7 + i % 3 * 2.5) + 's;animation-delay:-' + (i * 1.7) + 's"></i>';
    el.classList.add("kvote");
    el.style.setProperty("--kv-w1", W1); el.style.setProperty("--kv-w1h", W1H); el.style.setProperty("--kv-w2", W2);
    el.innerHTML = html;
    place(el);
  }
  // 船头对着用量那一点（0% 在起点，100% 顶到浮标前面）；开过的那段浪亮到船身中间
  function place(el) {
    el.querySelectorAll(".kv-row").forEach(function (row) {
      var p = +row.dataset.p, sea = row.querySelector(".kv-sea"), W = sea.offsetWidth - 12;
      var x = Math.round((W - BW) * p);
      row.querySelector(".kv-wk").style.transform = "translate3d(" + x + "px,0,0)";
      row.querySelector(".kv-rt1").style.clipPath = "inset(0 " + Math.max(0, W - (x + BW * 0.5)) + "px 0 0)";
    });
  }

  function mount(el, opts) {
    var o = Object.assign({ endpoint: "/api/usage", refreshSeconds: 60 }, opts || {});
    var last = null;
    function load() {
      return fetch(o.endpoint, { cache: "no-store" }).then(function (r) { return r.json(); })
        .then(function (d) { last = d; render(el, d); }).catch(function () { if (!last) render(el, {}); });
    }
    load();
    setInterval(load, o.refreshSeconds * 1000);
    // 倒计时和"几分钟前更新"自己走
    setInterval(function () {
      if (!last) return;
      el.querySelectorAll(".kv-reset[data-ts]").forEach(function (r) { r.textContent = resetText(Number(r.dataset.ts)); });
      var s = el.querySelector(".kv-head small"); if (s) s.textContent = updatedText(last.updated);
    }, 20000);
    window.addEventListener("resize", function () { place(el); });
    return { reload: load };
  }

  window.KvoteCard = { mount: mount, render: render, LABELS: LABELS };
})();
