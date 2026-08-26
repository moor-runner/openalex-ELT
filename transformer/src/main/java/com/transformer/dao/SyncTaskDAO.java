package com.transformer.dao;

import com.transformer.entity.SyncTask;

import jakarta.annotation.Resource;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class SyncTaskDAO{

    /**
     * id 由自增列生成；created_at / updated_at 交给数据库默认值，
     * 保证心跳判定 {@code now - updated_at} 前后用的是同一个时钟。
     */
    private static final String INSERT = """
            INSERT INTO sync_task (status, `min`, `max`)
            VALUES (?, ?, ?)
            """;

    /**
     * 水位：已被分片覆盖到的最大 id。取全部任务的 max，不按 status 过滤——
     * 一片只要切出来了就算覆盖过，跑没跑完是 worker 的事。
     */
    private static final String SELECT_MAX_SHARDED_ID = """
            SELECT MAX(`max`) AS max_sharded_id
              FROM sync_task
            """;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 查询分片水位，供 {@code shard()} 从水位以上接着切。
     * <p>
     * 和 {@link SocialEntityDAO#selectMinAndMax()} 同一个坑：聚合查询在空表上
     * 返回的是一行 NULL，不是零行，判空只能靠 {@code wasNull()}。
     *
     * @return 切过任务时返回已覆盖到的最大 id，从未切过返回 empty
     */
    public Optional<Long> selectMaxShardedId(){
        return jdbcTemplate.query(SELECT_MAX_SHARDED_ID, rs -> {
            if(!rs.next()){
                return Optional.empty();
            }
            long maxShardedId = rs.getLong("max_sharded_id");
            if(rs.wasNull()){
                return Optional.empty();
            }
            return Optional.of(maxShardedId);
        });
    }

    /**
     * 批量插入分片任务。空入参直接返回，不发起数据库交互。
     */
    public void insertBatch(List<SyncTask> list){
        if(list == null || list.isEmpty()){
            return;
        }
        jdbcTemplate.batchUpdate(INSERT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                SyncTask syncTask = list.get(i);
                ps.setString(1, syncTask.getStatus());
                ps.setLong(2, syncTask.getMin());
                ps.setLong(3, syncTask.getMax());
            }

            @Override
            public int getBatchSize() {
                return list.size();
            }
        });
    }
}
