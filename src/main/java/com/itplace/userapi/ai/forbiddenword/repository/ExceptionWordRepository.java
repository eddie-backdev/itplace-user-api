package com.itplace.userapi.ai.forbiddenword.repository;

import com.itplace.userapi.ai.forbiddenword.entity.ExceptionWord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExceptionWordRepository extends JpaRepository<ExceptionWord, Long> {
}
