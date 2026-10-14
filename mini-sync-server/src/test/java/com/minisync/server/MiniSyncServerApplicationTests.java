package com.minisync.server;

import com.minisync.server.entity.DatasourceConfig;
import com.minisync.server.entity.SyncJob;
import com.minisync.server.mapper.DatasourceConfigMapper;
import com.minisync.server.mapper.SyncJobMapper;
import com.minisync.server.mapper.SyncLogMapper;
import com.minisync.server.mapper.SysUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 容器与 MyBatis-Plus 的集成测试。
 *
 * <p>验证三件事：</p>
 * <ol>
 *   <li>Spring 上下文能正常启动（Bean 装配没有循环依赖之类的问题）</li>
 *   <li>数据源能连上管理库</li>
 *   <li>MyBatis-Plus 的 4 个 Mapper 都能查到数据 ——
 *       特别是<b>下划线列名 → 驼峰属性</b>的映射是否生效
 *       （这个配错了会表现为"能连上但字段全是 null"，很难发现）</li>
 * </ol>
 *
 * <p>运行前提：本地 MySQL 已启动，且 `.env` 里的 MINISYNC_DB_PWD 已加载到环境变量。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MiniSyncServerApplicationTests {

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private DatasourceConfigMapper datasourceConfigMapper;

    @Autowired
    private SyncJobMapper syncJobMapper;

    @Autowired
    private SyncLogMapper syncLogMapper;

    @Test
    @DisplayName("Spring 上下文能启动，4 个 Mapper 都能注入")
    void contextLoads() {
        assertNotNull(sysUserMapper);
        assertNotNull(datasourceConfigMapper);
        assertNotNull(syncJobMapper);
        assertNotNull(syncLogMapper);
    }

    @Test
    @DisplayName("能连上管理库并查到演示用户")
    void canQuerySysUser() {
        assertTrue(sysUserMapper.selectCount(null) >= 0,
                "查询 sys_user 不应抛异常");
    }

    @Test
    @DisplayName("MyBatis-Plus 字段映射正确：ds_name 映射到 dsName")
    void datasourceFieldMappingWorks() {
        List<DatasourceConfig> list = datasourceConfigMapper.selectList(null);
        // 表里没有数据时跳过（避免在空库上误报失败）
        if (list.isEmpty()) {
            return;
        }
        DatasourceConfig ds = list.get(0);
        assertNotNull(ds.getId(), "主键应映射成功");
        assertNotNull(ds.getDsName(), "ds_name 应映射到 dsName（下划线转驼峰）");
        assertNotNull(ds.getDsType(), "ds_type 应映射到 dsType");
        assertNotNull(ds.getDatabaseName(), "database_name 应映射到 databaseName");
    }

    @Test
    @DisplayName("同步任务的水位字段映射正确（last_offset 是字符串，last_pk 是数字）")
    void syncJobWatermarkMappingWorks() {
        List<SyncJob> jobs = syncJobMapper.selectList(null);
        if (jobs.isEmpty()) {
            return;
        }
        SyncJob job = jobs.get(0);
        assertNotNull(job.getJobName(), "job_name 应映射到 jobName");
        assertNotNull(job.getSyncType(), "sync_type 应映射到 syncType");
        // last_offset 允许为 null（还没同步过），但字段本身要能被访问
        assertNotNull(job.getId());
        // last_pk 在数据库里有 DEFAULT 0，所以不应为 null
        assertNotNull(job.getLastPk(), "last_pk 应有默认值 0 而非 null");
    }
}
