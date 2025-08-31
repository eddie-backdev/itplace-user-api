package com.itplace.userapi.ai.forbiddenword.repository;

import com.itplace.userapi.ai.forbiddenword.entity.ForbiddenWord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ForbiddenWordRepository extends JpaRepository<ForbiddenWord, Long> {
}
