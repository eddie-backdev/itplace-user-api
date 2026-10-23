SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '180s';

DO $$
DECLARE
    legacy_table TEXT;
    legacy_column TEXT;
    row_count BIGINT;
BEGIN
    FOREACH legacy_table IN ARRAY ARRAY['membershiphistory', 'membership', 'uplusdata'] LOOP
        IF to_regclass(format('%I.%I', current_schema(), legacy_table)) IS NOT NULL THEN
            EXECUTE format('LOCK TABLE %I.%I IN ACCESS EXCLUSIVE MODE', current_schema(), legacy_table);
            EXECUTE format('SELECT COUNT(*) FROM %I.%I', current_schema(), legacy_table)
                INTO row_count;
            IF row_count > 0 THEN
                RAISE EXCEPTION 'Refusing to remove legacy table %.%: % rows remain',
                    current_schema(), legacy_table, row_count;
            END IF;
        END IF;
    END LOOP;

    IF to_regclass(format('%I.%I', current_schema(), 'users')) IS NOT NULL THEN
        LOCK TABLE users IN ACCESS EXCLUSIVE MODE;
        FOREACH legacy_column IN ARRAY ARRAY['membershipid', 'membershipId'] LOOP
            IF EXISTS (
                SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'users'
                  AND column_name = legacy_column
            ) THEN
                EXECUTE format(
                    'SELECT COUNT(*) FROM %I.%I WHERE length(btrim(%I::text)) > 0',
                    current_schema(), 'users', legacy_column
                ) INTO row_count;
                IF row_count > 0 THEN
                    RAISE EXCEPTION 'Refusing to remove users.%: % nonblank values remain',
                        legacy_column, row_count;
                END IF;
            END IF;
        END LOOP;
    END IF;
END
$$;

ALTER TABLE IF EXISTS users
    DROP COLUMN IF EXISTS membershipid,
    DROP COLUMN IF EXISTS "membershipId";

DROP TABLE IF EXISTS membershiphistory;
DROP TABLE IF EXISTS membership;
DROP TABLE IF EXISTS uplusdata;
