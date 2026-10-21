/* 邻享生活：同源静态前端。所有业务状态均来自后端，不在浏览器中伪造订单成功。 */
(function () {
  'use strict';

  var STORAGE_KEY = 'linxiang.session';
  var PENDING_KEY = 'linxiang.pending';
  var PLACEHOLDER = '/assets/shop-placeholder.svg';
  // Redis 生成的订单 ID 可能超过 JS 安全整数范围。后端必须把这些 ID 序列化为字符串。
  function safeId(value) {
    if (typeof value === 'number' && !Number.isSafeInteger(value)) {
      var error = new Error('订单编号格式异常，请从我的订单核对结果。');
      error.uncertain = true;
      throw error;
    }
    if (!/^\d+$/.test(String(value))) throw new Error('返回的订单编号无效，请从我的订单核对结果。');
    return String(value);
  }
  function readStored(key) {
    try { return JSON.parse(sessionStorage.getItem(key) || 'null'); } catch (_) { return null; }
  }
  function writeStored(key, value) {
    try {
      if (value === null) sessionStorage.removeItem(key);
      else sessionStorage.setItem(key, JSON.stringify(value));
    } catch (_) { /* 浏览器禁用存储时，当前页面仍然可以工作。 */ }
  }
  function dateValue(value) {
    if (!value) return NaN;
    return new Date(String(value).replace(' ', 'T')).getTime();
  }
  function requestId() {
    if (window.crypto && window.crypto.randomUUID) return window.crypto.randomUUID();
    if (window.crypto && window.crypto.getRandomValues) {
      var bytes = new Uint8Array(16);
      window.crypto.getRandomValues(bytes);
      return Array.from(bytes, function (byte) { return byte.toString(16).padStart(2, '0'); }).join('');
    }
    return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2);
  }
  var stored = readStored(STORAGE_KEY) || {};

  window.linxiangApp = new Vue({
    el: '#app',
    data: function () {
      return {
        route: 'home', routeId: '', token: stored.token || '', phone: stored.phone || '13800000001',
        types: [], selectedType: '', shops: [], currentPage: 0, hasMore: true,
        shopsLoading: false, shopError: '', search: '', homeVersion: 0,
        featuredOffers: [], offersLoading: false, offerError: '', voucherNames: {},
        shopDetail: null, detailLoading: false, detailError: '', detailVersion: 0,
        vouchers: [], vouchersLoading: false, voucherError: '', buyingId: '',
        orders: [], ordersLoading: false, ordersError: '', payingId: '',
        loginVisible: false, loginLoading: false, loginError: '', logoutLoading: false, notice: null,
        pendingOrder: stored.token ? readStored(PENDING_KEY + '.' + stored.phone) : null,
        resultVisible: false, resultOrder: null, resultError: '', resultLoading: false,
        pollCount: 0, pollExpired: false, now: Date.now(),
        categorySymbols: ['♧', '☕', '◇', '⌂', '✳', '♬', '◈', '♧', '◉', '✦']
      };
    },
    computed: {
      maskedPhone: function () { return this.phone.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2'); },
      filteredShops: function () {
        var keyword = this.search.toLowerCase();
        return this.shops.filter(function (shop) {
          return !keyword || [shop.name, shop.address, shop.area].join(' ').toLowerCase().indexOf(keyword) !== -1;
        });
      },
      resultKind: function () {
        var order = this.resultOrder || {};
        if (order.requestStatus === 'FAILED' || Number(order.orderStatus) === 4) return 'failed';
        if (order.requestStatus === 'SUCCEEDED' && [1, 2, 3].indexOf(Number(order.orderStatus)) !== -1) return 'success';
        return 'pending';
      },
      resultHeading: function () {
        if (this.resultKind === 'failed') return Number(this.resultOrder.orderStatus) === 4 ? '这笔订单已关闭' : '本次抢购未成功';
        if (this.resultKind === 'success') return Number(this.resultOrder.orderStatus) === 1 ? '优惠已锁定，等待支付' : '优惠订单已确认';
        return this.pollExpired || this.resultError ? '结果仍待确认' : '正在确认抢购结果';
      },
      resultDescription: function () {
        if (this.resultKind === 'failed') return this.resultOrder.reason || '请以订单状态为准，可返回商户页面查看其他优惠。';
        if (this.resultKind === 'success') return Number(this.resultOrder.orderStatus) === 1 ? '请在有效期内完成模拟支付，超时未支付将关闭订单。' : '可以前往我的订单查看最新状态。';
        if (this.pollExpired) return '自动查询已暂停。你可以手动查询，或稍后到我的订单查看。';
        return '请求已提交，订单正在处理中。请稍候，先不要重复抢购。';
      }
    },
    created: function () {
      this._pollTimer = null;
      this._focusReturn = null;
      this._clockTimer = setInterval(function () { this.now = Date.now(); }.bind(this), 1000);
      this._routeHandler = this.handleRoute.bind(this);
      window.addEventListener('hashchange', this._routeHandler);
      this.handleRoute();
    },
    beforeDestroy: function () {
      clearInterval(this._clockTimer);
      clearTimeout(this._pollTimer);
      window.removeEventListener('hashchange', this._routeHandler);
    },
    watch: {
      loginVisible: function () { this.updateModalLock(); },
      resultVisible: function () { this.updateModalLock(); }
    },
    methods: {
      // 每次读取当前 token，避免登录后仍沿用脚本加载时的旧 token。
      api: async function (path, options) {
        options = options || {};
        var controller = new AbortController();
        var timeout = setTimeout(function () { controller.abort(); }, 12000);
        var headers = { Accept: 'application/json' };
        if (this.token) headers.authorization = this.token;
        if (options.requestId) headers['X-Request-Id'] = options.requestId;
        if (options.body !== undefined) headers['Content-Type'] = 'application/json';
        try {
          var response = await fetch(path, {
            method: options.method || 'GET', headers: headers,
            body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
            signal: controller.signal, credentials: 'same-origin', cache: 'no-store'
          });
          if (response.status === 401) {
            this.token = '';
            this.orders = [];
            this.pendingOrder = null;
            this.closeResult();
            writeStored(STORAGE_KEY, null);
            this.openLogin();
            var authError = new Error('登录已失效，请重新登录后再试。');
            authError.status = 401;
            throw authError;
          }
          if (response.status === 429) {
            var rateError = new Error('操作太快了，请稍候再试。');
            rateError.status = 429;
            throw rateError;
          }
          if (response.status === 404 && path === '/demo/login') {
            throw new Error('当前环境未开启演示登录，请启用 demo 配置后体验。');
          }
          if (!response.ok) {
            var serverError = new Error('服务暂时不可用（' + response.status + '），请稍后重试。');
            serverError.uncertain = options.method === 'POST' && response.status >= 500;
            throw serverError;
          }
          var payload;
          try { payload = await response.json(); } catch (_) {
            var formatError = new Error('服务返回格式异常，请稍后查询订单结果。');
            formatError.uncertain = options.method === 'POST';
            throw formatError;
          }
          if (!payload || payload.success !== true) throw new Error((payload && payload.errorMsg) || '操作未完成，请稍后重试。');
          return payload.data;
        } catch (error) {
          if (error.name === 'AbortError') {
            var timeoutError = new Error(options.method === 'POST' ? '请求超时，结果尚未确认。请先查看我的订单，避免重复操作。' : '请求超时，请检查服务后重试。');
            timeoutError.uncertain = options.method === 'POST';
            throw timeoutError;
          }
          if (error instanceof TypeError) {
            var networkError = new Error(options.method === 'POST' ? '网络连接中断，结果尚未确认。请先查看我的订单再操作。' : '无法连接服务，请检查网络或确认后端已启动。');
            networkError.uncertain = options.method === 'POST';
            throw networkError;
          }
          throw error;
        } finally { clearTimeout(timeout); }
      },
      handleRoute: function () {
        var match = window.location.hash.match(/^#\/(home|shop|orders|login)(?:\/(\d+))?/);
        this.route = match ? match[1] : 'home';
        this.routeId = match && match[2] ? match[2] : '';
        if (this.route === 'login') { this.route = 'home'; this.openLogin(); }
        if (this.route === 'shop' && !this.routeId) this.route = 'home';
        this.closeResult();
        if (this.route === 'home' && !this.types.length) this.initializeHome();
        if (this.route === 'shop') this.loadShop(this.routeId);
        if (this.route === 'orders' && this.token) this.loadOrders();
        document.title = (this.route === 'orders' ? '我的订单' : this.route === 'shop' ? '商户详情' : '附近的美好，值得常来') + ' · 邻享生活';
        window.scrollTo({ top: 0, behavior: 'instant' });
      },
      initializeHome: async function () {
        this.shopError = '';
        this.shopsLoading = true;
        try {
          var types = await this.api('/shop-type/list');
          this.types = Array.isArray(types) ? types : [];
          if (!this.types.length) { this.shops = []; return; }
          var requested = new URLSearchParams(window.location.hash.split('?')[1] || '').get('type');
          var existing = this.types.some(function (type) { return String(type.id) === String(requested); });
          await this.selectType(existing ? requested : (this.selectedType || this.types[0].id));
        } catch (error) { this.shopError = error.message; }
        finally { this.shopsLoading = false; }
      },
      selectType: async function (id) {
        this.selectedType = String(id);
        this.homeVersion += 1;
        this.shops = []; this.currentPage = 0; this.hasMore = true;
        this.shopError = ''; this.search = ''; this.featuredOffers = []; this.offerError = '';
        // 分类切换可能发生在上一个请求未返回时；版本号防止旧响应覆盖新分类。
        this.shopsLoading = false;
        await this.loadMore();
      },
      loadMore: async function () {
        if (this.shopsLoading || !this.selectedType || !this.hasMore) return;
        var version = this.homeVersion;
        var page = this.currentPage + 1;
        this.shopsLoading = true;
        try {
          var data = await this.api('/shop/of/type?typeId=' + encodeURIComponent(this.selectedType) + '&current=' + page);
          if (version !== this.homeVersion) return;
          var rows = Array.isArray(data) ? data : [];
          var previousIds = new Set(this.shops.map(function (s) { return String(s.id); }));
          this.shops = this.shops.concat(rows.filter(function (s) { return !previousIds.has(String(s.id)); }));
          this.currentPage = page;
          this.hasMore = rows.length > 0;
          this.loadFeatured(version);
        } catch (error) {
          if (version !== this.homeVersion) return;
          if (this.shops.length) this.showNotice(error.message, 'error');
          else this.shopError = error.message;
        } finally { if (version === this.homeVersion) this.shopsLoading = false; }
      },
      loadFeatured: async function (version) {
        this.offersLoading = true;
        this.offerError = '';
        var targets = this.shops.slice(0, 6);
        var results = await Promise.allSettled(targets.map(async function (shop) {
          var data = await this.api('/voucher/list/' + encodeURIComponent(shop.id));
          return (Array.isArray(data) ? data : []).map(function (v) { return Object.assign({}, v, { shopName: shop.name, shopId: shop.id }); });
        }.bind(this)));
        if (version !== this.homeVersion) return;
        var offers = [];
        results.forEach(function (result) { if (result.status === 'fulfilled') offers = offers.concat(result.value); });
        this.rememberVouchers(offers);
        this.featuredOffers = offers.filter(function (v) { return !v.endTime || dateValue(v.endTime) > this.now; }.bind(this)).slice(0, 4);
        if (results.length && results.every(function (r) { return r.status === 'rejected'; })) this.offerError = '优惠券暂时加载失败，请进入商户详情重试。';
        this.offersLoading = false;
      },
      loadShop: async function (id) {
        var version = ++this.detailVersion;
        this.detailLoading = true; this.detailError = ''; this.shopDetail = null;
        this.loadVouchers(id);
        try {
          var shop = await this.api('/shop/' + encodeURIComponent(id));
          if (version !== this.detailVersion) return;
          if (!shop) throw new Error('这家商户不存在或已下架。');
          this.shopDetail = shop;
          document.title = shop.name + ' · 邻享生活';
        } catch (error) { if (version === this.detailVersion) this.detailError = error.message; }
        finally { if (version === this.detailVersion) this.detailLoading = false; }
      },
      loadVouchers: async function (id) {
        this.vouchersLoading = true; this.voucherError = ''; this.vouchers = [];
        try {
          var rows = await this.api('/voucher/list/' + encodeURIComponent(id));
          if (String(id) !== this.routeId) return;
          this.vouchers = Array.isArray(rows) ? rows : [];
          this.rememberVouchers(this.vouchers);
        } catch (error) { if (String(id) === this.routeId) this.voucherError = error.message; }
        finally { if (String(id) === this.routeId) this.vouchersLoading = false; }
      },
      rememberVouchers: function (rows) {
        rows.forEach(function (voucher) { this.$set(this.voucherNames, String(voucher.id), voucher.title); }.bind(this));
      },
      voucherTitle: function (id) { return this.voucherNames[String(id)] || (this.pendingOrder && String(this.pendingOrder.voucherId) === String(id) && this.pendingOrder.title) || '优惠券 #' + id; },
      typeName: function (id) { var type = this.types.find(function (t) { return String(t.id) === String(id); }); return type ? type.name : ''; },
      shopImage: function (shop) {
        var image = Array.isArray(shop.images) ? shop.images[0] : String(shop.images || '').split(',')[0];
        return /^(\/[^/]|https?:\/\/)/i.test(image) ? image : PLACEHOLDER;
      },
      imageFallback: function (event) { if (event.target.getAttribute('src') !== PLACEHOLDER) event.target.src = PLACEHOLDER; },
      money: function (value) { return value == null || !Number.isFinite(Number(value)) ? '—' : (Number(value) / 100).toFixed(2); },
      score: function (value) { return (Number(value) / 10).toFixed(1); },
      formatDate: function (value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '以商户公布为准'; },
      isExpired: function (value) { return !!value && dateValue(value) <= this.now; },
      timeLeft: function (value) {
        var milliseconds = dateValue(value) - this.now;
        if (!Number.isFinite(milliseconds)) return '请以订单有效期为准';
        if (milliseconds <= 0) return '支付期限已过，等待确认关闭状态';
        var seconds = Math.ceil(milliseconds / 1000);
        return '请在 ' + Math.floor(seconds / 60) + ' 分 ' + String(seconds % 60).padStart(2, '0') + ' 秒内完成模拟支付';
      },
      canBuy: function (v) {
        // 券展示可能命中缓存；最终库存和活动资格始终由提交接口判断。
        return Number(v.type) === 1 && (!v.beginTime || dateValue(v.beginTime) <= this.now) && (!v.endTime || dateValue(v.endTime) > this.now);
      },
      voucherActionText: function (v) {
        if (Number(v.type) !== 1) return '暂未开放购买';
        if (v.beginTime && dateValue(v.beginTime) > this.now) return '活动尚未开始';
        if (v.endTime && dateValue(v.endTime) <= this.now) return '活动已结束';
        return this.token ? '立即抢购' : '登录后抢购';
      },
      buyVoucher: async function (voucher) {
        if (this.buyingId || !this.canBuy(voucher)) return;
        if (!this.token) { this.openLogin(); return; }
        if (this.pendingOrder) { this.openPending(); return; }
        this.buyingId = voucher.id;
        // 同一次抢购因断网而手动重试时复用请求标识，让后端识别同一意图。
        var intentKey = 'linxiang.intent.' + this.phone + '.' + voucher.id;
        var intent = readStored(intentKey) || requestId();
        writeStored(intentKey, intent);
        try {
          var id = safeId(await this.api('/voucher-order/seckill/' + encodeURIComponent(voucher.id), { method: 'POST', requestId: intent }));
          this.pendingOrder = { id: id, voucherId: String(voucher.id), title: voucher.title };
          this.savePending();
          writeStored(intentKey, null);
          this.openPending();
        } catch (error) {
          if (!error.uncertain) writeStored(intentKey, null);
          this.showNotice(error.message, 'error');
        }
        finally { this.buyingId = ''; }
      },
      savePending: function () { writeStored(PENDING_KEY + '.' + this.phone, this.pendingOrder); },
      openPending: function () {
        if (!this.pendingOrder) return;
        this._focusReturn = document.activeElement;
        this.resultOrder = Object.assign({ requestStatus: 'PREPARING' }, this.pendingOrder);
        this.resultVisible = true; this.resultError = ''; this.pollExpired = false; this.pollCount = 0;
        this.$nextTick(function () { this.focusDialog('resultDialog'); });
        this.refreshResult(true);
      },
      refreshResult: async function (autoPoll) {
        if (!this.resultOrder || this.resultLoading) return;
        clearTimeout(this._pollTimer);
        var id = this.resultOrder.id;
        this.resultLoading = true; this.resultError = '';
        try {
          var result = await this.api('/voucher-order/result/' + encodeURIComponent(id));
          if (!result || safeId(result.id) !== id) throw new Error('订单结果暂未就绪，请稍后手动查询。');
          if (!this.resultOrder || this.resultOrder.id !== id) return;
          this.resultOrder = Object.assign({}, this.resultOrder, result);
          if (this.resultKind !== 'pending') {
            if (this.pendingOrder && this.pendingOrder.id === id) { this.pendingOrder = null; this.savePending(); }
            if (this.route === 'orders') this.loadOrders();
          } else if (autoPoll && this.resultVisible) {
            this.pollCount += 1;
            if (this.pollCount < 15) this._pollTimer = setTimeout(function () { this.refreshResult(true); }.bind(this), 2000);
            else this.pollExpired = true;
          }
        } catch (error) { this.resultError = error.message; }
        finally { this.resultLoading = false; }
      },
      closeResult: function () {
        clearTimeout(this._pollTimer);
        var wasVisible = this.resultVisible;
        this.resultVisible = false;
        if (wasVisible) this.restoreFocus();
      },
      goToOrders: function () { this.closeResult(); window.location.hash = '/orders'; if (this.route === 'orders' && this.token) this.loadOrders(); },
      loadOrders: async function () {
        if (!this.token || this.ordersLoading) return;
        var token = this.token;
        this.ordersLoading = true; this.ordersError = '';
        try {
          var rows = await this.api('/voucher-order/my');
          if (token !== this.token) return;
          this.orders = (Array.isArray(rows) ? rows : []).map(function (row) { return Object.assign({}, row, { id: safeId(row.id) }); });
          // 已在订单列表看到的请求不再显示“处理中”，但仍以订单真实状态为准。
          if (this.pendingOrder && this.orders.some(function (o) { return o.id === this.pendingOrder.id; }.bind(this))) { this.pendingOrder = null; this.savePending(); }
        } catch (error) { if (token === this.token) this.ordersError = error.message; }
        finally { this.ordersLoading = false; }
      },
      refreshOrders: function () { this.loadOrders(); },
      orderStatusText: function (status) { return { 1: '待支付', 2: '已支付', 3: '已核销', 4: '已取消', 5: '退款中', 6: '已退款' }[Number(status)] || '状态待确认'; },
      payOrder: async function (order) {
        if (this.payingId || Number(order.status) !== 1 || this.isExpired(order.expireTime)) return;
        this.payingId = order.id;
        try {
          await this.api('/voucher-order/' + encodeURIComponent(order.id) + '/pay', { method: 'POST' });
          this.showNotice('模拟支付已完成，可在我的订单查看最新状态。', 'success');
          await this.loadOrders();
          if (this.resultOrder && this.resultOrder.id === String(order.id)) await this.refreshResult(false);
        } catch (error) {
          this.showNotice(error.message, 'error');
          if (this.resultVisible) this.resultError = error.message;
          // 支付与关单可能竞争，失败后也刷新展示，避免继续显示旧的可支付状态。
          await this.loadOrders();
          if (this.resultOrder && this.resultOrder.id === String(order.id)) await this.refreshResult(false);
        } finally { this.payingId = ''; }
      },
      openLogin: function () {
        this._focusReturn = document.activeElement;
        this.loginVisible = true; this.loginError = '';
        this.$nextTick(function () { if (this.$refs.phoneInput) this.$refs.phoneInput.focus(); });
      },
      closeLogin: function () { this.loginVisible = false; this.restoreFocus(); },
      login: async function () {
        if (this.loginLoading) return;
        if (!/^1\d{10}$/.test(this.phone)) { this.loginError = '请输入 11 位演示手机号。'; return; }
        this.loginLoading = true; this.loginError = '';
        try {
          var token = await this.api('/demo/login', { method: 'POST', body: { phone: this.phone } });
          if (typeof token !== 'string' || !token) throw new Error('登录返回格式异常，请重试。');
          this.token = token;
          writeStored(STORAGE_KEY, { token: token, phone: this.phone });
          this.pendingOrder = readStored(PENDING_KEY + '.' + this.phone);
          this.closeLogin();
          this.showNotice('已登录，可以开始发现附近优惠了。', 'success');
          if (this.route === 'orders') this.loadOrders();
        } catch (error) { this.loginError = error.message; }
        finally { this.loginLoading = false; }
      },
      logout: async function () {
        if (this.logoutLoading) return;
        this.logoutLoading = true;
        var confirmed = false;
        try {
          // 1. 请后端注销当前凭证，避免只清页面而使原 token 仍然有效。
          await this.api('/user/logout', { method: 'POST' });
          confirmed = true;
        } catch (error) {
          // 2. 401 表示凭证已经失效；其他错误只能确认本机退出。
          confirmed = error.status === 401;
        } finally {
          // 3. 无论网络是否正常，都清理本地会话和旧订单展示。
          this.token = ''; this.orders = []; this.pendingOrder = null;
          this.closeResult(); this.loginVisible = false; writeStored(STORAGE_KEY, null);
          this.logoutLoading = false;
          this.showNotice(confirmed ? '已退出当前演示账号。' : '已在本机退出；服务端会话注销尚未确认。', confirmed ? 'success' : 'error');
        }
      },
      showNotice: function (text, kind) { this.notice = { text: text, kind: kind || 'success' }; },
      scrollToShops: function () { var target = document.getElementById('shops'); if (target) target.scrollIntoView({ behavior: 'smooth' }); },
      updateModalLock: function () { document.body.classList.toggle('modal-open', this.loginVisible || this.resultVisible); },
      focusDialog: function (name) { var dialog = this.$refs[name]; if (dialog) { var target = dialog.querySelector('button, input'); if (target) target.focus(); } },
      restoreFocus: function () { var target = this._focusReturn; if (target && document.contains(target)) this.$nextTick(function () { target.focus(); }); },
      trapFocus: function (event) {
        var dialog = this.loginVisible ? this.$refs.loginDialog : this.$refs.resultDialog;
        if (!dialog) return;
        var focusables = dialog.querySelectorAll('button:not(:disabled), input:not(:disabled), a[href]');
        if (!focusables.length) return;
        var first = focusables[0]; var last = focusables[focusables.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
      }
    }
  });
}());
