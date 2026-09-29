import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

// Load the actual browser script with inert DOM/Leaflet surfaces and expose only its reader.
const source = readFileSync(new URL('../../main/resources/static/map.js', import.meta.url), 'utf8');
const chain = new Proxy({}, {get: (_, key) => key === 'attributionControl'
  ? {setPrefix() {}} : () => chain});
const element = {addEventListener() {}};
const context = vm.createContext({
  L: {map: () => chain, tileLayer: () => chain, rectangle: () => chain,
    layerGroup: () => chain, control: {layers: () => chain}},
  document: {getElementById: () => element}, window: {location: {search: ''}},
  URLSearchParams, Intl
});
vm.runInContext(source.replace(/\}\)\(\);\s*$/,
  'globalThis.readResponse = readResponse; globalThis.renderUnconnected = renderUnconnected; globalThis.testState = state;\n})();'), context);
const readResponse = context.readResponse;

test('an aborted body after HTTP 200 remains AbortError', async () => {
  const abort = new DOMException('Map viewport changed', 'AbortError');
  let streamController;
  const stream = new ReadableStream({start(controller) { streamController = controller; }});
  const response = new Response(stream, {status: 200, headers: {'Content-Type': 'application/json'}});
  const pending = readResponse(response);
  streamController.error(abort);
  await assert.rejects(pending, error => error === abort && error.name === 'AbortError');
});

test('valid JSON is returned and actual malformed bodies remain errors', async () => {
  assert.deepEqual(await readResponse(Response.json({status: 'ok'})), {status: 'ok'});
  await assert.rejects(readResponse(new Response('<html>wrong service</html>')),
    /Сервис вернул ответ без JSON \(200\)/);
});

test('structured HTTP errors retain the server explanation', async () => {
  await assert.rejects(readResponse(Response.json({message: 'Result unavailable'}, {status: 409})),
    /Result unavailable/);
});

test('missing-target diagnostics follow the selected variant', () => {
  context.testState.summaries = [
    {rank: 1, variantId: 'one', unconnectedTargetIds: [2], unsearchedTargetIds: [2],
      unsearchedReasons: [{targetId: 2, reason: 'NOT_ATTEMPTED_GROUP_BUDGET'}]},
    {rank: 2, variantId: 'two', unconnectedTargetIds: [4], unsearchedTargetIds: [4],
      unsearchedReasons: [{targetId: 4, reason: 'GROUP_CANDIDATES_REJECTED_OR_INCOMPLETE'}]}
  ];
  context.testState.result = {unsearchedTargetIds: [2], targetSearches: []};
  context.testState.selectedVariants = new Set(['two']);
  context.renderUnconnected();
  assert.match(element.innerHTML, /4 — вариант 2:/);
  assert.match(element.innerHTML, /штраф за их отсутствие включён/);
  assert.doesNotMatch(element.innerHTML, /2 — вариант 1:/);

  context.testState.selectedVariants = new Set(['one']);
  context.renderUnconnected();
  assert.match(element.innerHTML, /2 — вариант 1:/);
  assert.doesNotMatch(element.innerHTML, /4 — вариант 2:/);

  context.testState.selectedVariants.clear();
  context.renderUnconnected();
  assert.equal(element.hidden, true);
});
