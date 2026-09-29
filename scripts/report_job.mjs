import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { validateExport } from './validate_export.mjs';

const [jobId, inputName, outputName] = process.argv.slice(2);
if (!jobId || !inputName || !outputName) {
  process.stderr.write('Usage: node scripts/report_job.mjs JOB_ID INPUT.geojson REPORT.json\n');
  process.exit(2);
}

const baseUrl = (process.env.HEAT_DEMO_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const inputPath = resolve(inputName);
const outputPath = resolve(outputName);
const hash = value => createHash('sha256').update(value).digest('hex');
const key = value => JSON.stringify([typeof value, value]);

async function fetchResult(path) {
  const response = await fetch(`${baseUrl}${path}`, { cache: 'no-store' });
  const body = await response.text();
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}: ${body.slice(0, 500)}`);
  return body;
}

const input = await readFile(inputPath);
const dataset = JSON.parse(input.toString('utf8'));
if (dataset.type !== 'FeatureCollection' || !Array.isArray(dataset.features)) {
  throw new Error('Input must be a GeoJSON FeatureCollection');
}
const targetIds = dataset.features.filter(feature => feature.properties?.object_type === 'oks_connection_point')
  .map(feature => feature.properties.id);
const targetKeys = new Set(targetIds.map(key));
if (targetKeys.size !== targetIds.length) throw new Error('Input has duplicate typed target IDs');

const encoded = encodeURIComponent(jobId);
const job = JSON.parse(await fetchResult(`/api/jobs/${encoded}`));
if (job.status !== 'SUCCEEDED') throw new Error(`Job is ${job.status}; only completed results can be reported`);
const resultText = await fetchResult(`/api/jobs/${encoded}/variants`);
const result = JSON.parse(resultText);
const exportText = await fetchResult(`/api/jobs/${encoded}/export`);
const exported = JSON.parse(exportText);
if (exported.type !== 'FeatureCollection' || !Array.isArray(exported.features)) {
  throw new Error('Export is not a GeoJSON FeatureCollection');
}
if (result.totalTargets !== targetIds.length) {
  throw new Error(`API target count ${result.totalTargets} differs from input ${targetIds.length}`);
}

const variants = (result.variants || []).map(variant => {
  const connected = variant.connectedTargetIds || [];
  const connectedKeys = new Set(connected.map(key));
  if (connectedKeys.size !== connected.length || [...connectedKeys].some(id => !targetKeys.has(id))) {
    throw new Error(`Variant ${variant.variantId} contains duplicate or foreign target IDs`);
  }
  return {
    rank: variant.rank,
    variantId: variant.variantId,
    connectedCount: connected.length,
    connectedTargetIds: connected,
    unconnectedTargetIds: variant.unconnectedTargetIds || [],
    unsearchedTargetIds: variant.unsearchedTargetIds || null,
    unsearchedReasons: variant.unsearchedReasons || null,
    newNetworkLengthM: variant.newNetworkLengthM,
    constructionCost: variant.constructionCost,
    unconnectedPenalty: variant.unconnectedPenalty,
    calculatedCost: variant.calculatedCost,
    score: variant.score,
  };
});
const rankOne = variants.find(variant => variant.rank === 1) || null;
const maximumCoverage = variants.slice().sort((a, b) =>
  b.connectedCount - a.connectedCount || a.rank - b.rank)[0] || null;
const primaryUnconnected = result.unconnectedTargetIds || [];
const primaryUnsearched = result.unsearchedTargetIds || [];
const connectedKeys = new Set((rankOne?.connectedTargetIds || []).map(key));
const unconnectedKeys = new Set(primaryUnconnected.map(key));
const unsearchedReasons = new Map((rankOne?.unsearchedReasons || [])
  .map(item => [key(item.targetId), item.reason]));
const searchesByTarget = new Map((result.targetSearches || [])
  .map(item => [key(item.targetId), item.attempts || []]));
const targetOutcomes = targetIds.map(targetId => {
  const targetKey = key(targetId);
  const reason = unsearchedReasons.get(targetKey) || null;
  const category = connectedKeys.has(targetKey) ? 'connected'
    : reason?.startsWith('NOT_ATTEMPTED') ? 'not_attempted'
        : reason === 'GROUP_CANDIDATES_REJECTED_OR_INCOMPLETE' ? 'incomplete_or_rejected'
          : reason ? 'incomplete'
            : unconnectedKeys.has(targetKey) ? 'candidates_rejected'
          : 'incomplete';
  return { targetId, category, selectedVariantReason: reason,
    allTrajectoryStatusCount: searchesByTarget.get(targetKey)?.length || 0 };
});
const primaryPartition = [
  ...(rankOne?.connectedTargetIds || []), ...primaryUnconnected,
].map(key);
if (new Set(primaryPartition).size !== targetIds.length
    || primaryPartition.some(id => !targetKeys.has(id))
    || primaryUnsearched.some(id => !unconnectedKeys.has(key(id)))) {
  throw new Error('Rank 1 connected/unconnected IDs must partition the input; unsearched must be a subset of unconnected');
}
for (const variant of variants) {
  const unsearched = variant.unsearchedTargetIds
    ?? (variant.rank === 1 ? primaryUnsearched : null);
  if (unsearched === null) continue;
  const partition = [
    ...variant.connectedTargetIds, ...variant.unconnectedTargetIds,
  ].map(key);
  const unconnected = new Set(variant.unconnectedTargetIds.map(key));
  if (new Set(partition).size !== targetIds.length
      || partition.some(id => !targetKeys.has(id))
      || unsearched.some(id => !unconnected.has(key(id)))) {
    throw new Error(`Variant ${variant.variantId} connected/unconnected IDs must partition the input; unsearched must be a subset of unconnected`);
  }
}
const independentExportChecks = validateExport(dataset, result, exported);
const retainedReasons = {};
for (const event of result.attemptEvents || []) {
  const reason = event.reason || event.status || 'UNKNOWN';
  retainedReasons[reason] = (retainedReasons[reason] || 0) + 1;
}

const report = {
  jobId,
  baseUrl,
  inputPath,
  inputSha256: hash(input),
  inputBytes: input.length,
  inputFeatureCount: dataset.features.length,
  targetCount: targetIds.length,
  targetIds,
  jobStatus: job.status,
  searchStatus: result.searchStatus,
  createdAt: job.createdAt,
  finishedAt: job.updatedAt,
  elapsedMs: Date.parse(job.updatedAt) - Date.parse(job.createdAt),
  declaredConfiguration: process.env.HEAT_BENCH_CONFIG_JSON
    ? JSON.parse(process.env.HEAT_BENCH_CONFIG_JSON) : null,
  imageId: process.env.HEAT_BENCH_IMAGE_ID || null,
  jarSha256: process.env.HEAT_BENCH_JAR_SHA256 || null,
  memoryPeakBytesObserved: process.env.HEAT_BENCH_MEMORY_PEAK_BYTES
    ? Number(process.env.HEAT_BENCH_MEMORY_PEAK_BYTES) : null,
  memoryPeakEvidence: process.env.HEAT_BENCH_MEMORY_PEAK_EVIDENCE || null,
  rankOne,
  maximumCoverage,
  returnedVariants: variants,
  unconnectedTargetIds: primaryUnconnected,
  unsearchedTargetIds: primaryUnsearched,
  combinationsEvaluated: result.combinationsEvaluated,
  combinationLimitReached: result.combinationLimitReached,
  targetSearches: result.targetSearches,
  targetOutcomes,
  retainedAttemptEvents: (result.attemptEvents || []).length,
  truncatedAttemptEvents: result.truncatedAttemptEvents || 0,
  retainedReasonCounts: retainedReasons,
  exportFeatureCount: exported.features.length,
  exportSha256: hash(exportText),
  independentExportChecks,
  rawArtifacts: {
    variants: `${outputPath}.variants.json`,
    export: `${outputPath}.export.geojson`,
  },
  evidenceScope: 'Export is independently checked for topology, endpoints, totals and API agreement; engineering clearances are not revalidated. Target outcomes use rank-1 reasons; status counts include all trajectories and are not per-variant. Truncated attempt reasons and unranked candidates are unavailable.',
};
await mkdir(dirname(outputPath), { recursive: true });
await writeFile(report.rawArtifacts.variants, resultText);
await writeFile(report.rawArtifacts.export, exportText);
await writeFile(outputPath, `${JSON.stringify(report, null, 2)}\n`);
process.stdout.write(`${outputPath}\nrank1=${rankOne?.connectedCount ?? 0}/${targetIds.length} max=${maximumCoverage?.connectedCount ?? 0}/${targetIds.length} elapsedMs=${report.elapsedMs}\n`);
