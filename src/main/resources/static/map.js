(() => {
  'use strict';

  const COLORS = ['#de7b35', '#3d70a4', '#8457a4'];
  const LABELS = {
    source: 'Источники тепла',
    heat_network: 'Тепловые сети',
    heat_chamber: 'Тепловые камеры',
    oks_connection_point: 'Точки подключения',
    oks_building: 'Здания (ОКС)',
    restriction: 'Ограничения и спецпроходы',
    technical_node: 'Технические узлы'
  };
  const map = L.map('map', {preferCanvas: true, zoomControl: true}).setView([55.75, 37.62], 12);
  map.attributionControl.setPrefix('<a href="https://leafletjs.com/">Leaflet 1.9.4</a>');
  const baseMap = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
  }).addTo(map);
  const calculationBoundsLayer = L.rectangle([[0, 0], [0, 0]], {
    color: '#c78b38', weight: 1.5, dashArray: '6 5', fillOpacity: .035, interactive: false
  });
  const overlayLayers = {};
  const layerControl = L.control.layers({'Карта OpenStreetMap': baseMap}, overlayLayers,
    {collapsed: true, position: 'topright'}).addTo(map);
  const livePreviewGroup = L.layerGroup().addTo(map);
  layerControl.addOverlay(livePreviewGroup, 'Рост сети в реальном времени');
  const attemptMarkerGroup = L.layerGroup().addTo(map);
  layerControl.addOverlay(attemptMarkerGroup, 'Точки попыток поиска');
  const state = {
    jobId: null,
    jobStatus: null,
    result: null,
    summaries: [],
    selectedVariants: new Set(),
    inputGroups: new Map(),
    variantGroups: new Map(),
    inputNextCursor: null,
    variantNextCursors: new Map(),
    requestController: null,
    viewportRevision: 0,
    refreshTimer: null,
    progressRevision: -1,
    liveProgress: null,
    selectedTrajectoryId: null,
    polling: false
  };

  const el = id => document.getElementById(id);
  const statusBadge = el('job-status');
  const errorBox = el('job-error');
  const moreButton = el('load-more');
  const currency = new Intl.NumberFormat('ru-RU', {style: 'currency', currency: 'RUB', maximumFractionDigits: 2});
  const number = new Intl.NumberFormat('ru-RU', {maximumFractionDigits: 2});

  map.on('moveend', () => {
    if (!state.jobId) return;
    window.clearTimeout(state.refreshTimer);
    state.refreshTimer = window.setTimeout(() => refreshViewport(), 120);
  });

  el('dataset-file').addEventListener('change', event => {
    const file = event.target.files && event.target.files[0];
    if (file) uploadDataset(file);
  });
  el('open-job').addEventListener('click', () => openJob(el('job-id').value.trim()));
  el('calculate').addEventListener('click', startCalculation);
  el('use-map-bounds').addEventListener('click', () => {
    writeBoundsToInputs(map.getBounds());
    updateCalculationBoundsLayer();
  });
  ['west', 'south', 'east', 'north'].forEach(key => {
    el(`bbox-${key}`).addEventListener('change', updateCalculationBoundsLayer);
  });
  moreButton.addEventListener('click', loadMoreFeatures);
  el('trajectory-select').addEventListener('change', event => {
    state.selectedTrajectoryId = event.target.value;
    drawLiveGeometry();
  });
  const initialJobId = new URLSearchParams(window.location.search).get('job');
  if (initialJobId) openJob(initialJobId);

  async function uploadDataset(file) {
    setError('');
    setStatus('Загрузка и проверка', 'running');
    el('dataset-info').textContent = `${file.name} · ${formatBytes(file.size)}`;
    el('calculate').disabled = true;
    try {
      const response = await fetch('/api/jobs', {
        method: 'POST',
        headers: {'Content-Type': 'application/geo+json'},
        body: file
      });
      const upload = await readResponse(response);
      el('job-id').value = upload.jobId;
      await activateJob(upload.jobId, upload);
    } catch (error) {
      setStatus('Загрузка не выполнена', 'failed');
      setError(error.message);
    }
  }

  async function openJob(jobId) {
    if (!jobId) return setError('Введите ID задания.');
    setError('');
    try {
      const job = await readResponse(await fetch(`/api/jobs/${encodeURIComponent(jobId)}`, {cache: 'no-store'}));
      await activateJob(jobId, job);
    } catch (error) {
      setError(error.message);
    }
  }

  async function activateJob(jobId, info) {
    clearLivePreview();
    clearAttemptHistory();
    state.viewportRevision++;
    if (state.requestController) state.requestController.abort();
    state.requestController = null;
    state.inputGroups.forEach(group => {
      layerControl.removeLayer(group);
      map.removeLayer(group);
    });
    state.inputGroups.clear();
    state.variantGroups.forEach(group => {
      layerControl.removeLayer(group);
      map.removeLayer(group);
    });
    state.variantGroups.clear();
    state.inputNextCursor = null;
    state.jobId = jobId;
    state.jobStatus = info.status;
    state.result = null;
    state.summaries = [];
    state.selectedVariants.clear();
    state.variantNextCursors.clear();
    renderVariants();
    el('use-map-bounds').disabled = false;
    el('job-id').value = jobId;
    el('dataset-info').textContent = `${info.featureCount ?? '—'} объектов · ${jobId}`;
    updateCalculateButton();
    setStatus(statusLabel(info.status), statusKind(info.status));
    try {
      const extent = await readResponse(await fetch(`/api/jobs/${encodeURIComponent(jobId)}/view/extent`, {cache: 'no-store'}));
      if (jobId !== state.jobId) return;
      const [west, south, east, north] = extent.bbox;
      if (west === east && south === north) map.setView([south, west], 16);
      else map.fitBounds([[south, west], [north, east]], {padding: [28, 28], maxZoom: 17});
      writeBoundsToInputs(map.getBounds());
      if (info.status === 'SUCCEEDED') {
        clearLivePreview();
        await loadSummaries();
      }
      else if (info.status === 'VALIDATING' || info.status === 'CALCULATING' || info.status === 'CANCEL_REQUESTED') pollJob();
      else if (info.status === 'FAILED' || info.status === 'CANCELLED') setProgress(info.error?.message || 'Расчёт не завершён.');
      await refreshViewport();
    } catch (error) {
      setError(error.message);
    }
  }

  async function startCalculation() {
    if (!state.jobId) return;
    const bbox = ['west', 'south', 'east', 'north'].map(key => Number(el(`bbox-${key}`).value));
    if (bbox.some(value => !Number.isFinite(value)) || bbox[0] >= bbox[2] || bbox[1] >= bbox[3]) {
      return setError('Границы должны быть конечными числами: запад < восток, юг < север.');
    }
    setError('');
    el('calculate').disabled = true;
    setProgress('Задание отправлено на расчёт…');
    try {
      const response = await readResponse(await fetch(`/api/jobs/${encodeURIComponent(state.jobId)}/calculate`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({bbox})
      }));
      state.jobStatus = response.status;
      setStatus(statusLabel(response.status), statusKind(response.status));
      pollJob();
    } catch (error) {
      setError(error.message);
      updateCalculateButton();
    }
  }

  async function pollJob() {
    if (state.polling || !state.jobId) return;
    state.polling = true;
    try {
      while (state.jobId) {
        const jobId = state.jobId;
        const job = await readResponse(await fetch(`/api/jobs/${encodeURIComponent(jobId)}`, {cache: 'no-store'}));
        if (jobId !== state.jobId) continue;
        state.jobStatus = job.status;
        setStatus(statusLabel(job.status), statusKind(job.status));
        if (job.status === 'SUCCEEDED') {
          clearLivePreview();
          setProgress('Расчёт завершён. Загружаю сводки вариантов.');
          await loadSummaries();
          await refreshViewport();
          return;
        }
        if (job.status === 'READY') {
          clearLivePreview();
          setProgress('Данные проверены. Можно запускать расчёт.');
          await refreshViewport();
          return;
        }
        if (job.status === 'FAILED' || job.status === 'CANCELLED') {
          clearLivePreview();
          setProgress(job.error?.message || 'Задание завершилось без результата.');
          setError(job.error?.message || 'Задание завершилось без результата.');
          return;
        }
        if (job.status === 'CALCULATING') {
          const progress = await readResponse(await fetch(
            `/api/jobs/${encodeURIComponent(jobId)}/progress`, {cache: 'no-store'}));
          if (jobId !== state.jobId) continue;
          if (progress.available) renderLiveProgress(progress);
          else setProgress('Идёт расчёт маршрутов. Первые проверенные ветви появятся на карте по мере поиска…');
        } else {
          setProgress('Ожидание состояния задания…');
        }
        await delay(1500);
      }
    } catch (error) {
      setError(error.message);
    } finally {
      state.polling = false;
      updateCalculateButton();
    }
  }

  async function loadSummaries() {
    const jobId = state.jobId;
    const result = await readResponse(await fetch(`/api/jobs/${encodeURIComponent(jobId)}/variants`, {cache: 'no-store'}));
    if (jobId !== state.jobId) return;
    state.result = result;
    state.summaries = Array.isArray(result.variants) ? result.variants.slice(0, 3) : [];
    const widest = state.summaries.reduce((best, item) =>
      (item.connectedTargetIds?.length || 0) > (best?.connectedTargetIds?.length || 0) ? item : best, null);
    state.selectedVariants = new Set(widest ? [widest.variantId] : []);
    renderVariants();
    renderAttemptEvents(result.attemptEvents, result.truncatedAttemptEvents, true);
    setProgress(result.detail || `Проверено целей: ${result.totalTargets ?? 0}; вариантов: ${state.summaries.length}.`);
  }

  function renderVariants() {
    const list = el('variant-list');
    list.replaceChildren();
    if (!state.summaries.length) {
      list.innerHTML = '<span class="muted">Результаты появятся после расчёта.</span>';
      renderUnconnected();
      return;
    }
    state.summaries.forEach((summary, index) => {
      const card = document.createElement('label');
      card.className = 'variant-card';
      const checked = state.selectedVariants.has(summary.variantId);
      const widestCount = Math.max(...state.summaries.map(item => item.connectedTargetIds?.length || 0));
      const coverageLabel = (summary.connectedTargetIds?.length || 0) === widestCount
        ? '<span class="variant-coverage-badge">Наибольший охват</span>' : '';
      card.innerHTML = `<div class="variant-title"><input type="checkbox" ${checked ? 'checked' : ''} aria-label="Показать вариант ${summary.rank}"><span style="color:${COLORS[index]}">Вариант ${summary.rank}</span>${coverageLabel}</div>`
        + `<div class="variant-metrics"><span>Новая сеть<strong>${formatMeters(summary.newNetworkLengthM)}</strong></span>`
        + `<span>Стоимость строительства<strong>${formatMoney(summary.constructionCost)}</strong></span>`
        + `<span>Штраф за неподключение<strong>${formatMoney(summary.unconnectedPenalty)}</strong></span>`
        + `<span>Итоговая стоимость<strong>${formatMoney(summary.calculatedCost)}</strong></span>`
        + `<span>Оценка<strong>${formatNumber(summary.score)}</strong></span></div>`
        + `<div class="target-outcomes"><strong>Подключено ${summary.connectedTargetIds?.length || 0} из ${state.result?.totalTargets ?? '?'}:</strong> ${formatIds(summary.connectedTargetIds)}</div>`
        + comparisonMarkup(summary);
      card.querySelector('input').addEventListener('change', event => {
        if (event.target.checked && state.selectedVariants.size >= 3) {
          event.target.checked = false;
          setError('Можно одновременно сравнивать не более трёх вариантов.');
          return;
        }
        setError('');
        if (event.target.checked) state.selectedVariants.add(summary.variantId);
        else state.selectedVariants.delete(summary.variantId);
        renderUnconnected();
        refreshViewport();
      });
      list.appendChild(card);
    });
    renderUnconnected();
  }

  function renderUnconnected() {
    const box = el('unconnected');
    const selected = state.summaries.filter(summary => state.selectedVariants.has(summary.variantId));
    const ids = new Map();
    const variantsById = new Map();
    selected.forEach(summary => (summary.unconnectedTargetIds || []).forEach(id => {
      const key = typedIdKey(id);
      ids.set(key, id);
      if (!variantsById.has(key)) variantsById.set(key, []);
      variantsById.get(key).push(summary.rank);
    }));
    const searches = new Map((state.result?.targetSearches || []).map(search => [typedIdKey(search.targetId), search]));
    const unsearched = new Map();
    selected.forEach(summary => {
      const reasons = new Map((summary.unsearchedReasons || [])
        .map(item => [typedIdKey(item.targetId), item.reason]));
      (summary.unsearchedTargetIds || []).forEach(id => {
        const key = typedIdKey(id);
        if (!unsearched.has(key)) unsearched.set(key, {id, details: []});
        const reason = reasons.get(key);
        unsearched.get(key).details.push(`вариант ${summary.rank}: ${reason
          ? attemptLabel(reason) : 'поиск неполон'}`);
      });
    });
    const messages = [];
    if (ids.size) {
      const details = Array.from(ids.entries()).map(([key, id]) => {
        const ranks = variantsById.get(key);
        const rankText = ranks?.length ? ` · варианты ${ranks.join(', ')}` : '';
        const search = searches.get(key);
        const count = search?.attempts?.length || 0;
        const reason = count ? `Зафиксировано ${count} событий поиска по всем сценариям; для выбранных вариантов допустимый маршрут не найден`
          : 'Допустимый маршрут не найден';
        return `<li><strong>${escapeHtml(displayTypedId(id))} — нужна ручная проработка${escapeHtml(rankText)}</strong><br>${escapeHtml(reason)}</li>`;
      });
      messages.push(`<strong>Автопоиск не доказывает техническую невозможность подключения:</strong><ul>${details.join('')}</ul>`);
    }
    if (unsearched.size) {
      const details = Array.from(unsearched.values()).map(({id, details: reasons}) =>
        `${displayTypedId(id)} — ${reasons.join(', ')}`);
      messages.push(`<strong>Поиск по этим целям не завершён; штраф за их отсутствие включён в стоимость варианта:</strong> ${details.map(escapeHtml).join('; ')}. Это не доказывает техническую невозможность подключения. При необходимости задайте более широкую область или повторите расчёт с увеличенным лимитом.`);
    }
    box.innerHTML = messages.join('<br>');
    box.hidden = messages.length === 0;
  }

  function comparisonMarkup(summary) {
    const baseline = state.summaries.find(item => item.rank === 1);
    if (!baseline || baseline.variantId === summary.variantId) {
      return '<div class="variant-comparison">Базовый вариант сравнения.</div>';
    }
    const differences = [
      `стоимость строительства ${signedMoney(summary.constructionCost, baseline.constructionCost)}`,
      `штраф ${signedMoney(summary.unconnectedPenalty, baseline.unconnectedPenalty)}`,
      `длина сети ${signedMeters(summary.newNetworkLengthM, baseline.newNetworkLengthM)}`,
      `оценка ${signedNumber(summary.score, baseline.score)}`
    ];
    if (summary.existingChamberTieInCount !== baseline.existingChamberTieInCount) {
      differences.push(`врезок в существующие камеры ${signedInteger(summary.existingChamberTieInCount - baseline.existingChamberTieInCount)}`);
    }
    const currentIds = new Map((summary.connectedTargetIds || []).map(id => [typedIdKey(id), id]));
    const baselineIds = new Map((baseline.connectedTargetIds || []).map(id => [typedIdKey(id), id]));
    const added = Array.from(currentIds.entries()).filter(([key]) => !baselineIds.has(key)).map(([, id]) => id);
    const removed = Array.from(baselineIds.entries()).filter(([key]) => !currentIds.has(key)).map(([, id]) => id);
    if (added.length || removed.length) {
      differences.push(`подключения +${added.map(displayTypedId).join(', ') || 'нет'} / −${removed.map(displayTypedId).join(', ') || 'нет'}`);
    }
    return `<div class="variant-comparison"><strong>Ключевые отличия от варианта 1:</strong> ${differences.map(escapeHtml).join('; ')}.</div>`;
  }

  function attemptLabel(attempt) {
    return ({
      FOUND: 'допустимая ветвь найдена',
      NO_ATTACHMENT_CANDIDATE: 'не найдено допустимое место врезки',
      NO_ROUTE_WITHIN_SEARCH_AREA: 'маршрут не найден в области поиска',
      VISIBILITY_VERTEX_LIMIT_REACHED: 'исчерпан лимит вершин поиска',
      SEARCH_INCOMPLETE: 'поиск не завершён после упрощения кандидатов препятствий',
      FOUND_WITH_INCOMPLETE_SEARCH: 'найден допустимый маршрут, но поиск альтернатив ограничен',
      NO_FEASIBLE_DIAMETER: 'нет допустимого диаметра',
      VALIDATION_REJECTED: 'кандидат отклонён инженерной проверкой',
      TREE_BRANCH_ACCEPTED: 'ветвь к построенной сети принята',
      TREE_BRANCH_REJECTED: 'ветвь к построенной сети отклонена',
      TURN_ANGLE_OVER_90: 'поворот более 90°',
      SPATIAL_CLEARANCE_VIOLATION: 'нарушен нормативный отступ',
      OWN_OKS_APPROACH_INVALID: 'выход из здания нарушает правило ближайшей границы',
      INVALID_REQUEST: 'внутренний запрос маршрута отклонён; причину должен проверить специалист',
      OUTSIDE_SEARCH_AREA: 'цель находится вне области расчёта',
      ATTACHMENT_OUTSIDE_SEARCH_AREA: 'место врезки находится вне области расчёта',
      INITIAL_ROUTE_SEARCH_LIMIT: 'начальный поиск маршрута был ограничен',
      TREE_GOAL_WINDOW_LIMIT: 'исчерпано окно врезок в выбранном дереве',
      BRANCH_ATTEMPT_BUDGET: 'исчерпан бюджет ветвей выбранного дерева',
      NOT_ATTEMPTED_BUDGET: 'поиск этой цели не начат из-за общего бюджета',
      NOT_ATTEMPTED_GROUP_BUDGET: 'групповой поиск не дошёл до этой цели',
      NOT_ATTEMPTED_GROUP_SCOPE: 'цель не вошла в проверенные группы',
      GROUP_CANDIDATES_REJECTED_OR_INCOMPLETE: 'групповые кандидаты отклонены или поиск ограничен',
      NO_TREE_GOAL_GENERATED: 'не найдена допустимая точка врезки в выбранное дерево'
    })[attempt] || `этап поиска: ${attempt}`;
  }

  function attemptReason(reason) {
    if (!reason) return '';
    const vertexLimit = /^Relevant obstacle boundaries contain (\d+) vertices.*configured limit is (\d+)/.exec(reason);
    if (vertexLimit) return `препятствия: ${vertexLimit[1]} вершин при лимите ${vertexLimit[2]}`;
    return ({
      TURN_ANGLE_OVER_90: 'поворот более 90°',
      SPATIAL_CLEARANCE_VIOLATION: 'нарушен нормативный отступ',
      OWN_OKS_APPROACH_INVALID: 'выход из здания не через ближайшую границу',
      'Direct branch has no eligible internal trunk projection': 'нет допустимой внутренней точки врезки',
      'Direct branch projection is too close to a polyline bend': 'врезка слишком близко к излому ствола',
      'A valid route was found, but the bounded visibility search was not exhaustive': 'маршрут найден, перебор альтернатив неполный'
    })[reason] || String(reason).slice(0, 110);
  }

  function formatIds(ids) {
    return ids?.length ? ids.map(displayTypedId).map(escapeHtml).join(', ') : 'нет';
  }

  function typedIdKey(value) { return `${typeof value}:${String(value)}`; }

  async function refreshViewport() {
    if (!state.jobId) return;
    const revision = ++state.viewportRevision;
    if (state.requestController) state.requestController.abort();
    const controller = new AbortController();
    state.requestController = controller;
    state.inputNextCursor = null;
    state.variantNextCursors.clear();
    ensureVariantGroups();
    clearViewportLayers();
    setMapMessage('Загружаю объекты в видимой области…');
    moreButton.hidden = true;
    try {
      const inputResponse = await fetchView(null, null, null, controller.signal);
      if (revision !== state.viewportRevision) return;
      state.inputNextCursor = inputResponse.nextInputCursor;
      addInputFeatures(inputResponse.features.filter(feature => feature.properties?.view_layer === 'input'));
      const selected = state.summaries.filter(item => state.selectedVariants.has(item.variantId));
      const pages = await Promise.all(selected.map(item => fetchView(item.variantId, null, null, controller.signal)));
      if (revision !== state.viewportRevision) return;
      pages.forEach((page, index) => {
        const variantId = selected[index].variantId;
        state.variantNextCursors.set(variantId, page.nextVariantCursor);
        addVariantFeatures(variantId, page.features.filter(feature => feature.properties?.view_layer === 'variant'));
      });
      updateMoreButton();
      const inputFeatures = inputResponse.features.filter(feature => feature.properties?.view_layer === 'input');
      const buildingCount = inputFeatures.filter(feature => feature.properties?.object_type === 'restriction'
        && feature.properties?.restriction_type === 'oks').length;
      const featureCount = inputFeatures.length
        + pages.reduce((total, page) => total + page.features.filter(feature => feature.properties?.view_layer === 'variant').length, 0);
      setMapMessage(`${featureCount} объектов, зданий: ${buildingCount} на текущей странице · выдача ограничена областью карты`);
    } catch (error) {
      if (error.name !== 'AbortError') {
        setMapMessage('Не удалось загрузить слои');
        setError(error.message);
      }
    }
  }

  async function loadMoreFeatures() {
    if (!state.jobId) return;
    const revision = state.viewportRevision;
    const controller = state.requestController || new AbortController();
    moreButton.disabled = true;
    try {
      const requests = [];
      if (state.inputNextCursor) requests.push(fetchView(null, state.inputNextCursor, null, controller.signal)
        .then(page => ({kind: 'input', page})));
      for (const item of state.summaries) {
        const cursor = state.variantNextCursors.get(item.variantId);
        if (state.selectedVariants.has(item.variantId) && cursor) {
          requests.push(fetchView(item.variantId, null, cursor, controller.signal)
            .then(page => ({kind: item.variantId, page})));
        }
      }
      const pages = await Promise.all(requests);
      if (revision !== state.viewportRevision) return;
      for (const entry of pages) {
        if (entry.kind === 'input') {
          state.inputNextCursor = entry.page.nextInputCursor;
          addInputFeatures(entry.page.features.filter(feature => feature.properties?.view_layer === 'input'));
        } else {
          state.variantNextCursors.set(entry.kind, entry.page.nextVariantCursor);
          addVariantFeatures(entry.kind, entry.page.features.filter(feature => feature.properties?.view_layer === 'variant'));
        }
      }
      updateMoreButton();
    } catch (error) {
      if (error.name !== 'AbortError') setError(error.message);
    } finally {
      moreButton.disabled = false;
    }
  }

  async function fetchView(variantId, inputCursor, variantCursor, signal) {
    const bounds = map.getBounds();
    const bbox = [bounds.getWest(), bounds.getSouth(), bounds.getEast(), bounds.getNorth()];
    const query = new URLSearchParams({bbox: bbox.join(','), limit: '500'});
    if (variantId) query.set('variantId', variantId);
    if (inputCursor) query.set('inputCursor', inputCursor);
    if (variantCursor) query.set('variantCursor', variantCursor);
    return readResponse(await fetch(`/api/jobs/${encodeURIComponent(state.jobId)}/view?${query}`, {cache: 'no-store', signal}));
  }

  function ensureVariantGroups() {
    state.summaries.forEach((summary, index) => {
      const id = summary.variantId;
      if (state.selectedVariants.has(id) && !state.variantGroups.has(id)) {
        const group = L.layerGroup().addTo(map);
        state.variantGroups.set(id, group);
        layerControl.addOverlay(group, `<span style="color:${COLORS[index]}">Вариант ${summary.rank}</span>`);
      }
    });
    for (const [id, group] of state.variantGroups.entries()) {
      if (!state.selectedVariants.has(id)) {
        layerControl.removeLayer(group);
        map.removeLayer(group);
        state.variantGroups.delete(id);
      }
    }
  }

  function clearViewportLayers() {
    state.inputGroups.forEach(group => group.clearLayers());
    state.variantGroups.forEach(group => group.clearLayers());
  }

  function addInputFeatures(features) {
    const groups = new Map();
    features.forEach(feature => {
      const type = feature.properties?.object_type === 'restriction'
        && feature.properties?.restriction_type === 'oks' ? 'oks_building'
        : feature.properties?.object_type || 'other';
      if (!groups.has(type)) groups.set(type, []);
      groups.get(type).push(feature);
    });
    groups.forEach((items, type) => {
      let group = state.inputGroups.get(type);
      if (!group) {
        group = L.layerGroup().addTo(map);
        state.inputGroups.set(type, group);
        layerControl.addOverlay(group, LABELS[type] || type);
      }
      const geoJson = L.geoJSON({type: 'FeatureCollection', features: items}, {
        renderer: L.canvas(),
        pointToLayer: (feature, latlng) => L.circleMarker(latlng, pointStyle(feature.properties || {}, null)),
        style: feature => featureStyle(feature.properties || {}, null),
        onEachFeature: bindPopup
      });
      geoJson.eachLayer(layer => group.addLayer(layer));
    });
  }

  function addVariantFeatures(variantId, features) {
    const summaryIndex = state.summaries.findIndex(item => item.variantId === variantId);
    const color = COLORS[Math.max(0, summaryIndex) % COLORS.length];
    const group = state.variantGroups.get(variantId);
    if (!group || !features.length) return;
    const geoJson = L.geoJSON({type: 'FeatureCollection', features}, {
      renderer: L.canvas(),
      pointToLayer: (feature, latlng) => L.circleMarker(latlng, pointStyle(feature.properties || {}, color)),
      style: feature => featureStyle(feature.properties || {}, color),
      onEachFeature: bindPopup
    });
    geoJson.eachLayer(layer => group.addLayer(layer));
  }

  function renderLiveProgress(progress) {
    if (!progress || progress.revision <= state.progressRevision) return;
    state.progressRevision = progress.revision;
    state.liveProgress = progress;
    const trajectories = Array.isArray(progress.treeTrajectories) ? progress.treeTrajectories : [];
    const select = el('trajectory-select');
    const previous = state.selectedTrajectoryId;
    select.replaceChildren();
    trajectories.forEach(tree => {
      const option = document.createElement('option');
      option.value = tree.trajectoryId;
      option.textContent = `${trajectoryLabel(tree.trajectoryId)} · ${tree.connectedTargetIds?.length || 0} целей`;
      select.append(option);
    });
    state.selectedTrajectoryId = trajectories.some(tree => tree.trajectoryId === previous)
      ? previous : progress.activeTrajectoryId || trajectories[0]?.trajectoryId || null;
    select.value = state.selectedTrajectoryId || '';
    el('trajectory-control').hidden = trajectories.length === 0;
    drawLiveGeometry();
    const stage = progress.phase === 'SEED_SEARCH' ? 'ищу стартовый ствол'
      : progress.phase === 'TREE_GROWTH' ? 'наращиваю дерево труб'
        : progress.phase === 'COMBINING' ? 'собираю совместимую сеть' : 'перебираю подключения';
    const searchText = progress.phase === 'SEED_SEARCH'
      ? `проверено стартовых коридоров: ${progress.completedAttempts}`
      : progress.phase === 'TREE_GROWTH' ? 'рост дерева'
        : `попытки ${progress.completedAttempts}/${progress.totalAttempts}`;
    const candidateLabel = progress.phase === 'SEED_SEARCH' ? 'проверенных стартовых трасс' : 'отдельных кандидатов';
    setProgress(`${stage}: ${searchText}; ${candidateLabel}: ${(progress.previewRoutes || []).length}; проверяемых деревьев: ${trajectories.length}.`);
    renderAttemptEvents(progress.attemptEvents, progress.truncatedAttemptEvents, false);
  }

  function trajectoryLabel(id) {
    const joint = /^group:combined(\d+)$/.exec(id || '');
    if (joint) return `Общие стволы и отводы · совместный сценарий ${Number(joint[1]) + 1}`;
    const continued = /^group:continued(\d+)$/.exec(id || '');
    if (continued) return `Общий ствол и отводы · продолжение ${Number(continued[1]) + 1}`;
    const match = /^(first_valid|shortest_valid|group|portfolio):seed(\d+)$/.exec(id || '');
    if (!match) return id || 'Дерево';
    const label = match[1] === 'first_valid' ? 'Первый допустимый'
      : match[1] === 'group' ? 'Общий ствол и отводы'
        : match[1] === 'portfolio' ? 'Альтернативный ствол' : 'Кратчайший допустимый';
    return `${label} · сценарий ${Number(match[2]) + 1}`;
  }

  function drawLiveGeometry() {
    const progress = state.liveProgress;
    livePreviewGroup.clearLayers();
    if (!progress) return;
    (progress.previewRoutes || []).forEach(route => {
      const coordinates = Array.isArray(route.coordinates) ? route.coordinates : [];
      if (coordinates.length < 2) return;
      const points = coordinates.map(([longitude, latitude]) => [Number(latitude), Number(longitude)]);
      if (points.some(([latitude, longitude]) => !Number.isFinite(latitude) || !Number.isFinite(longitude))) return;
      const line = L.polyline(points, {
        renderer: L.svg(), color: '#d1842c', weight: 3, opacity: .75,
        dashArray: '7 8', className: 'live-route-preview'
      });
      line.bindTooltip(`Цель ${escapeHtml(route.targetId)} · ${formatMeters(route.lengthM)} · отдельный кандидат`);
      livePreviewGroup.addLayer(line);
    });
    const selected = (progress.treeTrajectories || [])
      .find(tree => tree.trajectoryId === state.selectedTrajectoryId);
    if (selected && !selected.geometryOmitted) {
      (selected.segments || []).forEach(coordinates => {
        if (!Array.isArray(coordinates) || coordinates.length < 2) return;
        const points = coordinates.map(([longitude, latitude]) => [Number(latitude), Number(longitude)]);
        if (points.some(([latitude, longitude]) => !Number.isFinite(latitude) || !Number.isFinite(longitude))) return;
        const line = L.polyline(points, {renderer: L.svg(), color: '#168a77', weight: 4, opacity: .92});
        line.bindTooltip(`${escapeHtml(trajectoryLabel(selected.trajectoryId))} · проверенное дерево · ${selected.connectedTargetIds?.length || 0} целей`);
        livePreviewGroup.addLayer(line);
      });
    }
    setMapMessage(selected
      ? selected.geometryOmitted
        ? 'Геометрия выбранного дерева превышает лимит live-просмотра; журнал попыток доступен.'
        : `Зелёное — одно проверенное дерево (${selected.connectedTargetIds?.length || 0} целей); пунктир — отдельные кандидаты.`
      : 'Пунктир — отдельные проверенные кандидаты; совместимое дерево ещё не построено.');
  }

  function clearLivePreview() {
    livePreviewGroup.clearLayers();
    state.progressRevision = -1;
    state.liveProgress = null;
    state.selectedTrajectoryId = null;
    el('trajectory-select').replaceChildren();
    el('trajectory-control').hidden = true;
  }

  function clearAttemptHistory() {
    attemptMarkerGroup.clearLayers();
    el('attempt-list').replaceChildren();
    el('attempt-summary').textContent = 'Для этого задания пока нет попыток поиска.';
  }

  function attemptKind(status) {
    if (status === 'TREE_BRANCH_ACCEPTED') return 'accepted';
    if (status === 'FOUND_WITH_INCOMPLETE_SEARCH' || status === 'SEARCH_INCOMPLETE'
      || status === 'VISIBILITY_VERTEX_LIMIT_REACHED') return 'incomplete';
    if (status === 'FOUND') return 'candidate';
    return 'rejected';
  }

  function renderAttemptEvents(events, truncated, completed) {
    const history = Array.isArray(events) ? events : [];
    const dropped = Number.isSafeInteger(truncated) && truncated > 0 ? truncated : 0;
    const list = el('attempt-list');
    list.replaceChildren();
    attemptMarkerGroup.clearLayers();
    if (!history.length) {
      el('attempt-summary').textContent = completed
        ? 'Для этого расчёта подробный журнал не сохранён.'
        : 'Идёт поиск. Первые попытки появятся здесь.';
      return;
    }
    el('attempt-summary').textContent = `Записано событий: ${history.length + dropped}. На карте — последние ${Math.min(history.length, 96)} точек.${dropped ? ` Старых событий скрыто: ${dropped}.` : ''}`;
    history.slice(-96).forEach(event => {
      const destination = event.destination;
      if (!Array.isArray(destination) || destination.length !== 2) return;
      const [longitude, latitude] = destination.map(Number);
      if (!Number.isFinite(longitude) || !Number.isFinite(latitude)
        || Math.abs(longitude) > 180 || Math.abs(latitude) > 90) return;
      const kind = attemptKind(event.status);
      const color = kind === 'accepted' ? '#168a77' : kind === 'rejected' ? '#bd564d'
        : kind === 'incomplete' ? '#9b6baa' : '#d1842c';
      const marker = L.circleMarker([latitude, longitude], {
        radius: kind === 'accepted' ? 6 : 4, color: '#fff', weight: 1.3,
        fillColor: color, fillOpacity: .95
      });
      const tooltip = document.createElement('span');
      tooltip.textContent = `Цель ${displayTypedId(event.targetId)} · ${attemptLabel(event.status)}`;
      marker.bindTooltip(tooltip);
      attemptMarkerGroup.addLayer(marker);
    });
    history.slice().reverse().forEach(event => {
      const item = document.createElement('li');
      const button = document.createElement('button');
      const kind = attemptKind(event.status);
      button.type = 'button';
      button.className = `attempt-item ${kind}`;
      const title = document.createElement('strong');
      const step = event.stage === 'TREE_GROWTH' ? 'дерево' : 'поиск трассы';
      title.textContent = `#${event.sequence} · цель ${displayTypedId(event.targetId)} · ${step}`;
      const status = document.createElement('span');
      status.textContent = attemptLabel(event.status);
      const detail = document.createElement('small');
      const parts = [];
      const trajectory = /^navigator:(first_valid|shortest_valid|portfolio):seed(\d+):(direct|search)/.exec(event.attemptId || '');
      if (trajectory) {
        const label = trajectory[1] === 'first_valid' ? 'первый допустимый'
          : trajectory[1] === 'portfolio' ? 'альтернативный ствол' : 'кратчайший допустимый';
        parts.push(`${label} · дерево ${Number(trajectory[2]) + 1}`);
        parts.push(trajectory[3] === 'direct' ? 'прямая врезка' : 'поиск обхода');
      }
      if (event.attachmentNetworkId != null) parts.push(`сеть ${displayTypedId(event.attachmentNetworkId)}`);
      if (Number.isFinite(event.lengthM)) parts.push(formatMeters(event.lengthM));
      if (event.reason) parts.push(attemptReason(event.reason));
      detail.textContent = parts.join(' · ');
      button.title = event.attemptId || '';
      button.append(title, status, detail);
      const destination = event.destination;
      if (Array.isArray(destination) && destination.length === 2
        && Number.isFinite(Number(destination[0])) && Number.isFinite(Number(destination[1]))) {
        button.addEventListener('click', () => {
          map.setView([Number(destination[1]), Number(destination[0])], Math.max(16, map.getZoom()));
        });
      } else button.disabled = true;
      item.appendChild(button);
      list.appendChild(item);
    });
  }

  function pointStyle(properties, color) {
    const type = properties.object_type;
    const base = color || (type === 'oks_connection_point' ? '#168a77' : type === 'source' ? '#b8453d' : '#697f8c');
    return {radius: type === 'source' ? 8 : type === 'oks_connection_point' ? 6 : 5,
      color: '#fff', weight: 1.5, fillColor: base, fillOpacity: .95};
  }

  function featureStyle(properties, color) {
    const type = properties.object_type;
    if (type === 'restriction') {
      if (properties.restriction_type === 'oks') {
        return {color: '#275d70', weight: 2.5, fillColor: '#5b9baa', fillOpacity: .32};
      }
      const restrictionColor = properties.restriction_type === 'gas_pipeline' ? '#c2484d'
        : properties.restriction_type === 'power_cable' ? '#8052a3' : '#cf8b42';
      return {color: restrictionColor, weight: 2, dashArray: '5 4', fillColor: restrictionColor, fillOpacity: .12};
    }
    if (type === 'heat_network') {
      const special = properties.laying_method === 'special';
      return {color: color || '#64777f', weight: color ? 4 : 2.5, opacity: .9, dashArray: special ? '7 4' : null};
    }
    if (type === 'heat_chamber' || type === 'technical_node' || type === 'oks_connection_point' || type === 'source') {
      return {color: '#fff', weight: 1.5, fillColor: color || (type === 'oks_connection_point' ? '#168a77' : '#64777f'), fillOpacity: .95, radius: 5};
    }
    return {color: color || '#73818a', weight: 2, fillOpacity: .2};
  }

  function bindPopup(feature, layer) {
    const properties = feature.properties || {};
    const isBuilding = properties.object_type === 'restriction' && properties.restriction_type === 'oks';
    const rows = [
      ['ID', properties.id],
      ['Название', properties.name],
      ['Тип', isBuilding ? 'Здание (ОКС)' : properties.object_type],
      ['Вид ограничения', isBuilding ? undefined : properties.restriction_type],
      ['Длина', properties.length],
      ['Расход, т/ч', properties.flow_tph],
      ['Диаметр, мм', properties.diameter],
      ['Способ', properties.laying_method === 'special' ? 'спецпроход' : properties.laying_method],
      ['Стоимость', properties.cost]
    ].filter(([, value]) => value !== undefined && value !== null && value !== '');
    const details = rows.map(([label, value]) => `<dt>${escapeHtml(label)}</dt><dd>${escapeHtml(value)}</dd>`).join('');
    layer.bindPopup(`<div class="feature-popup"><h3>${escapeHtml(isBuilding ? 'Здание (ОКС)' : LABELS[properties.object_type] || properties.object_type || 'Объект')}</h3><dl>${details}</dl></div>`);
    if (properties.object_type === 'source' && properties.view_layer === 'input') {
      const label = document.createElement('span');
      label.textContent = String(properties.name || 'Источник тепла');
      layer.bindTooltip(label, {
        permanent: true, direction: 'top', offset: [0, -7], className: 'source-label'
      });
    }
  }

  function updateMoreButton() {
    const hasMoreVariant = Array.from(state.variantNextCursors.entries())
      .some(([id, cursor]) => cursor && state.selectedVariants.has(id));
    moreButton.hidden = !(state.inputNextCursor || hasMoreVariant);
  }

  function writeBoundsToInputs(bounds) {
    el('bbox-west').value = fixed(bounds.getWest());
    el('bbox-south').value = fixed(bounds.getSouth());
    el('bbox-east').value = fixed(bounds.getEast());
    el('bbox-north').value = fixed(bounds.getNorth());
  }

  function updateCalculationBoundsLayer() {
    const bounds = ['west', 'south', 'east', 'north'].map(key => Number(el(`bbox-${key}`).value));
    if (bounds.some(value => !Number.isFinite(value)) || bounds[0] >= bounds[2] || bounds[1] >= bounds[3]) {
      if (map.hasLayer(calculationBoundsLayer)) map.removeLayer(calculationBoundsLayer);
      return;
    }
    calculationBoundsLayer.setBounds([[bounds[1], bounds[0]], [bounds[3], bounds[2]]]);
    if (!map.hasLayer(calculationBoundsLayer)) calculationBoundsLayer.addTo(map);
  }

  function statusLabel(status) {
    return ({READY: 'Готово к расчёту', CALCULATING: 'Идёт расчёт', SUCCEEDED: 'Расчёт завершён', FAILED: 'Ошибка задания', CANCELLED: 'Отменено', CANCEL_REQUESTED: 'Остановка запрошена', VALIDATING: 'Проверка данных'})[status] || status || 'Нет задания';
  }

  function statusKind(status) {
    if (status === 'SUCCEEDED' || status === 'READY') return 'ready';
    if (status === 'CALCULATING' || status === 'VALIDATING' || status === 'CANCEL_REQUESTED') return 'running';
    if (status === 'FAILED' || status === 'CANCELLED') return 'failed';
    return '';
  }

  function setStatus(label, kind) {
    statusBadge.textContent = label;
    statusBadge.className = `status-badge ${kind || ''}`;
    updateCalculateButton();
  }

  function updateCalculateButton() {
    el('calculate').disabled = !state.jobId || state.jobStatus !== 'READY' || state.polling;
  }

  function setError(message) {
    errorBox.textContent = message || '';
    errorBox.hidden = !message;
  }

  function setProgress(message) { el('progress-message').textContent = message || ''; }
  function setMapMessage(message) { el('map-message').textContent = message; }
  function formatMoney(value) { return value == null ? '—' : currency.format(Number(value)); }
  function signedMoney(value, baseline) { return signedDelta(value, baseline, delta => currency.format(Math.abs(delta))); }
  function signedMeters(value, baseline) { return signedDelta(value, baseline, delta => `${number.format(Math.abs(delta))} м`); }
  function signedNumber(value, baseline) { return signedDelta(value, baseline, delta => number.format(Math.abs(delta))); }
  function signedDelta(value, baseline, formatter) {
    if (value == null || baseline == null) return '—';
    const delta = Number(value) - Number(baseline);
    if (delta === 0) return 'без изменений';
    return `${delta > 0 ? '+' : '−'}${formatter(delta)}`;
  }
  function signedInteger(value) { return `${value > 0 ? '+' : '−'}${Math.abs(value)}`; }
  function formatMeters(value) { return value == null ? '—' : `${number.format(Number(value))} м`; }
  function formatNumber(value) { return value == null ? '—' : number.format(Number(value)); }
  function formatBytes(value) { return value < 1_000_000 ? `${Math.ceil(value / 1000)} КБ` : `${number.format(value / 1_000_000)} МБ`; }
  function displayTypedId(value) { return typeof value === 'string' ? JSON.stringify(value) : String(value); }
  function fixed(value) { return Number(value).toFixed(7); }
  function delay(milliseconds) { return new Promise(resolve => window.setTimeout(resolve, milliseconds)); }
  function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, character => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[character]));
  }

  async function readResponse(response) {
    let body;
    try { body = await response.json(); }
    catch (error) {
      if (error.name === 'AbortError') throw error;
      throw new Error(`Сервис вернул ответ без JSON (${response.status}).`);
    }
    if (!response.ok) throw new Error(body.message || body.error || `Ошибка сервиса ${response.status}.`);
    return body;
  }
})();
