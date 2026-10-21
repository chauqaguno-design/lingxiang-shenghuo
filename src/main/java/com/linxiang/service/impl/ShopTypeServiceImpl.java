package com.linxiang.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linxiang.dto.Result;
import com.linxiang.entity.ShopType;
import com.linxiang.mapper.ShopTypeMapper;
import com.linxiang.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.linxiang.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 1. 先查 Redis，命中后将 JSON 数组还原成店铺类型列表。
        String typeListJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_LIST);
        if (StrUtil.isNotBlank(typeListJson)) {
            List<ShopType> shopTypes = JSONUtil.toList(typeListJson, ShopType.class);
            return Result.ok(shopTypes);
        }

        // 2. 缓存未命中，按首页显示顺序查询数据库。
        List<ShopType> shopTypes = this.list(new LambdaQueryWrapper<ShopType>()
                .orderByAsc(ShopType::getSort));

        // 3. 缓存刚查到的列表；无数据时缓存 []，短时间内避免重复查库。
        long ttl = shopTypes.isEmpty() ? RedisConstants.CACHE_NULL_TTL : RedisConstants.CACHE_SHOP_TYPE_TTL;
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE_LIST,
                JSONUtil.toJsonStr(shopTypes), ttl, TimeUnit.MINUTES);
        return Result.ok(shopTypes);
    }

}
