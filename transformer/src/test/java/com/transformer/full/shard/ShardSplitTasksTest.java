package com.transformer.full.shard;

import com.transformer.entity.SyncTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Shard#splitTasks(long, long, int)} 的契约测试。
 * <p>
 * 后置条件（闭区间语义，与 {@link SyncTask} 的「一片 = [min, max]」一致）：
 * <ol>
 *   <li>返回非空 list，且本身已按 min 升序排列</li>
 *   <li>list[0].min == min</li>
 *   <li>list[last].max == max</li>
 *   <li>list[i].max + 1 == list[i+1].min —— 连续，因而无缝隙、无重叠</li>
 *   <li>每片长度 max - min + 1 &lt;= batch</li>
 *   <li>非尾片长度恰为 batch</li>
 *   <li>每片 id == null、status == Pending、errorMsg == null</li>
 * </ol>
 * 前置条件：min &lt;= max 且 batch &gt;= 1，违反则 {@link IllegalArgumentException}。
 * <p>
 * 断言的是结构关系，不是重算出来的期望值——测试里刻意不出现
 * {@code (max - min + 1 + batch - 1) / batch} 这类把实现抄一遍的算式。
 * <p>
 * 纯函数性由 fixture 本身保证：{@code new Shard()} 的两个 DAO 字段未注入，
 * 实现里一旦触碰 socialEntityDAO / syncTaskDAO 就会 NPE。
 * <p>
 * 范围约定：契约不承诺支持贴近 {@code Long.MAX_VALUE} 的区间——自增主键到不了那里，
 * 为此在实现里绕开中间量溢出不划算。守到 {@code Integer.MAX_VALUE} 附近即可。
 * <p>
 * 注意：若实现的循环某轮不推进（例如 {@code cur = hi} 而非 {@code cur = hi + 1}），
 * list 会无限增长，测试会以 OutOfMemoryError 崩掉整个 JVM 而不是干净失败——
 * 抢占式 timeout 唤不醒一个紧循环。届时看堆栈里的 splitTasks 行号即可定位。
 */
class ShardSplitTasksTest {

    /** 状态机初态，与 SyncTask#status 的取值域一致。 */
    private static final String PENDING = "Pending";

    private final Shard shard = new Shard();

    // ------------------------------------------------------------------
    // 不变量检查器
    // ------------------------------------------------------------------

    /** 断言 tasks 是闭区间 [min, max] 在批次大小 batch 下的唯一合法划分。 */
    private void assertPartitionOf(long min, long max, int batch, List<SyncTask> tasks) {
        String ctx = "[" + min + "," + max + "] batch=" + batch + " -> ";

        assertFalse(tasks.isEmpty(), ctx + "非空区间必须产出至少一片");

        assertEquals(min, tasks.get(0).getMin(), ctx + "I1 首片必须锚定 min");
        assertEquals(max, tasks.get(tasks.size() - 1).getMax(), ctx + "I2 尾片必须锚定 max");

        long covered = 0;
        for (int i = 0; i < tasks.size(); i++) {
            SyncTask task = tasks.get(i);
            String at = ctx + "第" + i + "片" + task.getMin() + ".." + task.getMax() + " ";

            assertNotNull(task.getMin(), at + "区间下界不可为 null");
            assertNotNull(task.getMax(), at + "区间上界不可为 null");
            long lo = task.getMin();
            long hi = task.getMax();

            assertTrue(lo <= hi, at + "区间倒挂");
            long size = hi - lo + 1;
            assertTrue(size <= batch, at + "I5 片长 " + size + " 超出 batch");

            if (i < tasks.size() - 1) {
                assertEquals(batch, size, at + "I6 非尾片必须是满片");
                assertEquals(hi + 1, tasks.get(i + 1).getMin(),
                        at + "I4 与下一片不相接（有缝隙或有重叠，也意味着未按 min 升序）");
            }

            assertFieldsFreshForInsert(task, at);
            covered += size;
        }

        assertEquals(max - min + 1, covered, ctx + "覆盖守恒：片长之和 != 区间长度");
    }

    /** 落库前的字段形态。 */
    private void assertFieldsFreshForInsert(SyncTask task, String at) {
        assertNull(task.getId(), at + "I7 id 应由数据库生成");
        assertEquals(PENDING, task.getStatus(), at + "I7 初始状态必须是 Pending");
        assertNull(task.getErrorMsg(), at + "I7 未失败的任务不应带 errorMsg");
    }

    // ------------------------------------------------------------------
    // 输入空间扫描
    // ------------------------------------------------------------------

    @Test
    @DisplayName("任意区间与批次，结果都是该区间的一个划分")
    void 任意区间与批次都构成一个划分() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (long base : new long[]{1L, 7L, 1_000_000_000L}) {
                for (long length = 1; length <= 40; length++) {
                    for (int batch = 1; batch <= 12; batch++) {
                        long max = base + length - 1;
                        assertPartitionOf(base, max, batch, shard.splitTasks(base, max, batch));
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("生产批次大小下同样成立")
    void 生产批次大小下的划分() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            for (long length : new long[]{1, 999, 1000, 1001, 1_000_000, 1_000_001}) {
                long max = length;
                assertPartitionOf(1, max, Shard.BATCH_SIZE, shard.splitTasks(1, max, Shard.BATCH_SIZE));
            }
        });
    }

    // ------------------------------------------------------------------
    // 边界：扫描到不了的地方，逐个钉死
    // ------------------------------------------------------------------

    @Test
    void 单点区间产出恰好一片() {
        List<SyncTask> tasks = shard.splitTasks(42, 42, Shard.BATCH_SIZE);

        assertEquals(1, tasks.size(), "单点区间应产出一片");
        assertPartitionOf(42, 42, Shard.BATCH_SIZE, tasks);
    }

    @Test
    void 整除时不产出空尾片() {
        List<SyncTask> tasks = shard.splitTasks(1, 3000, 1000);

        assertEquals(3, tasks.size(), "3000 个 id 按 1000 切应为 3 片，不应多出一个空尾片");
        assertPartitionOf(1, 3000, 1000, tasks);
    }

    @Test
    void 余数为一时尾片是单元素() {
        List<SyncTask> tasks = shard.splitTasks(1, 3001, 1000);
        SyncTask last = tasks.get(tasks.size() - 1);

        assertEquals(4, tasks.size());
        assertEquals(3001L, last.getMin(), "尾片下界");
        assertEquals(3001L, last.getMax(), "尾片上界");
        assertPartitionOf(1, 3001, 1000, tasks);
    }

    @Test
    void 批次大于区间长度时退化为一片() {
        List<SyncTask> tasks = shard.splitTasks(10, 15, Shard.BATCH_SIZE);

        assertEquals(1, tasks.size());
        assertPartitionOf(10, 15, Shard.BATCH_SIZE, tasks);
    }

    @Test
    void 批次为一时每片一个id() {
        List<SyncTask> tasks = shard.splitTasks(5, 9, 1);

        assertEquals(5, tasks.size());
        assertPartitionOf(5, 9, 1, tasks);
    }

    @Test
    @DisplayName("id 跨越 Integer 上界时不溢出")
    void 跨越Integer上界不溢出() {
        long min = Integer.MAX_VALUE - 5L;
        long max = Integer.MAX_VALUE + 5L;

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertPartitionOf(min, max, 4, shard.splitTasks(min, max, 4)));
    }

    @Test
    @DisplayName("跨零区间：min 为负也不能因 max-min 溢出而漏片")
    void 跨零区间不溢出() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertPartitionOf(-5, 5, 4, shard.splitTasks(-5, 5, 4)));
    }

    // ------------------------------------------------------------------
    // 前置条件：非法输入的行为同样是契约
    // ------------------------------------------------------------------

    @Test
    void 批次非正数快速失败() {
        assertThrows(IllegalArgumentException.class, () -> shard.splitTasks(1, 100, 0),
                "batch=0 会导致死循环，必须拒绝");
        assertThrows(IllegalArgumentException.class, () -> shard.splitTasks(1, 100, -1),
                "batch 为负必须拒绝");
    }

    @Test
    void 区间倒挂快速失败() {
        assertThrows(IllegalArgumentException.class, () -> shard.splitTasks(100, 1, 1000),
                "max < min 属于不该发生的状态，静默返回空 list 会让整轮同步假成功");
    }

    // ------------------------------------------------------------------
    // 纯函数
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同参数多次调用结果一致，且全程不触碰 DAO")
    void 同参数调用结果稳定() {
        // SyncTask 未实现 equals，按契约不改对象模型，这里用 toString 做等价比较
        assertEquals(shard.splitTasks(1, 5000, 1000).toString(),
                shard.splitTasks(1, 5000, 1000).toString());
    }
}
