package com.itplace.userapi.ai.forbiddenword.entity;

import com.itplace.userapi.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "forbiddenWord")
public class ForbiddenWord extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long wordId;

    @Column(name = "word", nullable = false, unique = true)
    private String word;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "class", nullable = false)
    private String wordClass;

}
