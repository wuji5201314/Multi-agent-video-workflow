/* 图片工作台：参数面板 + 生成 + 历史画廊。
   与后端约定：
   GET  /api/images/options          预设（比例/画风/质量/模型）
   POST /api/images/generate         生成
   POST /api/images/upload           原始二进制上传参考图
   POST /api/images/analyze          参考图 → 提示词
   GET  /api/images/history          历史
   DELETE /api/images/{id}           删除
*/
(function () {
  'use strict';

  var options = { ratios: [], styles: [], qualities: [], models: [] };
  var state = {
    style: 'photo',
    ratio: '9:16',
    quality: 'standard',
    count: 1,
    model: 'klein',
    seed: 0,
    reference: '',
    referenceNote: '',
    tab: 'results',
    busy: false
  };
  var lastItems = [];
  var toastTimer = null;

  function el(id) { return document.getElementById(id); }

  function esc(value) {
    return String(value === null || value === undefined ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function toast(message, isError) {
    var node = el('toast');
    if (!node) { return; }
    node.textContent = message;
    node.className = 'toast show' + (isError ? ' error' : '');
    if (toastTimer) { clearTimeout(toastTimer); }
    toastTimer = setTimeout(function () { node.className = 'toast'; }, isError ? 5200 : 2600);
  }

  function api(path, init) {
    var opts = init || {};
    var config = { method: opts.method || 'GET', headers: {} };
    if (opts.body !== undefined) {
      config.headers['Content-Type'] = 'application/json';
      config.body = JSON.stringify(opts.body);
    }
    return fetch(path, config).then(function (response) {
      return response.text().then(function (text) {
        var data = null;
        if (text) { try { data = JSON.parse(text); } catch (err) { data = { detail: text }; } }
        if (!response.ok) {
          var detail = data && data.detail ? data.detail : ('HTTP ' + response.status);
          throw new Error(typeof detail === 'string' ? detail : JSON.stringify(detail));
        }
        return data;
      });
    });
  }

  /* ---------------- 参数面板 ---------------- */

  function renderChips(containerId, items, activeKey, onPick) {
    var box = el(containerId);
    if (!box) { return; }
    box.innerHTML = items.map(function (item) {
      var active = item.key === activeKey ? ' active' : '';
      return '<button class="chip' + active + '" data-key="' + esc(item.key) + '">' + esc(item.label) + '</button>';
    }).join('');
    box.onclick = function (event) {
      var chip = event.target.closest('[data-key]');
      if (!chip) { return; }
      onPick(chip.getAttribute('data-key'));
      renderChips(containerId, items, onPick.__active(), onPick);
    };
  }

  function renderSegmented(containerId, items, activeKey, onPick) {
    var box = el(containerId);
    if (!box) { return; }
    box.innerHTML = items.map(function (item) {
      var active = item.key === activeKey ? ' active' : '';
      return '<button class="seg-item' + active + '" data-key="' + esc(item.key) + '">' + esc(item.label) + '</button>';
    }).join('');
    box.onclick = function (event) {
      var item = event.target.closest('[data-key]');
      if (!item) { return; }
      onPick(item.getAttribute('data-key'));
      renderSegmented(containerId, items, onPick.__active(), onPick);
    };
  }

  function activeStyle() { return state.style; }
  function activeRatio() { return state.ratio; }
  function activeQuality() { return state.quality; }
  function activeCount() { return String(state.count); }
  function activeModel() { return state.model; }

  function paintControls() {
    renderChips('f-style', options.styles, state.style, function (key) {
      state.style = key; activeStyle.__active = activeStyle;
    });
    activeStyle.__active = activeStyle;

    renderChips('f-ratio', options.ratios.map(function (r) {
      return { key: r.key, label: r.label };
    }), state.ratio, function (key) {
      state.ratio = key;
      var found = options.ratios.filter(function (r) { return r.key === key; })[0];
      if (found) { el('ratio-hint').textContent = found.width + ' × ' + found.height; }
    });
    activeRatio.__active = activeRatio;

    renderSegmented('f-quality', options.qualities, state.quality, function (key) { state.quality = key; });
    activeQuality.__active = activeQuality;

    renderSegmented('f-count', [
      { key: '1', label: '1 张' }, { key: '2', label: '2 张' },
      { key: '3', label: '3 张' }, { key: '4', label: '4 张' }
    ], String(state.count), function (key) { state.count = Number(key); });
    activeCount.__active = function () { return String(state.count); };

    renderSegmented('f-model', options.models, state.model, function (key) { state.model = key; });
    activeModel.__active = activeModel;
  }

  function loadOptions() {
    return api('/api/images/options').then(function (data) {
      options = data;
      if (!data.has_image_key) {
        toast('没有配置 IMAGE_API_KEY，出图会失败', true);
      }
      paintControls();
      var first = options.ratios.filter(function (r) { return r.key === '9:16'; })[0] || options.ratios[0];
      if (first) { el('ratio-hint').textContent = first.width + ' × ' + first.height; }
      return data;
    }).catch(function (err) {
      toast('读取预设失败：' + err.message, true);
    });
  }

  /* ---------------- 参考图 ---------------- */

  function bindDropzone() {
    var zone = el('dropzone');
    var input = el('f-file');
    if (!zone || !input) { return; }

    zone.addEventListener('click', function () { input.click(); });
    input.addEventListener('change', function () {
      if (input.files && input.files[0]) { uploadReference(input.files[0]); }
    });

    ['dragenter', 'dragover'].forEach(function (name) {
      zone.addEventListener(name, function (event) {
        event.preventDefault();
        zone.classList.add('dragging');
      });
    });
    ['dragleave', 'drop'].forEach(function (name) {
      zone.addEventListener(name, function (event) {
        event.preventDefault();
        if (name === 'dragleave' && zone.contains(event.relatedTarget)) { return; }
        zone.classList.remove('dragging');
      });
    });
    zone.addEventListener('drop', function (event) {
      var file = event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0];
      if (file) { uploadReference(file); }
    });

    // 整页也接受拖入，避免用户拖偏
    document.addEventListener('dragover', function (event) { event.preventDefault(); });
    document.addEventListener('drop', function (event) {
      var file = event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0];
      if (file && /^image\//.test(file.type)) {
        event.preventDefault();
        uploadReference(file);
      }
    });

    el('btn-clear-ref').addEventListener('click', clearReference);
    el('btn-analyze').addEventListener('click', analyzeReference);
  }

  function uploadReference(file) {
    if (!/^image\//.test(file.type)) {
      toast('只支持图片文件', true);
      return;
    }
    if (file.size > 20 * 1024 * 1024) {
      toast('图片超过 20MB', true);
      return;
    }
    el('gen-status').textContent = '正在上传参考图…';
    fetch('/api/images/upload', {
      method: 'POST',
      headers: { 'Content-Type': file.type || 'image/png' },
      body: file
    })
      .then(function (response) {
        if (!response.ok) { throw new Error('HTTP ' + response.status); }
        return response.json();
      })
      .then(function (data) {
        state.reference = data.file;
        state.referenceNote = '';
        var preview = el('ref-preview');
        preview.src = data.url + '?t=' + Date.now();
        preview.hidden = false;
        el('dropzone-inner').hidden = true;
        el('ref-actions').hidden = false;
        el('ref-note').hidden = true;
        el('gen-status').textContent = '参考图已就绪，生成时会先识别再重绘';
      })
      .catch(function (err) {
        el('gen-status').textContent = '';
        toast('上传失败：' + err.message, true);
      });
  }

  function clearReference() {
    state.reference = '';
    state.referenceNote = '';
    var preview = el('ref-preview');
    preview.hidden = true;
    preview.removeAttribute('src');
    el('dropzone-inner').hidden = false;
    el('ref-actions').hidden = true;
    el('ref-note').hidden = true;
    el('f-file').value = '';
    el('gen-status').textContent = '';
  }

  function analyzeReference() {
    if (!state.reference) { return; }
    el('gen-status').textContent = '正在识别参考图…';
    api('/api/images/analyze', { method: 'POST', body: { file: state.reference } })
      .then(function (data) {
        el('f-prompt').value = data.prompt;
        updatePromptCount();
        state.referenceNote = data.prompt;
        var note = el('ref-note');
        note.textContent = '识别结果：' + data.prompt;
        note.hidden = false;
        el('gen-status').textContent = '';
        toast('已写入提示词');
      })
      .catch(function (err) {
        el('gen-status').textContent = '';
        toast('识别失败：' + err.message, true);
      });
  }

  function updatePromptCount() {
    var value = el('f-prompt').value || '';
    el('prompt-count').textContent = String(value.length);
  }

  /* ---------------- 生成 ---------------- */

  function generate() {
    if (state.busy) { return; }
    var prompt = (el('f-prompt').value || '').trim();
    if (!prompt) {
      toast('先写一段提示词', true);
      el('f-prompt').focus();
      return;
    }
    var body = {
      prompt: prompt,
      negative_prompt: (el('f-negative').value || '').trim(),
      style: state.style,
      ratio: state.ratio,
      quality: state.quality,
      count: state.count,
      model: state.model,
      seed: Number(el('f-seed').value) || 0,
      reference: state.reference
    };

    state.busy = true;
    var button = el('btn-generate');
    button.disabled = true;
    button.textContent = '生成中…';
    el('gen-status').textContent = '正在出图，' + state.count + ' 张，通常 ' + (state.count > 1 ? '20–60' : '8–30') + ' 秒';

    api('/api/images/generate', { method: 'POST', body: body })
      .then(function (data) {
        lastItems = data.items || [];
        setTab('results');
        renderResults(data);
        if (data.errors && data.errors.length) {
          toast('部分失败：' + data.errors[0], true);
        } else {
          toast('生成完成，' + lastItems.length + ' 张');
        }
        loadHistory();
      })
      .catch(function (err) {
        toast('生成失败：' + err.message, true);
      })
      .then(function () {
        state.busy = false;
        button.disabled = false;
        button.textContent = '生成图片';
        el('gen-status').textContent = '';
      });
  }

  /* ---------------- 结果与历史 ---------------- */

  function tileHtml(item, index) {
    var meta = item.width + '×' + item.height + ' · seed ' + item.seed;
    return '' +
      '<div class="tile">' +
        '<img src="' + esc(item.url) + '" alt="" loading="lazy" data-open="' + esc(item.url) + '" data-meta="' + esc(meta) + '" />' +
        '<div class="tile-bar">' +
          '<b>' + esc(meta) + '</b>' +
          '<span class="tile-tools">' +
            '<button class="icon-btn" title="下载" data-download="' + esc(item.url) + '" data-name="' + esc(item.file) + '">' +
              '<svg viewBox="0 0 24 24"><path d="M12 4v11m0 0l-4-4m4 4l4-4"/><path d="M5 19h14"/></svg>' +
            '</button>' +
            '<button class="icon-btn" title="删除" data-delete="' + esc(item.id) + '">' +
              '<svg viewBox="0 0 24 24"><path d="M5 7h14M10 7V5h4v2M8 7l1 12h6l1-12"/></svg>' +
            '</button>' +
          '</span>' +
        '</div>' +
      '</div>';
  }

  function renderResults(data) {
    var gallery = el('gallery');
    var empty = el('images-empty');
    var items = data.items || [];
    el('result-title').textContent = items.length ? ('本次生成 ' + items.length + ' 张') : '本次没有产出';
    if (!items.length) {
      gallery.hidden = true;
      empty.hidden = false;
      return;
    }
    empty.hidden = true;
    gallery.hidden = false;
    gallery.innerHTML = items.map(tileHtml).join('');
  }

  function renderHistory(rows) {
    var gallery = el('gallery');
    var empty = el('images-empty');
    el('result-title').textContent = rows.length ? ('历史记录 · ' + rows.length + ' 张') : '还没有历史记录';
    if (!rows.length) {
      gallery.hidden = true;
      empty.hidden = false;
      return;
    }
    empty.hidden = true;
    gallery.hidden = false;
    gallery.innerHTML = rows.map(tileHtml).join('');
  }

  function loadHistory() {
    return api('/api/images/history?limit=40')
      .then(function (rows) {
        if (state.tab === 'history') { renderHistory(rows); }
        return rows;
      })
      .catch(function () { return []; });
  }

  function setTab(tab) {
    state.tab = tab;
    var buttons = el('tab-images').querySelectorAll('[data-tab]');
    for (var i = 0; i < buttons.length; i += 1) {
      buttons[i].classList.toggle('active', buttons[i].getAttribute('data-tab') === tab);
    }
    if (tab === 'history') {
      api('/api/images/history?limit=40')
        .then(renderHistory)
        .catch(function (err) { toast('读取历史失败：' + err.message, true); });
    } else if (lastItems.length) {
      renderResults({ items: lastItems });
    } else {
      el('gallery').hidden = true;
      el('images-empty').hidden = false;
      el('result-title').textContent = '还没有生成结果';
    }
  }

  function openLightbox(url, meta) {
    el('lightbox-img').src = url;
    el('lightbox-meta').textContent = meta || '';
    el('lightbox').classList.add('open');
  }

  function bindGallery() {
    var gallery = el('gallery');
    if (!gallery) { return; }
    gallery.addEventListener('click', function (event) {
      var open = event.target.closest('[data-open]');
      if (open) {
        openLightbox(open.getAttribute('data-open'), open.getAttribute('data-meta'));
        return;
      }
      var download = event.target.closest('[data-download]');
      if (download) {
        var link = document.createElement('a');
        link.href = download.getAttribute('data-download');
        link.download = download.getAttribute('data-name') || 'image.png';
        document.body.appendChild(link);
        link.click();
        link.remove();
        return;
      }
      var del = event.target.closest('[data-delete]');
      if (del) {
        var id = del.getAttribute('data-delete');
        api('/api/images/' + encodeURIComponent(id), { method: 'DELETE' })
          .then(function () {
            del.closest('.tile').remove();
            toast('已删除');
          })
          .catch(function (err) { toast('删除失败：' + err.message, true); });
      }
    });

    el('lightbox').addEventListener('click', function (event) {
      if (event.target === el('lightbox') || event.target.id === 'lightbox-close') {
        el('lightbox').classList.remove('open');
      }
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape') { el('lightbox').classList.remove('open'); }
    });
  }

  /* ---------------- 导航 ---------------- */

  function bindNav() {
    var rail = el('rail');
    rail.addEventListener('click', function (event) {
      var item = event.target.closest('[data-view]');
      if (!item) { return; }
      var view = item.getAttribute('data-view');
      var items = rail.querySelectorAll('[data-view]');
      for (var i = 0; i < items.length; i += 1) {
        items[i].classList.toggle('active', items[i].getAttribute('data-view') === view);
      }
      el('view-images').classList.toggle('active', view === 'images');
      el('view-video').classList.toggle('active', view === 'video');
      el('btn-new-video').hidden = view !== 'video';
      if (view === 'video' && window.__videoBoot) { window.__videoBoot(); }
    });
  }

  /* ---------------- 启动 ---------------- */

  function boot() {
    bindNav();
    bindDropzone();
    bindGallery();
    el('f-prompt').addEventListener('input', updatePromptCount);
    el('btn-generate').addEventListener('click', generate);
    el('btn-dice').addEventListener('click', function () {
      el('f-seed').value = String(Math.floor(Math.random() * 2147483000));
    });
    el('tab-images').addEventListener('click', function (event) {
      var button = event.target.closest('[data-tab]');
      if (button) { setTab(button.getAttribute('data-tab')); }
    });
    el('f-prompt').addEventListener('keydown', function (event) {
      if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') { generate(); }
    });
    loadOptions();
    loadHistory();
    // 支持 ?tab=history 直接打开历史，方便分享与截图
    try {
      var params = new URLSearchParams(window.location.search);
      if (params.get('tab') === 'history') { setTab('history'); }
    } catch (err) { /* 老浏览器忽略 */ }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
