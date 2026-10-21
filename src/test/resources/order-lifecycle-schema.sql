CREATE TABLE tb_seckill_voucher (
    voucher_id BIGINT PRIMARY KEY,
    stock INT NOT NULL CHECK (stock >= 0)
);

CREATE TABLE seckill_request (
    order_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    voucher_id BIGINT NOT NULL,
    client_request_id VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_request_client UNIQUE(user_id, client_request_id)
);

CREATE TABLE tb_voucher_order (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    voucher_id BIGINT NOT NULL,
    pay_type INT NOT NULL,
    status INT NOT NULL,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL,
    expire_time TIMESTAMP NOT NULL,
    pay_time TIMESTAMP,
    CONSTRAINT uq_user_voucher UNIQUE(user_id, voucher_id)
);

CREATE TABLE message_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INT NOT NULL,
    next_retry_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_order_event UNIQUE(order_id, event_type)
);
