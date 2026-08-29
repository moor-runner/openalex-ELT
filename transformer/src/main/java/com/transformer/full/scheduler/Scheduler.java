package com.transformer.full.scheduler;

import com.transformer.dao.SocialEntityDAO;
import com.transformer.dao.SyncTaskDAO;
import com.transformer.entity.SyncTask;
import com.common.entity.SocialEntity;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.logging.LogFactory;

@Component
public class Scheduler{
    private static int THREAD_SIZE=16;
    @Resource
    SyncTaskDAO syncTaskDAO;
    @Resource
    SocialEntityDAO socialEntityDAO;

    ExecutorService threadPool = Executors.newFixedThreadPool(THREAD_SIZE);

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    class SyncSwitch{
        //AQS共享模式制作的开关
    }
    /**
     * 这个方法只负责任务的获取和开始执行，不负责完成
     * 前置状态：SyncTask表里面有任务记录
     * 后置状态：ES，OSS里面存在这个Task对应的id范围的SocialEntity表里面的所有记录，Task状态置为完成
     * 失败情况：
     * 如果数据本身出现已知的异常情况，记录到死信表，继续执行
     * 出现程序处理异常，立即抛出
     * 出现崩溃情况，使用超时机制和幂等机制进行处理
     */
    public void schedule(){
        //创建线程池
        //提交16个任务，while true循环拉取，执行写操作
        //写操作进行分发操作，分发到不同的writer，分发之后进行转换操作和写入操作
        //写操作，需要幂等性保证
        //两次写操作需要同时成功才能将状态置为完成，at least once
        
        for (int i=0;i<16;i++){
            threadPool.execute(()->{
                while(true) {
                    //读取SyncTask表
                    Optional<SyncTask> task=syncTaskDAO.selectOne();
                    if(!task.isPresent()){
                        log.warn("");
                        break;
                    }
                    long min=task.get().getMin();
                    long max=task.get().getMax();
                    //读取SocialEntity表对应记录
                    List<SocialEntity> list=socialEntityDAO.selectBatch(min,max);
                    //调用分发方法
                    dispatch(list);
                }
            });
        }
    }

    @PreDestroy
    public void shutdown(){

    }

    /**
     * 把读取到的数据分发给不同的Writer，需要找到所有的Writer，传递数据给Writer
     */
    public void dispatch(List<SocialEntity> list){
        
    }
}