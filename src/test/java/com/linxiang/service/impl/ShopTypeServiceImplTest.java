package com.linxiang.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linxiang.dto.Result;
import com.linxiang.entity.ShopType;
import com.linxiang.utils.RedisConstants;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopTypeServiceImplTest {
    private ShopTypeServiceImpl service;
    private ValueOperations<String, String> values;

    @BeforeAll
    static void initializeTableMetadata() {
        // 单元测试未启动 MyBatis，手动注册 Lambda 查询需要的字段映射。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ShopType.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service = spy(new ShopTypeServiceImpl());
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
    }

    @Test
    void cacheHitReturnsTypesWithoutQueryingDatabaseOrRefreshingTtl() {
        List<ShopType> types = sampleTypes();
        when(values.get(RedisConstants.CACHE_SHOP_TYPE_LIST)).thenReturn(JSONUtil.toJsonStr(types));

        Result result = service.queryTypeList();

        assertTrue(result.getSuccess());
        assertEquals(types, result.getData());
        verify(service, never()).list(any(LambdaQueryWrapper.class));
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void cacheMissStoresDatabaseResultSoTheNextRequestHitsCache() {
        List<ShopType> types = sampleTypes();
        doReturn(types).when(service).list(any(LambdaQueryWrapper.class));
        AtomicReference<String> cached = new AtomicReference<>();
        when(values.get(RedisConstants.CACHE_SHOP_TYPE_LIST)).thenAnswer(invocation -> cached.get());
        doAnswer(invocation -> {
            cached.set(invocation.getArgument(1));
            return null;
        }).when(values).set(eq(RedisConstants.CACHE_SHOP_TYPE_LIST), anyString(),
                eq(RedisConstants.CACHE_SHOP_TYPE_TTL.longValue()), eq(TimeUnit.MINUTES));

        Result first = service.queryTypeList();
        assertNotNull(cached.get());
        assertEquals(types, JSONUtil.toList(cached.get(), ShopType.class));
        Result second = service.queryTypeList();

        assertTrue(first.getSuccess());
        assertTrue(second.getSuccess());
        assertEquals(first.getData(), second.getData());
        verify(service, times(1)).list(any(LambdaQueryWrapper.class));
        verify(values, times(1)).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void emptyDatabaseResultIsCachedAsAnEmptyListWithAShortTtl() {
        doReturn(Collections.emptyList()).when(service).list(any(LambdaQueryWrapper.class));
        when(values.get(RedisConstants.CACHE_SHOP_TYPE_LIST)).thenReturn(null, "[]");

        Result first = service.queryTypeList();
        Result second = service.queryTypeList();

        assertTrue(first.getSuccess());
        assertEquals(Collections.emptyList(), first.getData());
        assertEquals(first.getData(), second.getData());
        verify(values).set(RedisConstants.CACHE_SHOP_TYPE_LIST, "[]",
                RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
        verify(service, times(1)).list(any(LambdaQueryWrapper.class));
    }

    private List<ShopType> sampleTypes() {
        return Arrays.asList(
                new ShopType().setId(1L).setName("美食").setIcon("/types/ms.png").setSort(1),
                new ShopType().setId(2L).setName("KTV").setIcon("/types/ktv.png").setSort(2));
    }
}
