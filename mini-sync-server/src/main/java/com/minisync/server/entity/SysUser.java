package com.minisync.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户（对应 {@code sys_user} 表）。
 *
 * <p>字段名用驼峰，靠 MyBatis-Plus 的
 * {@code map-underscore-to-camel-case=true} 自动映射到
 * {@code create_time} 等下划线列名。</p>
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** 当前为明文比对（演示级）。生产应存 BCrypt 哈希。 */
    private String password;

    private String nickname;

    private LocalDateTime createTime;
}
