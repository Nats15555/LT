function escapeHtml(s) {
  const div = document.createElement('div');
  div.textContent = s;
  return div.innerHTML;
}

function escapeAttr(s) {
  return String(s ?? '')
      .replace(/&/g, '&amp;')
      .replace(/"/g, '&quot;')
      .replace(/</g, '&lt;');
}

function isSummaryStatusCompleted(status) {
  return String(status || '').trim().toUpperCase() === 'COMPLETED';
}

function isAwaitingExternalSummary(status) {
  return String(status || '').trim().toUpperCase() === 'AWAITING_EXTERNAL_CALLBACK';
}

function parseExternalPendingData(summaryData) {
  let d = summaryData;
  if (d == null) return { instructionsRu: '', deadlineAt: '' };
  if (typeof d === 'string') {
    try {
      d = JSON.parse(d);
    } catch (_) {
      return { instructionsRu: '', deadlineAt: '' };
    }
  }
  if (typeof d !== 'object' || d === null) return { instructionsRu: '', deadlineAt: '' };
  return {
    instructionsRu: typeof d.instructionsRu === 'string' ? d.instructionsRu : '',
    deadlineAt: typeof d.deadlineAt === 'string' ? d.deadlineAt : ''
  };
}

/** Текст отчёта и служебные поля из summaryData. */
function normalizeSummaryPayload(raw) {
  let data = raw;
  if (data == null) return { text: '', summarizerName: '', model: '', promptUsed: '' };
  if (typeof data === 'string') {
    const t = data.trim();
    if (!t) return { text: '', summarizerName: '', model: '', promptUsed: '' };
    try {
      data = JSON.parse(t);
    } catch (_) {
      return { text: t, summarizerName: '', model: '', promptUsed: '' };
    }
  }
  if (typeof data !== 'object' || data === null) {
    return { text: String(data), summarizerName: '', model: '', promptUsed: '' };
  }
  function pickText(d) {
    if (typeof d.text === 'string' && d.text.length > 0) return d.text;
    if (typeof d.body === 'string') return d.body;
    if (typeof d.content === 'string') return d.content;
    if (typeof d.message === 'string') return d.message;
    if (typeof d.markdown === 'string') return d.markdown;
    if (typeof d.answer === 'string') return d.answer;
    if (typeof d.output === 'string') return d.output;
    if (typeof d.report === 'string') return d.report;
    return '';
  }
  let text = pickText(data);
  if (!text.trim() && Object.keys(data).length > 0) {
    try {
      text = JSON.stringify(data, null, 2);
    } catch (_) {
      text = '';
    }
  }
  return {
    text,
    summarizerName: typeof data.summarizerName === 'string' ? data.summarizerName : '',
    model: typeof data.model === 'string' ? data.model : '',
    promptUsed: typeof data.promptUsed === 'string'
        ? data.promptUsed
        : (typeof data.prompt === 'string' ? data.prompt : '')
  };
}
