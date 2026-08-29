package com.transformer.dao;

import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class SocialEntityDAO{

    private static final String SELECT_MIN_MAX = """
            SELECT MIN(id) AS min_id, MAX(id) AS max_id
              FROM social_entity
            """;

    @Resource
    private JdbcTemplate jdbcTemplate;

    public record MinMax(long min,long max){}

    /**
     * 取 social_entity 主键区间，供分片切分使用。
     * <p>
     * 注意聚合查询在空表上返回的是一行 (NULL, NULL)，不是零行，
     * 所以判空靠 {@code wasNull()}，不能指望 queryForObject 抛异常。
     *
     * @return 表非空时返回 [min, max]，空表返回 empty
     */
    public Optional<MinMax> selectMinAndMax(){
        return jdbcTemplate.query(SELECT_MIN_MAX, rs -> {
            if(!rs.next()){
                return Optional.empty();
            }
            long min = rs.getLong("min_id");
            if(rs.wasNull()){
                return Optional.empty();
            }
            return Optional.of(new MinMax(min, rs.getLong("max_id")));
        });
    }

    /**
     * 查询[min,max]范围内的所有SocialEntity数据，添加超时重试逻辑
     */
    public List<SocialEntity> selectBatch(long min,long max){

    }
}
