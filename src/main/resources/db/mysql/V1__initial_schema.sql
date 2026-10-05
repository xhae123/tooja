CREATE TABLE teams (
    id INT PRIMARY KEY CHECK (id BETWEEN 1 AND 20),
    name TEXT NOT NULL, description TEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE accounts (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    code_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    role VARCHAR(16) NOT NULL CHECK (role IN ('INVESTOR','ADMIN')),
    kind VARCHAR(16) CHECK (kind IN ('PARTICIPANT','STAFF')),
    team_id INT, alias VARCHAR(255) NOT NULL,
    activated INT NOT NULL DEFAULT 0 CHECK (activated IN (0,1)),
    balance INT NOT NULL DEFAULT 0 CHECK (balance BETWEEN 0 AND 1000000),
    deleted_at VARCHAR(40), FOREIGN KEY (team_id) REFERENCES teams(id),
    CHECK ((role='ADMIN' AND kind IS NULL AND team_id IS NULL) OR
        (role='INVESTOR' AND kind IS NOT NULL AND
            ((kind='PARTICIPANT' AND team_id IS NOT NULL) OR (kind='STAFF' AND team_id IS NULL))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE grants (
    account_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    amount INT NOT NULL CHECK (amount=1000000), granted_at VARCHAR(40) NOT NULL,
    FOREIGN KEY (account_id) REFERENCES accounts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE sessions (
    token_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    role VARCHAR(16) NOT NULL, account_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    csrf VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at VARCHAR(40) NOT NULL, revoked_at VARCHAR(40),
    FOREIGN KEY (account_id) REFERENCES accounts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE contexts (
    token_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    role VARCHAR(16) NOT NULL, csrf VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE investments (
    id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    account_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    team_id INT NOT NULL, amount INT NOT NULL CHECK (amount BETWEEN 100000 AND 700000 AND amount%100000=0),
    confirmed_at VARCHAR(40) NOT NULL, balance_after INT NOT NULL CHECK (balance_after>=0),
    version BIGINT NOT NULL, UNIQUE (account_id,team_id),
    FOREIGN KEY (account_id) REFERENCES accounts(id), FOREIGN KEY (team_id) REFERENCES teams(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE requests (
    account_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    team_id INT NOT NULL, amount INT NOT NULL, status VARCHAR(16) NOT NULL,
    investment_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    http_status INT, error_code VARCHAR(64), error_message TEXT,
    PRIMARY KEY (account_id,request_key), FOREIGN KEY (account_id) REFERENCES accounts(id),
    FOREIGN KEY (investment_id) REFERENCES investments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE metadata (id INT PRIMARY KEY CHECK (id=1), version BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB;
INSERT INTO metadata VALUES (1,0);
CREATE TABLE investment_control (
    id INT PRIMARY KEY CHECK (id=1), status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING','PAUSED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0), updated_at VARCHAR(40)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
INSERT INTO investment_control VALUES (1,'RUNNING',0,NULL);
