package com.minisync.server.controller;

import com.minisync.server.common.R;
import com.minisync.server.service.DriverRegistryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 驱动类型查询接口。
 *
 * <p><b>路径必须与前端一致</b>：前端 {@code index.html} 的数据源类型下拉是
 * 通过 {@code /api/drivertype/list} 获取的。这个路径沿用了 Node 版的约定，
 * 所以这里单独建一个 Controller 而不是塞进 {@code /api/datasource} 下 ——
 * <b>让后端适配前端，而不是反过来改前端</b>，这样迁移期间前端一行都不用动。</p>
 */
@RestController
@RequestMapping("/api/drivertype")
public class DriverTypeController {

    private final DriverRegistryService driverRegistryService;

    public DriverTypeController(DriverRegistryService driverRegistryService) {
        this.driverRegistryService = driverRegistryService;
    }

    /**
     * 列出全部已注册的驱动类型。
     *
     * <p>返回 {@code [{type, display, jar}]}，前端下拉直接渲染。
     * 新增一个驱动插件放进 {@code plugins/} 目录后，这里自动多一项。</p>
     */
    @GetMapping("/list")
    public Map<String, Object> list() {
        return R.data(driverRegistryService.listTypes());
    }
}
