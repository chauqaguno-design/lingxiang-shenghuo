package com.linxiang.service;

import com.linxiang.dto.Result;
import com.linxiang.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 商户业务接口：Controller 依赖接口调用业务，具体流程由 ShopServiceImpl 实现。
 * 继承 MyBatis-Plus 的 IService 后，可以使用 getById、save、updateById 等基础数据库方法。
 * 下面额外声明 queryById 和 update，用于在基础操作之外加入缓存逻辑，并返回统一的 Result。
 *
 */
public interface IShopService extends IService<Shop> {

    /**
     * 按商户 id 查询详情，包含缓存查询、必要时的数据库回源，以及不存在时的失败响应。
     * 注意：queryById 是本项目的业务方法；继承的 getById 是基础查库方法，职责不同。
     *
     * @param id 商户主键
     * @return 包含商户详情或失败提示的统一结果
     */
    Result queryById(Long id);

    /**
     * 更新商户，并在执行数据库更新后删除对应缓存；业务流程在实现类中统一维护。
     * Controller 调用此方法，避免只调用基础 updateById 后遗漏缓存失效处理。
     *
     * @param shop 要更新的商户数据，必须携带 id
     * @return 更新流程的统一结果
     */
    Result update(Shop shop);
}
