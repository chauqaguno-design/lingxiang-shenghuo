package com.linxiang.config;
import com.linxiang.entity.SeckillVoucher;
import com.linxiang.service.ISeckillVoucherService;
import com.linxiang.service.cache.VoucherActivityCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
/** 只为从未发生请求的演示活动预热；已有业务数据时不能把 DB 余量直接覆盖 Redis。 */
@Slf4j
@Component
@ConditionalOnProperty(name="app.demo-enabled",havingValue="true")
public class DemoActivityInitializer implements ApplicationRunner {
    private final ISeckillVoucherService service;
    private final VoucherActivityCache cache;
    private final JdbcTemplate db;
    public DemoActivityInitializer(ISeckillVoucherService service,VoucherActivityCache cache,JdbcTemplate db) {
        this.service=service;this.cache=cache;this.db=db;
    }
    @Override public void run(ApplicationArguments args) {
        for(SeckillVoucher activity:service.list()) {
            Integer requests=db.queryForObject("SELECT COUNT(*) FROM seckill_request WHERE voucher_id=?",Integer.class,activity.getVoucherId());
            if(requests!=null && requests==0) cache.initializeNewActivity(activity);
        }
    }
}
