package com.transformer.writer;

import com.common.entity.SocialEntity;

import java.util.List;

public class EsWriter implements Writer{
    //引入Es的Client
    
    /**
     * 输入:SocialEntity list,拥有的转换纯函数，数据没有问题，转换函数没有问题
     * 输出:ES写入转换后的这批List成功
     * 异常处理:
     * 1.数据有问题
     * 2.转换服务有问题
     * 3.es写入异常
     * 崩溃处理:由上游编排层感知并重新拉取，id保证幂等性
     * todo:
     * 不变量
     */
    @Override
    public void write(List<SocialEntity> list){
        List<>//转换之后的类型
        for(SocialEntity socialEntity:list){
            converter.convert(socialEntity,socialEntity.getPlatform(),socialEntity.getEntityType())           
            list.add(socialEntity);
        }
        esclient.bulk(list);
    }
}