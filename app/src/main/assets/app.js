/* =========================================================================
 * 手机版统计页渲染脚本
 * 复用 core.js / stats.js 的纯函数，数据源改为 Android JS Bridge 注入的本地快照。
 * ========================================================================= */
(function () {
  'use strict';
  const BiliLib = window.BiliLib;
  const Stats = BiliLib.Stats;
  const K = BiliLib.KEYS;
  const $ = function (id) { return document.getElementById(id); };

  let state = { data: null, period: 'day' };

  function escapeHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  /** 从 native 注入的 JSON 字符串读取全部数据，补默认值 */
  function getAll() {
    let res = {};
    try {
      const raw = window.BiliBridge && window.BiliBridge.getData
        ? window.BiliBridge.getData() : '{}';
      res = (typeof raw === 'string') ? (JSON.parse(raw) || {}) : (raw || {});
    } catch (e) { res = {}; }
    const out = {};
    Object.keys(K).forEach(function (name) {
      const k = K[name];
      let v = res[k] != null ? res[k] : null;
      if (v == null) {
        if (k === K.STREAK) v = { current: 0, longest: 0, lastCheckinDate: '' };
        else if (k === K.SETTINGS) v = Object.assign({}, BiliLib.DEFAULT_SETTINGS);
        else v = {};
      }
      out[k] = v;
    });
    return out;
  }

  function renderOverview(data) {
    const daily = data[K.DAILY] || {};
    const total = Object.keys(daily).reduce(function (s, k) { return s + (daily[k].seconds || 0); }, 0);
    const streak = data[K.STREAK] || Stats.computeStreak(data[K.CHECKINS]);
    const courses = Stats.courses(data);
    const doneCourses = courses.filter(function (c) { return c.totalParts >= 1 && c.completedParts >= c.totalParts; }).length;
    $('ovTotal').textContent = (total / 3600).toFixed(1);
    $('ovStreak').textContent = streak.current || 0;
    $('ovCourses').textContent = doneCourses;
    $('ovParts').textContent = Stats.totalCompletedParts(data);
    $('topStreak').textContent = streak.current || 0;
    $('topLongest').textContent = streak.longest || 0;
  }

  function renderChart(data, period) {
    const daily = data[K.DAILY] || {};
    const res = Stats.period(data, period);
    let labels = [], values = [];

    if (period === 'day') {
      const today = BiliLib.dayKey();
      for (let i = 6; i >= 0; i--) {
        const k = BiliLib.addDays(today, -i);
        labels.push(i === 0 ? '今天' : k.slice(5));
        values.push(daily[k] ? (daily[k].seconds || 0) : 0);
      }
    } else if (period === 'week') {
      const names = ['一', '二', '三', '四', '五', '六', '日'];
      res.keys.forEach(function (k, i) {
        labels.push(names[i]);
        values.push(daily[k] ? (daily[k].seconds || 0) : 0);
      });
    } else {
      const keys = res.keys;
      for (let i = 0; i < keys.length; i += 7) {
        const slice = keys.slice(i, i + 7);
        labels.push('第' + (Math.floor(i / 7) + 1) + '周');
        values.push(slice.reduce(function (s, k) { return s + (daily[k] ? daily[k].seconds || 0 : 0); }, 0));
      }
    }

    const total = res.seconds;
    const label = period === 'day' ? '今日' : (period === 'week' ? '本周' : '本月');
    $('periodSummary').innerHTML = label + ' <b>' + BiliLib.formatDuration(total) + '</b><br>有效 '
      + res.activeDays + ' 天 · 日均 ' + BiliLib.formatCompact(res.avgSeconds);

    const svg = $('trendChart');
    const empty = $('chartEmpty');
    if (total <= 0) {
      svg.innerHTML = '';
      empty.style.display = 'flex';
      return;
    }
    empty.style.display = 'none';

    const W = 900, H = 220, padL = 48, padR = 18, padT = 18, padB = 30;
    const innerW = W - padL - padR, innerH = H - padT - padB;
    const maxV = Math.max.apply(null, values.concat([1]));
    const n = values.length;
    const step = n > 1 ? innerW / (n - 1) : 0;
    function x(i) { return padL + (n > 1 ? i * step : innerW / 2); }
    function y(v) { return padT + innerH - (v / maxV) * innerH; }

    let html = '';
    for (let g = 0; g <= 4; g++) {
      const gy = padT + (innerH / 4) * g;
      const gv = Math.round(maxV * (1 - g / 4));
      html += '<line x1="' + padL + '" y1="' + gy + '" x2="' + (W - padR) + '" y2="' + gy + '" stroke="#eef1ef" stroke-width="1"/>';
      html += '<text x="' + (padL - 8) + '" y="' + (gy + 4) + '" text-anchor="end" font-size="11" fill="#a6b0aa">' + BiliLib.formatCompact(gv) + '</text>';
    }
    const pts = values.map(function (v, i) { return x(i) + ',' + y(v); }).join(' ');
    const areaPts = padL + ',' + (padT + innerH) + ' ' + pts + ' ' + x(n - 1) + ',' + (padT + innerH);
    html += '<polygon points="' + areaPts + '" fill="rgba(35,179,109,.10)"/>';
    if (n > 1) html += '<polyline points="' + pts + '" fill="none" stroke="#23b36d" stroke-width="2.5" stroke-linejoin="round" stroke-linecap="round"/>';
    values.forEach(function (v, i) {
      html += '<circle cx="' + x(i) + '" cy="' + y(v) + '" r="4" fill="#fff" stroke="#12985a" stroke-width="2"/>';
      if (v > 0) html += '<text x="' + x(i) + '" y="' + (y(v) - 10) + '" text-anchor="middle" font-size="11" font-weight="600" fill="#12985a">' + BiliLib.formatCompact(v) + '</text>';
      html += '<text x="' + x(i) + '" y="' + (H - 8) + '" text-anchor="middle" font-size="12" fill="#84918b">' + escapeHtml(labels[i]) + '</text>';
    });
    svg.innerHTML = html;
  }

  function renderHeatmap(data) {
    const cells = Stats.heatmap(data, 26);
    const box = $('heatmapBig');
    box.innerHTML = cells.map(function (c) {
      const cls = 'heat-cell' + (c.inFuture ? ' future' : (' l' + Stats.heatLevel(c.seconds)));
      return '<i class="' + cls + '" title="' + c.key + '"></i>';
    }).join('');

    const months = [];
    let lastMonth = '';
    cells.forEach(function (c, i) {
      const mk = c.key.slice(0, 7);
      if (mk !== lastMonth) { months.push({ idx: Math.floor(i / 7), text: mk.slice(5) + '月' }); lastMonth = mk; }
    });
    $('heatmapMonths').innerHTML = months.map(function (m) {
      return '<span style="left:' + (m.idx * 13) + 'px">' + m.text + '</span>';
    }).join('');
  }

  function renderCourses(data) {
    const courses = Stats.courses(data);
    const box = $('courseList');
    $('courseCount').textContent = courses.length ? ('共 ' + courses.length + ' 门') : '';
    if (!courses.length) {
      box.innerHTML = '<div class="empty-state">还没有课程记录。去 B 站看节课，进度会自动出现在这里。</div>';
      return;
    }
    box.innerHTML = courses.map(function (c) {
      const pct = Math.round(c.progress * 100);
      const parts = c.parts.map(function (p) {
        const pr = Stats.lessonProgress(p);
        const lv = pr >= 0.999 ? 4 : (pr >= 0.66 ? 3 : (pr >= 0.33 ? 2 : (pr > 0 ? 1 : 0)));
        return '<div class="part-box p' + lv + '">' + (lv === 4 ? '✓' : (p.p || 1)) + '</div>';
      }).join('');
      return '<div class="course-item">' +
        '<div class="course-top"><div class="course-title">' + escapeHtml(c.title) + '</div>' +
        '<div class="course-pct">' + pct + '%</div></div>' +
        '<div class="course-progress"><i style="width:' + pct + '%"></i></div>' +
        '<div class="parts-grid">' + parts + '</div>' +
        '<div class="course-meta">已完成 ' + c.completedParts + ' / ' + c.totalParts + ' 节 · 累计观看 '
        + BiliLib.formatDuration(c.watchedSeconds) + '</div></div>';
    }).join('');
  }

  function renderBadges(data) {
    const badges = data[K.BADGES] || {};
    const defs = BiliLib.BADGE_DEFS;
    let unlocked = 0;
    $('badgeWall').innerHTML = defs.map(function (d) {
      let ok = false, date = '';
      if (Array.isArray(badges[d.key])) ok = badges[d.key].length > 0;
      else { ok = !!badges[d.key]; date = badges[d.key] ? BiliLib.formatDate(badges[d.key]) : ''; }
      if (ok) unlocked++;
      return '<div class="badge' + (ok ? ' unlocked' : '') + '">' +
        '<div class="badge-icon">' + d.icon + '</div>' +
        '<div class="badge-name">' + d.name + '</div>' +
        '<div class="badge-desc">' + d.desc + '</div>' +
        (ok ? '<div class="badge-date">' + (date || '已解锁') + '</div>' : '') + '</div>';
    }).join('');
    $('badgeCount').textContent = '已解锁 ' + unlocked + ' / ' + defs.length;
  }

  function renderAll() {
    const d = state.data;
    renderOverview(d);
    renderChart(d, state.period);
    renderHeatmap(d);
    renderCourses(d);
    renderBadges(d);
  }

  function bindEvents() {
    $('periodTabs').addEventListener('click', function (e) {
      const btn = e.target.closest('.tab');
      if (!btn) return;
      state.period = btn.getAttribute('data-period');
      const tabs = document.querySelectorAll('#periodTabs .tab');
      for (let i = 0; i < tabs.length; i++) tabs[i].classList.remove('active');
      btn.classList.add('active');
      renderChart(state.data, state.period);
    });
    $('clearBtn').addEventListener('click', function () {
      if (!window.confirm('确定清除全部观看记录吗？此操作不可恢复。')) return;
      if (window.BiliBridge && window.BiliBridge.clearAll) {
        window.BiliBridge.clearAll();
        reload();
      }
    });
  }

  function reload() {
    state.data = getAll();
    renderAll();
  }

  reload();
  bindEvents();
})();
