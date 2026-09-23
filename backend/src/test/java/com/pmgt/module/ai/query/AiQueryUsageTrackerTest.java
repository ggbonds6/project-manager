package com.pmgt.module.ai.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiQueryUsageTracker} 的计数测试（§11.6：本次问答用了几次系统查询、查了哪些 entity）。
 */
class AiQueryUsageTrackerTest {

    @Test
    void 未登记或未查询时为0且entity为空() {
        AiQueryUsageTracker tracker = new AiQueryUsageTracker();

        assertEquals(0, tracker.snapshot(null).count());
        assertEquals(0, tracker.snapshot("不存在").count());
        assertNull(tracker.snapshot("jti-1").entitiesText(), "没查过时 biz_entities 必须是 null 而不是空串");

        tracker.register("jti-1");
        AiQueryUsageTracker.Snapshot snapshot = tracker.snapshot("jti-1");
        assertEquals(0, snapshot.count());
        assertTrue(snapshot.entities().isEmpty());
        assertNull(snapshot.entitiesText());
    }

    @Test
    void 同一entity多次只记一次清单但次数累加() {
        AiQueryUsageTracker tracker = new AiQueryUsageTracker();
        tracker.register("jti-1");

        tracker.record("jti-1", "stats");
        tracker.record("jti-1", "stats");
        tracker.record("jti-1", "projects");

        AiQueryUsageTracker.Snapshot snapshot = tracker.snapshot("jti-1");
        assertEquals(3, snapshot.count());
        assertEquals(List.of("stats", "projects"), snapshot.entities());
        assertEquals("stats,projects", snapshot.entitiesText());
    }

    @Test
    void 未登记的jti也照记以免丢掉痕迹() {
        // 主系统在问答中途重启时，回调仍会进来：宁可留一条孤立计数，也不能把痕迹丢掉
        AiQueryUsageTracker tracker = new AiQueryUsageTracker();

        tracker.record("jti-unknown", "payments");

        assertEquals(1, tracker.snapshot("jti-unknown").count());
    }

    @Test
    void 清理后不再保留() {
        AiQueryUsageTracker tracker = new AiQueryUsageTracker();
        tracker.register("jti-1");
        tracker.record("jti-1", "stats");

        tracker.clear("jti-1");

        assertEquals(0, tracker.snapshot("jti-1").count());
        assertEquals(0, tracker.size());
    }

    @Test
    void 空jti不参与计数也不报错() {
        AiQueryUsageTracker tracker = new AiQueryUsageTracker();

        tracker.register(null);
        tracker.record("  ", "stats");
        tracker.clear(null);

        assertEquals(0, tracker.size());
        assertEquals(0, tracker.snapshot(null).count());
    }
}
