package com.clio.openalex.importer.work;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.clio.openalex.importer.dao.DeadRowDAO;
import com.clio.openalex.importer.dao.FileTaskDAO;
import static com.clio.openalex.importer.dao.JdbcConnections.DEFAULT_PASSWORD;
import static com.clio.openalex.importer.dao.JdbcConnections.DEFAULT_URL;
import static com.clio.openalex.importer.dao.JdbcConnections.DEFAULT_USER;
import static com.clio.openalex.importer.dao.JdbcConnections.setting;
import com.clio.openalex.importer.exception.RetryableException;
import com.clio.openalex.importer.plan.Entity;
import com.clio.openalex.importer.plan.FileTask;
import com.clio.openalex.importer.plan.PlanArgsParser;
import com.common.entity.SocialEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class Worker {
    //TODO 添加MDC日志用于性能监控和进度记录 分为文件级，数据块级，全局级
    public static final int THREAD_COUNT = 16;
    private static final HikariDataSource DS;
    static {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(setting("openalex.db.url", "OPENALEX_DB_URL", DEFAULT_URL));
        cfg.addDataSourceProperty("rewriteBatchedStatements", "true");  // 驱动把 addBatch 的多行合并成一条多值 INSERT，1000 次往返变 1 次
        cfg.setConnectionInitSql("SET sql_log_bin = 0");               // 建连接时关 binlog；远端数据可重放，不依赖 binlog 恢复
        cfg.setUsername(DEFAULT_USER);
        cfg.setPassword(DEFAULT_PASSWORD);
        cfg.setMaximumPoolSize(Worker.THREAD_COUNT + 4);  // 20:每个 worker 最多同时占 1 条
        cfg.setAutoCommit(true);                          // 池的默认值;事务里自己 setAutoCommit(false),归还时 Hikari 会还原
        DS = new HikariDataSource(cfg);
    }
    static Connection open() throws SQLException { return DS.getConnection(); }
    private static final FileTaskDAO fileTaskDAO = new FileTaskDAO(DS);
    private static final DeadRowDAO deadRowDAO = new DeadRowDAO(DS);
    private static HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))       // 必须：否则连不上就永久挂起
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final int BATCH_SIZE=1000;
    private static final Logger log = LoggerFactory.getLogger(Worker.class);
    private static final String PLATFORM = "openalex";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UPSERT_SOCIAL_ENTITY = """
            INSERT INTO social_entity
                (platform, entity_type, entity_id, data, created_at, updated_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE
                data = VALUES(data),
                updated_at = CURRENT_TIMESTAMP
            """;


    /**
     * 线程循环根据entity读取file_task表的Pending状态数据和僵尸数据，
     * 获取文件url，通过网络获取解压，转换，
     * 批量插入social_entity表
     * @param args
     * @return
     */
    public static int run(String[] args) {
        Entity entity = PlanArgsParser.parse(args);
        //创建线程池，循环提交固定大小的任务
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            pool.execute(() -> {
                while (true) {
                    //领取任务：claim 内部已原子置为 running 并刷新心跳
                    Optional<FileTask> fileTask = tryAcquireFileTask(entity);
                    if (fileTask.isEmpty()) {
                        break;   //没有可领取的任务，worker 收工
                    }
                    FileTask task = fileTask.get();
                    try {
                        importFile(task);
                        fileTaskDAO.markDone(task.getFileId());          //成功：done
                    } catch (RetryableException e) {
                        log.warn("可重试异常，将 fileId={} 的任务状态置为 Pending", fileTask.get().getFileId());
                        fileTaskDAO.markPending(task.getFileId());       //可重试：退回 pending
                    } catch (Exception e) {
                        log.error("其他异常，将 fileId={} 的任务状态置为 Failed", fileTask.get().getFileId());
                        fileTaskDAO.markFailed(task.getFileId());        //Fatal/IO/SQL 等：failed，继续领下一个
                    }
                }
            });
        }
        //提交完毕后关闭线程池，等所有 worker 领完活自行退出
        pool.shutdown();
        try {
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.warn("线程池执行被打断，立即终止");
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        return 0;
    }

    /**
     * 尝试获取某个entity下处于pending状态或者处于超时状态的file task
     *
     * @param entity
     * @return
     */
    public static Optional<FileTask> tryAcquireFileTask(Entity entity) {
        return fileTaskDAO.claim(entity);
    }

    /**
     * 在 chunk 事务内推进 read_count 并刷新心跳。
     * 委托 FileTaskDAO，参与调用方（commitChunk）事务，不提交不关闭连接。
     */
    static void updateHeartbeatAndCount(Connection conn, FileTask fileTask, long readCount) throws SQLException {
        fileTaskDAO.advanceProgress(conn, fileTask.getFileId(), readCount);
    }

    /**
     * 批量插入一批原始 JSON 行到目标落库表 social_entity。
     * 参与调用方（commitChunk）的事务：只 addBatch/executeBatch，绝不 commit / close 连接。
     * 幂等性由 (platform, entity_type, entity_id) 唯一键 + ON DUPLICATE KEY UPDATE 提供，
     * 崩溃后重新同步同一文件不会产生重复行。
     *
     * @param conn 调用方事务内的连接
     * @param list 本批次转换好的实体；为空时直接返回
     */
    static void batchInsert(Connection conn, List<SocialEntity> list) throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(list, "list");
        if (list.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = conn.prepareStatement(UPSERT_SOCIAL_ENTITY)) {
            for (SocialEntity socialEntity : list) {
                statement.setString(1, socialEntity.getPlatform());
                statement.setString(2, socialEntity.getEntityType());
                statement.setString(3, socialEntity.getEntityId());
                statement.setString(4, socialEntity.getData());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * 完成FileTask对应json gz文件的导入到数据库表任务
     * 网络异常--重试
     * 数量校验异常--不由此处负责，后续在reconcile阶段做校验
     * 解析过程异常--Fatal异常，直接报错
     * 数据本身有问题--Poisoned数据，记录至deadRow，继续解析
     *          完成FileTask对应json gz文件的导入到数据库表任务
     *          网络异常--重试
     *          量校验异常--不由此处负责，后续在reconcile阶段做校验
     *          解析过程异常--Fatal异常，直接报错
     *          数据本身有问题--Poisoned数据，记录至deadRow，继续解析
     *         1. Reader
     *            1. 读取文件--HttpClient--global
     *            2. 解压文件--GzInputStream
     *            3. 解析文件--BufferedReader
     *         while true
     *            读取一个批次,1000条
     *            3. commitChunk
     *                1. 校验数据
     *                2. 批量插入数据库(参考Spring Batch)+更新心跳+进度
     *                   1. 事务的处理
     *                   2. 事务的拆分
     *
     * @param fileTask
     * @throws IOException
     */
    public static void importFile(FileTask fileTask) throws IOException, SQLException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(toHttpUri(fileTask.getUrl()))
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new RetryableException("download failed: " + toHttpUri(fileTask.getUrl()), e);
        } catch (InterruptedException e) {
            throw new RetryableException("interrupted", e);
        }
        if(response.statusCode()!=200){
            response.body().close();
            throw new RetryableException("Http状态码错误"+response.statusCode());
        }

        // 外层：流的生命周期 = 整个文件；try-with-resources 保证正常/异常都关闭（含 GZIP 的 native inflater）
        try (InputStream body = response.body();
             GZIPInputStream gzip = new GZIPInputStream(body, 65536);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(gzip, StandardCharsets.UTF_8), 1 << 20)) {
            long read_count = 0;
            while (true) {
                long batchStart = read_count;   // 这批第一条之前已读的行数 = 行号基准（0 基偏移）
                // 读一个批次，最多 BATCH_SIZE 条；readLine 返回 null 即到 EOF
                List<String> list = new ArrayList<>();
                for (int i = 0; i < BATCH_SIZE; i++) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    read_count++;
                    list.add(line);
                }
                if (list.isEmpty()) {   // 整个文件读完
                    break;
                }

                // 内层：连接的生命周期 = 一个 chunk；每批从池借一条，用完还池
                try (Connection conn = open()) {
                    conn.setAutoCommit(false);
                    try {
                        TransformResult result = transform(
                                fileTask.getEntity(), fileTask.getFileId(), batchStart, list);
                        batchInsert(conn, result.entities());                      // 好行 → social_entity
                        if (!result.deadRows().isEmpty()) {                        // 非空才落，空批不进 DAO
                            deadRowDAO.insert(conn, result.deadRows());            // 坏行 → dead_row（同一事务）
                            log.debug("落 dead row: fileId={}, 本批坏行数={}",
                                    fileTask.getFileId(), result.deadRows().size());
                        }
                        updateHeartbeatAndCount(conn, fileTask, read_count);
                        conn.commit();
                    } catch (Exception e) {   // 任何异常都回滚并上报，绝不吞
                        conn.rollback();
                        throw e;
                    }
                }   // ← 本批结束，连接立即还池
            }
        }   // ← 文件读完，流按逆序关闭
    }

    /**
     * s3://bucket/key → https://bucket.s3.amazonaws.com/key；已是 http 的原样返回
     */
    static URI toHttpUri(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return URI.create(url);                        // ④ 防御：已经是 http，直连
        }
        if (!url.startsWith("s3://")) {
            throw new IllegalArgumentException("unsupported url scheme: " + url);
        }
        String rest = url.substring(5);                  // 去掉 "s3://"
        int slash = rest.indexOf('/');
        if (slash < 0) throw new IllegalArgumentException("s3 url has no key: " + url);
        String bucket = rest.substring(0, slash);
        String key = rest.substring(slash + 1);
        try {
            // 4 参构造器会正确编码 path、保留 '/'，比 URLEncoder 省心
            return new URI("https", bucket + ".s3.amazonaws.com", "/" + key, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("bad s3 url: " + url, e);
        }
    }

    /**
     * 把Json格式的字符串转换成SocialEntity对象
     * platform先定死为openalex
     * data 保留原始JSON原文（不重序列化），只解析出 entity_id 用于唯一性区分。
     * OpenAlex 的 id 是 URL（https://openalex.org/A5023888391），取最后一段作为 entity_id。
     * 单行数据本身有问题（解不开的JSON / 没有id）属于 Poisoned，
     * 按 worker 阶段设计不丢弃、落 dead row 后继续解析，不让一行脏数据拖垮整个文件；
     * 空行不是数据，静默跳过不算 Poisoned；数量差异交给 reconcile 阶段校验。
     * 纯函数：不碰数据库，坏行只组装成 DeadRow 返回，由 importFile 在 chunk 事务里落库。
     *
     * @param entity 当前任务的entity，决定 entity_type
     * @param fileId 当前 file_task 主键，dead row 定位用
     * @param lineOffset 本批第一条之前已读的行数，用于算 dead row 的绝对行号
     * @param origin_list 一个批次的原始JSON行
     * @return 好行(可落库实体) + 坏行(dead row) 两条通道
     */
    public static TransformResult transform(Entity entity, long fileId, long lineOffset, List<String> origin_list){
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(origin_list, "origin_list");
        String entityType = entity.name().toLowerCase(Locale.ROOT);
        List<SocialEntity> socialEntities = new ArrayList<>(origin_list.size());
        List<DeadRow> deadRows = new ArrayList<>();
        for (int i = 0; i < origin_list.size(); i++) {
            String line = origin_list.get(i);
            long lineNo = lineOffset + i + 1;              // 文件内绝对行号（1 基）
            if (line == null || line.isBlank()) {
                continue;                                  // 空行不是数据，静默跳过（不算 Poisoned）
            }
            String rawId;
            try {
                JsonNode node = MAPPER.readTree(line);
                rawId = node.path("id").asText("");
            } catch (JsonProcessingException e) {
                // Poisoned：数据本身解不开，落 dead row 继续
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("遇到Poisoned数据错误信息:{}",msg);
                deadRows.add(new DeadRow(fileId, lineNo, line, msg, entityType));
                continue;
            }
            int slash = rawId.lastIndexOf('/');
            String entityId = slash < 0 ? rawId : rawId.substring(slash + 1);
            if (entityId.isBlank()) {
                // 没有id就无法做幂等upsert，同样当 Poisoned 处理，落 dead row 继续
                deadRows.add(new DeadRow(fileId, lineNo, line, "missing id", entityType));
                continue;
            }
            socialEntities.add(new SocialEntity(null, entityType, PLATFORM, entityId, line));
        }
        return new TransformResult(socialEntities, deadRows);
    }

    private Worker() {
    }
}
