    async function refreshSummarizerProviderMap() {
      summarizerProviderByName = new Map();
      try {
        const r = await fetch(API + '/summarizers');
        const j = await r.json();
        if (j.status === 'error') return;
        const list = j.data != null ? (Array.isArray(j.data) ? j.data : (j.data.data || [])) : [];
        list.forEach(s => {
          if (s && s.name) summarizerProviderByName.set(String(s.name), String(s.provider || 'OPENAI').toUpperCase());
        });
      } catch (_) { /* ignore */ }
    }

    function summarizerSelectLabel(s) {
      const p = String(s.provider || 'OPENAI').toUpperCase();
      const hint = p === 'EXTERNAL' ? 'внешний контур (без LiteLLM)' : 'через summarization-service (часто LiteLLM)';
      return s.name + ' — ' + p + ' · ' + hint;
    }

    function summarizerRouteBadgeHtml(summarizerName) {
      if (!summarizerName) return '';
      const p = summarizerProviderByName.get(summarizerName);
      if (p === 'EXTERNAL') {
        return ' <span class="meta" title="Не идёт в LiteLLM: пакет наружу и callback summary">[EXTERNAL]</span>';
      }
      if (p) {
        return ' <span class="meta" title="Идёт в summarization-service → POST …/v1/chat/completions (у вас обычно LiteLLM)">[LiteLLM]</span>';
      }
      return '';
    }

    function applySummarizerFormProviderUi() {
      const form = document.getElementById('summarizerForm');
      if (!form || !form.provider) return;
      const pv = form.provider.value;
      const hasProvider = pv === 'OPENAI' || pv === 'EXTERNAL';
      const ext = pv === 'EXTERNAL';
      const wrap = document.getElementById('summarizerProviderSpecificFields');
      if (wrap) wrap.style.display = hasProvider ? '' : 'none';
      const submitBtn = document.getElementById('summarizerFormSubmit');
      if (submitBtn) {
        submitBtn.disabled = !editingSummarizerId && !hasProvider;
      }
      if (!hasProvider) return;
      const baseLbl = document.getElementById('summarizerBaseUrlLabel');
      const baseInp = document.getElementById('summarizerBaseUrlInput');
      const modelLbl = document.getElementById('summarizerModelIdLabel');
      const modelRow = document.getElementById('summarizerModelIdRow');
      const apiKeyRow = document.getElementById('summarizerApiKeyRow');
      if (baseLbl) {
        baseLbl.textContent = ext
          ? 'Полный URL приёма пакета (ingest), POST JSON — куда app-module отправит метрики и артефакты'
          : 'Base URL (LiteLLM / OpenAI — без суффикса /v1/...)';
      }
      if (baseInp) {
        baseInp.placeholder = ext ? 'https://your-gw.example.com/loadtest/ingest' : 'http://localhost:4000';
      }
      if (modelLbl) {
        modelLbl.textContent = ext ? 'Метка модели (опционально, по умолчанию external)' : 'Model ID (имя модели)';
      }
      if (modelRow) modelRow.style.display = '';
      if (apiKeyRow) apiKeyRow.style.display = ext ? 'none' : '';
    }

    function partitionSummarizersByProvider(list) {
      const llm = [];
      const ext = [];
      (list || []).forEach(s => {
        if (!s || !s.name) return;
        if (String(s.provider || 'OPENAI').toUpperCase() === 'EXTERNAL') {
          ext.push(s);
        } else {
          llm.push(s);
        }
      });
      return { llm, ext };
    }

    function summarizerOptionsInnerHtml(list) {
      const { llm, ext } = partitionSummarizersByProvider(list);
      let h = '';
      if (llm.length) {
        h += '<optgroup label="Через summarization-service (LiteLLM и др.)">';
        h += llm.map(s => `<option value="${escapeAttr(s.name)}">${escapeHtml(summarizerSelectLabel(s))}</option>`).join('');
        h += '</optgroup>';
      }
      if (ext.length) {
        h += '<optgroup label="Внешний контур (без LiteLLM)">';
        h += ext.map(s => `<option value="${escapeAttr(s.name)}">${escapeHtml(summarizerSelectLabel(s))}</option>`).join('');
        h += '</optgroup>';
      }
      return h;
    }


