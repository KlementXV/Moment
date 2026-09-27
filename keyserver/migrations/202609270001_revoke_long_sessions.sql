-- Sessions previously lasted 30 days although the signed challenge promised
-- 15 minutes. Revoke existing tokens once; reconnecting issues a 15-minute token.
DELETE FROM sessions;
