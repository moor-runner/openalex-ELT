package com.transformer.full.shard;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.transformer.dao.SocialEntityDAO;
import com.transformer.dao.SyncTaskDAO;
import com.transformer.entity.SyncTask;

import jakarta.annotation.Resource;
@Component
public class Shard {
    public static final int BATCH_SIZE=1000;
    private static final Logger log = LoggerFactory.getLogger(Shard.class);
    @Resource
    SocialEntityDAO socialEntityDAO;
    @Resource
    SyncTaskDAO syncTaskDAO;
    /**
     * 查询水位，找出水位以上的id范围,没有需要同步的就直接退出
     * 把social entity表按照BATCH_SIZE进行切分，放入任务表
     * 前置条件：social_entity表
     * social_entity需要满足存在，且有数据的条件
     * 后置条件：sync_task表
     * 对应每批次大小一条同步任务记录
     */
    @Transactional
    public void shard(){
        //查询min和max
        Optional<SocialEntityDAO.MinMax> minMax =socialEntityDAO.selectMinAndMax();
        if(!minMax.isPresent()){
            log.warn("");
            return;
        }
        //查询之前同步的水位
        Optional<Long> watermark=syncTaskDAO.selectMaxShardedId();
        long min=minMax.get().min();
        long max=minMax.get().max();
        long from = watermark.map(w -> Math.max(min, w + 1)).orElse(min);
        if(from > max){
            log.info("水位已到{},没有新数据需要分片", max);
            return;
        }
        //批量拆分
        List<SyncTask> list=splitTasks(from,max,BATCH_SIZE);
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
        if(batch<1){
            throw new IllegalArgumentException("batch必须为正数,实际:"+batch);
        }
        if(min>max){
            //min>max属于不该发生的状态,静默返回空list会让整轮同步假成功
            throw new IllegalArgumentException("区间倒挂,min:"+min+",max:"+max);
        }
        /*
            先考虑最简单的实现:
            第一次:min-cursor
            中间每次:上一个curosr作为min，下一个cursor作为max
            最后一次:上一个cursor-max
        */
        List<SyncTask> list=new ArrayList<>();
        long cursor=min;
        while (cursor<=max) { 
            SyncTask syncTask=new SyncTask();
            syncTask.setMin(cursor);
            cursor+=batch-1;
            syncTask.setMax(Math.min(cursor,max));
            syncTask.setStatus("Pending");
            list.add(syncTask);
            cursor++;
        }
        return list;
    }

}
