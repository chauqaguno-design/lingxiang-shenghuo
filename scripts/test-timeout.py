"""真实订单超时验收。仅连接本机 demo；先以 TTL=6、delayLevel=2 启动应用。
对本脚本创建的一张演示券订单，重投一次 CLOSE 消息验证幂等，不删除数据库数据。
"""
import json
import shutil
import subprocess
import time
import urllib.request
from datetime import datetime
from pathlib import Path

BASE = 'http://127.0.0.1:18081'
DOCKER = shutil.which('docker')
if not DOCKER:
    raise SystemExit('Please add Docker CLI to PATH')


def sql(query):
    command = ['exec', 'linxiang-mysql', 'sh', '-c',
               'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" -D"$MYSQL_DATABASE" -N -B -e "$1"',
               'sh', query]
    return subprocess.check_output([DOCKER] + command).decode('utf-8').strip()


def redis_stock():
    return int(subprocess.check_output([DOCKER, 'exec', 'linxiang-redis', 'redis-cli', 'GET', 'seckill:stock:106']))


def call(path, token=None, body=None, post=False):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['authorization'] = token
    data = json.dumps(body or {}).encode() if post else None
    with urllib.request.urlopen(urllib.request.Request(BASE + path, data=data, headers=headers), timeout=5) as response:
        return json.load(response)


before_db = int(sql('SELECT stock FROM tb_seckill_voucher WHERE voucher_id=106'))
before_redis = redis_stock()
assert before_db == before_redis
phone = '139' + str(time.time_ns())[-8:]
token = call('/demo/login', body={'phone': phone}, post=True)['data']
submitted = call('/voucher-order/seckill/106', token, post=True)
assert submitted['success'], submitted
order_id = submitted['data']
assert order_id.isdigit()
started = time.monotonic()
seen = []
while time.monotonic() - started < 25:
    result = call('/voucher-order/result/' + order_id, token)['data']
    state = (result['requestStatus'], result.get('orderStatus'))
    if state not in seen:
        seen.append(state)
    if state == ('SUCCEEDED', 4):
        break
    time.sleep(0.25)
else:
    raise AssertionError('Order did not close within 25 seconds; check TTL=6 configuration')

# 1. 等待 Redis 回补事件被消费。
for _ in range(40):
    if redis_stock() == before_redis:
        break
    time.sleep(0.25)
assert redis_stock() == before_redis
assert int(sql('SELECT stock FROM tb_seckill_voucher WHERE voucher_id=106')) == before_db
assert not call('/voucher-order/' + order_id + '/pay', token, post=True)['success']

# 2. 将自己的 CLOSE 事件重新排队，实际走一次 RocketMQ 延迟投递。
sql("UPDATE message_outbox SET status='NEW',next_retry_at=NOW() WHERE order_id=" + order_id + " AND event_type='CLOSE'")
time.sleep(8)
assert int(sql('SELECT status FROM tb_voucher_order WHERE id=' + order_id)) == 4
assert redis_stock() == before_redis
assert int(sql('SELECT stock FROM tb_seckill_voucher WHERE voucher_id=106')) == before_db
assert int(sql("SELECT COUNT(*) FROM message_outbox WHERE order_id=" + order_id + " AND event_type='RELEASE_CANCELLED'")) == 1
assert sql("SELECT status FROM message_outbox WHERE order_id=" + order_id + " AND event_type='CLOSE'") == 'SENT'
report = {'capturedAt': datetime.now().astimezone().isoformat(), 'orderId': order_id,
          'voucherId': 106, 'ttlSeconds': 6, 'delayLevel': 2, 'observedStates': seen,
          'databaseStockBeforeAfter': [before_db, int(sql('SELECT stock FROM tb_seckill_voucher WHERE voucher_id=106'))],
          'redisStockBeforeAfter': [before_redis, redis_stock()], 'cancelReleaseEvents': 1,
          'expiredPaymentRejected': True, 'duplicateCloseRelayed': True,
          'result': 'passed', 'note': '延迟消息早于6秒截止时间时由扫描补关；重投关闭事件后库存未重复回补。'}
output = Path(__file__).resolve().parents[1] / ('loadtest/evidence/' + datetime.now().strftime('%Y%m%d') + '-timeout.json')
output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=True))
