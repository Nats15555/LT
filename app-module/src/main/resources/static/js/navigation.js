    async function loadDockerProfilesForRun() {
      const sel = document.getElementById('dockerProfileSelect');
      if (!sel) return;
      try {
        const r = await fetch(API + '/docker-profiles?enabledOnly=true');
        const j = await r.json();
        if (j.status === 'error') throw new Error(j.message || 'profiles');
        const list = j.profiles || [];
        sel.innerHTML = '';
        list.forEach(function (p) {
          const o = document.createElement('option');
          o.value = p.id;
          o.textContent = p.name + ' (до ' + p.maxConcurrentContainers + ' параллельно)';
          sel.appendChild(o);
        });
        if (!sel.options.length) {
          sel.innerHTML = '<option value="">— нет включённых профилей —</option>';
        }
      } catch (e) {
        console.error(e);
      }
    }

    function fillSummarizerSelectOptgroups(selectEl, list) {
      selectEl.innerHTML = '<option value="">— как в этом прогоне —</option>';
      const { llm, ext } = partitionSummarizersByProvider(list);
      if (llm.length) {
        const og = document.createElement('optgroup');
        og.label = 'Через summarization-service (LiteLLM и др.)';
        llm.forEach(s => og.appendChild(new Option(summarizerSelectLabel(s), s.name)));
        selectEl.appendChild(og);
      }
      if (ext.length) {
        const og = document.createElement('optgroup');
        og.label = 'Внешний контур (без LiteLLM)';
        ext.forEach(s => og.appendChild(new Option(summarizerSelectLabel(s), s.name)));
        selectEl.appendChild(og);
      }
    }

    function showTab(id) {
      document.querySelectorAll('section').forEach(s => s.classList.remove('visible'));
      document.querySelectorAll('nav button[data-tab]').forEach(b => b.classList.remove('active'));
      document.getElementById(id).classList.add('visible');
      const tabBtn = document.querySelector(`nav button[data-tab="${id}"]`);
      if (tabBtn) tabBtn.classList.add('active');
      if (id === 'queue') loadQueue();
      if (id === 'history') {
        handleHistoryHash();
        const h = location.hash.replace(/^#/, '');
        if (!/^history\/[0-9a-fA-F-]{36}$/.test(h)) {
          loadHistory();
        }
      }
      if (id === 'run') loadDockerProfilesForRun();
      if (id === 'config') showConfigSubtab('tools');
    }

    function showConfigSubtab(subId) {
      document.querySelectorAll('.config-panel').forEach(p => p.classList.remove('visible'));
      document.querySelectorAll('.subnav button').forEach(b => b.classList.remove('active'));
      const panel = document.getElementById('config-' + subId);
      const btn = document.querySelector(`.subnav button[data-subtab="${subId}"]`);
      if (panel) panel.classList.add('visible');
      if (btn) btn.classList.add('active');
      if (subId === 'tools') loadToolsList();
      if (subId === 'summarizers') loadSummarizersList();
      if (subId === 'docker') loadDocker();
    }

    document.querySelectorAll('nav button[data-tab]').forEach(btn => {
      btn.addEventListener('click', () => showTab(btn.dataset.tab));
    });
    document.querySelectorAll('.subnav button[data-subtab]').forEach(btn => {
      btn.addEventListener('click', () => showConfigSubtab(btn.dataset.subtab));
    });


