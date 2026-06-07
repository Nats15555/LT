    async function loadQueuePauseUi() {
      const label = document.getElementById('queuePauseLabel');
      const btn = document.getElementById('queuePauseToggle');
      try {
        const r = await fetch(API + '/queue/pause');
        let j = {};
        try { j = await r.json(); } catch (_) { /* не JSON (например 404 HTML) */ }
        if (!r.ok) {
          const hint = r.status === 404
            ? ' Эндпоинт не найден: пересоберите и перезапустите app-module (образ loadtest-app).'
            : '';
          throw new Error((j.message || ('HTTP ' + r.status)) + hint);
        }
        const paused = !!j.paused;
        const n = j.pendingKafkaDispatchCount != null ? j.pendingKafkaDispatchCount : 0;
        label.textContent = paused
          ? 'Пауза очереди включена: задачи сохраняются в БД, в Kafka не уходят. Ожидают отправки после снятия паузы: ' + n + '. В этом режиме можно править профили Docker при непустой очереди.'
          : '';
        btn.textContent = paused ? 'Снять паузу очереди' : 'Поставить очередь на паузу';
        btn.dataset.paused = paused ? '1' : '0';
      } catch (e) {
        label.textContent = 'Состояние паузы: ошибка загрузки — ' + (e.message || String(e));
      }
    }

    async function toggleQueuePause() {
      const btn = document.getElementById('queuePauseToggle');
      const cur = btn.dataset.paused === '1';
      try {
        const r = await fetch(API + '/queue/pause', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ paused: !cur })
        });
        const j = await r.json().catch(() => ({}));
        if (!r.ok) {
          alert(j.message || ('Ошибка ' + r.status));
          return;
        }
        await loadQueuePauseUi();
        await loadQueue();
      } catch (e) {
        alert(e.message || String(e));
      }
    }

    async function loadQueue() {
      const el = document.getElementById('queueTable');
      const pageInfoEl = document.getElementById('queuePageInfo');
      const prevBtn = document.getElementById('queuePrevPage');
      const nextBtn = document.getElementById('queueNextPage');
      try {
        await loadQueuePauseUi();
        await refreshSummarizerProviderMap();
        const r = await fetch(API + '/tasks?page=' + queuePage + '&size=' + QUEUE_PAGE_SIZE);
        if (!r.ok) {
          throw new Error('HTTP ' + r.status);
        }
        const payload = await r.json();
        const tasks = Array.isArray(payload?.items) ? payload.items : [];
        const totalPages = Number.isFinite(payload?.totalPages) ? payload.totalPages : 0;
        const totalElements = Number.isFinite(payload?.totalElements) ? payload.totalElements : tasks.length;
        const currentPage = Number.isFinite(payload?.page) ? payload.page : queuePage;
        if (pageInfoEl) {
          const safeTotalPages = totalPages > 0 ? totalPages : 1;
          pageInfoEl.textContent = 'Страница ' + (currentPage + 1) + ' из ' + safeTotalPages + ' (' + totalElements + ' задач)';
        }
        if (prevBtn) prevBtn.disabled = currentPage <= 0;
        if (nextBtn) nextBtn.disabled = totalPages <= 0 || currentPage >= (totalPages - 1);
        if (!tasks.length) {
          el.innerHTML = '<p class="meta">Очередь пуста</p>';
          return;
        }
        el.innerHTML = `
          <table>
            <thead><tr><th>ID</th><th>Статус</th><th>Инструмент</th><th>Файл</th><th>Профиль</th><th>Маршрут отчёта</th><th>Создан</th><th></th></tr></thead>
            <tbody>
              ${tasks.map(t => `
                <tr>
                  <td><code>${t.id}</code></td>
                  <td>${escapeHtml(taskStatusLabel(t.status))}</td>
                  <td>${t.testTool}</td>
                  <td>${t.testFileName}</td>
                  <td class="meta">${t.dockerProfileName ? escapeHtml(dockerProfileDisplayName(t.dockerProfileName)) : '—'}</td>
                  <td class="meta">${t.summarizerName ? escapeHtml(t.summarizerName) + summarizerRouteBadgeHtml(t.summarizerName) : '—'}</td>
                  <td class="meta">${formatDate(t.createdAt)}</td>
                  <td class="actions-cell">${t.status === 'PENDING' ? '<button type="button" class="btn btn-small btn-danger btn-delete-queue" data-task-id="' + escapeHtml(t.id) + '">Удалить</button>' : '<span class="meta">—</span>'}</td>
                </tr>
              `).join('')}
            </tbody>
          </table>`;
        el.querySelectorAll('.btn-delete-queue').forEach(btn => {
          btn.addEventListener('click', async function (ev) {
            ev.preventDefault();
            const id = this.getAttribute('data-task-id');
            if (!id || !confirm('Удалить задачу из очереди? Её уже нельзя будет запустить из этого места.')) return;
            try {
              const r = await fetch(API + '/tasks/' + id, { method: 'DELETE' });
              const data = r.ok ? await r.json().catch(() => ({})) : await r.json().catch(() => ({}));
              if (r.ok) {
                if (tasks.length === 1 && queuePage > 0) queuePage -= 1;
                loadQueue();
              } else {
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


