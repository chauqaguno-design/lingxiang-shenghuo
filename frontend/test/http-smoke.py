"""邻享生活真实 HTTP 验收，仅允许本地 demo 服务；使用券 105 和新演示账号。"""
import argparse
import json
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:18081')
    parser.add_argument('--output', default=str(Path(tempfile.gettempdir()) / 'linxiang-http-smoke.json'))
    args = parser.parse_args()
    base = args.base_url.rstrip('/')
    assert urllib.parse.urlsplit(base).hostname in {'localhost', '127.0.0.1', '::1'}, '仅允许本地演示环境'
    report = {'baseUrl': base, 'voucherId': '105', 'checks': [], 'result': 'running'}
    tokens = []

    def request(path, method='GET', token=None, data=None, request_id=None):
        headers = {'Accept': 'application/json'}
        if token:
            headers['authorization'] = token
        if request_id:
            headers['X-Request-Id'] = request_id
        body = None
        if data is not None:
            body = json.dumps(data).encode('utf-8')
            headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(base + path, data=body, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=12)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read().decode('utf-8')
            return response.status, json.loads(raw) if raw else None

    def succeeded(path, method='GET', token=None, data=None, request_id=None):
        status, body = request(path, method, token, data, request_id)
        assert status == 200, f'{path}: HTTP {status}'
        assert body and body.get('success') is True, f'{path}: {(body or {}).get("errorMsg", "响应无效")}'
        return body.get('data')

    def denied(path, token=None, method='GET'):
        status, body = request(path, method=method, token=token)
        assert status in {401, 403} or (status == 200 and body and body.get('success') is False), f'{path}: 未阻止越权访问'
        assert not body or not body.get('data'), f'{path}: 越权响应不应包含订单数据'

    try:
        # 1. 确认 demo 环境，并验证未登录访问受到保护。
        assert succeeded('/demo/status')['demo'] is True
        denied('/voucher-order/my')
        report['checks'].append('未登录订单查询被拒绝')
        suffix = str(time.time_ns() // 1_000_000)[-8:]
        phones = ['137' + suffix, '136' + suffix]
        for phone in phones:
            token = succeeded('/demo/login', 'POST', data={'phone': phone})
            assert isinstance(token, str) and token, '登录没有返回有效凭证'
            tokens.append(token)
        buyer, other = tokens
        report['phones'] = phones
        report['checks'].append('两个新演示账号完成认证')

        # 2. 提交优惠券 105；同一请求标识重试应拿到同一个订单号。
        request_id = str(uuid.uuid4())
        order_id = succeeded('/voucher-order/seckill/105', 'POST', buyer, request_id=request_id)
        assert isinstance(order_id, str) and order_id.isdigit(), '订单号必须按字符串返回'
        repeated_id = succeeded('/voucher-order/seckill/105', 'POST', buyer, request_id=request_id)
        assert repeated_id == order_id, '同一抢购请求重复提交产生了不同订单号'
        report['orderId'] = order_id
        report['checks'].append('抢券提交与请求幂等通过')

        # 3. 等待异步建单，只接受明确的成功状态。
        deadline = time.monotonic() + 60
        observed = []
        while time.monotonic() < deadline:
            result = succeeded('/voucher-order/result/' + order_id, token=buyer)
            state = result.get('requestStatus')
            if not observed or observed[-1] != state:
                observed.append(state)
            assert state != 'FAILED', f'异步建单失败：{result.get("reason")}'
            if state == 'SUCCEEDED' and int(result.get('orderStatus', 0)) == 1:
                assert result.get('expireTime'), '待支付订单缺少有效期'
                break
            time.sleep(1)
        else:
            raise AssertionError('60 秒内未确认建单成功')
        report['observedRequestStates'] = observed
        mine = succeeded('/voucher-order/my', token=buyer)
        order = next(item for item in mine if item['id'] == order_id)
        assert int(order['status']) == 1 and str(order['voucherId']) == '105'
        assert order.get('createTime') and order.get('expireTime')
        report['checks'].append('异步建单、结果查询、我的订单及时间序列化通过')

        # 4. 其他账号不能查询或支付本人的订单。
        denied('/voucher-order/result/' + order_id, other)
        denied('/voucher-order/' + order_id + '/pay', other, 'POST')
        assert all(item['id'] != order_id for item in succeeded('/voucher-order/my', token=other))
        report['checks'].append('他人结果查询与支付被拒绝，订单列表隔离')

        # 5. 模拟支付及重复支付都不应重复修改状态。
        succeeded('/voucher-order/' + order_id + '/pay', 'POST', buyer)
        succeeded('/voucher-order/' + order_id + '/pay', 'POST', buyer)
        result = succeeded('/voucher-order/result/' + order_id, token=buyer)
        assert int(result['orderStatus']) == 2
        order = next(item for item in succeeded('/voucher-order/my', token=buyer) if item['id'] == order_id)
        assert int(order['status']) == 2
        report['checks'].append('模拟支付、重复支付幂等及已支付状态通过')

        # 6. 退出后，旧 token 必须失效。
        succeeded('/user/logout', 'POST', buyer)
        status, _ = request('/voucher-order/my', token=buyer)
        assert status == 401, f'退出后旧 token 仍未被拒绝：HTTP {status}'
        report['checks'].append('服务端退出后旧 token 返回 401')
        report['result'] = 'passed'
    except Exception as error:
        report['result'] = 'failed'
        report['error'] = str(error)
    finally:
        for token in tokens:
            try:
                request('/user/logout', 'POST', token)
            except Exception:
                pass
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report['result'] == 'passed' else 1


if __name__ == '__main__':
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    raise SystemExit(main())
