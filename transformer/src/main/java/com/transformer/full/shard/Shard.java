package com.transformer.full.shard;

import com.transformer.dao.SocialEntityDAO;
import com.transformer.dao.SyncTaskDAO;
import com.transformer.entity.SyncTask;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public class Shard {
    public static final int BATCH_SIZE=1000;
    private static final Logger log = LoggerFactory.getLogger(Shard.class);
    @Resource
    SocialEntityDAO socialEntityDAO;
    @Resource
    SyncTaskDAO syncTaskDAO;
    /**
     * 把social entity表按照BATCH_SIZE进行切分，放入任务表
     * 前置条件：social_entity表
     * social_entity需要满足存在，且有数据的条件
     * 后置条件：sync_task表
     * 对应每批次大小一条同步任务记录
     */
    public void shard(){
        //查询之前同步的水位

        //查询min和max
        Optional<SocialEntityDAO.MinMax> minMax =socialEntityDAO.selectMinAndMax();
        if(!minMax.isPresent()){
            log.warn("");
            return;
        }
        long min=minMax.get().min();
        long max=minMax.get().max();
        //根据水位对min max进行判断和改造，校验

        //批量拆分
        List list=splitTasks(min,max,BATCH_SIZE);
        //批量任务插入
        syncTaskDAO.insertBatch(list);
    }

    /**
     * 按照batch批次大小拆分min-max范围的id，每个批次对应一个SyncTask加入list中
     * @param min
     * @param max
     * @param batch
     * @return
     */
    public List<SyncTask> splitTasks(long min, long max, int batch){

    }

}
