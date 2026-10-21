package com.linxiang.service.impl;
import com.linxiang.dto.Result;
import com.linxiang.order.OrderLifecycle;
import com.linxiang.service.IVoucherOrderService;
import com.linxiang.utils.UserHolder;
import org.springframework.stereotype.Service;
@Service
public class VoucherOrderServiceImpl implements IVoucherOrderService {
    private final OrderLifecycle orders;
    public VoucherOrderServiceImpl(OrderLifecycle orders) { this.orders=orders; }
    public Result seckillVoucher(Long voucherId) {
        return orders.submit(UserHolder.getUser().getId(),voucherId,null);
    }
}
