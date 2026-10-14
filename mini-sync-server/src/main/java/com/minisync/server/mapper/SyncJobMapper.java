package com.minisync.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.minisync.server.entity.SyncJob;
import org.apache.ibatis.annotations.Mapper;

/** 同步任务 Mapper。 */
@Mapper
public interface SyncJobMapper extends BaseMapper<SyncJob> {
}
