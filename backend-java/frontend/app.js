/* Multi-Agent Video Workflow 前端：原生 JS，无构建依赖。
   与后端约定：
   - GET  /api/health
   - GET  /api/tasks
   - GET  /api/tasks/{id}
   - POST /api/tasks                      {brief, style}
   - POST /api/tasks/{id}/advance?auto=  推进到下一个 GATE
   - POST /api/tasks/{id}/gates/{step}   {decision, note, payload}
   - GET  /api/tasks/{id}/materials/{index}   素材原图
   - GET  /api/tasks/{id}/artifacts/{aid}     成片/封面
*/
(function () {
  'use strict';

  var STEP_ORDER = ['TOPIC', 'SCRIPT', 'MATERIAL', 'RENDER', 'PUBLISH'];
  var STEP_LABELS = {
    TOPIC: '选题', SCRIPT: '脚本', MATERIAL: '素材', RENDER: '成片', PUBLISH: '发布'
  };
  var TASK_BADGE = {
    WAITING_INPUT: ['waiting', '等待确认'],
    RUNNING: ['running', '生成中'],
    DONE: ['done', '已完成'],
    FAILED: ['failed', '失败']
  };

  var state = { tasks: [], task: null, health: null, busy: false };
  var edit = { topicIndex: null, script: null, shots: null };
  var toastTimer = null;

  function el(id) {
    return document.getElementById(id);
  }

  function esc(value) {
    return String(value === null || value === undefined ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function toast(message, isError) {
    var node = el('toast');
    node.textContent = message;
    node.className = 'toast show' + (isError ? ' error' : '');
    if (toastTimer) {
      clearTimeout(toastTimer);
    }
    toastTimer = setTimeout(function () {
      node.className = 'toast';
    }, isError ? 5200 : 2600);
  }

  function api(path, options) {
    var opts = options || {};
    var init = { method: opts.method || 'GET', headers: {} };
    if (opts.body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(opts.body);
    }
    return fetch(path, init).then(function (response) {
      return response.text().then(function (text) {
        var data = null;
        if (text) {
          try {
            data = JSON.parse(text);
          } catch (err) {
            data = { detail: text };
          }
        }
        if (!response.ok) {
          var detail = data && data.detail ? data.detail : 'HTTP ' + response.status;
          throw new Error(typeof detail === 'string' ? detail : JSON.stringify(detail));
        }
        return data;
      });
    });
  }

  /* ---------------- 顶栏健康检查 ---------------- */

  function refreshHealth() {
    api('/api/health')
      .then(function (data) {
        state.health = data;
        var dot = el('health').querySelector('.dot');
        dot.className = 'dot ok';
        var bits = [];
        bits.push(data.has_llm_key ? '模型已接' : '模板模式');
        bits.push('TTS ' + data.tts_provider);
        bits.push(data.video[0] + '×' + data.video[1]);
        el('health-text').textContent = '服务正常 · ' + bits.join(' · ');
      })
      .catch(function () {
        el('health').querySelector('.dot').className = 'dot bad';
        el('health-text').textContent = '服务未响应';
      });
  }

  /* ---------------- 任务列表 ---------------- */

  function loadTasks() {
    return api('/api/tasks?limit=30')
      .then(function (rows) {
        state.tasks = rows || [];
        renderTasks();
        return rows;
      })
      .catch(function (err) {
        toast('读取任务失败：' + err.message, true);
      });
  }

  function renderTasks() {
    var box = el('task-list');
    if (!state.tasks.length) {
      box.innerHTML = '<div class="subtle" style="padding:14px 4px">还没有任务，点右上角新建。</div>';
      return;
    }
    var html = '';
    state.tasks.forEach(function (task) {
      var badge = TASK_BADGE[task.status] || ['idle', task.status];
      var active = state.task && state.task.task_id === task.id ? ' active' : '';
      html += '<div class="task-item' + active + '" data-task="' + task.id + '">';
      html += '<div class="task-item-head">';
      html += '<span class="task-id">#' + task.id + '</span>';
      html += '<span class="badge ' + badge[0] + '">' + esc(badge[1]) + '</span>';
      html += '</div>';
      html += '<div class="task-brief">' + esc(task.brief) + '</div>';
      html += '<div class="task-meta"><span>' + esc((task.updated_at || '').replace('T', ' ').slice(0, 16)) + '</span></div>';
      html += '</div>';
    });
    box.innerHTML = html;
  }

  /* ---------------- 任务详情 ---------------- */

  function openTask(id) {
    return api('/api/tasks/' + id)
      .then(function (task) {
        state.task = task;
        syncEditState(task);
        renderTasks();
        renderStage();
      })
      .catch(function (err) {
        toast('读取任务失败：' + err.message, true);
      });
  }

  function stepOf(task, code) {
    for (var i = 0; i < task.steps.length; i += 1) {
      if (task.steps[i].code === code) {
        return task.steps[i];
      }
    }
    return null;
  }

  function currentStep(task) {
    for (var i = 0; i < STEP_ORDER.length; i += 1) {
      var row = stepOf(task, STEP_ORDER[i]);
      if (row && row.status !== 'SUCCEEDED') {
        return row;
      }
    }
    // 五步全通过：不再停在发布步骤，交给 renderStage 展示成片回顾。
    return null;
  }

  function syncEditState(task) {
    var topic = stepOf(task, 'TOPIC');
    var script = stepOf(task, 'SCRIPT');
    var material = stepOf(task, 'MATERIAL');
    edit.topicIndex =
      topic && topic.payload && topic.payload.chosen_index !== null &&
      topic.payload.chosen_index !== undefined
        ? topic.payload.chosen_index
        : null;
    edit.script =
      script && script.payload && script.payload.script
        ? JSON.parse(JSON.stringify(script.payload.script))
        : null;
    edit.shots =
      material && material.payload && material.payload.shots
        ? JSON.parse(JSON.stringify(material.payload.shots))
        : null;
  }

  function renderStage() {
    var stage = el('stage');
    var task = state.task;
    if (!task) {
      stage.innerHTML =
        '<div class="panel"><div class="empty"><div class="empty-mark">🎬</div>' +
        '<div class="empty-title">选一条任务，或者新建一条</div>' +
        '<div class="subtle">选题 → 脚本 → 素材 → 成片 → 发布，每一步都停下来等你确认</div></div></div>';
      return;
    }
    var step = currentStep(task);
    var badge = TASK_BADGE[task.status] || ['idle', task.status];
    var html = '';

    html += '<div class="panel">';
    html += '<div class="panel-head"><div>';
    html += '<div class="eyebrow">任务 #' + task.task_id + ' · ' + esc(task.created_at.replace('T', ' ').slice(0, 16)) + '</div>';
    html += '<div class="title-xl">' + esc(task.brief) + '</div>';
    if (task.style) {
      html += '<div class="subtle" style="margin-top:6px">风格：' + esc(task.style) + '</div>';
    }
    html += '</div><span class="badge ' + badge[0] + '">' + esc(badge[1]) + '</span></div>';
    html += renderStepsBar(task);
    html += '</div>';

    if (step) {
      html += renderPanel(task, step);
    } else {
      html += renderFinished(task);
    }
    stage.innerHTML = html;
    bindStage(task, step);
  }

  function renderStepsBar(task) {
    var html = '<div class="steps">';
    STEP_ORDER.forEach(function (code, index) {
      var row = stepOf(task, code);
      var status = row ? row.status : 'PENDING';
      var cls = 'step-node';
      if (status === 'SUCCEEDED') {
        cls += ' done';
      } else if (status === 'WAITING_INPUT' || status === 'RUNNING') {
        cls += ' current';
      } else if (status === 'FAILED') {
        cls += ' failed';
      }
      if (index > 0) {
        html += '<div class="step-link"></div>';
      }
      html += '<div class="' + cls + '"><span class="num">' + (status === 'SUCCEEDED' ? '✓' : index + 1) + '</span>';
      html += '<span>' + esc(STEP_LABELS[code]) + '</span></div>';
    });
    html += '</div>';
    return html;
  }

  function renderPanel(task, step) {
    // 素材缩略图要拼 URL，步骤对象本身不带 task_id，这里补上。
    step.task_id = task.task_id;
    var title = step.title || STEP_LABELS[step.code] || step.code;
    var inner = '';
    if (step.code === 'TOPIC') {
      inner = renderTopic(step);
    } else if (step.code === 'SCRIPT') {
      inner = renderScript(step);
    } else if (step.code === 'MATERIAL') {
      inner = renderMaterial(step);
    } else if (step.code === 'RENDER') {
      inner = renderRender(task, step);
    } else {
      inner = renderPublish(step);
    }

    var html = '<div class="panel" id="step-panel" data-code="' + esc(step.code) + '">';
    html += '<div class="panel-head"><div>';
    html += '<div class="eyebrow">步骤 ' + (STEP_ORDER.indexOf(step.code) + 1) + ' / 5</div>';
    html += '<div class="title-md">' + esc(title) + '</div>';
    html += '</div>';
    html += stepStatusBadge(step);
    html += '</div>';
    if (step.error) {
      html += '<div class="code" style="margin-bottom:16px;color:#c92a22">' + esc(step.error) + '</div>';
    }
    html += inner;
    html += renderGateBar(task, step);
    html += '</div>';
    return html;
  }

  function stepStatusBadge(step) {
    var map = {
      PENDING: ['idle', '待执行'],
      RUNNING: ['running', '生成中'],
      WAITING_INPUT: ['waiting', '等待确认'],
      SUCCEEDED: ['done', '已通过'],
      FAILED: ['failed', '失败']
    };
    var item = map[step.status] || ['idle', step.status];
    return '<span class="badge ' + item[0] + '">' + esc(item[1]) + '</span>';
  }

  /* ---------------- 各步骤面板 ---------------- */

  function renderTopic(step) {
    var payload = step.payload || {};
    var candidates = payload.candidates || [];
    if (!candidates.length) {
      return '<div class="subtle">选题还没生成。</div>';
    }
    var html = '<div class="grid-cards">';
    candidates.forEach(function (item, index) {
      var selected = edit.topicIndex === index ? ' selected' : '';
      html += '<div class="option-card' + selected + '" data-topic="' + index + '">';
      html += '<span class="pick"></span>';
      html += '<div class="option-title">' + esc(item.title) + '</div>';
      if (item.angle) {
        html += '<div class="option-line"><b>角度</b>' + esc(item.angle) + '</div>';
      }
      if (item.hook) {
        html += '<div class="option-line"><b>钩子</b>' + esc(item.hook) + '</div>';
      }
      html += '</div>';
    });
    html += '</div>';
    html += '<div class="subtle" style="margin-top:14px">点一张卡片选它，然后按「通过」进入脚本。</div>';
    return html;
  }

  function renderScript(step) {
    var script = edit.script || (step.payload && step.payload.script) || {};
    var shots = script.shots || [];
    var html = '';
    html += '<div class="field"><label>标题</label><input type="text" data-script="title" value="' + esc(script.title) + '" /></div>';
    html += '<div class="field"><label>封面标题</label><input type="text" data-script="cover_title" value="' + esc(script.cover_title) + '" /></div>';
    html += '<div class="field"><label>发布文案</label><textarea data-script="caption">' + esc(script.caption) + '</textarea></div>';
    html += '<div class="field"><label>标签（逗号分隔）</label><input type="text" data-script="tags" value="' + esc((script.tags || []).join('，')) + '" /></div>';
    html += '<div class="subtle" style="margin:6px 0 10px">分镜口播（' + shots.length + ' 镜，headline 是画面大字，body 是配音与字幕）</div>';
    html += '<div class="shot-list">';
    shots.forEach(function (shot, index) {
      html += '<div class="shot">';
      html += '<div class="shot-thumb" style="background-image:url(/api/tasks/' + step.task_id + '/materials/' + index + '?t=' + Date.now() + ')"></div>';
      html += '<div class="shot-fields">';
      html += '<div class="shot-index">镜头 ' + (index + 1) + '</div>';
      html += '<div class="field" style="margin:0"><label>大字</label><input type="text" data-shot="' + index + '" data-field="headline" value="' + esc(shot.headline) + '" /></div>';
      html += '<div class="field" style="margin:0"><label>口播</label><textarea data-shot="' + index + '" data-field="body">' + esc(shot.body) + '</textarea></div>';
      html += '</div></div>';
    });
    html += '</div>';
    return html;
  }

  function renderMaterial(step) {
    var shots = edit.shots || (step.payload && step.payload.shots) || [];
    if (!shots.length) {
      return '<div class="subtle">素材还没匹配。</div>';
    }
    var html = '<div class="shot-list">';
    shots.forEach(function (shot, index) {
      html += '<div class="shot">';
      html += '<div class="shot-thumb" style="background-image:url(/api/tasks/' + step.task_id + '/materials/' + index + '?t=' + Date.now() + ')"></div>';
      html += '<div class="shot-fields">';
      html += '<div class="shot-index">' + esc(shot.need_id || 'N' + (index + 1)) + ' · ' + esc(shot.asset_type || '') + '</div>';
      html += '<div class="field" style="margin:0"><label>画面大字</label><input type="text" data-mat="' + index + '" data-field="headline" value="' + esc(shot.headline) + '" /></div>';
      html += '<div class="field" style="margin:0"><label>素材路径（换成你的真实图片即可）</label><input type="text" data-mat="' + index + '" data-field="image_path" value="' + esc(shot.image_path) + '" /></div>';
      html += '</div></div>';
    });
    html += '</div>';
    html += '<div class="subtle" style="margin-top:12px">改完路径按「带着修改重跑」，管线会用新素材重渲染。</div>';
    return html;
  }

  function renderRender(task, step) {
    var payload = step.payload || {};
    var artifact = null;
    (task.artifacts || []).forEach(function (row) {
      if (row.kind === 'video' && row.step_code === 'RENDER') {
        artifact = row;
      }
    });
    if (!payload.video_object_key && !artifact) {
      return '<div class="subtle">成片还没生成。</div>';
    }
    var html = '<div class="preview-wrap">';
    if (artifact) {
      html += '<video class="preview-video" controls preload="metadata" src="/api/tasks/' + task.task_id + '/artifacts/' + artifact.id + '"></video>';
    } else {
      html += '<div class="preview-video"></div>';
    }
    html += '<div class="kv">';
    html += kvRow('时长', payload.duration_ms ? (payload.duration_ms / 1000).toFixed(2) + ' 秒' : '—');
    html += kvRow('体积', payload.video_file_size ? (payload.video_file_size / 1024 / 1024).toFixed(2) + ' MB' : '—');
    html += kvRow('成片对象', payload.video_object_key || '—');
    html += kvRow('封面对象', payload.cover_object_key || '—');
    html += '</div></div>';
    return html;
  }

  function renderFinished(task) {
    var renderStep = stepOf(task, 'RENDER');
    var publishStep = stepOf(task, 'PUBLISH');
    var html = '<div class="panel">';
    html += '<div class="panel-head"><div>';
    html += '<div class="eyebrow">成片</div>';
    html += '<div class="title-md">可以发了</div>';
    html += '</div><span class="badge done">已完成</span></div>';
    html += renderStep === null || !renderStep ? '<div class="subtle">没有成片记录。</div>' : renderRender(task, renderStep);
    html += '</div>';
    if (publishStep && publishStep.payload && publishStep.payload.payload) {
      html += '<div class="panel">';
      html += '<div class="panel-head"><div><div class="eyebrow">发布单</div>';
      html += '<div class="title-md">文案与平台</div></div></div>';
      html += renderPublish(publishStep);
      html += '</div>';
    }
    return html;
  }

  function renderPublish(step) {
    var payload = step.payload || {};
    var plan = payload.plan || [];
    var body = payload.payload || {};
    var html = '<div class="kv">';
    html += kvRow('标题', body.title || '—');
    html += kvRow('文案', body.caption || '—');
    html += kvRow('标签', (body.tags || []).join(' · ') || '—');
    html += kvRow('目标平台', (body.platforms || []).join(' · ') || '—');
    html += '</div>';
    html += '<div class="subtle" style="margin:14px 0 8px">发布计划</div>';
    html += '<div class="code">' + esc(JSON.stringify(plan, null, 2)) + '</div>';
    return html;
  }

  function kvRow(key, value) {
    return '<div class="kv-row"><span>' + esc(key) + '</span><b>' + esc(value) + '</b></div>';
  }

  /* ---------------- GATE 操作条 ---------------- */

  function renderGateBar(task, step) {
    var finished = task.status === 'DONE' && !step;
    if (finished) {
      return '';
    }
    var waiting = step.status === 'WAITING_INPUT';
    var failed = step.status === 'FAILED';
    var html = '<div class="gate-bar">';
    if (waiting) {
      html += '<button class="btn btn-primary" data-gate="approve">通过</button>';
      html += '<button class="btn btn-secondary" data-gate="edit">带着修改重跑</button>';
      html += '<button class="btn btn-danger" data-gate="reject">打回重做</button>';
      html += '<span class="gate-hint">通过后自动进入下一步；打回会作废下游产物。</span>';
    } else if (failed) {
      html += '<button class="btn btn-primary" data-gate="edit">修好后重跑</button>';
      html += '<span class="gate-hint">这一步失败了，看上面的报错。</span>';
    } else if (step.status === 'RUNNING') {
      html += '<span class="gate-hint">正在生成，成片渲染通常要 30–90 秒…</span>';
    } else if (step.status === 'SUCCEEDED') {
      html += '<span class="gate-hint">这一步已通过。</span>';
    } else {
      html += '<button class="btn btn-primary" data-gate="advance">开始执行</button>';
    }
    html += '</div>';
    return html;
  }

  /* ---------------- 事件绑定 ---------------- */

  function bindStage(task, step) {
    var panel = el('step-panel');
    if (!panel || !step) {
      return;
    }

    panel.addEventListener('click', function (event) {
      var topicCard = event.target.closest('[data-topic]');
      if (topicCard) {
        edit.topicIndex = Number(topicCard.getAttribute('data-topic'));
        var cards = panel.querySelectorAll('[data-topic]');
        for (var i = 0; i < cards.length; i += 1) {
          cards[i].classList.toggle('selected', Number(cards[i].getAttribute('data-topic')) === edit.topicIndex);
        }
        return;
      }

      var gateBtn = event.target.closest('[data-gate]');
      if (gateBtn) {
        submitGate(task, step, gateBtn.getAttribute('data-gate'));
      }
    });

    panel.addEventListener('input', function (event) {
      var target = event.target;
      if (target.hasAttribute('data-script')) {
        applyScriptField(target);
      } else if (target.hasAttribute('data-shot')) {
        applyShotField(target);
      } else if (target.hasAttribute('data-mat')) {
        applyMaterialField(target);
      }
    });
  }

  function applyScriptField(input) {
    if (!edit.script) {
      return;
    }
    var key = input.getAttribute('data-script');
    if (key === 'tags') {
      edit.script.tags = input.value
        .split(/[,，\s]+/)
        .map(function (item) {
          return item.trim();
        })
        .filter(Boolean);
    } else {
      edit.script[key] = input.value;
    }
  }

  function applyShotField(input) {
    if (!edit.script || !edit.script.shots) {
      return;
    }
    var index = Number(input.getAttribute('data-shot'));
    var field = input.getAttribute('data-field');
    if (edit.script.shots[index]) {
      edit.script.shots[index][field] = input.value;
    }
  }

  function applyMaterialField(input) {
    if (!edit.shots) {
      return;
    }
    var index = Number(input.getAttribute('data-mat'));
    var field = input.getAttribute('data-field');
    if (edit.shots[index]) {
      edit.shots[index][field] = input.value;
      if (field === 'image_path') {
        edit.shots[index].source_url = input.value;
      }
    }
  }

  function buildPayload(task, step) {
    if (step.code === 'TOPIC') {
      return edit.topicIndex === null ? {} : { chosen_index: edit.topicIndex };
    }
    if (step.code === 'SCRIPT') {
      return edit.script ? { script: edit.script } : {};
    }
    if (step.code === 'MATERIAL') {
      return edit.shots ? { shots: edit.shots } : {};
    }
    return {};
  }

  function submitGate(task, step, action) {
    if (state.busy) {
      return;
    }
    if (action === 'advance') {
      state.busy = true;
      toast('正在推进…');
      api('/api/tasks/' + task.task_id + '/advance?auto=true', { method: 'POST' })
        .then(function (next) {
          state.task = next;
          syncEditState(next);
          renderTasks();
          renderStage();
          toast('推进完成');
        })
        .catch(function (err) {
          toast('推进失败：' + err.message, true);
        })
        .then(function () {
          state.busy = false;
        });
      return;
    }

    var decision = action;
    var payload = buildPayload(task, step);
    if (action === 'approve' && step.code === 'TOPIC' && edit.topicIndex === null) {
      toast('先选一条选题', true);
      return;
    }
    var note = '';
    if (action === 'reject' || action === 'edit') {
      note = window.prompt(
        action === 'reject' ? '打回原因（会作为下一轮生成的要求）' : '要改什么（会作为下一轮生成的要求）',
        ''
      );
      if (note === null) {
        return;
      }
    }

    state.busy = true;
    toast(action === 'approve' ? '正在通过并生成下一步…' : '正在重跑…');
    api('/api/tasks/' + task.task_id + '/gates/' + step.code, {
      method: 'POST',
      body: { decision: decision, note: note, payload: payload }
    })
      .then(function (next) {
        state.task = next;
        syncEditState(next);
        renderTasks();
        renderStage();
        toast('已更新');
      })
      .catch(function (err) {
        toast('操作失败：' + err.message, true);
      })
      .then(function () {
        state.busy = false;
      });
  }

  /* ---------------- 新建任务 ---------------- */

  function openSheet() {
    el('overlay').classList.add('open');
    setTimeout(function () {
      el('brief').focus();
    }, 60);
  }

  function closeSheet() {
    el('overlay').classList.remove('open');
  }

  function createTask() {
    var brief = el('brief').value.trim();
    if (!brief) {
      toast('先写一句内容简报', true);
      return;
    }
    var style = el('style').value.trim();
    el('btn-create').disabled = true;
    api('/api/tasks', { method: 'POST', body: { brief: brief, style: style } })
      .then(function (task) {
        closeSheet();
        el('brief').value = '';
        el('style').value = '';
        state.task = task;
        syncEditState(task);
        return loadTasks();
      })
      .then(function () {
        renderStage();
        toast('任务已创建');
      })
      .catch(function (err) {
        toast('创建失败：' + err.message, true);
      })
      .then(function () {
        el('btn-create').disabled = false;
      });
  }

  /* ---------------- 启动 ---------------- */

  function bindGlobal() {
    el('btn-new').addEventListener('click', openSheet);
    el('btn-cancel').addEventListener('click', closeSheet);
    el('btn-create').addEventListener('click', createTask);
    el('btn-refresh').addEventListener('click', function () {
      loadTasks();
      if (state.task) {
        openTask(state.task.task_id);
      }
    });
    el('overlay').addEventListener('click', function (event) {
      if (event.target === el('overlay')) {
        closeSheet();
      }
    });
    el('task-list').addEventListener('click', function (event) {
      var item = event.target.closest('[data-task]');
      if (item) {
        openTask(Number(item.getAttribute('data-task')));
      }
    });
    el('brief').addEventListener('keydown', function (event) {
      if (event.key === 'Enter') {
        createTask();
      }
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape') {
        closeSheet();
      }
    });
  }

  function boot() {
    bindGlobal();
    refreshHealth();
    setInterval(refreshHealth, 30000);
    loadTasks().then(function (rows) {
      if (rows && rows.length) {
        openTask(rows[0].id);
      } else {
        renderStage();
      }
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
