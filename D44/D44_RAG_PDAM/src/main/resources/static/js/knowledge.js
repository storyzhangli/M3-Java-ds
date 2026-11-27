/* D44 RAG v3 演示页逻辑：仅调用既有只读端点（零新端点） */
(function () {
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  async function api(path, opt) {
    const r = await fetch(path, Object.assign({ headers: { 'Content-Type': 'application/json' } }, opt || {}));
    return { http: r.status, json: await r.json().catch(() => null) };
  }

  // 健康徽标
  api('/api/v1/ai/health').then((res) => {
    $('healthBadge').textContent = res.json && res.json.code === 200 ? '服务在线' : '服务异常';
  }).catch(() => { $('healthBadge').textContent = '服务不可达'; });

  async function runSearch() {
    const q = $('queryInput').value.trim();
    if (!q) { $('tipLine').textContent = '请先输入问题'; return null; }
    const topK = parseInt($('topKInput').value || '5', 10);
    const res = await api('/api/v1/knowledge/retrieval/search', { method: 'POST', body: JSON.stringify({ query: q, topK: topK }) });
    const d = res.json && res.json.data;
    if (!d) { $('hitsBox').textContent = '检索失败：' + (res.json ? res.json.msg : res.http); return null; }
    const hits = d.hits || [];
    $('hitsBox').innerHTML = hits.length
      ? '<table style="width:100%;border-collapse:collapse;font-size:13px"><tr style="background:#e2e8f0"><th style="padding:4px;text-align:left">documentId</th><th style="padding:4px;text-align:left">chunkId</th><th style="padding:4px">score</th><th style="padding:4px;text-align:left">内容摘要</th></tr>' +
        hits.map((h) => '<tr style="border-bottom:1px solid #e2e8f0"><td style="padding:4px">' + esc(h.documentId) + '</td><td style="padding:4px">' + esc(h.chunkId) + '</td><td style="padding:4px;text-align:center">' + esc(h.score != null ? Number(h.score).toFixed(4) : '-') + '</td><td style="padding:4px">' + esc((h.text || h.content || '').slice(0, 60)) + '…</td></tr>').join('') +
        '</table><div class="tip-line">filterDescription: ' + esc(d.filterDescription) + ' · hitCount=' + d.hitCount + ' · ' + d.elapsedMillis + 'ms</div>'
      : '零命中（权限过滤或无相关证据）· filterDescription: ' + esc(d.filterDescription);
    return d;
  }

  async function runAnswer() {
    const q = $('queryInput').value.trim();
    if (!q) { $('tipLine').textContent = '请先输入问题'; return; }
    $('askBtn').disabled = true;
    try {
      await runSearch();
      const topK = parseInt($('topKInput').value || '5', 10);
      const res = await api('/api/v1/rag/answer', { method: 'POST', body: JSON.stringify({ query: q, topK: topK }) });
      const j = res.json || {};
      const d = j.data || {};
      const card = $('decisionCard');
      card.style.display = 'block';
      if (j.code !== 200) {
        card.innerHTML = '<b>拒绝/错误</b> · code=' + j.code + ' · ' + esc(j.msg);
        card.style.background = '#fee2e2';
        $('answerBox').textContent = '（无回答体）';
        $('citesBox').textContent = '（无引用）';
        return;
      }
      const dec = d.decision || d.status;
      const safe = (dec === 'ANSWER');
      card.style.background = safe ? '#dcfce7' : '#fef9c3';
      card.innerHTML = '<b>decision=' + esc(String(dec)) + '</b> · status=' + esc(d.status) +
        ' · reasonCode=' + esc(d.reasonCode) + (d.decisionDetail ? ' · detail=' + esc(JSON.stringify(d.decisionDetail)) : '') +
        ' · 命中=' + d.retrievalHitCount + ' · 上下文块=' + d.contextChunkCount;
      $('answerBox').textContent = d.answer || '（answer=null，安全态：澄清/拒答不编造）';
      const cits = d.citedSources || [];
      $('citesBox').innerHTML = cits.length
        ? cits.map((c) => '<div style="border:1px solid #e2e8f0;border-radius:8px;padding:8px;margin:6px 0"><div style="font-size:12px;color:#475569">' + esc(c.documentId) + ' v' + esc(c.documentVersion) + ' · chunk=' + esc(c.chunkId) + ' · score=' + esc(c.score) + (c.pageNumbers ? ' · p' + esc(c.pageNumbers) : '') + '</div><div style="margin-top:4px">' + esc(c.excerpt) + '</div></div>').join('')
        : '（零引用——与 decision 一致性由主码后置校验保证）';
    } finally { $('askBtn').disabled = false; }
  }

  async function runSync() {
    const res = await api('/api/v1/knowledge/sync/status');
    $('tipLine').textContent = '同步状态：' + JSON.stringify(res.json && res.json.data ? res.json.data : res.json).slice(0, 300);
  }

  $('askBtn').addEventListener('click', runAnswer);
  $('searchBtn').addEventListener('click', runSearch);
  $('syncBtn').addEventListener('click', runSync);
  $('queryInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') runAnswer(); });
})();
