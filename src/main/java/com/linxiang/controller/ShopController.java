package com.linxiang.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linxiang.dto.Result;
import com.linxiang.entity.Shop;
import com.linxiang.service.IShopService;
import com.linxiang.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    public IShopService shopService;

    /**
     * 查询商户详情：GET /shop/{id}，例如 /shop/1 查询 id 为 1 的商户。
     * {@code @PathVariable} 将 URL 路径里的 id 绑定为 Long 参数；Controller 负责接收请求，
     * 缓存判断和数据库回源交给 Service，最终的 Result 由 Spring MVC 转成 JSON 返回。
     *
     * @param id URL 路径中的商户主键
     * @return 商户详情或不存在时的失败提示
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
        // 调用带缓存的业务查询；不能换成直接 getById，否则会绕过商户详情的缓存策略。
        return shopService.queryById(id);
    }

    /**
     * 新增商铺信息
     * @param shop 商铺数据
     * @return 商铺id
     */
    @PostMapping
    public Result saveShop(@RequestBody Shop shop) {
        // 写入数据库
        shopService.save(shop);
        // 返回店铺id
        return Result.ok(shop.getId());
    }

    /**
     * 更新商户信息：PUT /shop，请求体使用 JSON 提交，并携带目标商户的 id。
     * {@code @RequestBody} 将 JSON 转成 Shop；Service 负责校验 id、执行数据库更新和删除旧缓存。
     *
     * @param shop 请求体中的商户数据
     * @return 统一的成功或失败响应
     */
    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        // 使用业务 update，保证走到更新后的缓存失效流程；Controller 无须自行操作 Redis。
        return shopService.update(shop);
    }

    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        Page<Shop> page = shopService.query()
                .eq("type_id", typeId)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }

    /**
     * 根据商铺名称关键字分页查询商铺信息
     * @param name 商铺名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
}
