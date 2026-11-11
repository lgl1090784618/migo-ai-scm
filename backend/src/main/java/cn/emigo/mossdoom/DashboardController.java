package cn.emigo.mossdoom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 看板布局与模板：每个登录用户按页面保存自己的卡片布局，互不影响。 */
@RestController
@RequestMapping("/api/dashboards")
class DashboardController {
    private static final int MAX_CARDS = 60;
    private final JdbcTemplate db;
    private final AuditService audit;
    DashboardController(JdbcTemplate db, AuditService audit) { this.db = db; this.audit = audit; }

    @GetMapping("/{page}")
    Object templates(@PathVariable String page) {
        String owner = RequestContext.actor(), safe = page(page);
        List<Map<String, Object>> templates = new ArrayList<>();
        for (var row : db.queryForList("SELECT id,name,layout::text AS layout,updated_at FROM dashboard_layout WHERE tenant_id=? AND owner=? AND page=? ORDER BY updated_at DESC", DomainSupport.TENANT, owner, safe)) {
            templates.add(Map.of("name", row.get("name"), "layout", DomainSupport.parse((String) row.get("layout")), "updatedAt", String.valueOf(row.get("updated_at"))));
        }
        return Map.of("page", safe, "owner", owner, "templates", templates);
    }

    @PutMapping("/{page}")
    @PreAuthorize("hasAnyRole('ADMIN','VIEWER')")
    Object save(@PathVariable String page, @RequestBody JsonNode body) {
        String owner = RequestContext.actor(), safe = page(page);
        String name = DomainSupport.required(body, "name", 120).trim();
        if (name.isEmpty() || name.length() > 60) throw DomainSupport.invalid("模板名称需为 1 至 60 个字符");
        ArrayNode layout = normalize(body.path("layout"));
        UUID id = UUID.randomUUID();
        String json = DomainSupport.json(layout);
        db.update("INSERT INTO dashboard_layout(id,tenant_id,owner,page,name,layout) VALUES (?,?,?,?,?,?::jsonb) "
            + "ON CONFLICT (tenant_id,owner,page,name) DO UPDATE SET layout=EXCLUDED.layout, updated_at=now()",
            id, DomainSupport.TENANT, owner, safe, name, json);
        audit.record("DASHBOARD_SAVE", "dashboard", id, Map.of("page", safe, "name", name, "cards", layout.size()));
        return Map.of("ok", true, "page", safe, "name", name, "cards", layout.size());
    }

    @DeleteMapping("/{page}/{name}")
    @PreAuthorize("hasAnyRole('ADMIN','VIEWER')")
    Object remove(@PathVariable String page, @PathVariable String name) {
        String owner = RequestContext.actor(), safe = page(page);
        int removed = db.update("DELETE FROM dashboard_layout WHERE tenant_id=? AND owner=? AND page=? AND name=?", DomainSupport.TENANT, owner, safe, name);
        if (removed == 0) throw DomainSupport.invalid("模板不存在或已被删除");
        audit.record("DASHBOARD_DELETE", "dashboard", UUID.randomUUID(), Map.of("page", safe, "name", name));
        return Map.of("ok", true);
    }

    /** 校验并归一化布局：12 列栅格，坐标与跨度都必须落在栅格内。 */
    private ArrayNode normalize(JsonNode layout) {
        if (layout == null || !layout.isArray() || layout.isEmpty() || layout.size() > MAX_CARDS) throw DomainSupport.invalid("布局需为 1 至 " + MAX_CARDS + " 个卡片");
        ArrayNode out = DomainSupport.JSON.createArrayNode();
        Set<String> ids = new HashSet<>();
        for (JsonNode item : layout) {
            if (!item.isObject()) throw DomainSupport.invalid("布局项必须是对象");
            String id = DomainSupport.required(item, "id", 64);
            if (!ids.add(id)) throw DomainSupport.invalid("卡片标识重复：" + id);
            int x = intOf(item, "x", 0, 11), y = intOf(item, "y", 0, 500), w = intOf(item, "w", 1, 12), h = intOf(item, "h", 1, 40);
            if (x + w > 12) throw DomainSupport.invalid("卡片 " + id + " 超出 12 列栅格");
            ObjectNode node = out.addObject();
            node.put("id", id); node.put("x", x); node.put("y", y); node.put("w", w); node.put("h", h);
        }
        return out;
    }

    private static int intOf(JsonNode item, String field, int min, int max) {
        JsonNode value = item.get(field);
        if (value == null || !value.canConvertToInt()) throw DomainSupport.invalid("布局项缺少整数 " + field);
        int v = value.asInt();
        if (v < min || v > max) throw DomainSupport.invalid(field + " 需在 " + min + " 到 " + max + " 之间");
        return v;
    }

    private static String page(String page) {
        if (page == null || !page.matches("[a-z][a-z0-9-]{1,63}")) throw DomainSupport.invalid("页面标识不合法");
        return page;
    }
}
