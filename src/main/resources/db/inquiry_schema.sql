CREATE TABLE inquiries (
    id                  BIGSERIAL PRIMARY KEY,
    category            VARCHAR(50) NOT NULL,
    title               VARCHAR(200) NOT NULL,
    content             TEXT NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'UNREAD',
    createdDate       TIMESTAMP NOT NULL,
    lastModifiedDate  TIMESTAMP NOT NULL
);
CREATE INDEX idx_inquiries_status_created ON inquiries(status, createdDate DESC);
