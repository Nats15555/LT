    let runFormTools = [];

    function formatToolExtensions(exts) {
      if (!Array.isArray(exts) || !exts.length) return '— не заданы —';
      return exts.map(function (e) { return String(e).trim(); }).filter(Boolean).join(', ');
    }

    function updateTestFileUiForTool() {
      const toolName = (document.getElementById('toolSelect') && document.getElementById('toolSelect').value) || '';
      const hint = document.getElementById('testFileAllowedExts');
      const fileInput = document.getElementById('fileInput');
      const tool = runFormTools.find(function (t) { return t.name === toolName; });
      if (hint) {
        hint.textContent = tool
          ? tool.name + ': ' + formatToolExtensions(tool.fileExtensions)
          : '— выберите инструмент —';
      }
      if (fileInput && tool && Array.isArray(tool.fileExtensions) && tool.fileExtensions.length) {
        fileInput.accept = tool.fileExtensions.join(',');
      } else if (fileInput) {
        fileInput.accept = '.py,.js,.jmx';
      }
    }

    async function loadTools() {
      try {
        const r = await fetch(API + '/tools?enabled=true');
        const res = await r.json();
        const tools = Array.isArray(res.data) ? res.data : (res.data && res.data.data ? res.data.data : []);
        runFormTools = tools;
        const select = document.getElementById('toolSelect');
        select.innerHTML = '<option value="">— выбрать —</option>' +
          tools.map(t => `<option value="${t.name}">${t.name}</option>`).join('');
        updateTestFileUiForTool();
      } catch (e) {
        document.getElementById('toolSelect').innerHTML = '<option value="">Ошибка загрузки</option>';
        runFormTools = [];
        updateTestFileUiForTool();
      }
      loadSummarizers();
    }

    async function loadSummarizers() {
      try {
        await refreshSummarizerProviderMap();
        const r = await fetch(API + '/summarizers?enabled=true');
        const res = await r.json();
        const list = Array.isArray(res.data) ? res.data : (res.data && res.data.data ? res.data.data : []);
        const select = document.getElementById('summarizerSelect');
        select.innerHTML = '<option value="">— без суммаризации —</option>' + summarizerOptionsInnerHtml(list);
      } catch (e) {
        document.getElementById('summarizerSelect').innerHTML = '<option value="">— без суммаризации —</option>';
      }
    }

    loadTools();
    document.getElementById('toolSelect').addEventListener('change', updateTestFileUiForTool);
    (function () {
      const fileInput = document.getElementById('fileInput');
      const fileLabel = document.getElementById('fileInputLabel');
      if (!fileInput || !fileLabel) return;
      fileInput.addEventListener('change', function () {
        fileLabel.textContent = fileInput.files && fileInput.files.length
          ? fileInput.files[0].name
          : 'Файл не выбран';
      });
    })();


