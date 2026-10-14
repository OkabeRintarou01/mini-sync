package com.minisync.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.minisync.server.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper。
 *
 * <p>继承 {@code BaseMapper} 即可获得单表 CRUD 能力，
 * 简单查询不必手写 SQL。</p>
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
