/* 可选浏览器验收，需要在运行环境安装 playwright 并准备 Chromium/Edge。
 * 用法：BASE_URL 指向启用 demo 配置的本地服务，再运行 node frontend/test/browser-smoke.cjs。
 * 此脚本会创建一个演示账号，抢购商户 4 的优惠券并模拟支付，不用于生产环境。
 */
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');

(async () => {
  const baseUrl = process.env.BASE_URL || 'http://localhost:18081';
  const outputDir = process.env.OUTPUT_DIR || path.join(os.tmpdir(), 'linxiang-frontend-smoke');
  await fs.mkdir(outputDir, { recursive: true });
  const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  try {
    const demoResponse = await page.request.get(baseUrl + '/demo/status');
    const demo = await demoResponse.json();
    assert.equal(demo.data.demo, true, '只允许对演示环境执行购买验收');
    await page.goto(baseUrl, { waitUntil: 'networkidle' });
    await page.locator('.shop-card').first().waitFor();
    assert.match(await page.title(), /邻享生活/);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.screenshot({ path: path.join(outputDir, 'desktop-home.png'), fullPage: true });

    await page.getByLabel('筛选已加载的商户').fill('邻家轻食');
    assert.equal(await page.locator('.shop-card').count(), 1);
    await page.getByLabel('筛选已加载的商户').fill('');
    await page.locator('.shop-card').filter({ hasText: '邻家轻食' }).click();
    await page.getByRole('heading', { name: '邻家轻食', exact: true }).waitFor();
    await page.locator('.voucher-card').first().waitFor();
    await page.screenshot({ path: path.join(outputDir, 'desktop-shop.png'), fullPage: true });

    await page.getByRole('button', { name: '登录后抢购', exact: true }).click();
    const phone = '139' + String(Date.now()).slice(-8);
    await page.getByLabel('演示手机号', { exact: true }).fill(phone);
    await page.getByRole('button', { name: '进入邻享生活 →', exact: true }).click();
    await page.getByRole('dialog', { name: '好生活，从附近开始。' }).waitFor({ state: 'hidden' });
    await page.getByRole('button', { name: '立即抢购', exact: true }).click();
    await page.getByRole('heading', { name: '优惠已锁定，等待支付', exact: true }).waitFor({ timeout: 45000 });
    await page.getByRole('button', { name: '模拟支付', exact: true }).click();
    await page.getByRole('heading', { name: '优惠订单已确认', exact: true }).waitFor({ timeout: 20000 });
    await page.getByRole('button', { name: '查看我的订单 →', exact: true }).click();
    await page.locator('.status-2').first().waitFor();
    assert.equal(await page.getByRole('button', { name: '模拟支付', exact: true }).count(), 0);
    await page.screenshot({ path: path.join(outputDir, 'desktop-orders.png'), fullPage: true });

    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(baseUrl + '/index.html#/home', { waitUntil: 'networkidle' });
    await page.locator('.shop-card').first().waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.screenshot({ path: path.join(outputDir, 'mobile-home.png'), fullPage: true });
    await page.getByRole('link', { name: '我的订单', exact: true }).click();
    await page.locator('.order-card').first().waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.screenshot({ path: path.join(outputDir, 'mobile-orders.png'), fullPage: true });
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ result: 'passed', phone, screenshots: outputDir, checks: ['真实商户查询', '筛选商户', '详情与优惠券', '演示登录', '抢购结果确认', '模拟支付', '已支付订单', '桌面手机无横向溢出', '无浏览器脚本错误'] }, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
