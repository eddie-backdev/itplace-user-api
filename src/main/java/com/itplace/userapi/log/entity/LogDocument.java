package com.itplace.userapi.log.entity;

import jakarta.persistence.Id;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "logs")
public class LogDocument {
    @Id
    private String id;
    private Long userId;
    private String event;
    private Long benefitId;
    private String benefitName;
    private Long partnerId;
    private String partnerName;
    private String path;
    private String param;
    private Instant loggingAt;
}
