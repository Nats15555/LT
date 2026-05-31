    const API = (window.location.port === '8080' ? '' : 'http://localhost:8080') + '/api/v1/loadtest';

    (function initThemeToggle() {
      const KEY = 'loadtest-theme';
      const input = document.getElementById('themeToggle');
      function currentTheme() {
        return document.documentElement.getAttribute('data-theme') || 'dark';
      }
      function applyTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem(KEY, theme);
        if (input) {
          input.checked = theme === 'light';
          input.setAttribute('aria-label', theme === 'light' ? 'Светлая тема, переключить на тёмную' : 'Тёмная тема, переключить на светлую');
        }
      }
      applyTheme(currentTheme());
      if (input) {
        input.addEventListener('change', () => applyTheme(input.checked ? 'light' : 'dark'));
      }
    })();

    const METRICS_HTTP_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'];
    const METRICS_DEFAULT_REQUEST = {
      name: 'prometheus',
      method: 'GET',
      url: 'http://prometheus:9090/api/v1/query',
      queryParams: 'query=up'
    };
    const HISTORY_PAGE_SIZE = 20;
    let historyPage = 0;
    const QUEUE_PAGE_SIZE = 20;
    let queuePage = 0;
    let historyLegacyCache = null;
    let metricsConfigSchema = null;
    let metricsConfigUiInitialized = false;

    (function initMetricsSchemaLink() {
      const a = document.getElementById('metricsSchemaLink');
      if (a) a.href = API + '/metrics-config-schema';
    })();


