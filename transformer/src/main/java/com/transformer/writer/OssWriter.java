package com.transformer.writer;

import com.common.entity.SocialEntity;

import java.util.List;

public class OssWriter implements Writer{
    //引入minio的client

    /**
     * 每一个批次写入一个文件，每行一个json
     * 使用min id和max id来标识文件名，实现幂等性
     */
    @Override
    public void write(List<SocialEntity> list){
        
    }
}