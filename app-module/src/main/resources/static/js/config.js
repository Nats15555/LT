    async function loadToolsList() {
      const el = document.getElementById('toolsTable');
      try {
        const r = await fetch(API + '/tools');
        const j = await r.json();
        if (j.status === 'error') throw new Error(j.message);
        const tools = j.data != null ? (Array.isArray(j.data) ? j.data : j.data.data) : [];
        if (!tools.length) {
          el.innerHTML = '<p class="meta">Нет инструментов</p>';
          return;
        }
        function esc(s) {
          if (s == null) return '—';
          const d = document.createElement('div');
          d.textContent = String(s);
          return d.innerHTML;
        }
        const rows = tools.map(t => {
          const extStr = Array.isArray(t.fileExtensions) ? t.fileExtensions.join(', ') : (t.fileExtensions || '—');
          return `
            <tr class="tools-row" data-tool-id="${t.id}" style="cursor:pointer">
              <td><span class="expand-icon" data-tool-id="${t.id}">▶</span></td>
              <td><code>${esc(t.id)}</code></td>
              <td>${esc(t.name)}</td>
              <td>${esc(t.dockerImage)}</td>
              <td>${esc(extStr)}</td>
              <td>${t.enabled ? 'да' : 'нет'}</td>
              <td class="meta">${formatDate(t.createdAt)}</td>
              <td class="actions-cell">
                <button type="button" class="btn btn-small tool-edit" data-tool-id="${t.id}">Изменить</button>
                <button type="button" class="btn btn-small btn-danger tool-delete" data-tool-id="${t.id}">Удалить</button>
              </td>
            </tr>
            <tr class="tools-detail-row" data-detail-for="${t.id}" style="display:none">
              <td colspan="8"><div class="tools-detail-inner"><pre>${esc(JSON.stringify(t, null, 2))}</pre></div></td>
            </tr>`;
        }).join('');
        el.innerHTML = `<table><thead><tr><th></th><th>ID</th><th>Имя</th><th>Docker образ</th><th>Расширения</th><th>Включён</th><th>Создан</th><th>Действия</th></tr></thead><tbody>${rows}</tbody></table>`;
        el.querySelectorAll('.tools-row').forEach(tr => {
          const toolId = tr.dataset.toolId;
          tr.addEventListener('click', (e) => {
            if (e.target.classList.contains('expand-icon')) return;
            toggleToolsDetail(toolId);
          });
        });
        el.querySelectorAll('.tools-row .expand-icon').forEach(span => {
          span.addEventListener('click', (e) => { e.stopPropagation(); toggleToolsDetail(span.dataset.toolId); });
        });
        el.querySelectorAll('.tool-edit').forEach(btn => {
          btn.addEventListener('click', (e) => { e.stopPropagation(); editTool(btn.dataset.toolId); });
        });
        el.querySelectorAll('.tool-delete').forEach(btn => {
          btn.addEventListener('click', (e) => { e.stopPropagation(); deleteTool(btn.dataset.toolId); });
        });
      } catch (e) {
        el.innerHTML = '<p class="msg err">Ошибка: ' + e.message + '</p>';
      }
    }

    let editingToolId = null;
    async function editTool(id) {
      try {
        const r = await fetch(API + '/tools/' + id);
        const j = await r.json();
        if (j.status === 'error' || !j.data) throw new Error(j.message || 'Не удалось загрузить инструмент');
        const t = j.data;
        const form = document.getElementById('toolForm');
        form.name.value = t.name || '';
        form.name.disabled = true;
        form.dockerImage.value = t.dockerImage || '';
        form.fileExtensions.value = Array.isArray(t.fileExtensions) ? t.fileExtensions.join(', ') : (t.fileExtensions || '');
        form.enabled.checked = t.enabled !== false;
        editingToolId = id;
        document.getElementById('toolFormSubmit').textContent = 'Сохранить';
        document.getElementById('toolFormCancel').style.display = 'inline-block';
        document.getElementById('toolFormMsg').innerHTML = '';
        form.scrollIntoView({ behavior: 'smooth' });
      } catch (err) {
        document.getElementById('toolFormMsg').innerHTML = '<div class="msg err">' + err.message + '</div>';
      }
    }
    async function deleteTool(id) {
      if (!confirm('Удалить этот инструмент?')) return;
      try {
        const r = await fetch(API + '/tools/' + id, { method: 'DELETE' });
        const j = await r.json().catch(() => ({}));
        if (j.status === 'error' || (r.ok === false && j.message)) throw new Error(j.message || r.statusText);
        loadToolsList();
        loadTools();
      } catch (err) {
        alert('Ошибка: ' + err.message);
      }
    }

    function toggleToolsDetail(toolId) {
      const detailRow = document.querySelector(`tr.tools-detail-row[data-detail-for="${toolId}"]`);
      const mainRow = document.querySelector(`tr.tools-row[data-tool-id="${toolId}"]`);
      const icon = mainRow && mainRow.querySelector('.expand-icon');
      if (!detailRow) return;
      const isHidden = detailRow.style.display === 'none';
      detailRow.style.display = isHidden ? 'table-row' : 'none';
      if (icon) icon.textContent = isHidden ? '▼' : '▶';
    }

    document.getElementById('toolForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const form = e.target;
      const msgEl = document.getElementById('toolFormMsg');
      const exts = form.fileExtensions.value.trim().split(',').map(s => s.trim()).filter(Boolean);
      msgEl.innerHTML = '';
      try {
        if (editingToolId) {
          const body = { dockerImage: form.dockerImage.value.trim(), fileExtensions: exts, enabled: form.enabled.checked };
          const r = await fetch(API + '/tools/' + editingToolId, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
          const j = await r.json();
          if (j.status === 'error' || !r.ok) throw new Error(j.message || r.statusText);
          msgEl.innerHTML = '<div class="msg ok">Инструмент обновлён</div>';
          editingToolId = null;
          form.name.disabled = false;
          document.getElementById('toolFormSubmit').textContent = 'Создать';
          document.getElementById('toolFormCancel').style.display = 'none';
        } else {
          const body = { name: form.name.value.trim(), dockerImage: form.dockerImage.value.trim(), fileExtensions: exts, enabled: form.enabled.checked };
          const r = await fetch(API + '/tools', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
          const j = await r.json();
          if (j.status === 'error' || !r.ok) throw new Error(j.message || r.statusText);
          msgEl.innerHTML = '<div class="msg ok">Инструмент создан</div>';
        }
        loadToolsList();
        loadTools();
      } catch (err) {
        msgEl.innerHTML = '<div class="msg err">' + err.message + '</div>';
      }
    });
    document.getElementById('toolFormCancel').addEventListener('click', () => {
      editingToolId = null;
      document.getElementById('toolForm').reset();
      document.getElementById('toolForm').name.disabled = false;
      document.getElementById('toolFormSubmit').textContent = 'Создать';
      document.getElementById('toolFormCancel').style.display = 'none';
      document.getElementById('toolFormMsg').innerHTML = '';
    });

    document.getElementById('refreshTools').addEventListener('click', loadToolsList);

    async function loadSummarizersList() {
      const el = document.getElementById('summarizersTable');
      try {
        const r = await fetch(API + '/summarizers');
        const j = await r.json();
        if (j.status === 'error') throw new Error(j.message);
        const list = j.data != null ? (Array.isArray(j.data) ? j.data : j.data.data) : [];
        summarizerProviderByName = new Map();
        list.forEach(s => {
          if (s && s.name) summarizerProviderByName.set(String(s.name), String(s.provider || 'OPENAI').toUpperCase());
        });
        if (!list.length) {
          el.innerHTML = '<p class="meta">Нет маршрутов LLM</p>';
          return;
        }
        function esc(s) {
          if (s == null) return '—';
          const d = document.createElement('div');
          d.textContent = String(s);
          return d.innerHTML;
        }
        const rows = list.map(s => `
            <tr class="summarizers-row" data-summarizer-id="${s.id}" style="cursor:pointer">
              <td><span class="expand-icon" data-summarizer-id="${s.id}">▶</span></td>
              <td><code>${esc(s.id)}</code></td>
              <td>${esc(s.name)}</td>
              <td>${esc(s.provider)}</td>
              <td>${esc(s.baseUrl)}</td>
              <td>${esc(s.modelId)}</td>
              <td>${s.enabled ? 'да' : 'нет'}</td>
              <td class="actions-cell">
                <button type="button" class="btn btn-small summarizer-edit" data-summarizer-id="${s.id}">Изменить</button>
                <button type="button" class="btn btn-small btn-danger summarizer-delete" data-summarizer-id="${s.id}">Удалить</button>
              </td>
            </tr>
            <tr class="summarizers-detail-row" data-detail-for="${s.id}" style="display:none">
              <td colspan="8"><div class="tools-detail-inner"><pre>${esc(JSON.stringify(s, null, 2))}</pre></div></td>
            </tr>`).join('');
        el.innerHTML = `<table><thead><tr><th></th><th>ID</th><th>Имя</th><th>Провайдер</th><th>Base URL</th><th>Model ID</th><th>Включён</th><th>Действия</th></tr></thead><tbody>${rows}</tbody></table>`;
        el.querySelectorAll('.summarizers-row').forEach(tr => {
          tr.addEventListener('click', (e) => {
            if (e.target.classList.contains('expand-icon')) return;
            toggleSummarizersDetail(tr.dataset.summarizerId);
          });
        });
        el.querySelectorAll('.summarizers-row .expand-icon').forEach(span => {
          span.addEventListener('click', (e) => { e.stopPropagation(); toggleSummarizersDetail(span.dataset.summarizerId); });
        });
        el.querySelectorAll('.summarizer-edit').forEach(btn => {
          btn.addEventListener('click', (e) => { e.stopPropagation(); editSummarizer(btn.dataset.summarizerId); });
        });
        el.querySelectorAll('.summarizer-delete').forEach(btn => {
          btn.addEventListener('click', (e) => { e.stopPropagation(); deleteSummarizer(btn.dataset.summarizerId); });
        });
      } catch (e) {
        el.innerHTML = '<p class="msg err">Ошибка: ' + e.message + '</p>';
      }
    }

    async function editSummarizer(id) {
      try {
        const r = await fetch(API + '/summarizers/' + id);
        const j = await r.json();
        if (j.status === 'error' || !j.data) throw new Error(j.message || 'Не удалось загрузить маршрут LLM');
        const s = j.data;
        const form = document.getElementById('summarizerForm');
        form.name.value = s.name || '';
        form.name.disabled = true;
        form.provider.value = s.provider || 'OPENAI';
        form.baseUrl.value = s.baseUrl || '';
        form.modelId.value = s.modelId || '';
        form.apiKeyEnvVar.value = s.apiKeyEnvVar || '';
        form.enabled.checked = s.enabled !== false;
        editingSummarizerId = id;
        document.getElementById('summarizerFormSubmit').textContent = 'Сохранить';
        document.getElementById('summarizerFormCancel').style.display = 'inline-block';
        document.getElementById('summarizerFormMsg').innerHTML = '';
        applySummarizerFormProviderUi();
        form.scrollIntoView({ behavior: 'smooth' });
      } catch (err) {
        document.getElementById('summarizerFormMsg').innerHTML = '<div class="msg err">' + err.message + '</div>';
      }
    }
    async function deleteSummarizer(id) {
      if (!confirm('Удалить этот маршрут LLM?')) return;
      try {
        const r = await fetch(API + '/summarizers/' + id, { method: 'DELETE' });
        const j = await r.json().catch(() => ({}));
        if (j.status === 'error' || (r.ok === false && j.message)) throw new Error(j.message || r.statusText);
        loadSummarizersList();
        loadSummarizers();
      } catch (err) {
        alert('Ошибка: ' + err.message);
      }
    }
    function toggleSummarizersDetail(id) {
      const detailRow = document.querySelector(`tr.summarizers-detail-row[data-detail-for="${id}"]`);
      const mainRow = document.querySelector(`tr.summarizers-row[data-summarizer-id="${id}"]`);
      const icon = mainRow && mainRow.querySelector('.expand-icon');
      if (!detailRow) return;
      const isHidden = detailRow.style.display === 'none';
      detailRow.style.display = isHidden ? 'table-row' : 'none';
      if (icon) icon.textContent = isHidden ? '▼' : '▶';
    }
    document.getElementById('summarizerForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const form = e.target;
      const msgEl = document.getElementById('summarizerFormMsg');
      msgEl.innerHTML = '';
      const pv = form.provider.value;
      if (!editingSummarizerId && pv !== 'OPENAI' && pv !== 'EXTERNAL') {
        msgEl.innerHTML = '<div class="msg err">Сначала выберите провайдер.</div>';
        return;
      }
      try {
        if (editingSummarizerId) {
          const body = { provider: form.provider.value, baseUrl: form.baseUrl.value.trim() || null, modelId: form.modelId.value.trim(), apiKeyEnvVar: form.apiKeyEnvVar.value.trim() || null, enabled: form.enabled.checked };
          const r = await fetch(API + '/summarizers/' + editingSummarizerId, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
          const j = await r.json();
          if (j.status === 'error' || !r.ok) throw new Error(j.message || r.statusText);
          msgEl.innerHTML = '<div class="msg ok">Маршрут LLM обновлён</div>';
          editingSummarizerId = null;
          form.name.disabled = false;
          document.getElementById('summarizerFormSubmit').textContent = 'Создать';
          document.getElementById('summarizerFormCancel').style.display = 'none';
          applySummarizerFormProviderUi();
        } else {
          const body = { name: form.name.value.trim(), provider: form.provider.value, baseUrl: form.baseUrl.value.trim() || null, modelId: form.modelId.value.trim(), apiKeyEnvVar: form.apiKeyEnvVar.value.trim() || null, enabled: form.enabled.checked };
          const r = await fetch(API + '/summarizers', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
          const j = await r.json();
          if (j.status === 'error' || !r.ok) throw new Error(j.message || r.statusText);
          msgEl.innerHTML = '<div class="msg ok">Маршрут LLM создан</div>';
        }
        loadSummarizersList();
        loadSummarizers();
      } catch (err) {
        msgEl.innerHTML = '<div class="msg err">' + err.message + '</div>';
      }
    });
    document.getElementById('summarizerFormCancel').addEventListener('click', () => {
      editingSummarizerId = null;
      document.getElementById('summarizerForm').reset();
      document.getElementById('summarizerForm').name.disabled = false;
      document.getElementById('summarizerFormSubmit').textContent = 'Создать';
      document.getElementById('summarizerFormCancel').style.display = 'none';
      document.getElementById('summarizerFormMsg').innerHTML = '';
      applySummarizerFormProviderUi();
    });
    document.getElementById('summarizerForm').provider.addEventListener('change', applySummarizerFormProviderUi);
    applySummarizerFormProviderUi();
    document.getElementById('refreshSummarizers').addEventListener('click', loadSummarizersList);

    function fillDockerFormFromProfile(p) {
      const form = document.getElementById('dockerForm');
      document.getElementById('dockerEditingProfileId').value = p.id || '';
      form.profileName.value = p.name || '';
      document.getElementById('dockerHostUri').value = p.dockerHostUri || '';
      document.getElementById('namedVolumeForChildBinds').value = p.namedVolumeForChildBinds || '';
      document.getElementById('dockerProfileEnabled').checked = p.enabled !== false;
      form.memoryLimitMb.value = p.memoryLimitMb ?? '';
      form.memoryReservationMb.value = p.memoryReservationMb ?? '';
      form.cpuLimit.value = p.cpuLimit ?? '';
      form.cpuShares.value = p.cpuShares ?? '';
      form.maxConcurrentContainers.value = p.maxConcurrentContainers ?? 1;
      form.networkMode.value = p.networkMode ?? '';
      form.restartPolicy.value = p.restartPolicy ?? '';
      form.restartMaxRetries.value = p.restartMaxRetries ?? '';
      form.logDriver.value = p.logDriver ?? '';
      form.logMaxSize.value = p.logMaxSize ?? '';
      form.logMaxFiles.value = p.logMaxFiles ?? '';
      form.environmentVariables.value = p.environmentVariables ? (typeof p.environmentVariables === 'string' ? p.environmentVariables : JSON.stringify(p.environmentVariables)) : '';
      form.labels.value = p.labels ? (typeof p.labels === 'string' ? p.labels : JSON.stringify(p.labels)) : '';
    }


