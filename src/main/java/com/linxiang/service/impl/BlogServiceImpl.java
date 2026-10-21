package com.linxiang.service.impl;

import com.linxiang.entity.Blog;
import com.linxiang.mapper.BlogMapper;
import com.linxiang.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

}
