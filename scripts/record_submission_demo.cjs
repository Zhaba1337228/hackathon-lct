/* Record the completed, verified v28 job without starting another calculation. */
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const root = path.resolve(__dirname, '..');
const jobId = 'fb5bbf47-ecb4-4de8-8edd-46c961ba36b4';
const baseUrl = process.env.HEAT_DEMO_BASE_URL || 'http://localhost:8099';
const rawDir = path.join(root, 'artifacts', 'demo', 'video-raw');

async function caption(page, value) {
  await page.evaluate((label) => {
    let node = document.getElementById('demo-caption');
    if (!node) {
      node = document.createElement('div');
      node.id = 'demo-caption';
      Object.assign(node.style, {
        position: 'fixed', right: '32px', top: '105px', zIndex: '10000',
        maxWidth: '540px', padding: '16px 22px', borderRadius: '9px',
        borderLeft: '6px solid #17ac99', background: 'rgba(12,32,43,.94)',
        color: '#fff', font: '600 24px/1.3 Arial, sans-serif',
        boxShadow: '0 8px 24px rgba(0,0,0,.18)', pointerEvents: 'none',
      });
      document.body.appendChild(node);
    }
    node.textContent = label;
  }, value);
}

async function scrollSidebarTo(page, selector) {
  await page.evaluate((targetSelector) => {
    const sidebar = document.querySelector('.sidebar');
    const target = document.querySelector(targetSelector);
    if (!sidebar || !target) throw new Error(`Missing UI target: ${targetSelector}`);
    const delta = target.getBoundingClientRect().top - sidebar.getBoundingClientRect().top;
    sidebar.scrollTo({ top: sidebar.scrollTop + delta - 25, behavior: 'smooth' });
  }, selector);
  await page.waitForTimeout(1600);
}

async function main() {
  const [jobResponse, resultResponse] = await Promise.all([
    fetch(`${baseUrl}/api/jobs/${jobId}`),
    fetch(`${baseUrl}/api/jobs/${jobId}/variants`),
  ]);
  if (!jobResponse.ok || !resultResponse.ok) throw new Error('Completed job API is unavailable');
  const job = await jobResponse.json();
  const result = await resultResponse.json();
  const first = result.variants?.[0];
  if (job.status !== 'SUCCEEDED' || first?.connectedTargetIds?.length !== 14 ||
      result.totalTargets !== 17 || Number(first.calculatedCost) !== 672373137.83) {
    throw new Error('The live job no longer matches the verified 14/17 evidence');
  }

  fs.mkdirSync(rawDir, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: 1600, height: 900 },
    deviceScaleFactor: 1,
    recordVideo: { dir: rawDir, size: { width: 1600, height: 900 } },
  });
  const page = await context.newPage();
  try {
    await page.goto(`${baseUrl}/?job=${jobId}`, { waitUntil: 'domcontentloaded' });
    await page.locator('.variant-card').first().waitFor({ timeout: 30000 });
    await page.waitForTimeout(2500);
    await caption(page, 'Конкурсный набор: 144 объекта · 17 точек подключения');
    await page.waitForTimeout(4500);

    await caption(page, 'На карте: здания, существующая сеть и итоговые трубы');
    await page.waitForTimeout(6500);

    await caption(page, 'Итог полного API-прогона: 14 из 17 · PARTIAL');
    await scrollSidebarTo(page, '.results-panel');
    await page.waitForTimeout(6500);

    await caption(page, 'Строительство 333,378 млн ₽ · штраф 338,995 млн ₽');
    await page.waitForTimeout(5800);

    await caption(page, 'Цели 2, 5 и 10 пока без маршрута; причины доступны в сервисе');
    await scrollSidebarTo(page, '#unconnected');
    await page.waitForTimeout(6500);

    await caption(page, 'Результат выгружается через API как GeoJSON');
    await page.waitForTimeout(3800);
    await page.evaluate(() => document.getElementById('demo-caption')?.remove());
    await page.waitForTimeout(600);
  } finally {
    const video = page.video();
    await context.close();
    await browser.close();
    console.log(await video.path());
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
