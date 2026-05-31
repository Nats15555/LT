    function formatDate(s) {
      if (!s) return '—';
      try {
        const d = new Date(s);
        return isNaN(d.getTime()) ? s : d.toLocaleString();
      } catch (_) { return s; }
    }

    document.getElementById('refreshQueue').addEventListener('click', () => {
      queuePage = 0;
      loadQueue();
    });
    document.getElementById('queuePauseToggle').addEventListener('click', toggleQueuePause);
    document.getElementById('queuePrevPage').addEventListener('click', () => {
      if (queuePage <= 0) return;
      queuePage -= 1;
      loadQueue();
    });
    document.getElementById('queueNextPage').addEventListener('click', () => {
      queuePage += 1;
      loadQueue();
    });
    document.getElementById('refreshHistory').addEventListener('click', () => {
      historyPage = 0;
      historyLegacyCache = null;
      loadHistory(true);
    });
    document.getElementById('historyPrevPage').addEventListener('click', () => {
      if (historyPage <= 0) return;
      historyPage -= 1;
      loadHistory();
    });
    document.getElementById('historyNextPage').addEventListener('click', () => {
      historyPage += 1;
      loadHistory();
    });


