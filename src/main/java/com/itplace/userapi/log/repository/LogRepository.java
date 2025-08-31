package com.itplace.userapi.log.repository;

import com.itplace.userapi.log.entity.LogDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LogRepository extends MongoRepository<LogDocument, String>, CustomLogRepository {
}
