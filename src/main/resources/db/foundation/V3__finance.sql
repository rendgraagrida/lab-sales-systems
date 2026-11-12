-- Finance objects are scoped by both workspace and legal entity.
CREATE TABLE chart_accounts (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 code text NOT NULL, name text NOT NULL, account_type text NOT NULL, active boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 1, created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,code),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id)
);
CREATE TABLE fiscal_periods (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 code text NOT NULL, starts_on date NOT NULL, ends_on date NOT NULL, status text NOT NULL CHECK(status IN ('OPEN','CLOSED')),
 version bigint NOT NULL DEFAULT 1, created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(starts_on <= ends_on), UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,code),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 EXCLUDE USING gist (workspace_id extensions.gist_int8_ops WITH =, entity_id extensions.gist_int8_ops WITH =, daterange(starts_on,ends_on,'[]') WITH &&)
);
CREATE TABLE tax_codes (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 code text NOT NULL, name text NOT NULL, active boolean NOT NULL DEFAULT true, version bigint NOT NULL DEFAULT 1,
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,code),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id)
);
CREATE TABLE tax_versions (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, tax_code_id bigint NOT NULL,
 valid_from date NOT NULL, valid_to date, rate_pct numeric(9,4) NOT NULL CHECK(rate_pct BETWEEN 0 AND 100),
 price_inclusive boolean NOT NULL, rounding_mode text NOT NULL CHECK(rounding_mode='HALF_UP'), input_account_id bigint NOT NULL, output_account_id bigint NOT NULL,
 version bigint NOT NULL DEFAULT 1, created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(valid_to IS NULL OR valid_to>valid_from),
 FOREIGN KEY(workspace_id,entity_id,tax_code_id) REFERENCES tax_codes(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,input_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,output_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 EXCLUDE USING gist (workspace_id extensions.gist_int8_ops WITH =, entity_id extensions.gist_int8_ops WITH =, tax_code_id extensions.gist_int8_ops WITH =, daterange(valid_from,valid_to,'[)') WITH &&)
);
CREATE TABLE document_sequences (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 document_type text NOT NULL, next_value bigint NOT NULL CHECK(next_value>0), version bigint NOT NULL DEFAULT 1,
 UNIQUE(workspace_id,entity_id,document_type), FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id)
);
CREATE TABLE journal_entries (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 number text NOT NULL, source_type text NOT NULL, source_id text NOT NULL, posting_key text NOT NULL, posting_date date NOT NULL,
 currency text NOT NULL, description text NOT NULL, state text NOT NULL CHECK(state IN ('DRAFT','POSTED')),
 created_by bigint NOT NULL REFERENCES app_users(id), version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(), posted_at timestamptz,
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,number), UNIQUE(workspace_id,entity_id,posting_key),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 CHECK((state='DRAFT' AND posted_at IS NULL) OR (state='POSTED' AND posted_at IS NOT NULL))
);
CREATE TABLE journal_lines (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 journal_id bigint NOT NULL, account_id bigint NOT NULL, debit numeric(18,2) NOT NULL, credit numeric(18,2) NOT NULL,
 CHECK((debit>0 AND credit=0) OR (credit>0 AND debit=0)),
 FOREIGN KEY(workspace_id,entity_id,journal_id) REFERENCES journal_entries(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,account_id) REFERENCES chart_accounts(workspace_id,entity_id,id)
);
CREATE FUNCTION journal_entry_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.state='POSTED' THEN RAISE EXCEPTION 'posted journal is immutable' USING ERRCODE='23514'; END IF;
 RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER journal_entry_immutable BEFORE UPDATE OR DELETE ON journal_entries FOR EACH ROW EXECUTE FUNCTION journal_entry_immutable();
CREATE FUNCTION journal_line_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_state text;
BEGIN
 IF TG_OP <> 'INSERT' THEN
  SELECT state INTO parent_state FROM journal_entries WHERE id=OLD.journal_id FOR UPDATE;
  IF parent_state='POSTED' THEN RAISE EXCEPTION 'posted journal lines are immutable' USING ERRCODE='23514'; END IF;
 END IF;
 IF TG_OP <> 'DELETE' THEN
  SELECT state INTO parent_state FROM journal_entries WHERE id=NEW.journal_id FOR UPDATE;
  IF parent_state='POSTED' THEN RAISE EXCEPTION 'posted journal lines are immutable' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER journal_line_immutable BEFORE INSERT OR UPDATE OR DELETE ON journal_lines FOR EACH ROW EXECUTE FUNCTION journal_line_immutable();
CREATE FUNCTION journal_balance_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE j bigint; s text; d numeric; c numeric; n bigint; curr text; base text;
BEGIN
 IF TG_TABLE_NAME='journal_entries' THEN j=NEW.id; ELSE j=COALESCE(NEW.journal_id,OLD.journal_id); END IF;
 SELECT e.state,e.currency,l.base_currency INTO s,curr,base FROM journal_entries e JOIN legal_entities l ON l.workspace_id=e.workspace_id AND l.id=e.entity_id WHERE e.id=j;
 IF s='POSTED' THEN
  SELECT count(*),coalesce(sum(debit),0),coalesce(sum(credit),0) INTO n,d,c FROM journal_lines WHERE journal_id=j;
  IF n<2 OR d<=0 OR d<>c OR curr<>base THEN RAISE EXCEPTION 'invalid posted journal' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_entry_balance AFTER INSERT OR UPDATE ON journal_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION journal_balance_guard();
CREATE CONSTRAINT TRIGGER journal_line_balance AFTER INSERT OR UPDATE OR DELETE ON journal_lines DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION journal_balance_guard();
CREATE TABLE opening_balance_sources (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, source_key text NOT NULL,
 revision bigint NOT NULL CHECK(revision>0), posting_date date NOT NULL, currency text NOT NULL, payload_hash text NOT NULL,
 status text NOT NULL CHECK(status='POSTED'), created_by bigint NOT NULL REFERENCES app_users(id), approved_by bigint NOT NULL REFERENCES app_users(id),
 journal_id bigint NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(workspace_id,entity_id,id),
 UNIQUE(workspace_id,entity_id,source_key,revision), FOREIGN KEY(workspace_id,entity_id,journal_id) REFERENCES journal_entries(workspace_id,entity_id,id)
);
CREATE TABLE scenario_baseline_versions (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL UNIQUE REFERENCES scenario_workspaces(id), entity_id bigint NOT NULL,
 baseline_key text NOT NULL CHECK(baseline_key IN ('EMPTY','O2C_GOODS')), baseline_version text NOT NULL, source_hash text NOT NULL, payload_hash text NOT NULL,
 receipt_key text NOT NULL UNIQUE, status text NOT NULL CHECK(status='READY'), created_by bigint NOT NULL REFERENCES app_users(id),
 approved_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id)
);
CREATE TABLE inventory_balances (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, product_id bigint NOT NULL, warehouse_id bigint NOT NULL,
 on_hand numeric(18,6) NOT NULL CHECK(on_hand>=0), reserved numeric(18,6) NOT NULL DEFAULT 0 CHECK(reserved>=0 AND reserved<=on_hand),
 value numeric(18,2) NOT NULL CHECK(value>=0), version bigint NOT NULL DEFAULT 1, CHECK(on_hand<>0 OR value=0),
 UNIQUE(workspace_id,entity_id,product_id,warehouse_id), FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,warehouse_id) REFERENCES warehouses(workspace_id,entity_id,id)
);
CREATE TABLE inventory_valuation_pools (
 workspace_id bigint NOT NULL, entity_id bigint NOT NULL, product_id bigint NOT NULL, quantity numeric(18,6) NOT NULL CHECK(quantity>=0),
 value numeric(18,2) NOT NULL CHECK(value>=0), last_posting_date date NOT NULL, pool_sequence bigint NOT NULL CHECK(pool_sequence>0),
 CHECK(quantity<>0 OR value=0), PRIMARY KEY(workspace_id,entity_id,product_id),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id), FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id)
);
CREATE TABLE instrument_serials (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, product_id bigint NOT NULL, warehouse_id bigint NOT NULL,
 serial text NOT NULL, status text NOT NULL CHECK(status='IN_STOCK'), opening_source_id bigint NOT NULL,
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,serial), FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,warehouse_id) REFERENCES warehouses(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,opening_source_id) REFERENCES opening_balance_sources(workspace_id,entity_id,id)
);
CREATE TABLE inventory_lots (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, product_id bigint NOT NULL, warehouse_id bigint NOT NULL,
 lot text NOT NULL, expires_on date, opening_source_id bigint NOT NULL, UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,product_id,lot),
 FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id), FOREIGN KEY(workspace_id,entity_id,warehouse_id) REFERENCES warehouses(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,opening_source_id) REFERENCES opening_balance_sources(workspace_id,entity_id,id)
);
CREATE TABLE stock_movements (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, product_id bigint NOT NULL, warehouse_id bigint NOT NULL,
 opening_source_id bigint NOT NULL, quantity_delta numeric(18,6) NOT NULL CHECK(quantity_delta>0), value_delta numeric(18,2) NOT NULL CHECK(value_delta>=0),
 posting_date date NOT NULL, source_line_key text NOT NULL, serial_id bigint, lot_id bigint, created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,opening_source_id,source_line_key), CHECK(serial_id IS NULL OR lot_id IS NULL),
 FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id), FOREIGN KEY(workspace_id,entity_id,warehouse_id) REFERENCES warehouses(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,opening_source_id) REFERENCES opening_balance_sources(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,serial_id) REFERENCES instrument_serials(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,lot_id) REFERENCES inventory_lots(workspace_id,entity_id,id)
);

CREATE FUNCTION legal_entity_currency_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.base_currency IS DISTINCT FROM OLD.base_currency AND (
  EXISTS (SELECT 1 FROM chart_accounts WHERE workspace_id=OLD.workspace_id AND entity_id=OLD.id)
  OR EXISTS (SELECT 1 FROM fiscal_periods WHERE workspace_id=OLD.workspace_id AND entity_id=OLD.id)
  OR EXISTS (SELECT 1 FROM tax_codes WHERE workspace_id=OLD.workspace_id AND entity_id=OLD.id)
  OR EXISTS (SELECT 1 FROM journal_entries WHERE workspace_id=OLD.workspace_id AND entity_id=OLD.id)
  OR EXISTS (SELECT 1 FROM inventory_valuation_pools WHERE workspace_id=OLD.workspace_id AND entity_id=OLD.id)
  OR EXISTS (SELECT 1 FROM price_books WHERE workspace_id=OLD.workspace_id AND entity_id=OLD.id)
 ) THEN RAISE EXCEPTION 'entity currency is referenced' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER legal_entity_currency_guard BEFORE UPDATE OF base_currency ON legal_entities FOR EACH ROW EXECUTE FUNCTION legal_entity_currency_guard();

CREATE TABLE payment_posting_config (
 workspace_id bigint NOT NULL, entity_id bigint NOT NULL, bank_account_id bigint NOT NULL,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), updated_by bigint NOT NULL REFERENCES app_users(id),
 PRIMARY KEY(workspace_id,entity_id),
 FOREIGN KEY(workspace_id,entity_id,bank_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id)
);
