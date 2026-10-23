-- Adapters match trimmed, case-normalized labels. The DB must enforce the same identity.
CREATE UNIQUE INDEX uq_category_alias_normalized
    ON partner_category_alias (carrier_code, upper(btrim(source_label)));
