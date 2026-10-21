package com.lingxiangshenghuo.utils;

import com.lingxiangshenghuo.entity.Shop;
import com.lingxiangshenghuo.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ShopBloomFilterTest {

    private RedissonClient redisson;
    private RBloomFilter<Long> filter;
    private ShopMapper mapper;
    private ShopBloomFilter shopBloom;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisson = mock(RedissonClient.class);
        filter = mock(RBloomFilter.class);
        mapper = mock(ShopMapper.class);
        when(redisson.<Long>getBloomFilter(ShopBloomFilter.SHOP_BLOOM_KEY)).thenReturn(filter);
        shopBloom = new ShopBloomFilter(redisson, mapper);
    }

    @Test
    void queriesPassThroughBeforeInitialization() {
        assertTrue(shopBloom.mightContain(1L));
        verifyNoInteractions(filter, mapper);
    }

    @Test
    void initializationFailureKeepsQueriesPassingThrough() {
        when(filter.tryInit(anyLong(), anyDouble())).thenThrow(new IllegalStateException("Redis unavailable"));

        assertDoesNotThrow(() -> shopBloom.run(null));

        assertTrue(shopBloom.mightContain(1L));
        verifyNoInteractions(mapper);
        verify(filter, never()).contains(anyLong());
    }

    @Test
    void initializationImportsExistingIdsAndEnablesFiltering() {
        Shop first = new Shop();
        first.setId(1L);
        Shop second = new Shop();
        second.setId(2L);
        when(mapper.selectList(any())).thenReturn(Arrays.asList(first, second));
        when(filter.isExists()).thenReturn(true);
        when(filter.contains(1L)).thenReturn(true);
        when(filter.contains(999L)).thenReturn(false);

        shopBloom.run(null);

        verify(filter).add(1L);
        verify(filter).add(2L);
        assertTrue(shopBloom.mightContain(1L));
        assertFalse(shopBloom.mightContain(999L));
    }

    @Test
    void containsFailureDisablesFilteringUntilReinitialization() {
        initializeEmptyFilter();
        when(filter.contains(1L)).thenThrow(new IllegalStateException("Redis unavailable"));

        assertTrue(shopBloom.mightContain(1L));
        assertTrue(shopBloom.mightContain(2L));

        verify(filter, never()).contains(2L);
    }

    @Test
    void missingBitmapDoesNotRejectExistingShops() {
        initializeEmptyFilter();
        when(filter.isExists()).thenReturn(false);

        assertTrue(shopBloom.mightContain(1L));
        assertTrue(shopBloom.mightContain(2L));

        verify(filter, never()).contains(anyLong());
    }

    private void initializeEmptyFilter() {
        when(mapper.selectList(any())).thenReturn(Collections.emptyList());
        when(filter.isExists()).thenReturn(true);
        shopBloom.run(null);
    }
}
