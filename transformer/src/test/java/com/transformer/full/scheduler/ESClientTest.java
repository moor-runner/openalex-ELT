package com.transformer.full.scheduler;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetRequest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;

public class ESClientTest {
    @Resource
    ElasticsearchClient elasticsearchClient;
    @Test
    public void connect(){

        elasticsearchClient.index();
    }
}
