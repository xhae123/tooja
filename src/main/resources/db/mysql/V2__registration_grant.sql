-- Existing unpaid registrations receive their initial allocation once during the policy transition.
-- Already-issued balances and investments are preserved. Future grants are inserted with account provisioning.
UPDATE accounts a
LEFT JOIN grants g ON g.account_id=a.id
SET a.balance=1000000
WHERE a.role='INVESTOR' AND a.deleted_at IS NULL AND g.account_id IS NULL;

INSERT INTO grants(account_id,amount,granted_at)
SELECT a.id,1000000,DATE_FORMAT(UTC_TIMESTAMP(6),'%Y-%m-%dT%H:%i:%s.%fZ')
FROM accounts a LEFT JOIN grants g ON g.account_id=a.id
WHERE a.role='INVESTOR' AND a.deleted_at IS NULL AND g.account_id IS NULL;

ALTER TABLE accounts DROP COLUMN activated;
