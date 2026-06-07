    async function loadHistory(forceReload = false) {
      const el = document.getElementById('historyTable');
      const pageInfoEl = document.getElementById('historyPageInfo');
      const prevBtn = document.getElementById('historyPrevPage');
      const nextBtn = document.getElementById('historyNextPage');
      try {
        await refreshSummarizerProviderMap();
        let payload = null;
        let backendPaged = false;
        let rawList = [];

        const shouldUseLegacyCache = !forceReload && Array.isArray(historyLegacyCache);
        if (shouldUseLegacyCache) {
          rawList = historyLegacyCache;
        } else {
          const r = await fetch(API + '/history?page=' + historyPage + '&size=' + HISTORY_PAGE_SIZE);
          if (!r.ok) {
            throw new Error('HTTP ' + r.status);
          }
          payload = await r.json();
          backendPaged = !Array.isArray(payload) && Array.isArray(payload?.items);
          rawList =
            Array.isArray(payload?.items) ? payload.items :
            Array.isArray(payload?.data) ? payload.data :
            Array.isArray(payload) ? payload : [];

          historyLegacyCache = backendPaged ? null : rawList;
        }

        let list = rawList;
        let totalElements = Number.isFinite(payload?.totalElements) ? payload.totalElements : rawList.length;
        let totalPages = Number.isFinite(payload?.totalPages) ? payload.totalPages : 0;
        let currentPage = Number.isFinite(payload?.page) ? payload.page : historyPage;

        if (!backendPaged) {
          const start = Math.max(0, historyPage) * HISTORY_PAGE_SIZE;
          const end = start + HISTORY_PAGE_SIZE;
          list = rawList.slice(start, end);
          totalPages = Math.max(1, Math.ceil(rawList.length / HISTORY_PAGE_SIZE));
          totalElements = rawList.length;
          currentPage = Math.max(0, historyPage);
        }

        if (pageInfoEl) {
          const safeTotalPages = totalPages > 0 ? totalPages : 1;
          pageInfoEl.textContent = 'Страница ' + (currentPage + 1) + ' из ' + safeTotalPages + ' (' + totalElements + ' прогонов)';
        }
        if (prevBtn) prevBtn.disabled = currentPage <= 0;
        if (nextBtn) nextBtn.disabled = totalPages <= 0 || currentPage >= (totalPages - 1);

        if (!list.length) {
          el.innerHTML = '<p class="meta">История пуста</p>';
          return;
        }
        function truncateCmd(cmd) {
          if (!cmd) return '—';
          return cmd.length > 60 ? cmd.slice(0, 57) + '…' : cmd;
        }
        function esc(s) {
          if (!s) return '';
          const d = document.createElement('div');
          d.textContent = s;
          return d.innerHTML;
        }
        const rows = list.map(t => {
          const taskId = t.id;
          return `
            <tr class="history-row" data-task-id="${taskId}" style="cursor:pointer">
              <td>${esc(taskStatusLabel(t.finalStatus))}</td>
              <td>${esc(t.testTool)}</td>
              <td>${esc(t.testFileName)}</td>
              <td class="meta">${t.dockerProfileName ? esc(dockerProfileDisplayName(t.dockerProfileName)) : '—'}</td>
              <td class="meta">${t.summarizerName ? esc(t.summarizerName) + summarizerRouteBadgeHtml(t.summarizerName) : '—'}</td>
              <td class="meta" title="${esc(t.command || '')}">${truncateCmd(t.command)}</td>
              <td>${t.metricsCollected ? 'да' : 'нет'}</td>
              <td class="meta">${formatDate(t.createdAt)}</td>
              <td class="meta">${formatDate(t.finishedAt)}</td>
              <td class="actions-cell"><button type="button" class="btn btn-small btn-danger btn-delete-history" data-task-id="${taskId}">Удалить</button></td>
            </tr>`;
        }).join('');
        el.innerHTML = `<table><thead><tr><th>Статус</th><th>Инструмент</th><th>Файл</th><th>Профиль</th><th>Маршрут отчёта</th><th>Команда</th><th>Метрики</th><th>Создан</th><th>Завершён</th><th></th></tr></thead><tbody>${rows}</tbody></table>`;
        el.querySelectorAll('.history-row').forEach(tr => {
          tr.addEventListener('click', (ev) => {
            if (ev.target.closest && ev.target.closest('button.btn-delete-history')) return;
            location.hash = 'history/' + tr.dataset.taskId;
          });
        });
        el.querySelectorAll('.btn-delete-history').forEach(btn => {
          btn.addEventListener('click', async function (ev) {
            ev.preventDefault();
            ev.stopPropagation();
            const id = this.getAttribute('data-task-id');
            if (!id || !confirm('Удалить этот прогон из истории? Артефакты, метрики и отчёты суммаризации будут удалены безвозвратно.')) return;
            try {
              const r = await fetch(API + '/history/' + id, { method: 'DELETE' });
              if (r.status === 204 || r.ok) {
                historyLegacyCache = null;
                loadHistory(true);
              } else {
                const data = await r.json().catch(() => ({}));
                alert(data.message || ('Ошибка ' + r.status));
              }
            } catch (err) {
              alert(err.message || String(err));
            }
          });
        });
      } catch (e) {
        el.innerHTML = '<p class="msg err">Ошибка: ' + e.message + '</p>';
      }
    }

    function parseSummarySections(text) {
      if (!text || typeof text !== 'string') return [];
      const sections = [];
      const parts = text.split(/(?=^## .+$)/gm);
      for (const p of parts) {
        const m = p.match(/^##\s+(.+?)$/m);
        if (m) {
          let title = m[1].trim();
          let body = p.replace(/^##\s+.+?\n?/m, '').trim();
          const truncated = title.length > 1 ? title.substring(1) : '';
          if (truncated && body.startsWith(truncated)) {
            body = body.substring(truncated.length).replace(/^\s*\n+/, '').trim();
          }
          sections.push({ title: title, body: body });
        } else if (p.trim()) {
          sections.push({ title: null, body: p.trim() });
        }
      }
      if (sections.length === 0 && text.trim()) sections.push({ title: null, body: text.trim() });
      return sections;
    }

    function renderSummaryBody(body) {
      const lines = (body || '').split('\n');
      return lines.map(function (line) {
        const sub = line.match(/^###\s+(.+)$/);
        if (sub) return '<h4 class="summary-subtitle">' + escapeHtml(sub[1]) + '</h4>';
        return escapeHtml(line) + '<br>';
      }).join('');
    }

    function historyActionsRequestBody(summarizerSelectEl, customPromptEl) {
      const body = {};
      const summarizer = summarizerSelectEl && summarizerSelectEl.value ? summarizerSelectEl.value.trim() : '';
      if (summarizer) body.summarizer = summarizer;
      const customPrompt = customPromptEl && customPromptEl.value ? customPromptEl.value.trim() : '';
      if (customPrompt) body.customPrompt = customPrompt;
      return body;
    }

    async function postHistoryRerun(taskId, summarizerSelectEl, customPromptEl) {
      const payload = JSON.stringify(historyActionsRequestBody(summarizerSelectEl, customPromptEl));
      const r = await fetch(API + '/history/' + taskId + '/rerun', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: payload
      });
      let data = {};
      try {
        data = await r.json();
      } catch (_) { /* не JSON или пусто */ }
      return { r, data };
    }

    function applyHistorySummarizerSelectDefault(selectEl, defaultName) {
      if (!selectEl || defaultName == null || String(defaultName).trim() === '') return;
      const v = String(defaultName).trim();
      for (let i = 0; i < selectEl.options.length; i++) {
        if (selectEl.options[i].value === v) {
          selectEl.value = v;
          return;
        }
      }
    }

    async function loadAndRenderHistoryDetail(taskId) {
      const container = document.getElementById('historyDetailContent');
      if (!container) return;
      container.innerHTML = '<p class="meta">Загрузка…</p>';
      try {
        const [histR, artR, metricsR, summaryR] = await Promise.all([
          fetch(API + '/history/' + taskId),
          fetch(API + '/artifacts/' + taskId),
          fetch(API + '/metrics/' + taskId),
          fetch(API + '/history/' + taskId + '/summary')
        ]);
        const hist = histR.ok ? await histR.json() : {};
        const artifacts = artR.ok ? await artR.json() : [];
        const metricsList = metricsR.ok ? await metricsR.json() : [];
        const summaryList = summaryR.ok ? await summaryR.json() : [];
        const cmd = hist.command || '—';
        const fileName = hist.testFileName || '—';
        const fileContent = hist.fileContent != null ? hist.fileContent : '—';
        let metricsConfigStr = '—';
        if (hist.metricsConfig != null && hist.metricsConfig !== '') {
          try {
            metricsConfigStr = typeof hist.metricsConfig === 'string' ? JSON.stringify(JSON.parse(hist.metricsConfig), null, 2) : JSON.stringify(hist.metricsConfig, null, 2);
          } catch (_) {
            metricsConfigStr = hist.metricsConfig;
          }
        }
        const artHtml = artifacts.length
          ? '<ul class="artifacts-list">' + artifacts.map(a => {
              const url = API + '/artifacts/' + taskId + '/files/' + encodeURIComponent(a.fileName);
              const size = a.originalSizeBytes != null ? ' (' + a.originalSizeBytes + ' B)' : '';
              return '<li><a href="' + url + '" target="_blank" rel="noopener">' + escapeHtml(a.fileName) + '</a>' + size + '</li>';
            }).join('') + '</ul>'
          : '<p class="meta">Нет артефактов</p>';
        const metricsHtml = metricsList.length
          ? metricsList.map(m => {
              const dataStr = m.metricsData != null ? (typeof m.metricsData === 'string' ? m.metricsData : JSON.stringify(m.metricsData, null, 2)) : '—';
              const collectedAt = m.collectedAt ? new Date(m.collectedAt).toLocaleString() : '—';
              return '<div class="copy-block" style="margin-bottom:0.75rem"><strong>' + escapeHtml(m.sourceType || '—') + '</strong> <span class="meta">' + escapeHtml(m.endpointUrl || '') + ' ' + collectedAt + '</span><button type="button" class="btn-copy btn btn-small">Копировать</button><pre style="margin:0.5rem 0 0;max-height:8rem;overflow:auto">' + escapeHtml(dataStr) + '</pre></div>';
            }).join('')
          : '<p class="meta">Нет собранных метрик</p>';
        let summaryBlockHtml = '';
        const completedSummary = summaryList.find(s => isSummaryStatusCompleted(s.processingStatus) && s.summaryData != null);
        if (completedSummary) {
          const norm = normalizeSummaryPayload(completedSummary.summaryData);
          const text = norm.text;
          const meta = [norm.summarizerName, norm.model].filter(Boolean).join(' · ');
          const promptHtml = norm.promptUsed && norm.promptUsed.trim()
            ? '<details style="margin-top:0.75rem"><summary>Промпт суммаризации</summary><pre style="margin-top:0.5rem;max-height:12rem;overflow:auto">' + escapeHtml(norm.promptUsed) + '</pre></details>'
            : '';
          if (!text.trim()) {
            summaryBlockHtml = '<div class="block-card summary-card"><h2 class="block-card-title">Отчёт суммаризации' + (meta ? ' <span class="meta">' + escapeHtml(meta) + '</span>' : '') + '</h2><div class="block-card-body"><p class="meta">Статус COMPLETED, но текст отчёта пустой или в неожиданном формате. Проверьте ответ API <code>/history/' + escapeHtml(taskId) + '/summary</code>.</p>' + promptHtml + '</div></div>';
          } else {
            const sections = parseSummarySections(text);
            if (sections.length > 0) {
              summaryBlockHtml = '<div class="block-card summary-card"><h2 class="block-card-title">Отчёт суммаризации' + (meta ? ' <span class="meta">' + escapeHtml(meta) + '</span>' : '') + '</h2><div class="block-card-body"><div class="summary-sections">' +
                sections.map(sec => sec.title
                  ? '<div class="summary-section"><h3 class="summary-section-title">' + escapeHtml(sec.title) + '</h3><div class="summary-section-body">' + renderSummaryBody(sec.body) + '</div></div>'
                  : '<div class="summary-section"><div class="summary-section-body">' + renderSummaryBody(sec.body) + '</div></div>'
                ).join('') + '</div>' + promptHtml + '</div></div>';
            } else {
              summaryBlockHtml = '<div class="block-card summary-card"><h2 class="block-card-title">Отчёт суммаризации' + (meta ? ' <span class="meta">' + escapeHtml(meta) + '</span>' : '') + '</h2><div class="block-card-body"><pre class="summary-section-body">' + escapeHtml(text) + '</pre>' + promptHtml + '</div></div>';
            }
          }
        } else if (summaryList.some(s => isAwaitingExternalSummary(s.processingStatus))) {
          const s = summaryList.find(x => isAwaitingExternalSummary(x.processingStatus));
          const ext = parseExternalPendingData(s.summaryData);
          const dlLine = ext.deadlineAt ? '<p class="meta"><strong>До:</strong> ' + escapeHtml(ext.deadlineAt) + '</p>' : '';
          const api = '<p class="meta"><code>GET ' + escapeHtml(API) + '/history/' + escapeHtml(taskId) + '/external-llm/package</code><br><code>POST ' + escapeHtml(API) + '/history/' + escapeHtml(taskId) + '/external-llm/summary</code> с JSON <code>{"text":"…"}</code></p>';
          summaryBlockHtml = '<div class="block-card summary-card"><h2 class="block-card-title">Внешний отчёт <span class="meta">' + escapeHtml(taskStatusLabel('AWAITING_EXTERNAL_CALLBACK')) + '</span></h2><div class="block-card-body"><p>Окно открыто: заберите метрики и артефакты, затем загрузите текст отчёта.</p>' + dlLine + (ext.instructionsRu ? '<p class="meta">' + escapeHtml(ext.instructionsRu) + '</p>' : '') + api + '</div></div>';
        } else if (summaryList.length > 0) {
          const s = summaryList[0];
          const status = s.processingStatus || '—';
          const err = s.errorMessage ? '<p class="msg err">' + escapeHtml(s.errorMessage) + '</p>' : '';
          const norm = normalizeSummaryPayload(s.summaryData);
          const promptHtml = norm.promptUsed && norm.promptUsed.trim()
            ? '<details style="margin-top:0.75rem"><summary>Промпт суммаризации</summary><pre style="margin-top:0.5rem;max-height:12rem;overflow:auto">' + escapeHtml(norm.promptUsed) + '</pre></details>'
            : '';
          summaryBlockHtml = '<div class="block-card summary-card"><h2 class="block-card-title">Отчёт суммаризации <span class="meta">' + escapeHtml(taskStatusLabel(status)) + '</span></h2><div class="block-card-body">' + err + '<p class="meta">' + (status === 'PENDING' || status === 'PROCESSING' ? 'В обработке…' : 'Нет готового отчёта') + '</p>' + promptHtml + '</div></div>';
        } else {
          summaryBlockHtml = '<div class="block-card summary-card"><h2 class="block-card-title">Отчёт суммаризации</h2><div class="block-card-body"><p class="meta">Нет отчёта по этой задаче (встроенный или внешний маршрут).</p></div></div>';
        }
        const actionsBlockHtml = '<div class="block-card"><h2 class="block-card-title">Действия</h2><div class="block-card-body"><select class="summarizer-select-detail history-action-field"><option value="">— как в этом прогоне —</option></select><textarea class="custom-prompt-detail history-action-field" rows="4" placeholder="Промпт (необязательно)"></textarea><button type="button" class="btn btn-rerun-detail" data-task-id="' + taskId + '">Перезапустить тест</button> <span class="rerun-detail-msg"></span><br><br><button type="button" class="btn btn-small btn-request-summarize-detail" data-task-id="' + taskId + '">Перезапросить отчёт</button> <span class="summarize-detail-msg" style="margin-left:0.5rem"></span><br><br><button type="button" class="btn btn-small btn-danger btn-delete-history-detail" data-task-id="' + taskId + '">Удалить прогон из истории</button> <span class="delete-history-detail-msg"></span></div></div>';
        container.innerHTML =
          '<div class="block-card"><h2 class="block-card-title">Прогон ' + escapeHtml(taskId) + '</h2><div class="block-card-body"><p><strong>Статус:</strong> ' + escapeHtml(taskStatusLabel(hist.finalStatus) || '—') + ' &nbsp; <strong>Инструмент:</strong> ' + escapeHtml(hist.testTool || '—') + ' &nbsp; <strong>Маршрут отчёта:</strong> ' + (hist.summarizerName ? escapeHtml(hist.summarizerName) + summarizerRouteBadgeHtml(hist.summarizerName) : '—') + ' &nbsp; <strong>Создан:</strong> ' + formatDate(hist.createdAt) + ' &nbsp; <strong>Завершён:</strong> ' + formatDate(hist.finishedAt) + '</p></div></div>' +
          actionsBlockHtml +
          '<div class="block-card"><h2 class="block-card-title">Параметры запуска</h2><div class="block-card-body"><p><strong>Команда</strong></p><pre>' + escapeHtml(cmd) + '</pre><p style="margin-top:1rem"><strong>Файл теста</strong> ' + escapeHtml(fileName) + '</p><details style="margin-top:0.5rem"><summary>Содержимое файла</summary><pre style="margin-top:0.5rem">' + escapeHtml(fileContent) + '</pre></details><details style="margin-top:0.5rem"><summary>Конфиг метрик</summary><pre style="margin-top:0.5rem">' + escapeHtml(metricsConfigStr) + '</pre></details></div></div>' +
          summaryBlockHtml +
          '<div class="block-card"><h2 class="block-card-title">Артефакты</h2><div class="block-card-body">' + artHtml + '</div></div>' +
          '<div class="block-card"><h2 class="block-card-title">Метрики</h2><div class="block-card-body">' + metricsHtml + '</div></div>';
        container.querySelectorAll('.btn-copy').forEach(btn => {
          btn.addEventListener('click', function () {
            const block = this.closest('.copy-block');
            const pre = block ? block.querySelector('pre') : null;
            const text = pre ? pre.textContent : '';
            if (!text) return;
            navigator.clipboard.writeText(text).then(() => { this.textContent = 'Скопировано'; setTimeout(() => { this.textContent = 'Копировать'; }, 1500); }).catch(() => {});
          });
        });
        const rerunBtn = container.querySelector('.btn-rerun-detail');
        const rerunMsg = container.querySelector('.rerun-detail-msg');
        if (rerunBtn && rerunMsg) {
          rerunBtn.addEventListener('click', async function () {
            const id = this.getAttribute('data-task-id');
            if (!id) return;
            this.disabled = true;
            rerunMsg.textContent = '…';
            rerunMsg.className = '';
            try {
              const sel = container.querySelector('.summarizer-select-detail');
              const promptEl = container.querySelector('.custom-prompt-detail');
              const { r, data } = await postHistoryRerun(id, sel, promptEl);
              if (r.ok && data.taskId) {
                rerunMsg.textContent = 'В очередь поставлена задача ' + data.taskId;
                rerunMsg.className = 'msg ok';
                loadQueue();
              } else {
                rerunMsg.textContent = 'Ошибка: ' + (data.message || r.status);
                rerunMsg.className = 'msg err';
              }
            } catch (e) {
              rerunMsg.textContent = 'Ошибка: ' + e.message;
              rerunMsg.className = 'msg err';
            }
            this.disabled = false;
          });
        }
        const summarizerSelectDetail = container.querySelector('.summarizer-select-detail');
        if (summarizerSelectDetail) {
          fetch(API + '/summarizers?enabled=true').then(async (r) => {
            const res = await r.json();
            const list = Array.isArray(res.data) ? res.data : (res.data && res.data.data ? res.data.data : []);
            fillSummarizerSelectOptgroups(summarizerSelectDetail, list);
            applyHistorySummarizerSelectDefault(summarizerSelectDetail, hist.summarizerName);
          }).catch(() => {});
        }
        const summarizeBtn = container.querySelector('.btn-request-summarize-detail');
        const summarizeMsg = container.querySelector('.summarize-detail-msg');
        if (summarizeBtn && summarizeMsg) {
          summarizeBtn.addEventListener('click', async function () {
            const id = this.getAttribute('data-task-id');
            if (!id) return;
            const sel = container.querySelector('.summarizer-select-detail');
            const promptEl = container.querySelector('.custom-prompt-detail');
            const body = JSON.stringify(historyActionsRequestBody(sel, promptEl));
            this.disabled = true;
            summarizeMsg.textContent = '…';
            summarizeMsg.className = '';
            try {
              const r = await fetch(API + '/history/' + id + '/summarize', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body });
              const data = r.ok ? await r.json() : {};
              if (r.status === 202 && data.message) {
                summarizeMsg.textContent = data.message;
                summarizeMsg.className = 'msg ok';
                setTimeout(() => { loadAndRenderHistoryDetail(id); }, 5000);
              } else {
                summarizeMsg.textContent = data.message || 'Ошибка ' + r.status;
                summarizeMsg.className = 'msg err';
              }
            } catch (e) {
              summarizeMsg.textContent = 'Ошибка: ' + e.message;
              summarizeMsg.className = 'msg err';
            }
            this.disabled = false;
          });
        }
        const deleteHistDetailBtn = container.querySelector('.btn-delete-history-detail');
        const deleteHistDetailMsg = container.querySelector('.delete-history-detail-msg');
        if (deleteHistDetailBtn && deleteHistDetailMsg) {
          deleteHistDetailBtn.addEventListener('click', async function () {
            const id = this.getAttribute('data-task-id');
            if (!id || !confirm('Удалить этот прогон из истории? Артефакты, метрики и отчёты суммаризации будут удалены безвозвратно.')) return;
            this.disabled = true;
            deleteHistDetailMsg.textContent = '…';
            deleteHistDetailMsg.className = '';
            try {
              const r = await fetch(API + '/history/' + id, { method: 'DELETE' });
              if (r.status === 204 || r.ok) {
                deleteHistDetailMsg.textContent = 'Удалено';
                deleteHistDetailMsg.className = 'msg ok';
                location.hash = 'history';
                loadHistory();
              } else {
                const data = await r.json().catch(() => ({}));
                deleteHistDetailMsg.textContent = data.message || ('Ошибка ' + r.status);
                deleteHistDetailMsg.className = 'msg err';
              }
            } catch (e) {
              deleteHistDetailMsg.textContent = 'Ошибка: ' + e.message;
              deleteHistDetailMsg.className = 'msg err';
            }
            this.disabled = false;
          });
        }
      } catch (e) {
        container.innerHTML = '<p class="msg err">Ошибка: ' + escapeHtml(e.message) + '</p>';
      }
    }

    function handleHistoryHash() {
      const hash = location.hash.replace(/^#/, '') || '';
      const listView = document.getElementById('historyListView');
      const detailPage = document.getElementById('historyDetailPage');
      if (!listView || !detailPage) return;
      const match = hash.match(/^history\/([0-9a-fA-F-]{36})$/);
      if (match) {
        listView.style.display = 'none';
        detailPage.style.display = 'block';
        loadAndRenderHistoryDetail(match[1]);
      } else {
        listView.style.display = 'block';
        detailPage.style.display = 'none';
        if (hash === 'history') loadHistory();
      }
    }

    const historyDetailBackEl = document.getElementById('historyDetailBack');
    if (historyDetailBackEl) historyDetailBackEl.addEventListener('click', function (e) { e.preventDefault(); location.hash = 'history'; });
    window.addEventListener('hashchange', handleHistoryHash);
    (function initHistoryHash() {
      const h = location.hash.replace(/^#/, '');
      if (h.startsWith('history')) showTab('history');
    })();

    async function toggleHistoryDetail(taskId) {
      const detailRow = document.querySelector(`tr.history-detail-row[data-detail-for="${taskId}"]`);
      const mainRow = document.querySelector(`tr.history-row[data-task-id="${taskId}"]`);
      const icon = mainRow && mainRow.querySelector('.expand-icon');
      if (!detailRow) return;
      const isHidden = detailRow.style.display === 'none';
      if (isHidden) {
        detailRow.style.display = 'table-row';
        if (icon) icon.textContent = '▼';
        const inner = detailRow.querySelector(`.history-detail-inner[data-detail-body="${taskId}"]`);
        if (inner && inner.textContent.trim() === 'Загрузка…') {
          try {
            const [histR, artR, metricsR, summaryR] = await Promise.all([fetch(API + '/history/' + taskId), fetch(API + '/artifacts/' + taskId), fetch(API + '/metrics/' + taskId), fetch(API + '/history/' + taskId + '/summary')]);
            const hist = histR.ok ? await histR.json() : {};
            const artifacts = artR.ok ? await artR.json() : [];
            const metricsList = metricsR.ok ? await metricsR.json() : [];
            const summaryList = summaryR.ok ? await summaryR.json() : [];
            const cmd = hist.command || '—';
            const fileName = hist.testFileName || '—';
            const fileContent = hist.fileContent != null ? hist.fileContent : '—';
            let metricsConfigStr = '—';
            if (hist.metricsConfig != null && hist.metricsConfig !== '') {
              try {
                metricsConfigStr = typeof hist.metricsConfig === 'string' ? JSON.stringify(JSON.parse(hist.metricsConfig), null, 2) : JSON.stringify(hist.metricsConfig, null, 2);
              } catch (_) {
                metricsConfigStr = hist.metricsConfig;
              }
            }
            const artHtml = artifacts.length
              ? artifacts.map(a => {
                  const url = API + '/artifacts/' + taskId + '/files/' + encodeURIComponent(a.fileName);
                  const size = a.originalSizeBytes != null ? ` (${a.originalSizeBytes} B)` : '';
                  return `<li><a href="${url}" target="_blank" rel="noopener">${a.fileName}</a>${size}</li>`;
                }).join('')
              : '<li class="meta">Нет артефактов</li>';
            const metricsHtml = metricsList.length
              ? metricsList.map(m => {
                  const dataStr = m.metricsData != null ? (typeof m.metricsData === 'string' ? m.metricsData : JSON.stringify(m.metricsData, null, 2)) : '—';
                  const collectedAt = m.collectedAt ? new Date(m.collectedAt).toLocaleString() : '—';
                  return `<li class="metrics-item copy-block"><strong>${escapeHtml(m.sourceType || '—')}</strong> <span class="meta">${escapeHtml(m.endpointUrl || '')}</span> <span class="meta">${collectedAt}</span><button type="button" class="btn-copy btn btn-small">Копировать</button><pre class="metrics-data">${escapeHtml(dataStr)}</pre></li>`;
                }).join('')
              : '<li class="meta">Нет собранных метрик</li>';
            const summaryHtml = summaryList.length
              ? summaryList.map(s => {
                  const status = s.processingStatus || '—';
                  const norm = normalizeSummaryPayload(s.summaryData);
                  const text = norm.text.trim() ? norm.text : '';
                  const meta = [norm.summarizerName, norm.model].filter(Boolean).join(' · ');
                  const err = s.errorMessage ? `<p class="msg err">${escapeHtml(s.errorMessage)}</p>` : '';
                  const prompt = norm.promptUsed && norm.promptUsed.trim()
                    ? `<details style="margin-top:0.5rem"><summary>Промпт суммаризации</summary><pre style="margin-top:0.35rem;max-height:10rem;overflow:auto">${escapeHtml(norm.promptUsed)}</pre></details>`
                    : '';
                  if (isSummaryStatusCompleted(status) && text) {
                    return `<div class="copy-block summary-block"><h4>Отчёт суммаризации${meta ? ' <span class="meta">' + escapeHtml(meta) + '</span>' : ''}<button type="button" class="btn-copy btn btn-small">Копировать</button></h4>${err}<pre class="summary-text">${escapeHtml(text)}</pre>${prompt}</div>`;
                  }
                  if (isSummaryStatusCompleted(status) && !text) {
                    return `<div class="copy-block summary-block"><h4>Отчёт суммаризации <span class="meta">${escapeHtml(meta || 'COMPLETED')}</span></h4>${err}<p class="meta">Отчёт без текста (проверьте формат summary_data).</p>${prompt}</div>`;
                  }
                  if (String(status).toUpperCase() === 'FAILED') {
                    return `<div class="copy-block summary-block"><h4>Отчёт суммаризации <span class="meta">${escapeHtml(meta || status)}</span></h4>${err}<p class="meta">Ошибка обработки</p>${prompt}</div>`;
                  }
                  if (isAwaitingExternalSummary(status)) {
                    const ext = parseExternalPendingData(s.summaryData);
                    const dl = ext.deadlineAt ? `<p class="meta"><strong>До:</strong> ${escapeHtml(ext.deadlineAt)}</p>` : '';
                    const api = `<p class="meta"><code>GET ${escapeHtml(API)}/history/${escapeHtml(taskId)}/external-llm/package</code><br><code>POST ${escapeHtml(API)}/history/${escapeHtml(taskId)}/external-llm/summary</code> JSON <code>{"text":"…"}</code></p>`;
                    return `<div class="copy-block summary-block"><h4>Внешний отчёт <span class="meta">${escapeHtml(taskStatusLabel('AWAITING_EXTERNAL_CALLBACK'))}</span></h4>${ext.instructionsRu ? `<p class="meta">${escapeHtml(ext.instructionsRu)}</p>` : ''}${dl}${api}</div>`;
                  }
                  return `<div class="copy-block summary-block"><h4>Отчёт суммаризации <span class="meta">${escapeHtml(taskStatusLabel(meta || status))}</span></h4><p class="meta">${status === 'PENDING' || status === 'PROCESSING' ? 'В обработке…' : escapeHtml(taskStatusLabel(status))}</p></div>`;
                }).join('')
              : '<div class="meta">Нет отчёта по этой задаче</div>';
            const summarySectionId = 'summary-section-' + taskId;
            const summaryActionsId = 'summary-actions-' + taskId;
            inner.innerHTML = `
              <div class="history-detail-actions">
                <select class="summarizer-select-detail history-action-field"><option value="">— как в этом прогоне —</option></select>
                <textarea class="custom-prompt-detail history-action-field" rows="3" placeholder="Промпт (необязательно)"></textarea>
                <button type="button" class="btn btn-rerun" data-task-id="${taskId}">Перезапустить тест</button><span class="rerun-msg"></span>
              </div>
              <div class="copy-block"><h4>CLI-команда<button type="button" class="btn-copy btn btn-small">Копировать</button></h4><pre>${escapeHtml(cmd)}</pre></div>
              <div class="copy-block"><h4>Файл теста<button type="button" class="btn-copy btn btn-small">Копировать</button></h4><pre>${escapeHtml(fileName)}</pre></div>
              <div class="copy-block"><h4>Содержимое файла<button type="button" class="btn-copy btn btn-small">Копировать</button></h4><pre>${escapeHtml(fileContent)}</pre></div>
              <div class="copy-block"><h4>Конфиг метрик<button type="button" class="btn-copy btn btn-small">Копировать</button></h4><pre>${escapeHtml(metricsConfigStr)}</pre></div>
              <h4>Отчёт суммаризации <span class="meta">(встроенный сервис или внешний маршрут)</span></h4>
              <div id="${summarySectionId}" class="summary-section-wrap">${summaryHtml}</div>
              <div id="${summaryActionsId}" class="summary-actions" style="margin-top:0.5rem;">
                <button type="button" class="btn btn-small btn-request-summarize" data-task-id="${taskId}">Перезапросить отчёт</button>
                <span class="summarize-msg" style="margin-left:0.5rem;"></span>
              </div>
              <h4>Артефакты</h4><ul class="artifacts-list">${artHtml}</ul>
              <h4>Метрики</h4><ul class="artifacts-list metrics-list">${metricsHtml}</ul>`;
            inner.querySelectorAll('.btn-copy').forEach(btn => {
              btn.addEventListener('click', function () {
                const block = this.closest('.copy-block, .metrics-item');
                const pre = block ? block.querySelector('pre') : null;
                const text = pre ? pre.textContent : '';
                if (!text) return;
                navigator.clipboard.writeText(text).then(() => {
                  const lbl = this.textContent;
                  this.textContent = 'Скопировано';
                  setTimeout(() => { this.textContent = lbl; }, 1500);
                }).catch(() => { this.textContent = 'Ошибка'; setTimeout(() => { this.textContent = 'Копировать'; }, 1500); });
              });
            });
            const rerunBtn = inner.querySelector('.btn-rerun');
            const rerunMsg = inner.querySelector('.rerun-msg');
            if (rerunBtn && rerunMsg) {
              rerunBtn.addEventListener('click', async function () {
                const id = this.getAttribute('data-task-id');
                if (!id) return;
                this.disabled = true;
                rerunMsg.textContent = '…';
                rerunMsg.className = 'rerun-msg';
                try {
                  const sel = inner.querySelector('.summarizer-select-detail');
                  const promptEl = inner.querySelector('.custom-prompt-detail');
                  const { r, data } = await postHistoryRerun(id, sel, promptEl);
                  if (r.ok && data.taskId) {
                    rerunMsg.textContent = 'В очередь поставлена задача ' + data.taskId;
                    rerunMsg.className = 'rerun-msg msg ok';
                    loadQueue();
                  } else {
                    rerunMsg.textContent = 'Ошибка: ' + (data.message || r.status);
                    rerunMsg.className = 'rerun-msg msg err';
                  }
                } catch (e) {
                  rerunMsg.textContent = 'Ошибка: ' + e.message;
                  rerunMsg.className = 'rerun-msg msg err';
                }
                this.disabled = false;
              });
            }
            const summarizerSelectDetail = inner.querySelector('.summarizer-select-detail');
            const summarizeBtn = inner.querySelector('.btn-request-summarize');
            const summarizeMsg = inner.querySelector('.summarize-msg');
            if (summarizerSelectDetail) {
              fetch(API + '/summarizers?enabled=true').then(async (r) => {
                const res = await r.json();
                const list = Array.isArray(res.data) ? res.data : (res.data && res.data.data ? res.data.data : []);
                fillSummarizerSelectOptgroups(summarizerSelectDetail, list);
                applyHistorySummarizerSelectDefault(summarizerSelectDetail, hist.summarizerName);
              }).catch(() => {});
            }
            function buildSummaryBlockHtml(summaryList) {
              if (!summaryList || summaryList.length === 0) return '<div class="meta">Нет отчёта по этой задаче</div>';
              return summaryList.map(s => {
                const status = s.processingStatus || '—';
                const norm = normalizeSummaryPayload(s.summaryData);
                const text = norm.text.trim() ? norm.text : '';
                const meta = [norm.summarizerName, norm.model].filter(Boolean).join(' · ');
                const err = s.errorMessage ? '<p class="msg err">' + escapeHtml(s.errorMessage) + '</p>' : '';
                const prompt = norm.promptUsed && norm.promptUsed.trim()
                  ? '<details style="margin-top:0.5rem"><summary>Промпт суммаризации</summary><pre style="margin-top:0.35rem;max-height:10rem;overflow:auto">' + escapeHtml(norm.promptUsed) + '</pre></details>'
                  : '';
                if (isSummaryStatusCompleted(status) && text) {
                  return '<div class="copy-block summary-block"><h4>Отчёт суммаризации' + (meta ? ' <span class="meta">' + escapeHtml(meta) + '</span>' : '') + '<button type="button" class="btn-copy btn btn-small">Копировать</button></h4>' + err + '<pre class="summary-text">' + escapeHtml(text) + '</pre>' + prompt + '</div>';
                }
                if (isSummaryStatusCompleted(status) && !text) {
                  return '<div class="copy-block summary-block"><h4>Отчёт суммаризации <span class="meta">' + escapeHtml(meta || 'COMPLETED') + '</span></h4>' + err + '<p class="meta">Отчёт без текста.</p>' + prompt + '</div>';
                }
                if (String(status).toUpperCase() === 'FAILED') return '<div class="copy-block summary-block"><h4>Отчёт суммаризации <span class="meta">' + escapeHtml(meta || status) + '</span></h4>' + err + '<p class="meta">Ошибка обработки</p>' + prompt + '</div>';
                if (isAwaitingExternalSummary(status)) {
                  const ext = parseExternalPendingData(s.summaryData);
                  const dl = ext.deadlineAt ? '<p class="meta"><strong>До:</strong> ' + escapeHtml(ext.deadlineAt) + '</p>' : '';
                  const api = '<p class="meta"><code>GET ' + escapeHtml(API) + '/history/' + escapeHtml(taskId) + '/external-llm/package</code><br><code>POST ' + escapeHtml(API) + '/history/' + escapeHtml(taskId) + '/external-llm/summary</code> JSON <code>{"text":"…"}</code></p>';
                  return '<div class="copy-block summary-block"><h4>Внешний отчёт <span class="meta">' + escapeHtml(taskStatusLabel('AWAITING_EXTERNAL_CALLBACK')) + '</span></h4>' + (ext.instructionsRu ? '<p class="meta">' + escapeHtml(ext.instructionsRu) + '</p>' : '') + dl + api + '</div>';
                }
                return '<div class="copy-block summary-block"><h4>Отчёт суммаризации <span class="meta">' + escapeHtml(taskStatusLabel(meta || status)) + '</span></h4><p class="meta">' + (status === 'PENDING' || status === 'PROCESSING' ? 'В обработке…' : escapeHtml(taskStatusLabel(status))) + '</p></div>';
              }).join('');
            }
            if (summarizeBtn && summarizeMsg) {
              summarizeBtn.addEventListener('click', async function () {
                const id = this.getAttribute('data-task-id');
                if (!id) return;
                const sel = inner.querySelector('.summarizer-select-detail');
                const promptEl = inner.querySelector('.custom-prompt-detail');
                const body = JSON.stringify(historyActionsRequestBody(sel, promptEl));
                this.disabled = true;
                summarizeMsg.textContent = '…';
                summarizeMsg.className = 'summarize-msg';
                try {
                  const r = await fetch(API + '/history/' + id + '/summarize', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body });
                  const data = r.ok ? await r.json() : {};
                  if (r.status === 202 && data.message) {
                    summarizeMsg.textContent = data.message;
                    summarizeMsg.className = 'summarize-msg msg ok';
                    const wrapId = summarySectionId;
                    setTimeout(async () => {
                      try {
                        const sumR = await fetch(API + '/history/' + id + '/summary');
                        const sumList = sumR.ok ? await sumR.json() : [];
                        const wrap = document.getElementById(wrapId);
                        if (wrap) {
                          wrap.innerHTML = buildSummaryBlockHtml(sumList);
                          wrap.querySelectorAll('.btn-copy').forEach(btn => {
                            btn.addEventListener('click', function () {
                              const block = this.closest('.copy-block');
                              const pre = block ? block.querySelector('pre') : null;
                              const text = pre ? pre.textContent : '';
                              if (!text) return;
                              navigator.clipboard.writeText(text).then(() => { this.textContent = 'Скопировано'; setTimeout(() => { this.textContent = 'Копировать'; }, 1500); }).catch(() => {});
                            });
                          });
                        }
                      } catch (e) { summarizeMsg.textContent = 'Обновление не удалось: ' + e.message; }
                    }, 5000);
                  } else {
                    summarizeMsg.textContent = data.message || 'Ошибка ' + r.status;
                    summarizeMsg.className = 'summarize-msg msg err';
                  }
                } catch (e) {
                  summarizeMsg.textContent = 'Ошибка: ' + e.message;
                  summarizeMsg.className = 'summarize-msg msg err';
                }
                this.disabled = false;
              });
            }
          } catch (e) {
            inner.innerHTML = '<span class="msg err">Ошибка: ' + escapeHtml(e.message) + '</span>';
          }
        }
      } else {
        detailRow.style.display = 'none';
        if (icon) icon.textContent = '▶';
      }
    }


