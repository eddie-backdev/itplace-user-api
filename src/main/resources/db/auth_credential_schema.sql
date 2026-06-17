CREATE TABLE IF NOT EXISTS authCredential (
    authCredentialId BIGSERIAL PRIMARY KEY,
    userId BIGINT NOT NULL REFERENCES users(userId) ON DELETE CASCADE,
    type VARCHAR(30) NOT NULL,
    provider VARCHAR(30),
    providerUserId VARCHAR(255),
    passwordHash VARCHAR(255),
    createdDate TIMESTAMP,
    lastModifiedDate TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_auth_credential_local_password
    ON authCredential (userId, type)
    WHERE type = 'LOCAL_PASSWORD';

CREATE UNIQUE INDEX IF NOT EXISTS uq_auth_credential_oauth_provider
    ON authCredential (provider, providerUserId)
    WHERE type = 'OAUTH';

INSERT INTO authCredential (userId, type, passwordHash, createdDate, lastModifiedDate)
SELECT u.userId, 'LOCAL_PASSWORD', u.password, NOW(), NOW()
FROM users u
WHERE u.password IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM authCredential c
      WHERE c.userId = u.userId
        AND c.type = 'LOCAL_PASSWORD'
  );

INSERT INTO authCredential (userId, type, provider, providerUserId, createdDate, lastModifiedDate)
SELECT s.userId, 'OAUTH', s.provider, s.providerId, NOW(), NOW()
FROM socialAccount s
WHERE NOT EXISTS (
    SELECT 1
    FROM authCredential c
    WHERE c.type = 'OAUTH'
      AND c.provider = s.provider
      AND c.providerUserId = s.providerId
);
