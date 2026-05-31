    async function fetchMetricsConfigSchema() {
      if (metricsConfigSchema) return metricsConfigSchema;
      const r = await fetch(API + '/metrics-config-schema');
      if (!r.ok) throw new Error('Не удалось загрузить JSON Schema метрик (HTTP ' + r.status + ')');
      metricsConfigSchema = await r.json();
      return metricsConfigSchema;
    }

    function metricsQueryParamsToString(qp) {
      if (qp == null || qp === '') return '';
      if (typeof qp === 'object') return JSON.stringify(qp);
      return String(qp);
    }

    function appendMetricsRequestCard(initial) {
      const list = document.getElementById('metricsRequestsList');
      const art = document.createElement('article');
      art.className = 'metrics-req-card';
      const sel = METRICS_HTTP_METHODS.map(function (m) {
        const selAttr = (initial.method || 'GET').toUpperCase() === m ? ' selected' : '';
        return '<option value="' + m + '"' + selAttr + '>' + m + '</option>';
      }).join('');
      art.innerHTML =
        '<header class="metrics-req-card-head">' +
        '  <p class="metrics-req-card-title">Запрос</p>' +
        '  <button type="button" class="btn btn-secondary btn-small metrics-req-remove">Удалить</button>' +
        '</header>' +
        '<div class="metrics-req-fields">' +
        '  <div class="form-group">' +
        '    <label>Имя в отчёте <span class="meta">(необязательно)</span></label>' +
        '    <input type="text" class="metrics-in-name" placeholder="prometheus" autocomplete="off">' +
        '  </div>' +
        '  <div class="form-group">' +
        '    <label>Метод</label>' +
        '    <select class="metrics-in-method">' + sel + '</select>' +
        '  </div>' +
        '  <div class="form-group">' +
        '    <label>URL <span class="meta">(обязательно)</span></label>' +
        '    <input type="text" class="metrics-in-url" placeholder="http://prometheus:9090/api/v1/query" autocomplete="off">' +
        '  </div>' +
        '  <div class="form-group">' +
        '    <label>Query</label>' +
        '    <input type="text" class="metrics-in-query" placeholder="query=up или JSON-объект">' +
        '    <span class="metrics-field-hint">Строка вида key=value&amp;… или JSON-объект с ключами (значения — строка/число/логическое).</span>' +
        '  </div>' +
        '  <div class="form-group">' +
        '    <label>Заголовки <span class="meta">(необязательно, JSON)</span></label>' +
        '    <textarea class="metrics-in-headers" rows="2" placeholder="{ }"></textarea>' +
        '  </div>' +
        '  <div class="form-group">' +
        '    <label>Тело <span class="meta">(необязательно, для POST/PUT/PATCH)</span></label>' +
        '    <textarea class="metrics-in-body" rows="3" placeholder="{ }"></textarea>' +
        '  </div>' +
        '</div>';
      art.querySelector('.metrics-in-name').value = initial.name != null ? String(initial.name) : '';
      art.querySelector('.metrics-in-url').value = initial.url != null ? String(initial.url) : '';
      art.querySelector('.metrics-in-query').value = metricsQueryParamsToString(initial.queryParams);
      art.querySelector('.metrics-in-headers').value = initial.headers && typeof initial.headers === 'object'
        ? JSON.stringify(initial.headers, null, 2) : '';
      if (initial.body !== undefined && initial.body !== null && initial.body !== '') {
        art.querySelector('.metrics-in-body').value = typeof initial.body === 'object'
          ? JSON.stringify(initial.body, null, 2) : String(initial.body);
      }
      list.appendChild(art);
    }

    function renumberMetricsRequestCards() {
      const list = document.getElementById('metricsRequestsList');
      const cards = list.querySelectorAll('.metrics-req-card');
      cards.forEach(function (card, i) {
        const t = card.querySelector('.metrics-req-card-title');
        if (t) t.textContent = 'Запрос ' + (i + 1);
      });
      const onlyOne = cards.length <= 1;
      list.querySelectorAll('.metrics-req-remove').forEach(function (b) {
        b.disabled = onlyOne;
        b.style.opacity = onlyOne ? '0.45' : '';
        b.title = onlyOne ? 'Нужен хотя бы один запрос' : '';
      });
    }

    function initMetricsConfigUiOnce() {
      if (metricsConfigUiInitialized) return;
      const list = document.getElementById('metricsRequestsList');
      list.innerHTML = '';
      appendMetricsRequestCard(METRICS_DEFAULT_REQUEST);
      renumberMetricsRequestCards();
      metricsConfigUiInitialized = true;
    }

    document.getElementById('metricsRequestsList').addEventListener('click', function (e) {
      if (!e.target.closest('.metrics-req-remove')) return;
      const cards = this.querySelectorAll('.metrics-req-card');
      if (cards.length <= 1) return;
      e.target.closest('.metrics-req-card').remove();
      renumberMetricsRequestCards();
    });

    document.getElementById('metricsAddRequest').addEventListener('click', function () {
      if (!document.getElementById('metricsConfigEnabled').checked) return;
      appendMetricsRequestCard({ method: 'GET', url: '', name: '' });
      renumberMetricsRequestCards();
    });

    function serializeMetricsConfig() {
      const errors = [];
      const delayEl = document.getElementById('metricsDelaySeconds');
      let delaySeconds = parseInt(delayEl.value, 10);
      if (!Number.isFinite(delaySeconds) || delaySeconds < 0) delaySeconds = 0;
      const requests = [];
      document.querySelectorAll('#metricsRequestsList .metrics-req-card').forEach(function (card, i) {
        const n = i + 1;
        const url = card.querySelector('.metrics-in-url').value.trim();
        const name = card.querySelector('.metrics-in-name').value.trim();
        const method = (card.querySelector('.metrics-in-method').value || 'GET').trim().toUpperCase();
        const qRaw = card.querySelector('.metrics-in-query').value.trim();
        const hRaw = card.querySelector('.metrics-in-headers').value.trim();
        const bRaw = card.querySelector('.metrics-in-body').value.trim();
        if (!url) errors.push('Запрос ' + n + ': укажите URL');
        if (METRICS_HTTP_METHODS.indexOf(method) < 0) errors.push('Запрос ' + n + ': недопустимый метод HTTP');
        const req = { url: url, method: method };
        if (name) req.name = name;
        if (qRaw) {
          if (qRaw.startsWith('{')) {
            try {
              req.queryParams = JSON.parse(qRaw);
            } catch (_) {
              errors.push('Запрос ' + n + ': поле Query — неверный JSON');
            }
          } else {
            req.queryParams = qRaw;
          }
        }
        if (hRaw) {
          try {
            const o = JSON.parse(hRaw);
            if (typeof o !== 'object' || o === null || Array.isArray(o)) {
              errors.push('Запрос ' + n + ': заголовки должны быть JSON-объектом');
            } else {
              req.headers = o;
            }
          } catch (_) {
            errors.push('Запрос ' + n + ': заголовки — неверный JSON');
          }
        }
        if (bRaw) {
          try {
            req.body = JSON.parse(bRaw);
          } catch (_) {
            req.body = bRaw;
          }
        }
        requests.push(req);
      });
      return { errors: errors, data: { delaySeconds: delaySeconds, requests: requests } };
    }

    document.getElementById('metricsConfigEnabled').addEventListener('change', function () {
      const wrap = document.getElementById('metricsConfigEditorWrap');
      const msgEl = document.getElementById('runMsg');
      if (this.checked) {
        wrap.style.display = 'block';
        msgEl.innerHTML = '';
        initMetricsConfigUiOnce();
        fetchMetricsConfigSchema().catch(function () { /* ссылка на схему всё равно ведёт на API */ });
      } else {
        wrap.style.display = 'none';
      }
    });

    document.getElementById('metricsConfigRawToggle').addEventListener('click', function () {
      const ta = document.getElementById('metricsConfigRaw');
      if (!metricsConfigUiInitialized) return;
      const show = ta.style.display === 'none';
      if (show) {
        const pack = serializeMetricsConfig();
        ta.value = pack.errors.length
          ? '// Исправьте: ' + pack.errors.join(' ') + '\n' + JSON.stringify(pack.data, null, 2)
          : JSON.stringify(pack.data, null, 2);
        this.textContent = 'Скрыть JSON';
      } else {
        this.textContent = 'Посмотреть итоговый JSON';
      }
      ta.style.display = show ? 'block' : 'none';
    });

    /** Имя маршрута → OPENAI | EXTERNAL (из GET /summarizers, все записи). */
    let summarizerProviderByName = new Map();
    let editingSummarizerId = null;


