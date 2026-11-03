/* 工作台前端：分类页（面包屑/标题/提示/分组卡片）+ 图片工具工作区。
   后端约定：
   GET  /api/images/options   预设
   POST /api/images/generate  生成
   POST /api/images/upload    原始二进制上传参考图
   POST /api/images/analyze   参考图 → 提示词
   GET  /api/images/history   历史
   DELETE /api/images/{id}    删除
*/
(function () {
  'use strict';

  /* ---------------- 图标 ---------------- */

  var ICONS = {
    image: '<rect x="3" y="4" width="18" height="16" rx="3"/><circle cx="8.5" cy="9.5" r="1.6"/><path d="M4 17l4.5-4.5 3 3 3-3L20 17"/>',
    remix: '<path d="M4 8h9l-2.5-2.5M20 16h-9l2.5 2.5"/><circle cx="18" cy="8" r="2.4"/><circle cx="6" cy="16" r="2.4"/>',
    video: '<rect x="3" y="5" width="13" height="14" rx="3"/><path d="M16 10l5-3v10l-5-3z"/>',
    cover: '<rect x="4" y="3" width="16" height="18" rx="3"/><path d="M8 8h8M8 12h8M8 16h4"/>',
    voice: '<path d="M12 15a3 3 0 003-3V6a3 3 0 10-6 0v6a3 3 0 003 3z"/><path d="M6 11a6 6 0 0012 0M12 17v4"/>',
    subtitle: '<rect x="3" y="5" width="18" height="14" rx="3"/><path d="M7 14h4M13 14h4"/>',
    copy: '<path d="M5 4h9l5 5v11a2 2 0 01-2 2H5a2 2 0 01-2-2V6a2 2 0 012-2z"/><path d="M13 4v6h6"/>',
    edit: '<path d="M4 7h10M4 12h16M4 17h7"/><circle cx="17" cy="7" r="2"/><circle cx="13" cy="17" r="2"/>',
    upscale: '<path d="M4 14v4a2 2 0 002 2h4M20 10V6a2 2 0 00-2-2h-4"/><path d="M9 15l6-6"/>',
    clone: '<circle cx="9" cy="9" r="3"/><path d="M4 19a5 5 0 0110 0"/><path d="M16 8a3 3 0 010 6"/><path d="M18 19a5 5 0 00-3-4.6"/>',
    script: '<rect x="4" y="3" width="16" height="18" rx="2"/><path d="M8 8h8M8 12h8M8 16h5"/>',
    frame: '<rect x="3" y="4" width="8" height="16" rx="2"/><rect x="13" y="4" width="8" height="16" rx="2"/>'
  };

  function icon(name, size) {
    var body = ICONS[name] || ICONS.image;
    var s = size || 11;
    return '<svg width="' + s + '" height="' + s + '" viewBox="0 0 24 24">' + body + '</svg>';
  }

  /* 图标配色：浅色底 + 饱和色图标，克制地给每类一点色彩提示 */
  var TONES = {
    blue: ['rgba(0, 113, 227, 0.12)', '#0071e3'],
    purple: ['rgba(175, 82, 222, 0.12)', '#af52de'],
    green: ['rgba(52, 199, 89, 0.14)', '#248a3d'],
    orange: ['rgba(255, 149, 0, 0.14)', '#b26a00'],
    pink: ['rgba(255, 45, 85, 0.12)', '#d61b47'],
    grey: ['rgba(0, 0, 0, 0.055)', '#86868b']
  };

  /* ---------------- 工具与分类 ---------------- */

  var TOOLS = {
    'image-generate': {
      name: '图片生成', sub: '文生图 · 参考图重绘', icon: 'image', ready: true, mode: 'generate', tone: 'blue',
      desc: '写描述直接出图；上传一张参考图，会先读懂画面再按新要求重画。',
      tags: ['已接通', 'flux', 'vision']
    },
    'video-pipeline': {
      name: '智能成片', sub: '新手入口', icon: 'video', ready: true, mode: 'video', tone: 'green',
      desc: '选题、脚本、素材、成片、发布五步走，每个环节都能回退重做。',
      tags: ['已接通', 'TTS + ffmpeg']
    },
    'video-text': {
      name: '文字成片', sub: '5 步向导', icon: 'video', ready: false, tone: 'blue',
      desc: '给一段文案，自动配音、配图、卡点，直接出竖屏成片。',
      tags: ['即将上线', '建议 15 秒起']
    },
    'video-image': {
      name: '图生视频', sub: '首帧驱动', icon: 'frame', ready: false, tone: 'blue',
      desc: '首帧图加运动描述，生成一段带镜头运动的短片。',
      tags: ['即将上线', '最低 5 秒']
    },
    'video-firstlast': {
      name: '首尾帧过渡', sub: 'HL', icon: 'frame', ready: false, tone: 'blue',
      desc: '给首帧与尾帧，自动补出中间过渡，适合转场与变身。',
      tags: ['即将上线']
    },
    'cover': {
      name: '平台封面', sub: '新手入口', icon: 'cover', ready: false, tone: 'orange',
      desc: '一张参考图加一个主题，生成小红书、抖音或快手封面。',
      tags: ['即将上线', '默认 9:16']
    },
    'edit-clip': {
      name: '智能剪辑', sub: '自动挑片', icon: 'edit', ready: false, tone: 'blue',
      desc: '导入长素材，按脚本自动切段、配字幕、卡点成片。',
      tags: ['即将上线']
    },
    'edit-upscale': {
      name: '视频高清放大', sub: '2–4 倍', icon: 'upscale', ready: false, tone: 'purple',
      desc: '低清素材放大到 1080p 或 4K，保留细节。',
      tags: ['即将上线']
    },
    'voice-tts': {
      name: '配音工坊', sub: '多音色', icon: 'voice', ready: true, mode: 'audio', tone: 'orange',
      desc: '文案转语音，可选音色、语速与音量，产物可试听下载。',
      tags: ['已接通', 'edge-tts']
    },
    'voice-clone': {
      name: '声音克隆', sub: '样音 10 秒', icon: 'clone', ready: false, tone: 'orange',
      desc: '给一段样音，复刻音色并用它读任意文案。',
      tags: ['即将上线']
    },
    'subtitle': {
      name: '字幕工坊', sub: '自动断句', icon: 'subtitle', ready: true, mode: 'subtitle', tone: 'green',
      desc: '贴口播稿，自动断句，导出 ASS / SRT / VTT 字幕。',
      tags: ['已接通', 'ASS · SRT · VTT']
    },
    'copy-detail': {
      name: '详情页文案', sub: '成交型', icon: 'copy', ready: false, tone: 'purple',
      desc: '按产品卖点写详情页文案与分镜脚本。',
      tags: ['即将上线']
    },
    'copy-rewrite': {
      name: '文案仿写', sub: '去重改写', icon: 'copy', ready: false, tone: 'purple',
      desc: '给一段参考文案，改写成自己的口吻。',
      tags: ['即将上线']
    }
  };

  var CATS = {
    home: {
      crumb: ['全部分类'],
      title: '首页',
      desc: '已经接通的功能排在前面，其余功能陆续开放。',
      notice: '生成结果都会进「历史记录」，图片可直接下载。',
      groups: [
        { name: '已接通', accent: '#30d158', tools: ['image-generate', 'video-pipeline', 'voice-tts', 'subtitle'] },
        { name: '即将上线', accent: '#c7c7cc', tools: ['cover', 'copy-detail'] }
      ]
    },
    image: {
      crumb: ['全部分类', '图片创作'],
      title: '图片创作',
      desc: '从文字或参考图出发，生成可直接使用的图片与封面。',
      notice: '图片按张计费，一次最多 4 张；上传参考图即切换为重绘，固定种子可复现同一张。',
      groups: [
        { name: '生成', accent: '#0a84ff', tools: ['image-generate'] },
        { name: '封面与投放', accent: '#ff9f0a', tools: ['cover'] }
      ]
    },
    video: {
      crumb: ['全部分类', '视频生成'],
      title: '视频生成',
      desc: '用文字、图片或首尾帧生成竖屏成片，支持多步骤回退。',
      notice: '默认输出 1080×1920 竖屏；配音与字幕在成片时一起合成。',
      groups: [
        { name: '文字与图片生成', accent: '#30d158', tools: ['video-pipeline', 'video-text', 'video-image'] },
        { name: '首尾帧过渡', accent: '#0a84ff', tools: ['video-firstlast'] }
      ]
    },
    edit: {
      crumb: ['全部分类', '视频编辑'],
      title: '视频编辑',
      desc: '对已有素材做剪辑、放大与替换，不重新生成画面。',
      notice: '上传前请确认素材格式为 mp4 / mov，单个文件建议不超过 500MB。',
      groups: [
        { name: '剪辑', accent: '#5ac8fa', tools: ['edit-clip'] },
        { name: '画质', accent: '#bf5af2', tools: ['edit-upscale'] }
      ]
    },
    audio: {
      crumb: ['全部分类', '声音工具'],
      title: '声音工具',
      desc: '把文案变成可用的配音，或复刻一个音色。',
      notice: '配音按字数计费；克隆音色需要至少 10 秒清晰样音。',
      groups: [
        { name: '配音', accent: '#ff9f0a', tools: ['voice-tts', 'voice-clone'] },
        { name: '字幕', accent: '#30d158', tools: ['subtitle'] }
      ]
    },
    text: {
      crumb: ['全部分类', '文案工具'],
      title: '文案工具',
      desc: '从选题到口播稿，都在这一个分类里完成。',
      notice: '文案生成会调用大模型，长文建议分段处理。',
      groups: [
        { name: '成交与口播', accent: '#bf5af2', tools: ['copy-detail', 'copy-rewrite'] }
      ]
    }
  };

  /* ---------------- 状态 ---------------- */

  var options = { ratios: [], styles: [], qualities: [], models: [] };
  var hasImageKey = true;
  var state = {
    cat: 'home',
    tool: 'image-generate',
    style: 'photo',
    ratio: '9:16',
    quality: 'standard',
    count: 1,
    model: 'klein',
    reference: '',
    tab: 'results',
    busy: false,
    // 配音工作台
    audioVoices: [],
    audioBusy: false,
    audioItems: [],
    // 字幕工作台
    subMode: 'sentence',
    subMaxChars: 16,
    subBusy: false,
    subResult: null
  };
  var lastItems = [];
  var historyRows = [];
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
    toastTimer = setTimeout(function () { node.className = 'toast'; }, isError ? 5200 : 2400);
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

  /* ---------------- 分类页渲染 ---------------- */

  function cardHtml(toolKey) {
    var tool = TOOLS[toolKey];
    if (!tool) { return ''; }
    var ready = tool.ready;
    var cls = 'tool-card' + (ready ? '' : ' soon');
    var tags = tool.tags.map(function (tag) {
      var kind = tag === '已接通' ? ' hot' : (tag === '即将上线' ? '' : ' new');
      return '<span class="card-tag' + kind + '">' + esc(tag) + '</span>';
    }).join('');
    var tone = ready ? (tool.tone || 'blue') : 'grey';
    var bg = TONES[tone][0];
    var fg = TONES[tone][1];
    return '' +
      '<button class="' + cls + '" data-tool="' + esc(toolKey) + '">' +
        '<div class="card-top">' +
          '<span class="card-clock" style="--icon-bg:' + bg + ';--icon-fg:' + fg + '">' + icon(tool.icon) + '</span>' +
          '<span class="card-title">' + esc(tool.name) + '</span>' +
          '<span class="card-arrow">↗</span>' +
        '</div>' +
        (tool.sub ? '<div class="card-sub">' + esc(tool.sub) + '</div>' : '') +
        '<div class="card-desc">' + esc(tool.desc) + '</div>' +
        '<div class="card-tags">' + tags + '</div>' +
      '</button>';
  }

  function renderCategory(catKey) {
    var cat = CATS[catKey] || CATS.home;
    state.cat = catKey;

    // 面包屑
    el('crumb').innerHTML = cat.crumb.map(function (item, index) {
      var last = index === cat.crumb.length - 1;
      var node = last
        ? '<span class="now">' + esc(item) + '</span>'
        : '<button data-cat-jump="' + (index === 0 ? 'home' : catKey) + '">' + esc(item) + '</button>';
      return (index ? '<span class="sep">/</span>' : '') + node;
    }).join('');

    el('page-title').textContent = cat.title;
    el('page-desc').textContent = cat.desc;

    var total = cat.groups.reduce(function (sum, group) { return sum + group.tools.length; }, 0);
    el('page-count').textContent = total + ' 个功能';

    el('page-notice').innerHTML = '<span>·</span><span>' + esc(cat.notice) + '</span>';

    el('group-list').innerHTML = cat.groups.map(function (group) {
      return '' +
        '<div class="group-block">' +
          '<div class="group-head" style="--accent:' + esc(group.accent) + '">' + esc(group.name) + '</div>' +
          '<div class="card-grid">' + group.tools.map(cardHtml).join('') + '</div>' +
        '</div>';
    }).join('');

    // 侧栏高亮
    var items = document.querySelectorAll('.rail-item[data-cat]');
    for (var i = 0; i < items.length; i += 1) {
      items[i].classList.toggle('active', items[i].getAttribute('data-cat') === catKey);
    }
  }

  /* ---------------- 视图切换 ---------------- */

  function switchView(name) {
    el('view-home').classList.toggle('active', name === 'home');
    el('view-studio').classList.toggle('active', name === 'studio');
    el('view-video').classList.toggle('active', name === 'video');
    el('view-audio').classList.toggle('active', name === 'audio');
    el('view-subtitle').classList.toggle('active', name === 'subtitle');
    el('btn-new-video').hidden = name !== 'video';
  }

  // 工具归属哪个分类（用于侧栏高亮与返回时的落点）
  // home 是聚合页，只有别的分类都不认领时才回退到它
  function catOfTool(toolKey) {
    var key;
    var groups;
    var i;
    for (key in CATS) {
      if (!Object.prototype.hasOwnProperty.call(CATS, key) || key === 'home') { continue; }
      groups = CATS[key].groups;
      for (i = 0; i < groups.length; i += 1) {
        if (groups[i].tools.indexOf(toolKey) >= 0) { return key; }
      }
    }
    return 'home';
  }

  function highlightRail(catKey) {
    var items = document.querySelectorAll('.rail-item[data-cat]');
    for (var i = 0; i < items.length; i += 1) {
      items[i].classList.toggle('active', items[i].getAttribute('data-cat') === catKey);
    }
  }

  // 旧链接兼容：参考图重绘已并入图片生成
  function normalizeTool(toolKey) {
    return toolKey === 'image-remix' ? 'image-generate' : toolKey;
  }

  function openTool(rawKey) {
    var toolKey = normalizeTool(rawKey);
    var tool = TOOLS[toolKey];
    if (!tool) { return; }
    if (!tool.ready) {
      toast(tool.name + ' 还没接通，先用已上线的功能');
      return;
    }
    state.cat = catOfTool(toolKey);
    highlightRail(state.cat);
    if (tool.mode === 'video') {
      switchView('video');
      if (window.__videoBoot) { window.__videoBoot(); }
      return;
    }
    if (tool.mode === 'audio') {
      openAudioWorkspace();
      return;
    }
    if (tool.mode === 'subtitle') {
      openSubtitleWorkspace();
      return;
    }
    state.tool = toolKey;
    el('panel-title').textContent = tool.name;
    el('panel-desc').textContent = tool.desc;
    el('tool-eyebrow').textContent = tool.name;
    el('ref-group').hidden = false;
    el('f-prompt').placeholder = state.reference
      ? '想怎么改，例如：换成秋天傍晚的氛围，保留人物和构图'
      : '描述你想要的画面，例如：清晨书桌上摊开的笔记本与一杯咖啡，暖光侧照，浅景深';
    renderSamples();
    updatePromptMode();
    switchView('studio');
  }

  /* ---------------- 参数面板 ---------------- */

  function getStyle() { return state.style; }
  function getRatio() { return state.ratio; }
  function getQuality() { return state.quality; }
  function getCount() { return String(state.count); }
  function getModel() { return state.model; }

  function renderChips(containerId, items, getActive, onPick) {
    var box = el(containerId);
    if (!box) { return; }
    var activeKey = getActive();
    box.innerHTML = items.map(function (item) {
      var active = item.key === activeKey ? ' active' : '';
      return '<button class="chip' + active + '" data-key="' + esc(item.key) + '">' + esc(item.label) + '</button>';
    }).join('');
    box.onclick = function (event) {
      var chip = event.target.closest('[data-key]');
      if (!chip) { return; }
      onPick(chip.getAttribute('data-key'));
      renderChips(containerId, items, getActive, onPick);
    };
  }

  function renderSegmented(containerId, items, getActive, onPick) {
    var box = el(containerId);
    if (!box) { return; }
    var activeKey = getActive();
    box.innerHTML = items.map(function (item) {
      var active = item.key === activeKey ? ' active' : '';
      return '<button class="seg-item' + active + '" data-key="' + esc(item.key) + '">' + esc(item.label) + '</button>';
    }).join('');
    box.onclick = function (event) {
      var item = event.target.closest('[data-key]');
      if (!item) { return; }
      onPick(item.getAttribute('data-key'));
      renderSegmented(containerId, items, getActive, onPick);
    };
  }

  function paintControls() {
    renderChips('f-ratio', options.ratios.map(function (r) {
      return { key: r.key, label: r.label };
    }), getRatio, function (key) {
      state.ratio = key;
      var found = options.ratios.filter(function (r) { return r.key === key; })[0];
      if (found) { el('ratio-hint').textContent = found.width + ' × ' + found.height; }
    });

    renderChips('f-style', options.styles, getStyle, function (key) { state.style = key; });

    renderSegmented('f-count', [
      { key: '1', label: '1 张' }, { key: '2', label: '2 张' },
      { key: '3', label: '3 张' }, { key: '4', label: '4 张' }
    ], getCount, function (key) { state.count = Number(key); });

    renderSegmented('f-quality', options.qualities, getQuality, function (key) { state.quality = key; });
    renderSegmented('f-model', options.models, getModel, function (key) { state.model = key; });
  }

  var SAMPLES = {
    generate: [
      '清晨书桌上摊开的笔记本、一杯冒热气的咖啡，暖光侧照，浅景深',
      '深夜便利店的玻璃窗，雨滴与霓虹反光，电影感冷色调',
      '毛线质感的小熊坐在窗台，柔光，浅色背景，适合做头像'
    ],
    remix: [
      '换成秋天傍晚的氛围，暖黄逆光，保留人物姿态与构图',
      '改成水彩插画风格，纸纹质感，颜色更克制',
      '改成黑白胶片质感，高对比，颗粒明显'
    ]
  };

  function renderSamples() {
    var list = state.reference ? SAMPLES.remix : SAMPLES.generate;
    el('f-samples').innerHTML = list.map(function (text) {
      return '<button class="sample-chip" data-sample="' + esc(text) + '">' + esc(text) + '</button>';
    }).join('');
  }

  // 参考图决定当前是"文生图"还是"按图重绘"，提示词与按钮都跟着变
  function updatePromptMode() {
    var hasRef = !!state.reference;
    var button = el('btn-generate');
    if (!state.busy) {
      if (!hasImageKey) {
        button.disabled = true;
        button.textContent = '未配置出图密钥';
      } else {
        button.disabled = false;
        button.textContent = hasRef ? '按参考图重绘' : '生成图片';
      }
    }
    el('f-prompt').placeholder = hasRef
      ? '想怎么改，例如：换成秋天傍晚的氛围，保留人物和构图'
      : '描述你想要的画面，例如：清晨书桌上摊开的笔记本与一杯咖啡，暖光侧照，浅景深';
    renderSamples();
  }

  function loadOptions() {
    return api('/api/images/options').then(function (data) {
      options = data;
      paintControls();
      var first = options.ratios.filter(function (r) { return r.key === '9:16'; })[0] || options.ratios[0];
      if (first) { el('ratio-hint').textContent = first.width + ' × ' + first.height; }
      hasImageKey = !!data.has_image_key;
      applyKeyGuard();
      if (!data.has_image_key) { toast('没有配置 IMAGE_API_KEY，出图会失败', true); }
      return data;
    }).catch(function (err) {
      toast('读取预设失败：' + err.message, true);
    });
  }

  // 未配置出图密钥时禁用生成按钮，而不是等用户点了才报错。
  function applyKeyGuard() {
    var button = el('btn-generate');
    if (!button) { return; }
    if (!hasImageKey && !state.busy) {
      button.disabled = true;
      button.textContent = '未配置出图密钥';
    } else if (!state.busy) {
      button.disabled = false;
      updatePromptMode();
    }
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
    if (!/^image\//.test(file.type)) { toast('只支持图片文件', true); return; }
    if (file.size > 20 * 1024 * 1024) { toast('图片超过 20MB', true); return; }
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
        var preview = el('ref-preview');
        preview.src = data.url + '?t=' + Date.now();
        preview.hidden = false;
        el('dropzone-inner').hidden = true;
        el('ref-actions').hidden = false;
        el('ref-note').hidden = true;
        el('gen-status').textContent = '参考图已就绪，生成时会先识别再重绘';
        updatePromptMode();
      })
      .catch(function (err) {
        el('gen-status').textContent = '';
        toast('上传失败：' + err.message, true);
      });
  }

  function clearReference() {
    state.reference = '';
    var preview = el('ref-preview');
    preview.hidden = true;
    preview.removeAttribute('src');
    el('dropzone-inner').hidden = false;
    el('ref-actions').hidden = true;
    el('ref-note').hidden = true;
    el('f-file').value = '';
    el('gen-status').textContent = '';
    updatePromptMode();
  }

  function analyzeReference() {
    if (!state.reference) { return; }
    el('gen-status').textContent = '正在识别参考图…';
    api('/api/images/analyze', { method: 'POST', body: { file: state.reference } })
      .then(function (data) {
        el('f-prompt').value = data.prompt;
        updatePromptCount();
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
    el('prompt-count').textContent = String((el('f-prompt').value || '').length);
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
      .catch(function (err) { toast('生成失败：' + err.message, true); })
      .then(function () {
        state.busy = false;
        button.disabled = false;
        updatePromptMode();
        el('gen-status').textContent = '';
      });
  }

  /* ---------------- 结果 ---------------- */

  function tileHtml(item) {
    var meta = item.width + '×' + item.height + ' · seed ' + item.seed;
    return '' +
      '<div class="tile">' +
        '<img src="' + esc(item.url) + '" alt="" loading="lazy" data-open="' + esc(item.url) + '" data-meta="' + esc(meta) + '" />' +
        '<div class="tile-bar">' +
          '<b>' + esc(meta) + '</b>' +
          '<span class="tile-tools">' +
            '<button class="icon-btn" title="复用参数" data-reuse="' + esc(item.id) + '">' +
              '<svg viewBox="0 0 24 24"><path d="M4 8h9l-2.5-2.5M20 16h-9l2.5 2.5"/><circle cx="18" cy="8" r="2.4"/><circle cx="6" cy="16" r="2.4"/></svg>' +
            '</button>' +
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
    if (!items.length) { gallery.hidden = true; empty.hidden = false; return; }
    empty.hidden = true;
    gallery.hidden = false;
    gallery.innerHTML = items.map(tileHtml).join('');
  }

  function renderHistory(rows) {
    var gallery = el('gallery');
    var empty = el('images-empty');
    el('result-title').textContent = rows.length ? ('历史记录 · ' + rows.length + ' 张') : '还没有历史记录';
    if (!rows.length) { gallery.hidden = true; empty.hidden = false; return; }
    empty.hidden = true;
    gallery.hidden = false;
    gallery.innerHTML = rows.map(tileHtml).join('');
  }

  function loadHistory() {
    return api('/api/images/history?limit=40')
      .then(function (rows) {
        historyRows = rows || [];
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
        .then(function (rows) { historyRows = rows || []; renderHistory(rows); })
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

  // 从一条历史记录里把提示词、画风、尺寸、质量、模型、种子回填到左侧面板，
  // 让用户能"照上次再来一张"或微调后重试。
  function restoreParams(imageId) {
    var rows = historyRows.concat(lastItems);
    var item = null;
    for (var i = 0; i < rows.length; i += 1) {
      if (rows[i] && rows[i].id === imageId) { item = rows[i]; break; }
    }
    if (!item) { toast('找不到这条历史参数', true); return; }

    el('f-prompt').value = item.prompt || '';
    el('f-negative').value = item.negative_prompt || '';
    if (item.style) { state.style = item.style; }
    if (item.quality) { state.quality = item.quality; }
    if (item.model) {
      // 历史里存的是完整模型名（如 flux-3-image-klein），要映射回预设 key。
      var matched = null;
      options.models.forEach(function (m) {
        if (m.key === item.model || m.label === item.model) { matched = m.key; }
      });
      // 已知别名兜底
      if (!matched) {
        if (/klein/.test(item.model)) { matched = 'klein'; }
        else if (/schnell/.test(item.model)) { matched = 'schnell'; }
        else if (/dev/.test(item.model)) { matched = 'dev'; }
      }
      if (matched) { state.model = matched; }
    }
    // 尺寸：历史里存的是宽高，回推成最接近的预设比例。
    if (item.width && item.height) {
      var best = null;
      options.ratios.forEach(function (r) {
        if (r.width === item.width && r.height === item.height) { best = r; }
      });
      if (best) {
        state.ratio = best.key;
        el('ratio-hint').textContent = best.width + ' × ' + best.height;
      }
    }
    el('f-seed').value = String(item.seed || 0);
    state.count = 1;
    state.reference = '';
    clearReference();
    paintControls();
    updatePromptCount();
    updatePromptMode();
    toast('已回填参数，可以再生成一次');
    switchView('studio');
  }

  function bindGallery() {
    var gallery = el('gallery');
    if (!gallery) { return; }
    gallery.addEventListener('click', function (event) {
      var open = event.target.closest('[data-open]');
      if (open) { openLightbox(open.getAttribute('data-open'), open.getAttribute('data-meta')); return; }
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
            var tile = del.closest('.tile');
            if (tile) { tile.remove(); }
            toast('已删除');
            loadHistory();
          })
          .catch(function (err) { toast('删除失败：' + err.message, true); });
        return;
      }
      var reuse = event.target.closest('[data-reuse]');
      if (reuse) {
        restoreParams(reuse.getAttribute('data-reuse'));
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

  /* ---------------- 配音工作区 ---------------- */

  var AUDIO_SAMPLES = [
    '大家好，欢迎来到本期内容。今天我们用三分钟，把这件事讲清楚。',
    '产品已经上线，点击下方链接即可体验。',
    '这个开场白适合口播：先抛问题，再给答案，最后留个钩子。'
  ];

  function openAudioWorkspace() {
    el('audio-title').textContent = TOOLS['voice-tts'].name;
    el('audio-eyebrow').textContent = TOOLS['voice-tts'].name;
    renderAudioSamples();
    updateAudioCount();
    loadAudioVoices();
    loadAudioHistory();
    switchView('audio');
  }

  function renderAudioSamples() {
    var box = el('a-samples');
    if (!box) { return; }
    box.innerHTML = AUDIO_SAMPLES.map(function (text) {
      return '<button class="sample-chip" data-audio-sample="' + esc(text) + '">' + esc(text) + '</button>';
    }).join('');
  }

  function updateAudioCount() {
    el('a-count').textContent = String((el('a-text').value || '').length);
  }

  function loadAudioVoices() {
    return api('/api/audio/voices').then(function (data) {
      state.audioVoices = data.voices || [];
      var select = el('a-voice');
      select.innerHTML = state.audioVoices.map(function (v) {
        return '<option value="' + esc(v.id) + '">' + esc(v.label) + '</option>';
      }).join('');
      return data;
    }).catch(function (err) {
      toast('读取音色失败：' + err.message, true);
    });
  }

  function audioItemHtml(item) {
    var dur = (item.duration_ms / 1000).toFixed(1);
    return '' +
      '<div class="audio-item" data-audio-id="' + esc(item.id) + '">' +
        '<div class="audio-meta">' +
          '<div class="audio-title">' + esc(item.text) + '</div>' +
          '<div class="audio-sub">' + esc(item.voice) + ' · 语速 ' + esc(item.rate) + ' · ' + dur + ' 秒</div>' +
        '</div>' +
        '<div class="inline-row">' +
          '<audio controls preload="none" src="' + esc(item.url) + '"></audio>' +
          '<button class="icon-btn" title="下载" data-audio-download="' + esc(item.url) + '" data-name="' + esc(item.file) + '">' +
            '<svg viewBox="0 0 24 24"><path d="M12 4v11m0 0l-4-4m4 4l4-4"/><path d="M5 19h14"/></svg>' +
          '</button>' +
          '<button class="icon-btn" title="删除" data-audio-delete="' + esc(item.id) + '">' +
            '<svg viewBox="0 0 24 24"><path d="M5 7h14M10 7V5h4v2M8 7l1 12h6l1-12"/></svg>' +
          '</button>' +
        '</div>' +
      '</div>';
  }

  function renderAudioItems(items) {
    var list = el('audio-list');
    var empty = el('audio-empty');
    state.audioItems = items || [];
    el('a-result-title').textContent = items.length ? ('配音记录 · ' + items.length + ' 条') : '还没有配音';
    if (!items.length) { list.hidden = true; empty.hidden = false; return; }
    empty.hidden = true;
    list.hidden = false;
    list.innerHTML = items.map(audioItemHtml).join('');
  }

  function loadAudioHistory() {
    return api('/api/audio/history?limit=30')
      .then(function (rows) { renderAudioItems(rows); return rows; })
      .catch(function () { return []; });
  }

  function generateAudio() {
    if (state.audioBusy) { return; }
    var text = (el('a-text').value || '').trim();
    if (!text) { toast('先写点要念的文案', true); el('a-text').focus(); return; }
    var voice = el('a-voice').value || '';
    var rate = Number(el('a-rate').value) || 0;
    var volume = Number(el('a-volume').value) || 0;

    state.audioBusy = true;
    var button = el('btn-audio-generate');
    button.disabled = true;
    button.textContent = '合成中…';
    el('a-status').textContent = '正在合成语音，通常 2–8 秒';

    api('/api/audio/tts', { method: 'POST', body: { text: text, voice: voice, rate: rate, volume: volume } })
      .then(function (item) {
        var items = [item].concat(state.audioItems.filter(function (row) { return row.id !== item.id; }));
        renderAudioItems(items);
        toast('合成完成，' + (item.duration_ms / 1000).toFixed(1) + ' 秒');
        loadAudioHistory();
      })
      .catch(function (err) { toast('合成失败：' + err.message, true); })
      .then(function () {
        state.audioBusy = false;
        button.disabled = false;
        button.textContent = '合成语音';
        el('a-status').textContent = '';
      });
  }

  function bindAudioWorkspace() {
    el('btn-audio-back').addEventListener('click', function () {
      renderCategory(state.cat || 'audio');
      switchView('home');
    });
    el('a-text').addEventListener('input', updateAudioCount);
    el('a-text').addEventListener('keydown', function (event) {
      if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') { generateAudio(); }
    });
    el('a-rate').addEventListener('input', function () { el('a-rate-val').textContent = el('a-rate').value; });
    el('a-volume').addEventListener('input', function () { el('a-volume-val').textContent = el('a-volume').value; });
    el('a-samples').addEventListener('click', function (event) {
      var chip = event.target.closest('[data-audio-sample]');
      if (!chip) { return; }
      el('a-text').value = chip.getAttribute('data-audio-sample');
      updateAudioCount();
      el('a-text').focus();
    });
    el('btn-audio-generate').addEventListener('click', generateAudio);

    var list = el('audio-list');
    list.addEventListener('click', function (event) {
      var download = event.target.closest('[data-audio-download]');
      if (download) {
        var link = document.createElement('a');
        link.href = download.getAttribute('data-audio-download');
        link.download = download.getAttribute('data-name') || 'speech.wav';
        document.body.appendChild(link); link.click(); link.remove();
        return;
      }
      var del = event.target.closest('[data-audio-delete]');
      if (del) {
        var id = del.getAttribute('data-audio-delete');
        api('/api/audio/' + encodeURIComponent(id), { method: 'DELETE' })
          .then(function () {
            var row = del.closest('.audio-item');
            if (row) { row.remove(); }
            toast('已删除');
            loadAudioHistory();
          })
          .catch(function (err) { toast('删除失败：' + err.message, true); });
      }
    });
  }

  /* ---------------- 字幕工作区 ---------------- */

  var SUB_MODES = [
    { key: 'sentence', label: '整句' },
    { key: 'word', label: '逐词' }
  ];

  function openSubtitleWorkspace() {
    el('subtitle-title').textContent = TOOLS['subtitle'].name;
    el('subtitle-eyebrow').textContent = TOOLS['subtitle'].name;
    renderSubModes();
    updateSubCount();
    switchView('subtitle');
  }

  function renderSubModes() {
    var box = el('s-mode');
    if (!box) { return; }
    box.innerHTML = SUB_MODES.map(function (m) {
      var active = m.key === state.subMode ? ' active' : '';
      return '<button class="seg-item' + active + '" data-sub-mode="' + esc(m.key) + '">' + esc(m.label) + '</button>';
    }).join('');
  }

  function updateSubCount() {
    el('s-count').textContent = String((el('s-text').value || '').length);
  }

  function cueTime(ms) {
    var total = Math.max(0, ms || 0);
    var m = Math.floor(total / 60000);
    var s = Math.floor((total % 60000) / 1000);
    var cs = Math.floor((total % 1000) / 10);
    return (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s + '.' + (cs < 10 ? '0' : '') + cs;
  }

  function renderSubtitleResult(item) {
    var panel = el('subtitle-panel');
    var empty = el('subtitle-empty');
    state.subResult = item;
    if (!item) { panel.hidden = true; empty.hidden = false; return; }
    empty.hidden = true;
    panel.hidden = false;
    el('s-result-title').textContent = '共 ' + item.cue_count + ' 条 · ' + (item.duration_ms / 1000).toFixed(1) + ' 秒';

    var buttons = ['ass', 'srt', 'vtt'].map(function (ext) {
      var url = (item.urls && item.urls[ext]) || '';
      return '<button class="btn btn-secondary btn-sm" data-sub-download="' + esc(url) + '" data-name="' + esc(item.base) + '.' + ext + '">下载 ' + ext.toUpperCase() + '</button>';
    }).join('');

    var rows = (item.cues || []).map(function (cue) {
      return '<div class="cue-row"><span class="cue-time">' + cueTime(cue.start_ms) + ' → ' + cueTime(cue.end_ms) + '</span>' +
        '<span class="cue-text">' + esc(cue.text) + '</span></div>';
    }).join('');

    panel.innerHTML = '' +
      '<div class="subtitle-toolbar">' +
        '<span class="subtle">' + esc(item.mode === 'word' ? '逐词切分' : '整句切分') + ' · 每行 ' + item.max_chars + ' 字</span>' +
        buttons +
      '</div>' +
      '<div class="cue-list">' + rows + '</div>';
  }

  function buildSubtitle() {
    if (state.subBusy) { return; }
    var text = (el('s-text').value || '').trim();
    if (!text) { toast('先贴一段口播稿', true); el('s-text').focus(); return; }
    var maxChars = Number(el('s-maxchars').value) || 16;

    state.subBusy = true;
    var button = el('btn-subtitle-build');
    button.disabled = true;
    button.textContent = '生成中…';
    el('s-status').textContent = '正在断句…';

    api('/api/subtitle/build', { method: 'POST', body: { text: text, mode: state.subMode, max_chars: maxChars } })
      .then(function (item) {
        renderSubtitleResult(item);
        toast('生成完成，共 ' + item.cue_count + ' 条');
      })
      .catch(function (err) { toast('生成失败：' + err.message, true); })
      .then(function () {
        state.subBusy = false;
        button.disabled = false;
        button.textContent = '生成字幕';
        el('s-status').textContent = '';
      });
  }

  function bindSubtitleWorkspace() {
    el('btn-subtitle-back').addEventListener('click', function () {
      renderCategory(state.cat || 'audio');
      switchView('home');
    });
    el('s-text').addEventListener('input', updateSubCount);
    el('s-text').addEventListener('keydown', function (event) {
      if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') { buildSubtitle(); }
    });
    el('s-mode').addEventListener('click', function (event) {
      var item = event.target.closest('[data-sub-mode]');
      if (!item) { return; }
      state.subMode = item.getAttribute('data-sub-mode');
      renderSubModes();
    });
    el('s-maxchars').addEventListener('input', function () {
      state.subMaxChars = Number(el('s-maxchars').value) || 16;
      el('s-maxchars-val').textContent = String(state.subMaxChars);
    });
    el('btn-subtitle-build').addEventListener('click', buildSubtitle);
    el('subtitle-panel').addEventListener('click', function (event) {
      var download = event.target.closest('[data-sub-download]');
      if (!download) { return; }
      var link = document.createElement('a');
      link.href = download.getAttribute('data-sub-download');
      link.download = download.getAttribute('data-name') || 'subtitle.txt';
      document.body.appendChild(link); link.click(); link.remove();
    });
  }

  /* ---------------- 导航 ---------------- */

  function bindNav() {
    var rail = el('rail');
    rail.addEventListener('click', function (event) {
      var item = event.target.closest('[data-cat]');
      if (!item) { return; }
      var cat = item.getAttribute('data-cat');
      renderCategory(cat);
      switchView('home');
    });

    el('crumb').addEventListener('click', function (event) {
      var jump = event.target.closest('[data-cat-jump]');
      if (jump) { renderCategory(jump.getAttribute('data-cat-jump')); }
    });

    el('group-list').addEventListener('click', function (event) {
      var card = event.target.closest('[data-tool]');
      if (card) { openTool(card.getAttribute('data-tool')); }
    });

    el('btn-back').addEventListener('click', function () {
      renderCategory(state.cat || 'home');
      switchView('home');
    });

    el('f-samples').addEventListener('click', function (event) {
      var chip = event.target.closest('[data-sample]');
      if (!chip) { return; }
      el('f-prompt').value = chip.getAttribute('data-sample');
      updatePromptCount();
      el('f-prompt').focus();
    });
  }

  /* ---------------- 启动 ---------------- */

  function boot() {
    bindNav();
    bindDropzone();
    bindGallery();
    bindAudioWorkspace();
    bindSubtitleWorkspace();
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

    renderCategory('home');
    renderSamples();
    loadOptions();
    loadHistory();
    loadAudioVoices();
    el('s-maxchars-val').textContent = String(state.subMaxChars);

    try {
      var params = new URLSearchParams(window.location.search);
      var tool = params.get('tool');
      if (tool) { openTool(tool); }
      else if (params.get('cat')) { renderCategory(params.get('cat')); }
      if (params.get('tab') === 'history') { setTab('history'); }
    } catch (err) { /* 忽略 */ }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
