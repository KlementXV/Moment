-- One like per wallet and post. Posts are never deleted before their likes.
CREATE TABLE likes (
    commitment TEXT COLLATE "C" NOT NULL REFERENCES posts (commitment) ON DELETE CASCADE,
    wallet TEXT NOT NULL,
    created BIGINT NOT NULL,
    PRIMARY KEY (commitment, wallet)
);
