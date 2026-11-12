package cn.emigo.mossdoom;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * 生产、库存等业务页的真实聚合视图。
 *
 * <p>数据来源只有一处：已投影到 {@code standard_event_projection} 的标准事件，
 * 因此这里不会触发任何对源平台的调用，也不会出现示例数字。每一块都带回
 * {@code events}(原始事件条数) 与 {@code asOf}(数据截至时间)，前端据此标注覆盖范围。</p>
 */
@RestController @RequestMapping("/api/biz")
class BusinessInsightController {
    private final JdbcTemplate db, biz;

    BusinessInsightController(JdbcTemplate db, @Qualifier("businessJdbc") JdbcTemplate biz) { this.db = db; this.biz = biz; }

    /** 按数据集编码找到业务库里的投影分区；未登记则返回 null。 */
    private UUID dataset(String code) {
        List<UUID> ids = db.queryForList("SELECT id FROM dataset WHERE tenant_id=? AND code=?", UUID.class, DomainSupport.TENANT, code);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private long events(UUID dataset) {
        Long v = biz.queryForObject("SELECT count(*) FROM standard_event_projection WHERE dataset_id=?", Long.class, dataset);
        return v == null ? 0 : v;
    }

    /** 新物云生产订单的地区/店铺是形如 [{code,label}] 的 JSON 串，这里只取第一个 label。 */
    private static String label(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode node = DomainSupport.parse(raw);
            if (node.isArray() && !node.isEmpty()) {
                String text = node.get(0).path("label").asText("");
                return text.isBlank() ? null : text;
            }
        } catch (Exception ignored) {
            // 存量的自由文本直接原样返回
            return raw;
        }
        return raw;
    }

    /** 生产：新物云生产订单，按生产单取最新状态后汇总。 */
    @GetMapping("/production")
    Map<String, Object> production() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", "production_order_observations");
        out.put("source", "新物云生产订单");
        UUID id = dataset("production_order_observations");
        if (id == null) { out.put("available", false); return out; }

        List<Map<String, Object>> raw = latestProductionRows(id);

        double totalQty = 0;
        Map<String, double[]> byStatus = new LinkedHashMap<>(), byShop = new LinkedHashMap<>(), byRegion = new LinkedHashMap<>();
        List<Map<String, Object>> orders = new ArrayList<>();
        String asOf = null, firstSeen = null, lastModified = null;
        for (Map<String, Object> r : raw) {
            String status = Objects.toString(r.get("status"), "未知");
            String shop = label((String) r.get("shop_raw"));
            String region = label((String) r.get("region_raw"));
            double qty = r.get("quantity") == null ? 0 : ((Number) r.get("quantity")).doubleValue();
            totalQty += qty;
            byStatus.computeIfAbsent(status, k -> new double[2]);
            byStatus.get(status)[0] += 1; byStatus.get(status)[1] += qty;
            byShop.computeIfAbsent(shop == null ? "未标注店铺" : shop, k -> new double[2]);
            byShop.get(shop == null ? "未标注店铺" : shop)[0] += 1;
            byShop.get(shop == null ? "未标注店铺" : shop)[1] += qty;
            byRegion.computeIfAbsent(region == null ? "未标注地区" : region, k -> new double[2]);
            byRegion.get(region == null ? "未标注地区" : region)[0] += 1;
            byRegion.get(region == null ? "未标注地区" : region)[1] += qty;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("apply_no", r.get("apply_no"));
            row.put("order_no", r.get("order_no"));
            row.put("goods_no", r.get("goods_no"));
            row.put("goods_name", r.get("goods_name"));
            row.put("quantity", qty);
            row.put("status", status);
            row.put("shop", shop);
            row.put("region", region);
            row.put("creator", r.get("creator"));
            row.put("modified", r.get("modified"));
            orders.add(row);

            String occurred = String.valueOf(r.get("occurred_at"));
            if (asOf == null || occurred.compareTo(asOf) > 0) asOf = occurred;
            if (firstSeen == null || occurred.compareTo(firstSeen) < 0) firstSeen = occurred;
            Object modified = r.get("modified");
            if (modified != null && (lastModified == null || String.valueOf(modified).compareTo(lastModified) > 0)) lastModified = String.valueOf(modified);
        }
        orders.sort((a, b) -> Double.compare(((Number) b.get("quantity")).doubleValue(), ((Number) a.get("quantity")).doubleValue()));

        out.put("available", true);
        out.put("events", events(id));
        out.put("orders", orders.size());
        out.put("totalQty", totalQty);
        out.put("byStatus", flatten(byStatus));
        out.put("byShop", flatten(byShop));
        out.put("byRegion", flatten(byRegion));
        out.put("asOf", asOf);
        out.put("firstSeen", firstSeen);
        out.put("lastModified", lastModified);
        out.put("rows", orders);
        return out;
    }

    /** 库存：国内仓(WMS)、工厂仓(供应链协同)、平台仓(FBA)三块独立统计，不合并口径。 */
    @GetMapping("/inventory")
    Map<String, Object> inventory() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wms", wmsStock());
        out.put("factory", factoryStock());
        out.put("fba", fbaStock());
        return out;
    }

    private Map<String, Object> wmsStock() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("key", "wms");
        block.put("title", "国内仓 WMS");
        block.put("source", "米果 WMS 库存快照");
        UUID id = dataset("wms_stock_observations");
        if (id == null) { block.put("available", false); return block; }
        Map<String, Object> sum = biz.queryForMap("""
            SELECT count(DISTINCT payload->>'skuKey') AS skus,
                   coalesce(sum((payload->>'qtyTotal')::numeric), 0) AS total,
                   coalesce(sum((payload->>'qtyActive')::numeric), 0) AS usable,
                   coalesce(sum((payload->>'qtyFreeze')::numeric), 0) AS frozen,
                   coalesce(sum((payload->>'stockMoney')::numeric), 0) AS amount,
                   max(payload->>'modifyTime') AS as_of
            FROM standard_event_projection WHERE dataset_id=?
            """, id);
        block.put("available", true);
        block.put("events", events(id));
        block.putAll(sum);
        block.put("rows", biz.queryForList("""
            SELECT payload->>'skuKey' AS sku, payload->>'skuCode' AS code, payload->>'skuMode' AS mode,
                   coalesce((payload->>'qtyTotal')::numeric, 0) AS total,
                   coalesce((payload->>'qtyActive')::numeric, 0) AS usable,
                   coalesce((payload->>'qtyFreeze')::numeric, 0) AS frozen,
                   coalesce((payload->>'stockMoney')::numeric, 0) AS amount,
                   payload->>'modifyTime' AS updated
            FROM standard_event_projection WHERE dataset_id=?
            ORDER BY (payload->>'qtyTotal')::numeric DESC LIMIT 50
            """, id));
        return block;
    }

    private Map<String, Object> factoryStock() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("key", "factory");
        block.put("title", "工厂仓");
        block.put("source", "供应链协同工厂库存");
        UUID id = dataset("factory_stock_observations");
        if (id == null) { block.put("available", false); return block; }
        Map<String, Object> sum = biz.queryForMap("""
            SELECT count(DISTINCT payload->>'skuKey') AS skus,
                   coalesce(sum((payload->>'totalInventoryQty')::numeric), 0) AS total,
                   coalesce(sum((payload->>'usableInventoryQty')::numeric), 0) AS usable,
                   coalesce(sum((payload->>'occupyInventoryQty')::numeric), 0) AS occupied,
                   max(payload->>'updateTime') AS as_of
            FROM standard_event_projection WHERE dataset_id=?
            """, id);
        block.put("available", true);
        block.put("events", events(id));
        block.putAll(sum);
        block.put("rows", biz.queryForList("""
            SELECT payload->>'skuKey' AS sku, payload->>'localSpuCode' AS spu, payload->>'factoryName' AS factory,
                   coalesce((payload->>'totalInventoryQty')::numeric, 0) AS total,
                   coalesce((payload->>'usableInventoryQty')::numeric, 0) AS usable,
                   coalesce((payload->>'occupyInventoryQty')::numeric, 0) AS occupied,
                   payload->>'updateTime' AS updated
            FROM standard_event_projection WHERE dataset_id=?
            ORDER BY (payload->>'totalInventoryQty')::numeric DESC LIMIT 50
            """, id));
        return block;
    }

    private Map<String, Object> fbaStock() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("key", "fba");
        block.put("title", "平台仓 FBA");
        block.put("source", "TMS 平台仓库存");
        UUID id = dataset("logistics_fba_observations");
        if (id == null) { block.put("available", false); return block; }
        Map<String, Object> sum = biz.queryForMap("""
            SELECT count(DISTINCT payload->>'skuKey') AS skus,
                   coalesce(sum((payload->>'totalQty')::numeric), 0) AS total,
                   coalesce(sum((payload->>'fbaAbleQty')::numeric), 0) AS usable,
                   coalesce(sum((payload->>'unableQty')::numeric), 0) AS unable,
                   coalesce(sum((payload->>'reservedQty')::numeric), 0) AS reserved,
                   coalesce(sum((payload->>'inboundWorkingQty')::numeric), 0)
                     + coalesce(sum((payload->>'inboundShippQty')::numeric), 0)
                     + coalesce(sum((payload->>'inboundReceivingQty')::numeric), 0) AS inbound,
                   max(payload->>'updateTime') AS as_of
            FROM standard_event_projection WHERE dataset_id=?
            """, id);
        block.put("available", true);
        block.put("events", events(id));
        block.putAll(sum);
        block.put("rows", biz.queryForList("""
            SELECT payload->>'skuKey' AS sku, payload->>'msku' AS msku, payload->>'fnSku' AS fnsku,
                   payload->>'shopName' AS shop,
                   coalesce((payload->>'fbaAbleQty')::numeric, 0) AS usable,
                   coalesce((payload->>'totalQty')::numeric, 0) AS total,
                   coalesce((payload->>'reservedQty')::numeric, 0) AS reserved,
                   payload->>'updateTime' AS updated
            FROM standard_event_projection WHERE dataset_id=?
            ORDER BY (payload->>'fbaAbleQty')::numeric DESC LIMIT 50
            """, id));
        return block;
    }

    /** 每张生产单只保留最后一条事件，返回列与手工查询一致。 */
    private List<Map<String, Object>> latestProductionRows(UUID dataset) {
        return biz.queryForList("""
            SELECT DISTINCT ON (payload->>'apply_no')
                   payload->>'apply_no'  AS apply_no,
                   payload->>'order_no'  AS order_no,
                   payload->>'goods_no'  AS goods_no,
                   payload->>'goods_name' AS goods_name,
                   coalesce(nullif(payload->>'quantity', ''), '0')::numeric AS quantity,
                   payload->>'status'    AS status,
                   payload->>'attr_1'    AS region_raw,
                   payload->>'attr_9'    AS shop_raw,
                   payload->>'creator'   AS creator,
                   payload->>'modified'  AS modified,
                   occurred_at
            FROM standard_event_projection
            WHERE dataset_id=?
            ORDER BY payload->>'apply_no', occurred_at DESC
            """, dataset);
    }

    /**
     * SKU 全链路：以款号(SPU)为轴，把生产、工厂仓、国内仓、平台仓四段观测拼到一行。
     *
     * <p>归并规则：生产单的 {@code goods_no}、工厂库存的 {@code localSpuCode}、
     * WMS/平台仓的 {@code skuKey} 前缀（最后一个 {@code -} 之前）都等于款号，
     * 因此不需要额外映射表。四个来源各自用真实 UUID 查，缺哪段就在结果里留空 null，
     * 前端据此显示"未接入"，而不是显示 0。</p>
     */
    @GetMapping("/chain")
    Map<String, Object> chain() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Map<String, Object>> spus = new LinkedHashMap<>();

        // 生产段：按 goods_no 归并
        UUID prod = dataset("production_order_observations");
        if (prod != null) {
            for (Map<String, Object> r : latestProductionRows(prod)) {
                String spu = str(r.get("goods_no"));
                if (spu == null) continue;
                Map<String, Object> e = spu(spus, spu);
                if (e.get("goodsName") == null && r.get("goods_name") != null) e.put("goodsName", r.get("goods_name"));
                Map<String, Object> p = section(e, "production");
                p.put("orders", num(p.get("orders")) + 1);
                p.put("qty", num(p.get("qty")) + num(r.get("quantity")));
                status(p, str(r.get("status")), num(r.get("quantity")));
                String occurred = String.valueOf(r.get("occurred_at"));
                if (p.get("lastAt") == null || occurred.compareTo(String.valueOf(p.get("lastAt"))) > 0) p.put("lastAt", occurred);
            }
        }

        // 工厂仓：localSpuCode 直接给款号；缺失时用 skuKey 前缀兜底
        UUID factory = dataset("factory_stock_observations");
        if (factory != null) {
            for (Map<String, Object> r : biz.queryForList("""
                SELECT DISTINCT ON (payload->>'skuKey')
                       payload->>'skuKey' AS sku, payload->>'localSpuCode' AS spu,
                       payload->>'brandName' AS brand, payload->>'teamName' AS team,
                       coalesce(nullif(payload->>'totalInventoryQty',''),'0')::numeric AS total,
                       coalesce(nullif(payload->>'usableInventoryQty',''),'0')::numeric AS usable,
                       coalesce(nullif(payload->>'occupyInventoryQty',''),'0')::numeric AS occupied,
                       payload->>'updateTime' AS updated, occurred_at
                FROM standard_event_projection WHERE dataset_id=?
                ORDER BY payload->>'skuKey', occurred_at DESC
                """, factory)) {
                Map<String, Object> e = spu(spus, spuOf(r.get("spu"), r.get("sku")));
                if (e == null) continue;
                meta(e, "brand", r.get("brand"));
                meta(e, "dept", r.get("team"));
                Map<String, Object> f = section(e, "factory");
                f.put("skus", num(f.get("skus")) + 1);
                f.put("total", num(f.get("total")) + num(r.get("total")));
                f.put("usable", num(f.get("usable")) + num(r.get("usable")));
                f.put("occupied", num(f.get("occupied")) + num(r.get("occupied")));
                String updated = str(r.get("updated"));
                if (updated != null && (f.get("asOf") == null || updated.compareTo(String.valueOf(f.get("asOf"))) > 0)) f.put("asOf", updated);
                sku(e, str(r.get("sku")));
            }
        }

        // 平台仓 FBA
        UUID fba = dataset("logistics_fba_observations");
        if (fba != null) {
            for (Map<String, Object> r : biz.queryForList("""
                SELECT DISTINCT ON (payload->>'skuKey')
                       payload->>'skuKey' AS sku,
                       coalesce(nullif(payload->>'totalQty',''),'0')::numeric AS total,
                       coalesce(nullif(payload->>'fbaAbleQty',''),'0')::numeric AS usable,
                       coalesce(nullif(payload->>'reservedQty',''),'0')::numeric AS reserved,
                       payload->>'updateTime' AS updated, occurred_at
                FROM standard_event_projection WHERE dataset_id=?
                ORDER BY payload->>'skuKey', occurred_at DESC
                """, fba)) {
                Map<String, Object> e = spu(spus, spuOf(null, r.get("sku")));
                if (e == null) continue;
                Map<String, Object> f = section(e, "fba");
                f.put("skus", num(f.get("skus")) + 1);
                f.put("total", num(f.get("total")) + num(r.get("total")));
                f.put("usable", num(f.get("usable")) + num(r.get("usable")));
                f.put("reserved", num(f.get("reserved")) + num(r.get("reserved")));
                String updated = str(r.get("updated"));
                if (updated != null && (f.get("asOf") == null || updated.compareTo(String.valueOf(f.get("asOf"))) > 0)) f.put("asOf", updated);
                sku(e, str(r.get("sku")));
            }
        }

        // 国内仓 WMS
        UUID wms = dataset("wms_stock_observations");
        if (wms != null) {
            for (Map<String, Object> r : biz.queryForList("""
                SELECT DISTINCT ON (payload->>'skuKey')
                       payload->>'skuKey' AS sku,
                       payload->>'brandName' AS brand, payload->>'category2' AS category,
                       coalesce(nullif(payload->>'qtyTotal',''),'0')::numeric AS total,
                       coalesce(nullif(payload->>'qtyActive',''),'0')::numeric AS usable,
                       coalesce(nullif(payload->>'qtyFreeze',''),'0')::numeric AS frozen,
                       payload->>'modifyTime' AS updated, occurred_at
                FROM standard_event_projection WHERE dataset_id=?
                ORDER BY payload->>'skuKey', occurred_at DESC
                """, wms)) {
                Map<String, Object> e = spu(spus, spuOf(null, r.get("sku")));
                if (e == null) continue;
                meta(e, "brand", r.get("brand"));
                meta(e, "category", r.get("category"));
                Map<String, Object> w = section(e, "wms");
                w.put("skus", num(w.get("skus")) + 1);
                w.put("total", num(w.get("total")) + num(r.get("total")));
                w.put("usable", num(w.get("usable")) + num(r.get("usable")));
                w.put("frozen", num(w.get("frozen")) + num(r.get("frozen")));
                String updated = str(r.get("updated"));
                if (updated != null && (w.get("asOf") == null || updated.compareTo(String.valueOf(w.get("asOf"))) > 0)) w.put("asOf", updated);
                sku(e, str(r.get("sku")));
            }
        }

        // sell 主数据快照：品牌/部门/品类以主数据为准，覆盖源载荷里的同名字段（源载荷的 brandName 不一定等于品牌）
        try {
            for (Map<String, Object> m : biz.queryForList("SELECT spu, brand, dept, category, category_class, source FROM spu_meta")) {
                Map<String, Object> e = spus.get(str(m.get("spu")));
                if (e == null) continue;
                if (str(m.get("brand")) != null) e.put("brand", str(m.get("brand")));
                if (str(m.get("dept")) != null) e.put("dept", str(m.get("dept")));
                if (str(m.get("category")) != null) e.put("category", str(m.get("category")));
                if (str(m.get("category_class")) != null) e.put("categoryClass", str(m.get("category_class")));
                if (str(m.get("source")) != null) e.put("metaSource", str(m.get("source")));
            }
        } catch (RuntimeException ex) {
            // 主数据表缺失或无权读取时不影响链路本身，但把原因带回给页面，避免静默降级
            out.put("metaError", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }

        List<Map<String, Object>> rows = new ArrayList<>(spus.values());
        // 有生产单的排前面，其次按各段合计库存
        rows.sort((a, b) -> {
            double qa = num(section(a, "production").get("qty")) + stock(a);
            double qb = num(section(b, "production").get("qty")) + stock(b);
            return Double.compare(qb, qa);
        });
        double prodQty = 0, factoryUsable = 0, wmsUsable = 0, fbaUsable = 0;
        for (Map<String, Object> r : rows) {
            prodQty += num(section(r, "production").get("qty"));
            factoryUsable += num(section(r, "factory").get("usable"));
            wmsUsable += num(section(r, "wms").get("usable"));
            fbaUsable += num(section(r, "fba").get("usable"));
        }
        out.put("spus", rows);
        out.put("spuCount", rows.size());
        out.put("productionQty", prodQty);
        out.put("factoryUsable", factoryUsable);
        out.put("wmsUsable", wmsUsable);
        out.put("fbaUsable", fbaUsable);
        out.put("sources", List.of(
                Map.of("key", "production", "title", "生产", "available", prod != null),
                Map.of("key", "factory", "title", "工厂仓", "available", factory != null),
                Map.of("key", "wms", "title", "国内仓", "available", wms != null),
                Map.of("key", "fba", "title", "平台仓", "available", fba != null)));
        return out;
    }

    private static double stock(Map<String, Object> row) {
        return num(section(row, "factory").get("total")) + num(section(row, "wms").get("total")) + num(section(row, "fba").get("total"));
    }

    private static String str(Object value) { return value == null ? null : String.valueOf(value); }

    private static double num(Object value) { return value instanceof Number n ? n.doubleValue() : 0D; }

    /** 款号：优先取 localSpuCode，缺失时用 SKU 编码最后一个 '-' 之前的部分。 */
    private static String spuOf(Object spu, Object sku) {
        String code = str(spu);
        if (code != null && !code.isBlank()) return code;
        String key = str(sku);
        if (key == null || key.isBlank()) return null;
        int i = key.lastIndexOf('-');
        return i > 0 ? key.substring(0, i) : key;
    }

    private static Map<String, Object> spu(Map<String, Map<String, Object>> spus, String code) {
        if (code == null || code.isBlank()) return null;
        return spus.computeIfAbsent(code, k -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("spu", k);
            for (String section : List.of("production", "factory", "wms", "fba")) e.put(section, new LinkedHashMap<String, Object>());
            e.put("skus", new LinkedHashSet<String>());
            return e;
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> entry, String key) {
        return (Map<String, Object>) entry.get(key);
    }

    @SuppressWarnings("unchecked")
    private static void status(Map<String, Object> production, String name, double qty) {
        Map<String, Object> byStatus = (Map<String, Object>) production.computeIfAbsent("byStatus", k -> new LinkedHashMap<String, Object>());
        byStatus.merge(name == null ? "未知" : name, qty, (a, b) -> num(a) + num(b));
    }

    @SuppressWarnings("unchecked")
    private static void sku(Map<String, Object> entry, String code) {
        if (code != null) ((Set<String>) entry.get("skus")).add(code);
    }

    /** 只在字段还空着时写入：先到的数据优先（生产单 > 工厂仓 > 国内仓 > 平台仓的遍历顺序）。 */
    private static void meta(Map<String, Object> entry, String key, Object value) {
        String text = str(value);
        if (text != null && !text.isBlank() && (entry.get(key) == null || String.valueOf(entry.get(key)).isBlank())) {
            entry.put(key, text);
        }
    }

    private static List<Map<String, Object>> flatten(Map<String, double[]> source) {
        List<Map<String, Object>> list = new ArrayList<>();
        source.forEach((name, v) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("count", (long) v[0]);
            item.put("qty", v[1]);
            list.add(item);
        });
        list.sort((a, b) -> Double.compare(((Number) b.get("qty")).doubleValue(), ((Number) a.get("qty")).doubleValue()));
        return list;
    }
}
