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

        List<Map<String, Object>> raw = biz.queryForList("""
            SELECT DISTINCT ON (payload->>'apply_no')
                   payload->>'apply_no'  AS apply_no,
                   payload->>'order_no'  AS order_no,
                   payload->>'goods_no'  AS goods_no,
                   payload->>'goods_name' AS goods_name,
                   (payload->>'quantity')::numeric AS quantity,
                   payload->>'status'    AS status,
                   payload->>'attr_1'    AS region_raw,
                   payload->>'attr_9'    AS shop_raw,
                   payload->>'creator'   AS creator,
                   payload->>'modified'  AS modified,
                   occurred_at
            FROM standard_event_projection
            WHERE dataset_id=?
            ORDER BY payload->>'apply_no', occurred_at DESC
            """, id);

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

    /** {名称: [单数, 数量]} → 按数量倒序的数组，方便前端直接渲染。 */
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
