import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const inputPath = resolve(process.argv[2] ?? 'inputs/ТЗ/Датасет скорректированный.geojson');
const outputPath = resolve(process.argv[3] ?? 'artifacts/demo/heat-network-variants.geojson');
const baseUrl = (process.env.HEAT_DEMO_BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '');
const pollIntervalMs = positiveInteger(process.env.HEAT_DEMO_POLL_MS, 2_000);
const timeoutMs = positiveInteger(process.env.HEAT_DEMO_TIMEOUT_MS, 15 * 60_000);

function positiveInteger(value, fallback) {
  if (value === undefined) return fallback;
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) throw new Error('Polling and timeout values must be positive integers');
  return parsed;
}

function log(stage, detail = '') {
  const suffix = detail ? ` ${detail}` : '';
  process.stdout.write(`${new Date().toISOString()} ${stage}${suffix}\n`);
}

async function request(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, options);
  const body = await response.text();
  if (!response.ok) throw new Error(`${options.method ?? 'GET'} ${path} returned HTTP ${response.status}: ${body}`);
  return { response, body };
}

async function requestJson(path, options) {
  const { body } = await request(path, options);
  try {
    return JSON.parse(body);
  } catch (error) {
    throw new Error(`${path} returned invalid JSON: ${error.message}`);
  }
}

async function run() {
  const startedAt = Date.now();
  const input = await readFile(inputPath);
  const inputGeoJson = JSON.parse(input.toString('utf8'));
  if (inputGeoJson.type !== 'FeatureCollection' || !Array.isArray(inputGeoJson.features)) {
    throw new Error('Input must be a GeoJSON FeatureCollection');
  }
  log('INPUT', `path=${inputPath} bytes=${input.byteLength} features=${inputGeoJson.features.length}`);

  const upload = await requestJson('/api/jobs', {
    method: 'POST',
    headers: { 'content-type': 'application/geo+json' },
    body: input,
  });
  if (upload.status !== 'READY' || upload.validationStatus !== 'PASSED') {
    throw new Error(`Upload validation did not pass: ${JSON.stringify(upload)}`);
  }
  const jobId = upload.jobId;
  log('UPLOAD_VALIDATED', `jobId=${jobId} features=${upload.featureCount} counts=${JSON.stringify(upload.countsByType)}`);

  const extent = await requestJson(`/api/jobs/${encodeURIComponent(jobId)}/view/extent`);
  if (extent.crs !== 'EPSG:4326' || !Array.isArray(extent.bbox) || extent.bbox.length !== 4
      || extent.bbox.some(value => !Number.isFinite(value))) {
    throw new Error(`API returned an invalid WGS84 extent: ${JSON.stringify(extent)}`);
  }
  log('EXTENT', `bbox=${JSON.stringify(extent.bbox)}`);

  const calculation = await requestJson(`/api/jobs/${encodeURIComponent(jobId)}/calculate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ bbox: extent.bbox }),
  });
  if (calculation.jobId !== jobId) throw new Error('Calculation response refers to a different job');
  log('CALCULATION_STARTED', `status=${calculation.calculationStatus}`);

  const deadline = Date.now() + timeoutMs;
  let status;
  let previousState = '';
  while (Date.now() < deadline) {
    status = await requestJson(`/api/jobs/${encodeURIComponent(jobId)}`);
    const state = `${status.status}/${status.calculationStatus}`;
    if (state !== previousState) {
      log('CALCULATION_STATE', `state=${state}`);
      previousState = state;
    }
    if (status.status === 'FAILED') throw new Error(`Calculation failed: ${JSON.stringify(status.error)}`);
    if (status.status === 'SUCCEEDED') break;
    await new Promise(resolveTimeout => setTimeout(resolveTimeout, pollIntervalMs));
  }
  if (status?.status !== 'SUCCEEDED') throw new Error(`Calculation did not finish within ${timeoutMs} ms`);

  const result = await requestJson(`/api/jobs/${encodeURIComponent(jobId)}/variants`);
  if (result.totalTargets !== inputGeoJson.features.filter(feature =>
    feature.properties?.object_type === 'oks_connection_point').length) {
    throw new Error(`Result target count ${result.totalTargets} differs from the input target count`);
  }
  log('RESULT_READY', `searchStatus=${result.searchStatus} targets=${result.totalTargets} variants=${result.variants.length}`);

  const { body: exportedText } = await request(`/api/jobs/${encodeURIComponent(jobId)}/export`);
  const exported = JSON.parse(exportedText);
  if (exported.type !== 'FeatureCollection' || !Array.isArray(exported.features)) {
    throw new Error('Export is not a GeoJSON FeatureCollection');
  }
  await mkdir(dirname(outputPath), { recursive: true });
  await writeFile(outputPath, `${exportedText.trimEnd()}\n`, 'utf8');

  const summary = {
    jobId,
    jobStatus: status.status,
    calculationStatus: status.calculationStatus,
    inputPath,
    inputBytes: input.byteLength,
    inputFeatures: inputGeoJson.features.length,
    uploadedFeatureCount: upload.featureCount,
    countsByType: upload.countsByType,
    bboxWgs84: extent.bbox,
    searchStatus: result.searchStatus,
    detail: result.detail,
    totalTargets: result.totalTargets,
    combinationsEvaluated: result.combinationsEvaluated,
    combinationLimitReached: result.combinationLimitReached,
    unconnectedTargetIds: result.unconnectedTargetIds,
    unsearchedTargetIds: result.unsearchedTargetIds,
    targetSearches: result.targetSearches,
    variants: result.variants,
    exportedFeatures: exported.features.length,
    outputPath,
    elapsedMs: Date.now() - startedAt,
  };
  const summaryPath = `${outputPath}.summary.json`;
  await writeFile(summaryPath, `${JSON.stringify(summary, null, 2)}\n`, 'utf8');
  log('EXPORT_SAVED', `path=${outputPath} features=${exported.features.length}`);
  log('SUMMARY_SAVED', `path=${summaryPath} elapsedMs=${summary.elapsedMs}`);
  process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  run().catch(error => {
    process.stderr.write(`DEMO_FAILED ${error.stack ?? error.message}\n`);
    process.exitCode = 1;
  });
}
