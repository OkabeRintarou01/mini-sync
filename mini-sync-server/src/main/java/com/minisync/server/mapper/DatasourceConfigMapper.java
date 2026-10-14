package com.minisync.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.minisync.server.entity.DatasourceConfig;
import org.apache.ibatis.annotations.Mapper;

/** 数据源配置 Mapper。 */
@Mapper
public interface DatasourceConfigMapper extends BaseMapper<DatasourceConfig> {
}
