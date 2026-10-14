package com.minisync.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * JSON 序列化配置。
 *
 * <p><b>为什么要专门配这个？</b></p>
 *
 * <p>Java 8 的 {@code LocalDateTime} 默认被 Jackson 序列化成 ISO-8601：
 * {@code "2026-10-14T01:01:16"} —— 中间带一个 {@code T}。
 * 这个格式：</p>
 * <ul>
 *   <li>前端直接显示会很难看（用户看到的是带 T 的字符串）</li>
 *   <li>和数据库里 {@code DATETIME} 的自然字面量不一致，核对时容易看错</li>
 * </ul>
 *
 * <p>阶段一在"水位落库"上踩过完全一样的坑（{@code LocalDateTime.toString()}
 * 产出带 T 的格式），所以这里一次性统一成
 * {@code "yyyy-MM-dd HH:mm:ss"}，让接口返回、日志、数据库三处格式一致。</p>
 */
@Configuration
public class JacksonConfig {

    private static final String PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(PATTERN);
            JavaTimeModule module = new JavaTimeModule();
            module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(fmt));
            module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(fmt));
            builder.modules(module);
            // 不把 null 字段消掉：前端判断"水位置空"依赖字段存在
            builder.postConfigurer((ObjectMapper om) -> om.configure(
                    com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false));
        };
    }
}
