import {readFileSync} from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';
import {validateExport} from '../../../scripts/validate_export.mjs';

const root = new URL('../../../', import.meta.url);
const read = relative => JSON.parse(readFileSync(new URL(relative, root), 'utf8'));
const input = read('inputs/ТЗ/Датасет скорректированный.geojson');
const historical = read('artifacts/bench/group-focused-v25-final-report.json');
const historicalExport = read('artifacts/bench/group-focused-v25-final-report.json.export.geojson');

test('independent export validator catches omitted penalties in real historical variants', () => {
  const exported = structuredClone(historicalExport);
  const summaries = new Map(exported.features
    .filter(feature => feature.properties.object_type === 'variant_summary')
    .map(feature => [feature.properties.variant_id, feature.properties]));
  const result = {variants: historical.returnedVariants.map(variant => ({
    ...variant,
    existingChamberTieInCost: summaries.get(variant.variantId).existing_chamber_tie_in_cost,
  }))};
  assert.throws(() => validateExport(input, result, exported), /does not list every missing target/);

  const targets = input.features.filter(feature => feature.properties.object_type === 'oks_connection_point');
  for (const variant of result.variants) {
    const connected = new Set(variant.connectedTargetIds);
    const missing = targets.filter(feature => !connected.has(feature.properties.id));
    const penalty = missing.reduce((sum, feature) => sum + 100_000_000
      + 500_000 * feature.properties.flow_tph, 0);
    const calculatedCost = variant.constructionCost + penalty;
    const score = Number((0.7 * calculatedCost / 25_000_000
      + 0.3 * variant.newNetworkLengthM / 100).toFixed(7));
    Object.assign(variant, {unconnectedTargetIds: missing.map(feature => feature.properties.id),
      unconnectedPenalty: penalty, calculatedCost, score});
    Object.assign(summaries.get(variant.variantId), {
      unconnected_oks_ids: variant.unconnectedTargetIds,
      unconnected_penalty: penalty,
      calculated_cost: calculatedCost,
      score,
    });
  }
  const checks = validateExport(input, result, exported);
  assert.equal(checks.length, result.variants.length);
  assert.equal(checks.length, 3);
});
