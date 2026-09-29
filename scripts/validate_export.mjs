const typedKey = value => JSON.stringify([typeof value, value]);

function sameIds(left, right) {
  return left.length === right.length
    && left.every(id => right.some(other => typedKey(id) === typedKey(other)));
}

function closeNumber(actual, expected, tolerance, label) {
  if (!Number.isFinite(actual) || !Number.isFinite(expected)
      || Math.abs(actual - expected) > tolerance) {
    throw new Error(`${label}: ${actual} differs from ${expected}`);
  }
}

/** Structural and monetary checks rebuilt from exported GeoJSON, independently of Java's in-memory tree. */
export function validateExport(input, result, exported) {
  if (input?.type !== 'FeatureCollection' || !Array.isArray(input.features)
      || exported?.type !== 'FeatureCollection' || !Array.isArray(exported.features)) {
    throw new Error('Both input and export must be GeoJSON FeatureCollections');
  }
  const inputNodes = new Map();
  const targetFlows = new Map();
  for (const feature of input.features) {
    const type = feature.properties?.object_type;
    if (type !== 'oks_connection_point' && type !== 'heat_chamber') continue;
    if (feature.geometry?.type !== 'Point') throw new Error(`Input node ${feature.properties?.id} is not a Point`);
    inputNodes.set(typedKey(feature.properties.id), { type, coordinate: feature.geometry.coordinates });
    if (type === 'oks_connection_point') {
      const flow = Number(feature.properties.flow_tph);
      if (!Number.isFinite(flow) || flow <= 0) throw new Error('Input target requires positive flow_tph');
      targetFlows.set(typedKey(feature.properties.id), flow);
    }
  }
  const byVariant = new Map();
  for (const feature of exported.features) {
    const props = feature.properties;
    if (!props || typeof props.variant_id !== 'string') throw new Error('Export feature has no variant_id');
    if (!['heat_network', 'heat_chamber', 'technical_node', 'variant_summary'].includes(props.object_type)) {
      throw new Error(`Unexpected export object_type: ${props.object_type}`);
    }
    if (!byVariant.has(props.variant_id)) byVariant.set(props.variant_id, []);
    byVariant.get(props.variant_id).push(feature);
  }
  const summaries = result.variants || [];
  if (byVariant.size !== summaries.length) throw new Error('Exported variant count differs from API');
  const checks = [];
  for (const summary of summaries) {
    const features = byVariant.get(summary.variantId);
    if (!features) throw new Error(`Missing export for ${summary.variantId}`);
    const summaryFeatures = features.filter(feature => feature.properties.object_type === 'variant_summary');
    if (summaryFeatures.length !== 1) throw new Error(`Variant ${summary.variantId} needs one summary`);
    const exportedSummary = summaryFeatures[0].properties;
    if (exportedSummary.rank !== summary.rank) throw new Error('API/export rank mismatch');
    for (const [apiField, exportField] of [
      ['constructionCost', 'construction_cost'], ['calculatedCost', 'calculated_cost'],
      ['unconnectedPenalty', 'unconnected_penalty'], ['newNetworkLengthM', 'new_network_length'],
      ['score', 'score'],
    ]) closeNumber(Number(summary[apiField]), Number(exportedSummary[exportField]), 0.01,
      `${summary.variantId} ${apiField}`);
    if (!sameIds(summary.unconnectedTargetIds || [], exportedSummary.unconnected_oks_ids || [])) {
      throw new Error(`API/export unconnected IDs differ for ${summary.variantId}`);
    }
    if (Array.isArray(exportedSummary.unsearched_oks_ids)
        && !sameIds(summary.unsearchedTargetIds || [], exportedSummary.unsearched_oks_ids)) {
      throw new Error(`API/export unsearched IDs differ for ${summary.variantId}`);
    }

    const nodes = new Map(inputNodes);
    const lines = features.filter(feature => feature.properties.object_type === 'heat_network');
    const newChambers = features.filter(feature => feature.properties.object_type === 'heat_chamber');
    for (const feature of features) {
      if (feature.properties.object_type !== 'heat_chamber'
          && feature.properties.object_type !== 'technical_node') continue;
      if (feature.geometry?.type !== 'Point') throw new Error('Export node is not a Point');
      const id = typedKey(feature.properties.id);
      if (nodes.has(id)) throw new Error(`Export node ID collides with input: ${id}`);
      nodes.set(id, { type: feature.properties.object_type, coordinate: feature.geometry.coordinates });
    }
    const parent = new Map();
    const degree = new Map();
    const outgoing = new Map();
    const find = id => {
      if (!parent.has(id)) parent.set(id, id);
      const root = parent.get(id);
      if (root !== id) parent.set(id, find(root));
      return parent.get(id);
    };
    let length = 0;
    let lineCost = 0;
    for (const feature of lines) {
      const props = feature.properties;
      const from = typedKey(props.start_node_id), to = typedKey(props.end_node_id);
      if (from === to || !nodes.has(from) || !nodes.has(to)) {
        throw new Error(`Unknown or duplicate line endpoint in ${summary.variantId}`);
      }
      const coords = feature.geometry?.coordinates;
      if (feature.geometry?.type !== 'LineString' || !Array.isArray(coords) || coords.length < 2) {
        throw new Error(`Invalid LineString in ${summary.variantId}`);
      }
      for (const pair of coords) {
        if (!Array.isArray(pair) || pair.length < 2 || !Number.isFinite(pair[0])
            || !Number.isFinite(pair[1]) || Math.abs(pair[0]) > 180 || Math.abs(pair[1]) > 90) {
          throw new Error(`Invalid WGS84 coordinate in ${summary.variantId}`);
        }
      }
      for (const [id, endpoint] of [[from, coords[0]], [to, coords[coords.length - 1]]]) {
        const node = nodes.get(id).coordinate;
        if (Math.hypot(endpoint[0] - node[0], endpoint[1] - node[1]) > 1e-6) {
          throw new Error(`Line does not meet exported node ${id}`);
        }
        degree.set(id, (degree.get(id) || 0) + 1);
      }
      const fromRoot = find(from), toRoot = find(to);
      if (fromRoot === toRoot) throw new Error(`Export graph contains a cycle in ${summary.variantId}`);
      parent.set(fromRoot, toRoot);
      outgoing.set(from, (outgoing.get(from) || 0) + 1);
      const segmentLength = Number(props.length), segmentCost = Number(props.cost);
      if (!(segmentLength > 0) || !(segmentCost >= 0) || !(Number(props.flow_tph) > 0)
          || !(Number(props.diameter) > 0)) throw new Error('Export line lacks engineering totals');
      length += segmentLength;
      lineCost += segmentCost;
    }
    if (lines.length === 0) throw new Error(`Variant ${summary.variantId} has no lines`);
    closeNumber(length, Number(summary.newNetworkLengthM), 0.05, 'Export line lengths');
    const chamberCost = newChambers.reduce((sum, feature) => sum + Number(feature.properties.cost), 0);
    closeNumber(lineCost + chamberCost + Number(summary.existingChamberTieInCost),
      Number(summary.constructionCost), 0.05, 'Export construction cost');
    const connected = [...degree.keys()].filter(id => nodes.get(id).type === 'oks_connection_point');
    const apiConnected = (summary.connectedTargetIds || []).map(typedKey);
    if (!sameIds(connected, apiConnected)) throw new Error(`Export target coverage differs for ${summary.variantId}`);
    const missing = [...targetFlows.keys()].filter(id => !connected.includes(id));
    if (!sameIds(missing, (summary.unconnectedTargetIds || []).map(typedKey))) {
      throw new Error(`Variant ${summary.variantId} does not list every missing target`);
    }
    const unsearched = (summary.unsearchedTargetIds || []).map(typedKey);
    if (new Set(unsearched).size !== unsearched.length
        || unsearched.some(id => !missing.includes(id))) {
      throw new Error(`Variant ${summary.variantId} has invalid unsearched diagnostics`);
    }
    const expectedPenalty = missing.reduce((sum, id) => sum + 100_000_000 + 500_000 * targetFlows.get(id), 0);
    closeNumber(Number(summary.unconnectedPenalty), expectedPenalty, 0.01, 'Full missing-target penalty');
    const expectedTotal = Number(summary.constructionCost) + expectedPenalty;
    closeNumber(Number(summary.calculatedCost), expectedTotal, 0.01, 'Calculated cost with full penalty');
    const expectedScore = 0.7 * (expectedTotal / 25_000_000) + 0.3 * (length / 100);
    closeNumber(Number(summary.score), expectedScore, 0.000001, 'Score with full penalty');
    for (const id of connected) {
      if (degree.get(id) !== 1) throw new Error(`Target ${id} does not have degree one`);
    }
    for (const [id, count] of degree) {
      if (nodes.get(id).type === 'heat_chamber' && count > 4) {
        throw new Error(`Chamber ${id} exceeds four connections`);
      }
    }
    const roots = [...degree.keys()].filter(id => nodes.get(id).type === 'heat_chamber'
      && !outgoing.has(id));
    const components = new Set([...degree.keys()].map(find));
    if (roots.length !== components.size) {
      throw new Error(`Every export component needs one chamber root in ${summary.variantId}`);
    }
    for (const id of degree.keys()) {
      if (!roots.includes(id) && outgoing.get(id) !== 1) {
        throw new Error(`Non-root node ${id} has no unique sourceward edge`);
      }
    }
    checks.push({ variantId: summary.variantId, rank: summary.rank,
      lineCount: lines.length, chamberCount: newChambers.length, componentCount: components.size,
      connectedCount: connected.length, scope: 'export topology, endpoints, amounts and API agreement' });
  }
  return checks;
}
