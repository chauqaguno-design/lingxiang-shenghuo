/* 无第三方测试依赖：node --test frontend/test/state.test.cjs */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../js/linxiang.js'), 'utf8');

function createApp(fetchImpl) {
  const store = new Map();
  const context = {
    Date, URLSearchParams, AbortController, Set, Promise, TypeError,
    setTimeout: () => 1, clearTimeout() {}, setInterval: () => 1, clearInterval() {},
    fetch: fetchImpl || (async () => ({ status: 200, ok: true, json: async () => ({ success: true, data: [] }) })),
    sessionStorage: { getItem: k => store.get(k), setItem: (k, v) => store.set(k, v), removeItem: k => store.delete(k) },
    document: { title: '', activeElement: null, contains: () => false, body: { classList: { toggle() {} } } },
    window: { location: { hash: '#/home' }, addEventListener() {}, removeEventListener() {}, scrollTo() {} },
    Vue: function (options) {
      Object.assign(this, options.data());
      for (const [name, method] of Object.entries(options.methods)) this[name] = method.bind(this);
      for (const [name, getter] of Object.entries(options.computed)) Object.defineProperty(this, name, { get: getter.bind(this) });
      this.$set = (target, key, value) => { target[key] = value; };
      this.$refs = {};
      this.$nextTick = fn => fn.call(this);
    }
  };
  vm.runInNewContext(source, context);
  return context.window.linxiangApp;
}

test('PREPARING/RESERVED and incomplete SUCCEEDED never appear as successful purchases', () => {
  const app = createApp();
  for (const requestStatus of ['PREPARING', 'RESERVED', 'SUCCEEDED']) {
    app.resultOrder = { id: '1', requestStatus };
    assert.equal(app.resultKind, 'pending');
  }
  app.resultOrder = { requestStatus: 'SUCCEEDED', orderStatus: 1 };
  assert.equal(app.resultKind, 'success');
  assert.match(app.resultHeading, /等待支付/);
  app.resultOrder.orderStatus = 4;
  assert.equal(app.resultKind, 'failed');
  assert.match(app.resultHeading, /已关闭/);
});

test('each API call uses the current authorization token', async () => {
  const seen = [];
  const app = createApp(async (url, options) => {
    seen.push(options.headers.authorization);
    return { status: 200, ok: true, json: async () => ({ success: true, data: [] }) };
  });
  app.token = 'first'; await app.api('/voucher-order/my');
  app.token = 'second'; await app.api('/voucher-order/my');
  assert.deepEqual(seen, ['first', 'second']);
});

test('429 is a recoverable user-facing rate-limit error', async () => {
  const app = createApp(async () => ({ status: 429, ok: false }));
  await assert.rejects(app.api('/voucher-order/seckill/101', { method: 'POST' }), error => error.status === 429 && /操作太快/.test(error.message));
});

test('401 clears credentials and prompts login', async () => {
  const app = createApp(async () => ({ status: 401, ok: false }));
  app.token = 'expired'; app.orders = [{ id: '1' }];
  await assert.rejects(app.api('/voucher-order/my'), /登录已失效/);
  assert.equal(app.token, ''); assert.equal(app.orders.length, 0); assert.equal(app.loginVisible, true);
});

test('network failure on POST does not tell the user that purchase failed definitively', async () => {
  const app = createApp(async () => { throw new TypeError('network'); });
  await assert.rejects(app.api('/voucher-order/seckill/101', { method: 'POST' }), error => error.uncertain === true && /结果尚未确认/.test(error.message));
});

test('unsafe numeric order IDs are rejected instead of silently rounded', async () => {
  const app = createApp(); app.token = 'test';
  app.api = async () => [{ id: 999999999999999999, status: 1 }];
  await app.loadOrders();
  assert.equal(app.orders.length, 0); assert.match(app.ordersError, /编号格式异常/);
});

test('old category response cannot overwrite a newly selected category', async () => {
  const app = createApp();
  let resolveFirst;
  app.api = url => url.includes('typeId=1') ? new Promise(resolve => { resolveFirst = resolve; }) : Promise.resolve([{ id: '2', name: 'new' }]);
  app.loadFeatured = () => {};
  const oldRequest = app.selectType('1');
  await app.selectType('2');
  resolveFirst([{ id: '1', name: 'old' }]); await oldRequest;
  assert.equal(app.selectedType, '2'); assert.equal(app.shops[0].name, 'new');
});

test('automatic result polling stops after 15 reads while preserving manual recovery', async () => {
  const app = createApp();
  app.resultOrder = { id: '123', requestStatus: 'RESERVED' };
  app.pendingOrder = { id: '123' }; app.resultVisible = true; app.pollCount = 14;
  app.api = async () => ({ id: '123', requestStatus: 'RESERVED' });
  await app.refreshResult(true);
  assert.equal(app.pollExpired, true); assert.equal(app.pendingOrder.id, '123'); assert.equal(app.resultKind, 'pending');
  app.api = async () => ({ id: '123', requestStatus: 'SUCCEEDED', orderStatus: 1 });
  await app.refreshResult(false);
  assert.equal(app.resultKind, 'success'); assert.equal(app.pendingOrder, null);
});

test('confirmed failed reservation removes local pending marker', async () => {
  const app = createApp(); app.pendingOrder = { id: '123' }; app.resultOrder = { id: '123' };
  app.api = async () => ({ id: '123', requestStatus: 'FAILED', reason: '库存不足' });
  await app.refreshResult(false);
  assert.equal(app.resultKind, 'failed'); assert.equal(app.resultDescription, '库存不足'); assert.equal(app.pendingOrder, null);
});

test('paid, cancelled or expired orders never trigger payment requests', async () => {
  const app = createApp(); let requests = 0;
  app.api = async () => { requests += 1; };
  for (const status of [2, 3, 4, 5, 6]) await app.payOrder({ id: '123', status });
  await app.payOrder({ id: '123', status: 1, expireTime: '2000-01-01T00:00:00' });
  assert.equal(requests, 0);
});

test('voucher prices accept serialized Long strings and do not fabricate absent prices', () => {
  const app = createApp();
  assert.equal(app.money('9900'), '99.00'); assert.equal(app.money(0), '0.00'); assert.equal(app.money(null), '—');
});

test('cached stock is not treated as authoritative for disabling an active voucher', () => {
  const app = createApp();
  assert.equal(app.canBuy({ type: 1, stock: 0, beginTime: '2000-01-01T00:00:00', endTime: '2099-01-01T00:00:00' }), true);
  assert.equal(app.canBuy({ type: 0 }), false);
});

test('manual retry after an uncertain POST reuses the same request identifier', async () => {
  const app = createApp(); app.token = 'test';
  const identifiers = [];
  app.api = async (url, options) => {
    identifiers.push(options.requestId);
    const error = new Error('网络中断'); error.uncertain = true; throw error;
  };
  await app.buyVoucher({ id: '101', type: 1 });
  await app.buyVoucher({ id: '101', type: 1 });
  assert.equal(identifiers.length, 2);
  assert.ok(identifiers[0]); assert.equal(identifiers[0], identifiers[1]);
  assert.equal(app.pendingOrder, null);
});

test('logout revokes the token on the server before clearing the local session', async () => {
  const seen = [];
  const app = createApp(async (url, options) => {
    seen.push({ url, method: options.method, token: options.headers.authorization });
    return { status: 200, ok: true, json: async () => ({ success: true }) };
  });
  app.token = 'active'; app.orders = [{ id: '123' }];
  await app.logout();
  assert.deepEqual(seen, [{ url: '/user/logout', method: 'POST', token: 'active' }]);
  assert.equal(app.token, ''); assert.equal(app.orders.length, 0);
  assert.equal(app.notice.kind, 'success');
});

test('logout still clears local state on a network error without claiming server revocation', async () => {
  const app = createApp(async () => { throw new TypeError('network'); });
  app.token = 'active'; app.pendingOrder = { id: '123' };
  await app.logout();
  assert.equal(app.token, ''); assert.equal(app.pendingOrder, null);
  assert.equal(app.notice.kind, 'error'); assert.match(app.notice.text, /服务端会话注销尚未确认/);
});
