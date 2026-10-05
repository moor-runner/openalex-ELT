package com.transformer.writer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.common.entity.SocialEntity;
import com.transformer.converter.AuthorConverter;
import jakarta.annotation.Resource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EsWriter implements Writer{
    //引入Es的Client
    @Resource
    ElasticsearchClient elasticsearchClient;
    @Resource
    AuthorConverter authorConverter;
    /**
     * 需求：把获取到的SocialEntity list调用转换函数，插入对应的数据库
     * 输入:SocialEntity list,拥有的转换纯函数，数据没有问题，转换函数没有问题
     * 输出:ES写入转换后的这批List成功
     * 异常处理:
     * 1.数据有问题
     * 2.转换服务有问题
     * 3.es写入异常--重试
     * 崩溃处理:由上游编排层感知并重新拉取，id保证幂等性
     * todo:
     * 不变量
     */
    @Override
    public void write(List<SocialEntity> list){
        //convert
        List<Map<String,Object>> docs=new ArrayList<>();
        for(SocialEntity socialEntity:list){
            //todo 转换操作的异常处理
            Map<String, Object> doc= authorConverter.convert(socialEntity);
            docs.add(doc);
        }

        //request response
        BulkRequest.Builder br = new BulkRequest.Builder();
        for(Map<String,Object> doc:docs){
            br.operations(op->op.index(
                    idx->idx
                            .index("openalex_authors")
                            .id(doc.get("entity_id").toString())
                            .document(doc)
            ));
        }
        BulkResponse response;
        try {
            response = elasticsearchClient.bulk(br.build());
        } catch (IOException e) {
            throw new RetryableException(e);
        }
        //todo 对响应错误进行处理
        if(response.errors()){

        }
    }
}