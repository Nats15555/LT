    async function loadDocker() {
      const contentEl = document.getElementById('dockerContent');
      const formBox = document.getElementById('dockerFormBox');
      const formTitle = document.getElementById('dockerFormTitle');
      const formSubmit = document.getElementById('dockerFormSubmit');
      const formCancel = document.getElementById('dockerFormCancel');
      const deleteBtn = document.getElementById('dockerDelete');
      const msgEl = document.getElementById('dockerFormMsg');
      msgEl.innerHTML = '';
      try {
        const r = await fetch(API + '/docker-profiles');
        const j = await r.json();
        if (j.status === 'error') throw new Error(j.message || 'profiles');
        const profiles = j.profiles || [];
        if (!profiles.length) {
          contentEl.innerHTML = '<p class="meta">Нет профилей. Заполните форму ниже и создайте первый.</p>';
          formBox.style.display = 'block';
          formTitle.textContent = 'Создать профиль Docker';
          formSubmit.textContent = 'Создать';
          formCancel.style.display = 'none';
          deleteBtn.style.display = 'none';
          document.getElementById('dockerEditingProfileId').value = '';
          document.getElementById('dockerForm').reset();
          document.getElementById('dockerProfileEnabled').checked = true;
          return;
        }
        const rows = profiles.map(function (p) {
          return '<tr><td>' + escapeHtml(dockerProfileDisplayName(p.name)) + '</td><td>' + p.maxConcurrentContainers + '</td><td>' + (p.enabled ? 'да' : 'нет') + '</td><td><button type="button" class="btn btn-small btn-edit-docker" data-id="' + escapeAttr(p.id) + '">Править</button></td></tr>';
        }).join('');
        contentEl.innerHTML = '<table><thead><tr><th>Имя</th><th>Макс. параллельно</th><th>Вкл.</th><th></th></tr></thead><tbody>' + rows + '</tbody></table><p style="margin-top:0.75rem"><button type="button" class="btn" id="dockerNewBtn">Новый профиль</button></p>';
        formBox.style.display = 'none';
        deleteBtn.style.display = 'none';
        contentEl.querySelectorAll('.btn-edit-docker').forEach(function (btn) {
          btn.addEventListener('click', async function () {
            try {
              const id = this.getAttribute('data-id');
              const pr = await fetch(API + '/docker-profiles/' + encodeURIComponent(id));
              const pj = await pr.json();
              if (pj.status === 'error' || !pj.profile) throw new Error(pj.message || 'profile');
              const p = pj.profile;
              formBox.style.display = 'block';
              formTitle.textContent = 'Обновить профиль';
              formSubmit.textContent = 'Сохранить';
              formCancel.style.display = 'inline-block';
              deleteBtn.style.display = p.name === 'Default' ? 'none' : 'inline-block';
              fillDockerFormFromProfile(p);
            } catch (err) {
              document.getElementById('dockerFormMsg').innerHTML = '<div class="msg err">' + err.message + '</div>';
            }
          });
        });
        document.getElementById('dockerNewBtn').addEventListener('click', function () {
          formBox.style.display = 'block';
          formTitle.textContent = 'Создать профиль Docker';
          formSubmit.textContent = 'Создать';
          formCancel.style.display = 'inline-block';
          deleteBtn.style.display = 'none';
          document.getElementById('dockerForm').reset();
          document.getElementById('dockerEditingProfileId').value = '';
          document.getElementById('dockerProfileEnabled').checked = true;
        });
      } catch (e) {
        contentEl.innerHTML = '<p class="msg err">Ошибка: ' + e.message + '</p>';
      }
    }

    document.getElementById('dockerForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const form = e.target;
      const msgEl = document.getElementById('dockerFormMsg');
      const editId = document.getElementById('dockerEditingProfileId').value.trim();
      const num = (name) => { const v = form[name].value; return v === '' ? null : (name === 'cpuLimit' ? parseFloat(v) : parseInt(v, 10)); };
      const str = (name) => form[name].value.trim() || null;
      const body = {
        name: form.profileName.value.trim(),
        dockerHostUri: str('dockerHostUri'),
        namedVolumeForChildBinds: str('namedVolumeForChildBinds'),
        enabled: document.getElementById('dockerProfileEnabled').checked,
        memoryLimitMb: num('memoryLimitMb'),
        memoryReservationMb: num('memoryReservationMb'),
        cpuLimit: num('cpuLimit'),
        cpuShares: num('cpuShares'),
        maxConcurrentContainers: num('maxConcurrentContainers') ?? 1,
        networkMode: str('networkMode'),
        restartPolicy: str('restartPolicy'),
        restartMaxRetries: num('restartMaxRetries'),
        logDriver: str('logDriver'),
        logMaxSize: str('logMaxSize'),
        logMaxFiles: num('logMaxFiles'),
        environmentVariables: str('environmentVariables'),
        labels: str('labels')
      };
      if (!body.name) {
        msgEl.innerHTML = '<div class="msg err">Укажите имя профиля</div>';
        return;
      }
      if (body.memoryLimitMb == null) body.memoryLimitMb = 512;
      if (body.memoryReservationMb == null) body.memoryReservationMb = 256;
      if (body.cpuLimit == null) body.cpuLimit = 1;
      if (body.cpuShares == null) body.cpuShares = 0;
      if (body.maxConcurrentContainers == null) body.maxConcurrentContainers = 1;
      if (body.logMaxFiles == null) body.logMaxFiles = 3;
      msgEl.innerHTML = '';
      try {
        const url = editId
            ? (API + '/docker-profiles/' + encodeURIComponent(editId))
            : (API + '/docker-profiles');
        const method = editId ? 'PUT' : 'POST';
        const r = await fetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        const j = await r.json();
        if (j.status === 'error' || !r.ok) throw new Error(j.message || r.statusText);
        msgEl.innerHTML = '<div class="msg ok">Профиль сохранён</div>';
        loadDocker();
        loadDockerProfilesForRun();
      } catch (err) {
        msgEl.innerHTML = '<div class="msg err">' + err.message + '</div>';
      }
    });
    document.getElementById('dockerFormCancel').addEventListener('click', () => { document.getElementById('dockerFormBox').style.display = 'none'; loadDocker(); });
    document.getElementById('dockerDelete').addEventListener('click', async () => {
      const editId = document.getElementById('dockerEditingProfileId').value.trim();
      if (!editId) return;
      if (!confirm('Удалить этот профиль Docker?')) return;
      try {
        const r = await fetch(API + '/docker-profiles/' + encodeURIComponent(editId), { method: 'DELETE' });
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(j.message || r.statusText);
        document.getElementById('dockerFormBox').style.display = 'none';
        loadDocker();
        loadDockerProfilesForRun();
      } catch (e) {
        document.getElementById('dockerFormMsg').innerHTML = '<div class="msg err">' + e.message + '</div>';
      }
    });
    document.getElementById('refreshDocker').addEventListener('click', loadDocker);

    function applyRunPromptUi() {
      const useStandard = document.getElementById('runUseStandardPrompt');
      const row = document.getElementById('runCustomPromptRow');
      const input = document.getElementById('runCustomPrompt');
      const showCustom = useStandard ? !useStandard.checked : false;
      if (row) row.style.display = showCustom ? '' : 'none';
      if (input) input.required = showCustom;
    }

    let standardPromptTemplateLoaded = false;
    let standardPromptTemplateLoading = false;
    async function loadStandardPromptTemplate() {
      if (standardPromptTemplateLoaded || standardPromptTemplateLoading) return;
      const pre = document.getElementById('runStandardPromptHelpText');
      if (!pre) return;
      standardPromptTemplateLoading = true;
      try {
        const r = await fetch(API + '/standard-summarization-prompt-template');
        const j = r.ok ? await r.json() : {};
        pre.textContent = j.template || 'Не удалось загрузить шаблон промпта.';
        standardPromptTemplateLoaded = true;
      } catch (err) {
        pre.textContent = 'Не удалось загрузить шаблон промпта.';
      } finally {
        standardPromptTemplateLoading = false;
      }
    }

    const runStandardPromptHelpWrap = document.getElementById('runStandardPromptHelpWrap');
    if (runStandardPromptHelpWrap) {
      runStandardPromptHelpWrap.addEventListener('mouseenter', () => { loadStandardPromptTemplate(); });
      runStandardPromptHelpWrap.addEventListener('focusin', () => { loadStandardPromptTemplate(); });
    }

    document.getElementById('runForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const msgEl = document.getElementById('runMsg');
      const form = e.target;
      const fileInput = document.getElementById('fileInput');
      if (!fileInput.files.length) {
        msgEl.innerHTML = '<div class="msg err">Выберите файл</div>';
        return;
      }
      const fd = new FormData();
      fd.append('file', fileInput.files[0]);
      fd.append('tool', form.tool.value);
      fd.append('command', form.command.value);
      fd.append('expectedDurationSeconds', form.expectedDurationSeconds.value);
      if (document.getElementById('metricsConfigEnabled').checked) {
        if (!metricsConfigUiInitialized) initMetricsConfigUiOnce();
        const pack = serializeMetricsConfig();
        if (pack.errors.length) {
          msgEl.innerHTML = '<div class="msg err">' + pack.errors.map(function (x) {
            return String(x).replace(/&/g, '&amp;').replace(/</g, '&lt;');
          }).join('<br>') + '</div>';
          return;
        }
        fd.append('metricsConfig', JSON.stringify(pack.data));
      }
      if (form.summarizer && form.summarizer.value.trim()) fd.append('summarizer', form.summarizer.value.trim());
      if (!document.getElementById('runUseStandardPrompt').checked) {
        const p = document.getElementById('runCustomPrompt').value.trim();
        if (!p) {
          msgEl.innerHTML = '<div class="msg err">Введите свой промпт или включите стандартный.</div>';
          return;
        }
        fd.append('customPrompt', p);
      }
      const dps = document.getElementById('dockerProfileSelect');
      if (!dps || !dps.value) {
        msgEl.innerHTML = '<div class="msg err">Выберите профиль Docker-выполнения.</div>';
        return;
      }
      fd.append('dockerExecutionProfileId', dps.value);

      msgEl.innerHTML = '<div class="msg">Отправка…</div>';
      try {
        const r = await fetch(API + '/upload', { method: 'POST', body: fd });
        const res = await r.json();
        if (res.status === 'success') {
          msgEl.innerHTML = `<div class="msg ok">Задача добавлена: ${res.taskId}</div>`;
          loadQueue();
        } else {
          msgEl.innerHTML = '<div class="msg err">' + (res.message || r.status) + '</div>';
        }
      } catch (err) {
        msgEl.innerHTML = '<div class="msg err">' + err.message + '</div>';
      }
    });

    document.getElementById('runUseStandardPrompt').addEventListener('change', applyRunPromptUi);
    applyRunPromptUi();

    loadDockerProfilesForRun();
    loadQueue();

