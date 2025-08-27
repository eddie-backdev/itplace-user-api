package com.itplace.userapi.common.db;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@Configuration
@EnableMongoRepositories(basePackages = "com.itplace.userapi.log")
public class MongoConfig {
}
