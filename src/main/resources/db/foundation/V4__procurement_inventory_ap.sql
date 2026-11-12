-- M2 local procurement and inventory. Existing opening identities and journals remain unchanged.
ALTER TABLE uoms ADD COLUMN quantity_scale integer NOT NULL DEFAULT 6 CHECK (quantity_scale BETWEEN 0 AND 6);
ALTER TABLE journal_entries ADD COLUMN business_posting_hash text;
ALTER TABLE journal_entries ADD COLUMN reverse_of bigint;
ALTER TABLE journal_entries ADD CONSTRAINT journal_reverse_scoped_fk FOREIGN KEY(workspace_id,entity_id,reverse_of) REFERENCES journal_entries(workspace_id,entity_id,id);
ALTER TABLE journal_entries ADD CONSTRAINT journal_reverse_unique UNIQUE(workspace_id,entity_id,reverse_of);
ALTER TABLE journal_entries ADD CONSTRAINT journal_no_self_reverse CHECK(reverse_of IS NULL OR reverse_of<>id);

-- A warehouse value was an opening-only duplication. Refuse inconsistent old data.
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM inventory_balances b FULL JOIN inventory_valuation_pools p
   ON p.workspace_id=b.workspace_id AND p.entity_id=b.entity_id AND p.product_id=b.product_id
   GROUP BY coalesce(b.workspace_id,p.workspace_id),coalesce(b.entity_id,p.entity_id),coalesce(b.product_id,p.product_id),p.quantity,p.value
   HAVING coalesce(sum(b.on_hand),0)<>coalesce(p.quantity,0) OR coalesce(sum(b.value),0)<>coalesce(p.value,0)) THEN
  RAISE EXCEPTION 'inconsistent V3 inventory balances and valuation pools' USING ERRCODE='23514';
 END IF;
 IF EXISTS (SELECT 1 FROM stock_movements m FULL JOIN inventory_valuation_pools p
  ON p.workspace_id=m.workspace_id AND p.entity_id=m.entity_id AND p.product_id=m.product_id
  GROUP BY coalesce(m.workspace_id,p.workspace_id),coalesce(m.entity_id,p.entity_id),coalesce(m.product_id,p.product_id),p.quantity,p.value,p.last_posting_date
  HAVING coalesce(sum(m.quantity_delta),0)<>coalesce(p.quantity,0) OR coalesce(sum(m.value_delta),0)<>coalesce(p.value,0)
   OR max(m.posting_date)>p.last_posting_date) THEN
  RAISE EXCEPTION 'inconsistent V3 stock movements and valuation pool' USING ERRCODE='23514';
 END IF;
 IF EXISTS (SELECT 1 FROM instrument_serials s LEFT JOIN stock_movements m
   ON m.workspace_id=s.workspace_id AND m.entity_id=s.entity_id AND m.serial_id=s.id
   GROUP BY s.workspace_id,s.entity_id,s.id,s.product_id,s.warehouse_id
   HAVING count(m.id)<>1 OR coalesce(sum(m.quantity_delta),0)<>1 OR bool_or(m.product_id<>s.product_id OR m.warehouse_id<>s.warehouse_id)) THEN
  RAISE EXCEPTION 'inconsistent V3 serial history' USING ERRCODE='23514';
 END IF;
 IF EXISTS (SELECT 1 FROM inventory_lots l LEFT JOIN stock_movements m
   ON m.workspace_id=l.workspace_id AND m.entity_id=l.entity_id AND m.lot_id=l.id
   GROUP BY l.workspace_id,l.entity_id,l.id,l.product_id,l.warehouse_id
   HAVING count(m.id)=0 OR bool_or(m.product_id<>l.product_id OR m.warehouse_id<>l.warehouse_id)) THEN
  RAISE EXCEPTION 'inconsistent V3 lot history' USING ERRCODE='23514';
 END IF;
 IF EXISTS (SELECT 1 FROM stock_movements m JOIN products p ON p.workspace_id=m.workspace_id AND p.id=m.product_id
  WHERE (p.tracking='SERIAL' AND m.serial_id IS NULL) OR (p.tracking IN ('LOT','LOT_EXPIRY') AND m.lot_id IS NULL)
    OR (p.tracking='NONE' AND (m.serial_id IS NOT NULL OR m.lot_id IS NOT NULL))) THEN
  RAISE EXCEPTION 'inconsistent V3 tracking mode' USING ERRCODE='23514';
 END IF;
END $$;
ALTER TABLE inventory_balances DROP COLUMN value;
ALTER TABLE instrument_serials ALTER COLUMN opening_source_id DROP NOT NULL;
ALTER TABLE instrument_serials DROP CONSTRAINT instrument_serials_status_check;
ALTER TABLE instrument_serials ADD CONSTRAINT instrument_serials_status_check CHECK(status IN ('IN_STOCK','ISSUED'));
ALTER TABLE inventory_lots ALTER COLUMN opening_source_id DROP NOT NULL;
ALTER TABLE stock_movements ALTER COLUMN opening_source_id DROP NOT NULL;
ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_quantity_delta_check;
ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_value_delta_check;
ALTER TABLE stock_movements ADD COLUMN source_type text NOT NULL DEFAULT 'OPENING';
ALTER TABLE stock_movements ADD COLUMN source_id text;
ALTER TABLE stock_movements ADD COLUMN pool_sequence bigint;
UPDATE stock_movements m SET source_id=m.opening_source_id::text,
 pool_sequence=(SELECT count(*) FROM stock_movements x WHERE x.workspace_id=m.workspace_id AND x.entity_id=m.entity_id AND x.product_id=m.product_id AND (x.posting_date,x.id)<=(m.posting_date,m.id));
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM inventory_valuation_pools p WHERE p.pool_sequence < (SELECT count(*) FROM stock_movements m WHERE m.workspace_id=p.workspace_id AND m.entity_id=p.entity_id AND m.product_id=p.product_id)) THEN
  RAISE EXCEPTION 'V3 stock movement sequence exceeds valuation pool watermark' USING ERRCODE='23514';
 END IF;
END $$;
ALTER TABLE stock_movements ALTER COLUMN source_id SET NOT NULL;
ALTER TABLE stock_movements ALTER COLUMN pool_sequence SET NOT NULL;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movement_nonzero CHECK(quantity_delta<>0);
ALTER TABLE stock_movements ADD CONSTRAINT stock_movement_value_direction CHECK((quantity_delta>0 AND value_delta>=0) OR (quantity_delta<0 AND value_delta<=0));
-- A transfer has two movement rows for a single pool event; sequence is indexed, not unique.
CREATE INDEX stock_movement_pool_sequence_idx ON stock_movements(workspace_id,entity_id,product_id,pool_sequence);
ALTER TABLE stock_movements ADD CONSTRAINT stock_movement_source_unique UNIQUE(workspace_id,entity_id,source_type,source_id,source_line_key);
CREATE TABLE inventory_lot_balances (
 workspace_id bigint NOT NULL, entity_id bigint NOT NULL, lot_id bigint NOT NULL, warehouse_id bigint NOT NULL,
 quantity numeric(18,6) NOT NULL CHECK(quantity>=0), reserved numeric(18,6) NOT NULL DEFAULT 0 CHECK(reserved>=0 AND reserved<=quantity), version bigint NOT NULL DEFAULT 1,
 PRIMARY KEY(workspace_id,entity_id,lot_id,warehouse_id), FOREIGN KEY(workspace_id,entity_id,lot_id) REFERENCES inventory_lots(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,warehouse_id) REFERENCES warehouses(workspace_id,entity_id,id)
);
INSERT INTO inventory_lot_balances(workspace_id,entity_id,lot_id,warehouse_id,quantity)
 SELECT workspace_id,entity_id,lot_id,warehouse_id,sum(quantity_delta) FROM stock_movements WHERE lot_id IS NOT NULL GROUP BY workspace_id,entity_id,lot_id,warehouse_id;

CREATE TABLE procurement_posting_config (
 workspace_id bigint NOT NULL, entity_id bigint NOT NULL, inventory_account_id bigint NOT NULL, ap_account_id bigint NOT NULL, grni_account_id bigint NOT NULL,
 adjustment_gain_account_id bigint NOT NULL, adjustment_loss_account_id bigint NOT NULL, service_expense_account_id bigint NOT NULL,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), updated_by bigint NOT NULL REFERENCES app_users(id), PRIMARY KEY(workspace_id,entity_id),
 FOREIGN KEY(workspace_id,entity_id,inventory_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,ap_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,grni_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,adjustment_gain_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,adjustment_loss_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,service_expense_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 CHECK(inventory_account_id<>ap_account_id AND inventory_account_id<>grni_account_id AND ap_account_id<>grni_account_id)
);
ALTER TABLE tax_versions ADD CONSTRAINT tax_versions_scope_id UNIQUE(workspace_id,entity_id,id);
ALTER TABLE instrument_serials ADD CONSTRAINT instrument_serials_scope_product_id UNIQUE(workspace_id,entity_id,id,product_id);
ALTER TABLE inventory_lots ADD CONSTRAINT inventory_lots_scope_product_id UNIQUE(workspace_id,entity_id,id,product_id);
ALTER TABLE stock_movements ADD CONSTRAINT stock_movement_serial_product_fk FOREIGN KEY(workspace_id,entity_id,serial_id,product_id) REFERENCES instrument_serials(workspace_id,entity_id,id,product_id);
ALTER TABLE stock_movements ADD CONSTRAINT stock_movement_lot_product_fk FOREIGN KEY(workspace_id,entity_id,lot_id,product_id) REFERENCES inventory_lots(workspace_id,entity_id,id,product_id);

CREATE TABLE purchase_requests (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 number text NOT NULL, status text NOT NULL CHECK(status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED')),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0), version bigint NOT NULL DEFAULT 1 CHECK(version>0), business_date date NOT NULL, purpose text NOT NULL,
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,number), FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id)
);
CREATE TABLE purchase_request_revisions (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, request_id bigint NOT NULL,
 revision bigint NOT NULL CHECK(revision>0), status text NOT NULL CHECK(status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED')),
 submitted_by bigint REFERENCES app_users(id), submitted_at timestamptz, decided_by bigint REFERENCES app_users(id), decided_at timestamptz, decision_reason text,
 business_date date NOT NULL, purpose text NOT NULL,
 payload_hash text, UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,request_id,revision),
 FOREIGN KEY(workspace_id,entity_id,request_id) REFERENCES purchase_requests(workspace_id,entity_id,id)
);
CREATE TABLE purchase_request_contributors (
 workspace_id bigint NOT NULL, entity_id bigint NOT NULL, revision_id bigint NOT NULL, user_id bigint NOT NULL REFERENCES app_users(id),
 PRIMARY KEY(workspace_id,entity_id,revision_id,user_id), FOREIGN KEY(workspace_id,entity_id,revision_id) REFERENCES purchase_request_revisions(workspace_id,entity_id,id)
);
CREATE TABLE purchase_request_lines (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, revision_id bigint NOT NULL,
 line_key text NOT NULL, product_id bigint NOT NULL, quantity numeric(18,6) NOT NULL CHECK(quantity>0), quantity_scale integer NOT NULL CHECK(quantity_scale BETWEEN 0 AND 6),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,revision_id,line_key), FOREIGN KEY(workspace_id,entity_id,revision_id) REFERENCES purchase_request_revisions(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id)
);
CREATE TABLE purchase_orders (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, number text NOT NULL,
 supplier_account_id bigint NOT NULL, status text NOT NULL CHECK(status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED')),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0), version bigint NOT NULL DEFAULT 1 CHECK(version>0), created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,number), FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,supplier_account_id) REFERENCES supplier_entity_accounts(workspace_id,entity_id,id)
);
CREATE TABLE purchase_order_revisions (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, order_id bigint NOT NULL,
 revision bigint NOT NULL CHECK(revision>0), status text NOT NULL CHECK(status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED')),
 currency text NOT NULL, terms_id bigint, terms_days integer NOT NULL CHECK(terms_days BETWEEN 0 AND 3650), config_version bigint NOT NULL CHECK(config_version>0),
 inventory_account_id bigint NOT NULL, ap_account_id bigint NOT NULL, grni_account_id bigint NOT NULL,
 submitted_by bigint REFERENCES app_users(id), submitted_at timestamptz, decided_by bigint REFERENCES app_users(id), decided_at timestamptz, decision_reason text, payload_hash text,
 FOREIGN KEY(workspace_id,entity_id,terms_id) REFERENCES payment_terms(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,inventory_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,ap_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,grni_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,order_id,revision), FOREIGN KEY(workspace_id,entity_id,order_id) REFERENCES purchase_orders(workspace_id,entity_id,id)
);
CREATE TABLE purchase_order_contributors (
 workspace_id bigint NOT NULL, entity_id bigint NOT NULL, revision_id bigint NOT NULL, user_id bigint NOT NULL REFERENCES app_users(id), PRIMARY KEY(workspace_id,entity_id,revision_id,user_id),
 FOREIGN KEY(workspace_id,entity_id,revision_id) REFERENCES purchase_order_revisions(workspace_id,entity_id,id)
);
CREATE TABLE purchase_order_lines (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, revision_id bigint NOT NULL, request_line_id bigint NOT NULL,
 line_key text NOT NULL, previous_line_id bigint, product_id bigint NOT NULL, uom_id bigint NOT NULL, tracking text NOT NULL CHECK(tracking IN ('NONE','SERIAL','LOT','LOT_EXPIRY')), quantity numeric(18,6) NOT NULL CHECK(quantity>0), quantity_scale integer NOT NULL CHECK(quantity_scale BETWEEN 0 AND 6),
 unit_cost numeric(18,6) NOT NULL CHECK(unit_cost>=0), subtotal numeric(18,2) NOT NULL CHECK(subtotal>=0), discount numeric(18,2) NOT NULL CHECK(discount>=0),
 net numeric(18,2) NOT NULL CHECK(net>=0), tax numeric(18,2) NOT NULL CHECK(tax>=0), gross numeric(18,2) NOT NULL CHECK(gross=net+tax),
 tax_version_id bigint NOT NULL, tax_rate_pct numeric(9,4) NOT NULL CHECK(tax_rate_pct BETWEEN 0 AND 100), input_account_id bigint NOT NULL, expense_account_id bigint,
 CHECK(subtotal=net+discount), UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,revision_id,line_key),
 FOREIGN KEY(workspace_id,entity_id,revision_id) REFERENCES purchase_order_revisions(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,request_line_id) REFERENCES purchase_request_lines(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,previous_line_id) REFERENCES purchase_order_lines(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,uom_id) REFERENCES uoms(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,tax_version_id) REFERENCES tax_versions(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,input_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,expense_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id)
);
CREATE TABLE procurement_receipts (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, order_id bigint NOT NULL,
 number text NOT NULL, kind text NOT NULL CHECK(kind IN ('GOODS','SERVICE')), status text NOT NULL CHECK(status IN ('DRAFT','POSTED')),
 posting_date date NOT NULL, journal_id bigint, posting_hash text, version bigint NOT NULL DEFAULT 1, created_by bigint NOT NULL REFERENCES app_users(id), posted_by bigint REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(), posted_at timestamptz,
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,number), FOREIGN KEY(workspace_id,entity_id,order_id) REFERENCES purchase_orders(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,journal_id) REFERENCES journal_entries(workspace_id,entity_id,id)
);
CREATE TABLE procurement_receipt_lines (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, receipt_id bigint NOT NULL, order_line_id bigint NOT NULL,
 line_key text NOT NULL, warehouse_id bigint, slice_start numeric(18,6) NOT NULL, slice_end numeric(18,6) NOT NULL CHECK(slice_end>slice_start AND slice_start>=0),
 subtotal numeric(18,2) NOT NULL, discount numeric(18,2) NOT NULL, net numeric(18,2) NOT NULL, tax numeric(18,2) NOT NULL, gross numeric(18,2) NOT NULL,
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,receipt_id,line_key), FOREIGN KEY(workspace_id,entity_id,receipt_id) REFERENCES procurement_receipts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,order_line_id) REFERENCES purchase_order_lines(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,warehouse_id) REFERENCES warehouses(workspace_id,entity_id,id)
);
CREATE TABLE receipt_tracking (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, receipt_line_id bigint NOT NULL,
 serial text, lot text, expires_on date, quantity numeric(18,6) NOT NULL CHECK(quantity>0), UNIQUE(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,receipt_line_id) REFERENCES procurement_receipt_lines(workspace_id,entity_id,id), CHECK(serial IS NULL OR lot IS NULL)
);
CREATE TABLE inventory_actions (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, action_key text NOT NULL,
 kind text NOT NULL CHECK(kind IN ('TRANSFER','POSITIVE_ADJUSTMENT','NEGATIVE_ADJUSTMENT')), status text NOT NULL CHECK(status IN ('DRAFT','SUBMITTED','POSTED','REJECTED')),
 product_id bigint NOT NULL, from_warehouse_id bigint, to_warehouse_id bigint, quantity numeric(18,6) NOT NULL CHECK(quantity>0), value numeric(18,2), posting_date date NOT NULL,
 reason text NOT NULL, created_by bigint NOT NULL REFERENCES app_users(id), approved_by bigint REFERENCES app_users(id), journal_id bigint, posting_hash text, posted_at timestamptz, version bigint NOT NULL DEFAULT 1,
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,id,product_id), UNIQUE(workspace_id,entity_id,action_key), FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,from_warehouse_id) REFERENCES warehouses(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,to_warehouse_id) REFERENCES warehouses(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,journal_id) REFERENCES journal_entries(workspace_id,entity_id,id)
);
CREATE TABLE inventory_action_contributors (
 workspace_id bigint NOT NULL, entity_id bigint NOT NULL, action_id bigint NOT NULL, user_id bigint NOT NULL REFERENCES app_users(id), PRIMARY KEY(workspace_id,entity_id,action_id,user_id),
 FOREIGN KEY(workspace_id,entity_id,action_id) REFERENCES inventory_actions(workspace_id,entity_id,id)
);
CREATE TABLE inventory_action_tracking (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, action_id bigint NOT NULL,
 product_id bigint NOT NULL, serial_id bigint, lot_id bigint, serial text, lot text, expires_on date, quantity numeric(18,6) NOT NULL CHECK(quantity>0), UNIQUE(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,action_id) REFERENCES inventory_actions(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,serial_id,product_id) REFERENCES instrument_serials(workspace_id,entity_id,id,product_id),
 FOREIGN KEY(workspace_id,entity_id,lot_id,product_id) REFERENCES inventory_lots(workspace_id,entity_id,id,product_id), CHECK(serial_id IS NULL OR lot_id IS NULL)
);
CREATE TABLE vendor_invoices (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, supplier_account_id bigint NOT NULL,
 reference text NOT NULL CHECK(reference=trim(reference) AND length(reference)>0), status text NOT NULL CHECK(status IN ('DRAFT','HELD_FOR_REVIEW','POSTED')),
 currency text NOT NULL, invoice_date date NOT NULL, due_date date NOT NULL, terms_days integer NOT NULL CHECK(terms_days BETWEEN 0 AND 3650), hold_reason text, journal_id bigint, created_by bigint NOT NULL REFERENCES app_users(id), posted_by bigint REFERENCES app_users(id), posted_at timestamptz,
 posting_hash text,
 version bigint NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,supplier_account_id,reference),
 FOREIGN KEY(workspace_id,entity_id,supplier_account_id) REFERENCES supplier_entity_accounts(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,journal_id) REFERENCES journal_entries(workspace_id,entity_id,id)
);
CREATE TABLE vendor_invoice_lines (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, invoice_id bigint NOT NULL, receipt_line_id bigint NOT NULL, line_key text NOT NULL,
 quantity numeric(18,6) NOT NULL CHECK(quantity>0), unit_cost numeric(18,6) NOT NULL CHECK(unit_cost>=0), subtotal numeric(18,2) NOT NULL, discount numeric(18,2) NOT NULL,
 net numeric(18,2) NOT NULL, tax numeric(18,2) NOT NULL, gross numeric(18,2) NOT NULL, UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,id,receipt_line_id), UNIQUE(workspace_id,entity_id,invoice_id,line_key),
 FOREIGN KEY(workspace_id,entity_id,invoice_id) REFERENCES vendor_invoices(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,receipt_line_id) REFERENCES procurement_receipt_lines(workspace_id,entity_id,id)
);
CREATE TABLE invoice_source_allocations (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, invoice_line_id bigint NOT NULL, receipt_line_id bigint NOT NULL,
 slice_start numeric(18,6) NOT NULL CHECK(slice_start>=0), slice_end numeric(18,6) NOT NULL CHECK(slice_end>slice_start),
 subtotal numeric(18,2) NOT NULL, discount numeric(18,2) NOT NULL, net numeric(18,2) NOT NULL, tax numeric(18,2) NOT NULL, gross numeric(18,2) NOT NULL,
 UNIQUE(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,invoice_line_id,receipt_line_id) REFERENCES vendor_invoice_lines(workspace_id,entity_id,id,receipt_line_id),
 FOREIGN KEY(workspace_id,entity_id,receipt_line_id) REFERENCES procurement_receipt_lines(workspace_id,entity_id,id)
);
CREATE TABLE supplier_payments (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, supplier_account_id bigint NOT NULL,
 payment_key text NOT NULL, status text NOT NULL CHECK(status IN ('DRAFT','POSTED')), payment_date date NOT NULL, currency text NOT NULL, amount numeric(18,2) NOT NULL CHECK(amount>0),
 bank_account_id bigint NOT NULL, ap_account_id bigint NOT NULL, journal_id bigint, posting_hash text, created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(), posted_by bigint REFERENCES app_users(id), posted_at timestamptz, version bigint NOT NULL DEFAULT 1,
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,payment_key), FOREIGN KEY(workspace_id,entity_id,supplier_account_id) REFERENCES supplier_entity_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,bank_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,ap_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,journal_id) REFERENCES journal_entries(workspace_id,entity_id,id)
);
CREATE TABLE supplier_payment_allocations (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, payment_id bigint NOT NULL, invoice_id bigint NOT NULL,
 amount numeric(18,2) NOT NULL CHECK(amount>0), UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,payment_id,invoice_id),
 FOREIGN KEY(workspace_id,entity_id,payment_id) REFERENCES supplier_payments(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,invoice_id) REFERENCES vendor_invoices(workspace_id,entity_id,id)
);
CREATE TABLE procurement_reversals (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, source_type text NOT NULL CHECK(source_type IN ('INVOICE','PAYMENT')),
 invoice_id bigint, payment_id bigint, reversal_date date NOT NULL, reason text NOT NULL, journal_id bigint, created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,invoice_id), UNIQUE(workspace_id,entity_id,payment_id),
 CHECK((source_type='INVOICE' AND invoice_id IS NOT NULL AND payment_id IS NULL) OR (source_type='PAYMENT' AND payment_id IS NOT NULL AND invoice_id IS NULL)),
 FOREIGN KEY(workspace_id,entity_id,invoice_id) REFERENCES vendor_invoices(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,payment_id) REFERENCES supplier_payments(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,journal_id) REFERENCES journal_entries(workspace_id,entity_id,id)
);

-- Submitted revisions and posted business documents are immutable histories.
CREATE FUNCTION m2_document_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'DRAFT' THEN RAISE EXCEPTION 'business document must start as draft' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'business history cannot be deleted' USING ERRCODE='23514'; END IF;
 IF OLD.status IN ('POSTED','APPROVED','REJECTED') THEN RAISE EXCEPTION 'business history is immutable' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME IN ('purchase_request_revisions','purchase_order_revisions') AND OLD.status='DRAFT' AND NEW.status NOT IN ('DRAFT','SUBMITTED') THEN
  RAISE EXCEPTION 'revision must be submitted before decision' USING ERRCODE='23514';
 END IF;
 IF TG_TABLE_NAME IN ('purchase_request_revisions','purchase_order_revisions') AND OLD.status='SUBMITTED' THEN
  IF NEW.status NOT IN ('APPROVED','REJECTED') OR
     (to_jsonb(NEW)-ARRAY['status','decided_by','decided_at','decision_reason']) IS DISTINCT FROM
     (to_jsonb(OLD)-ARRAY['status','decided_by','decided_at','decision_reason']) OR NEW.decided_by IS NULL OR NEW.decided_at IS NULL THEN
   RAISE EXCEPTION 'submitted revision is immutable except decision' USING ERRCODE='23514';
  END IF;
  IF TG_TABLE_NAME='purchase_request_revisions' THEN
   IF EXISTS(SELECT 1 FROM purchase_request_contributors c WHERE c.workspace_id=NEW.workspace_id AND c.entity_id=NEW.entity_id AND c.revision_id=NEW.id AND c.user_id=NEW.decided_by)
    OR EXISTS(SELECT 1 FROM purchase_requests p WHERE p.workspace_id=NEW.workspace_id AND p.entity_id=NEW.entity_id AND p.id=NEW.request_id AND p.created_by=NEW.decided_by) THEN
    RAISE EXCEPTION 'revision contributor cannot decide' USING ERRCODE='23514';
   END IF;
  ELSE
   IF EXISTS(SELECT 1 FROM purchase_order_contributors c WHERE c.workspace_id=NEW.workspace_id AND c.entity_id=NEW.entity_id AND c.revision_id=NEW.id AND c.user_id=NEW.decided_by)
    OR EXISTS(SELECT 1 FROM purchase_orders p WHERE p.workspace_id=NEW.workspace_id AND p.entity_id=NEW.entity_id AND p.id=NEW.order_id AND p.created_by=NEW.decided_by) THEN
    RAISE EXCEPTION 'revision contributor cannot decide' USING ERRCODE='23514';
   END IF;
  END IF;
 END IF;
 IF TG_TABLE_NAME='inventory_actions' AND EXISTS(
  SELECT 1 FROM inventory_action_tracking t WHERE t.workspace_id=NEW.workspace_id AND t.entity_id=NEW.entity_id
   AND t.action_id=NEW.id AND t.active AND t.product_id<>(to_jsonb(NEW)->>'product_id')::bigint) THEN
  RAISE EXCEPTION 'active tracking must match action product' USING ERRCODE='23514';
 END IF;
 IF TG_TABLE_NAME='inventory_actions' AND OLD.status='SUBMITTED' THEN
  IF NEW.status NOT IN ('POSTED','REJECTED') OR (to_jsonb(NEW)-ARRAY['status','approved_by','journal_id','posting_hash','posted_at','posted_by','version']) IS DISTINCT FROM
     (to_jsonb(OLD)-ARRAY['status','approved_by','journal_id','posting_hash','posted_at','posted_by','version']) THEN
   RAISE EXCEPTION 'submitted adjustment is immutable except decision' USING ERRCODE='23514';
  END IF;
 END IF;
 IF TG_TABLE_NAME='inventory_actions' AND to_jsonb(NEW)->>'kind'='POSITIVE_ADJUSTMENT' AND OLD.status='DRAFT' AND NEW.status='POSTED' THEN
  RAISE EXCEPTION 'positive adjustment must be submitted' USING ERRCODE='23514';
 END IF;
 IF TG_TABLE_NAME='inventory_actions' AND to_jsonb(NEW)->>'kind'='POSITIVE_ADJUSTMENT' AND NEW.status='POSTED' THEN
  IF NEW.approved_by IS NULL OR NEW.approved_by=NEW.created_by OR EXISTS(SELECT 1 FROM inventory_action_contributors c WHERE c.workspace_id=NEW.workspace_id AND c.entity_id=NEW.entity_id AND c.action_id=NEW.id AND c.user_id=NEW.approved_by) THEN
   RAISE EXCEPTION 'adjustment contributor cannot approve' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER request_revision_guard BEFORE INSERT OR UPDATE OR DELETE ON purchase_request_revisions FOR EACH ROW EXECUTE FUNCTION m2_document_guard();
CREATE TRIGGER order_revision_guard BEFORE INSERT OR UPDATE OR DELETE ON purchase_order_revisions FOR EACH ROW EXECUTE FUNCTION m2_document_guard();
CREATE TRIGGER receipt_guard BEFORE INSERT OR UPDATE OR DELETE ON procurement_receipts FOR EACH ROW EXECUTE FUNCTION m2_document_guard();
CREATE TRIGGER inventory_action_guard BEFORE INSERT OR UPDATE OR DELETE ON inventory_actions FOR EACH ROW EXECUTE FUNCTION m2_document_guard();
CREATE TRIGGER invoice_guard BEFORE INSERT OR UPDATE OR DELETE ON vendor_invoices FOR EACH ROW EXECUTE FUNCTION m2_document_guard();
CREATE TRIGGER payment_guard BEFORE INSERT OR UPDATE OR DELETE ON supplier_payments FOR EACH ROW EXECUTE FUNCTION m2_document_guard();

CREATE FUNCTION m2_child_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE item jsonb; parent_status text; parent_id bigint; w bigint; e bigint;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'business history cannot be deleted' USING ERRCODE='23514'; END IF;
 item=to_jsonb(NEW); w=(item->>'workspace_id')::bigint; e=(item->>'entity_id')::bigint;
 parent_id=(item->>TG_ARGV[1])::bigint;
 IF TG_NARGS=2 THEN
  EXECUTE format('SELECT status FROM %I WHERE workspace_id=$1 AND entity_id=$2 AND id=$3 FOR UPDATE',TG_ARGV[0]) INTO parent_status USING w,e,parent_id;
 ELSE
  EXECUTE format('SELECT p.status FROM %I c JOIN %I p ON p.workspace_id=c.workspace_id AND p.entity_id=c.entity_id AND p.id=c.%I WHERE c.workspace_id=$1 AND c.entity_id=$2 AND c.id=$3 FOR UPDATE OF p',TG_ARGV[0],TG_ARGV[2],TG_ARGV[3]) INTO parent_status USING w,e,parent_id;
 END IF;
 IF parent_status IS NULL OR parent_status NOT IN ('DRAFT','HELD_FOR_REVIEW') THEN RAISE EXCEPTION 'business child is immutable' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND (to_jsonb(OLD)->>'workspace_id'<>item->>'workspace_id' OR to_jsonb(OLD)->>'entity_id'<>item->>'entity_id' OR to_jsonb(OLD)->>TG_ARGV[1]<>item->>TG_ARGV[1]) THEN
  RAISE EXCEPTION 'business child cannot change parent' USING ERRCODE='23514';
 END IF;
 IF TG_TABLE_NAME='inventory_action_tracking' AND (item->>'active')::boolean AND NOT EXISTS(
  SELECT 1 FROM inventory_actions a WHERE a.workspace_id=w AND a.entity_id=e AND a.id=parent_id AND a.product_id=(item->>'product_id')::bigint) THEN
  RAISE EXCEPTION 'active tracking must match action product' USING ERRCODE='23514';
 END IF;
 IF TG_TABLE_NAME='purchase_order_lines' THEN
  IF NOT EXISTS(SELECT 1 FROM purchase_request_lines l JOIN purchase_request_revisions r ON r.workspace_id=l.workspace_id AND r.entity_id=l.entity_id AND r.id=l.revision_id WHERE l.workspace_id=NEW.workspace_id AND l.entity_id=NEW.entity_id AND l.id=NEW.request_line_id AND l.product_id=NEW.product_id AND r.status='APPROVED') THEN
   RAISE EXCEPTION 'order line requires matching approved request line' USING ERRCODE='23514';
  END IF;
  IF NEW.previous_line_id IS NOT NULL AND NOT EXISTS(
    SELECT 1 FROM purchase_order_lines old_line JOIN purchase_order_revisions old_rev ON old_rev.workspace_id=old_line.workspace_id AND old_rev.entity_id=old_line.entity_id AND old_rev.id=old_line.revision_id
    JOIN purchase_order_revisions new_rev ON new_rev.workspace_id=NEW.workspace_id AND new_rev.entity_id=NEW.entity_id AND new_rev.id=NEW.revision_id
    WHERE old_line.workspace_id=NEW.workspace_id AND old_line.entity_id=NEW.entity_id AND old_line.id=NEW.previous_line_id AND old_line.product_id=NEW.product_id AND old_line.request_line_id=NEW.request_line_id AND old_rev.order_id=new_rev.order_id AND old_rev.revision<new_rev.revision AND old_rev.status='APPROVED') THEN
   RAISE EXCEPTION 'invalid amended order lineage' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER request_line_guard BEFORE INSERT OR UPDATE OR DELETE ON purchase_request_lines FOR EACH ROW EXECUTE FUNCTION m2_child_guard('purchase_request_revisions','revision_id');
CREATE TRIGGER request_contributor_guard BEFORE INSERT OR UPDATE OR DELETE ON purchase_request_contributors FOR EACH ROW EXECUTE FUNCTION m2_child_guard('purchase_request_revisions','revision_id');
CREATE TRIGGER order_line_guard BEFORE INSERT OR UPDATE OR DELETE ON purchase_order_lines FOR EACH ROW EXECUTE FUNCTION m2_child_guard('purchase_order_revisions','revision_id');
CREATE TRIGGER order_contributor_guard BEFORE INSERT OR UPDATE OR DELETE ON purchase_order_contributors FOR EACH ROW EXECUTE FUNCTION m2_child_guard('purchase_order_revisions','revision_id');
CREATE TRIGGER receipt_line_guard BEFORE INSERT OR UPDATE OR DELETE ON procurement_receipt_lines FOR EACH ROW EXECUTE FUNCTION m2_child_guard('procurement_receipts','receipt_id');
CREATE TRIGGER action_contributor_guard BEFORE INSERT OR UPDATE OR DELETE ON inventory_action_contributors FOR EACH ROW EXECUTE FUNCTION m2_child_guard('inventory_actions','action_id');
CREATE TRIGGER action_tracking_guard BEFORE INSERT OR UPDATE OR DELETE ON inventory_action_tracking FOR EACH ROW EXECUTE FUNCTION m2_child_guard('inventory_actions','action_id');
CREATE TRIGGER invoice_line_guard BEFORE INSERT OR UPDATE OR DELETE ON vendor_invoice_lines FOR EACH ROW EXECUTE FUNCTION m2_child_guard('vendor_invoices','invoice_id');
CREATE TRIGGER payment_allocation_guard BEFORE INSERT OR UPDATE OR DELETE ON supplier_payment_allocations FOR EACH ROW EXECUTE FUNCTION m2_child_guard('supplier_payments','payment_id');
CREATE TRIGGER receipt_tracking_guard BEFORE INSERT OR UPDATE OR DELETE ON receipt_tracking FOR EACH ROW EXECUTE FUNCTION m2_child_guard('procurement_receipt_lines','receipt_line_id','procurement_receipts','receipt_id');
CREATE TRIGGER invoice_source_guard BEFORE INSERT OR UPDATE OR DELETE ON invoice_source_allocations FOR EACH ROW EXECUTE FUNCTION m2_child_guard('vendor_invoice_lines','invoice_line_id','vendor_invoices','invoice_id');

-- Draft rows are retained as inactive rather than deleted, preserving append-only runtime grants.
ALTER TABLE purchase_request_lines ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE purchase_order_lines ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE procurement_receipt_lines ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE receipt_tracking ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE inventory_action_tracking ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE vendor_invoice_lines ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE supplier_payment_allocations ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE invoice_source_allocations ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE purchase_order_lines ADD COLUMN discount_pct numeric(9,4) CHECK(discount_pct BETWEEN 0 AND 100);
ALTER TABLE purchase_order_lines ADD COLUMN discount_amount numeric(18,2) CHECK(discount_amount>=0);
ALTER TABLE purchase_order_lines ADD CONSTRAINT po_one_discount_method CHECK(discount_pct IS NULL OR discount_amount IS NULL);
ALTER TABLE vendor_invoice_lines ADD COLUMN discount_pct numeric(9,4) CHECK(discount_pct BETWEEN 0 AND 100);
ALTER TABLE vendor_invoice_lines ADD COLUMN discount_amount numeric(18,2) CHECK(discount_amount>=0);
ALTER TABLE vendor_invoice_lines ADD CONSTRAINT invoice_one_discount_method CHECK(discount_pct IS NULL OR discount_amount IS NULL);
ALTER TABLE purchase_request_revisions ADD CONSTRAINT request_decision_fields CHECK(
 (status='DRAFT' AND submitted_by IS NULL AND submitted_at IS NULL AND decided_by IS NULL AND decided_at IS NULL)
 OR (status='SUBMITTED' AND submitted_by IS NOT NULL AND submitted_at IS NOT NULL AND payload_hash IS NOT NULL AND decided_by IS NULL AND decided_at IS NULL)
 OR (status IN ('APPROVED','REJECTED') AND submitted_by IS NOT NULL AND submitted_at IS NOT NULL AND payload_hash IS NOT NULL AND decided_by IS NOT NULL AND decided_at IS NOT NULL));
ALTER TABLE purchase_order_revisions ADD CONSTRAINT order_decision_fields CHECK(
 (status='DRAFT' AND submitted_by IS NULL AND submitted_at IS NULL AND decided_by IS NULL AND decided_at IS NULL)
 OR (status='SUBMITTED' AND submitted_by IS NOT NULL AND submitted_at IS NOT NULL AND payload_hash IS NOT NULL AND decided_by IS NULL AND decided_at IS NULL)
 OR (status IN ('APPROVED','REJECTED') AND submitted_by IS NOT NULL AND submitted_at IS NOT NULL AND payload_hash IS NOT NULL AND decided_by IS NOT NULL AND decided_at IS NOT NULL));
ALTER TABLE procurement_receipts ADD CONSTRAINT receipt_post_fields CHECK((status='DRAFT' AND posted_by IS NULL AND posted_at IS NULL AND posting_hash IS NULL AND journal_id IS NULL) OR (status='POSTED' AND posted_by IS NOT NULL AND posted_at IS NOT NULL AND posting_hash IS NOT NULL));
ALTER TABLE vendor_invoices ADD CONSTRAINT invoice_post_fields CHECK((status<>'POSTED' AND posted_by IS NULL AND posted_at IS NULL AND posting_hash IS NULL AND journal_id IS NULL) OR (status='POSTED' AND posted_by IS NOT NULL AND posted_at IS NOT NULL AND posting_hash IS NOT NULL));
ALTER TABLE supplier_payments ADD CONSTRAINT payment_post_fields CHECK((status='DRAFT' AND posted_by IS NULL AND posted_at IS NULL AND posting_hash IS NULL AND journal_id IS NULL) OR (status='POSTED' AND posted_by IS NOT NULL AND posted_at IS NOT NULL AND posting_hash IS NOT NULL AND journal_id IS NOT NULL));
ALTER TABLE procurement_receipt_lines ADD CONSTRAINT receipt_line_amounts CHECK(subtotal>=0 AND discount>=0 AND net>=0 AND tax>=0 AND subtotal=net+discount AND gross=net+tax);
ALTER TABLE vendor_invoice_lines ADD CONSTRAINT invoice_line_amounts CHECK(subtotal>=0 AND discount>=0 AND net>=0 AND tax>=0 AND gross>=0);
ALTER TABLE invoice_source_allocations ADD CONSTRAINT invoice_allocation_amounts CHECK(subtotal>=0 AND discount>=0 AND net>=0 AND tax>=0 AND subtotal=net+discount AND gross=net+tax);
ALTER TABLE inventory_actions ADD CONSTRAINT action_post_fields CHECK(
 (status<>'POSTED' AND posted_at IS NULL AND posting_hash IS NULL AND journal_id IS NULL)
 OR (status='POSTED' AND posted_at IS NOT NULL AND posting_hash IS NOT NULL));
ALTER TABLE inventory_actions ADD COLUMN posted_by bigint REFERENCES app_users(id);
ALTER TABLE inventory_actions ADD CONSTRAINT action_posted_actor CHECK((status='POSTED') = (posted_by IS NOT NULL));
ALTER TABLE purchase_order_lines ADD COLUMN product_category text NOT NULL CHECK(product_category IN ('LAB_UNIT','LAB_SUPPLY','REPLACEMENT_PART','SERVICE_PLAN','IMPLEMENTATION_SERVICE'));
ALTER TABLE purchase_order_lines ADD COLUMN product_sku text NOT NULL;
ALTER TABLE purchase_order_lines ADD COLUMN product_name text NOT NULL;
ALTER TABLE purchase_order_lines ADD COLUMN uom_code text NOT NULL;
ALTER TABLE purchase_order_lines ADD CONSTRAINT po_snapshot_text CHECK(length(trim(product_sku))>0 AND length(trim(product_name))>0 AND length(trim(uom_code))>0);
ALTER TABLE procurement_reversals ADD COLUMN reversal_key text NOT NULL;
ALTER TABLE procurement_reversals ADD COLUMN payload_hash text NOT NULL;
ALTER TABLE procurement_reversals ADD CONSTRAINT procurement_reversal_key_unique UNIQUE(workspace_id,entity_id,reversal_key);
ALTER TABLE purchase_order_revisions ADD COLUMN expense_account_id bigint NOT NULL;
ALTER TABLE purchase_order_revisions ADD CONSTRAINT po_expense_account_fk FOREIGN KEY(workspace_id,entity_id,expense_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id);
ALTER TABLE inventory_actions ADD COLUMN product_version bigint NOT NULL CHECK(product_version>0);
ALTER TABLE inventory_actions ADD COLUMN uom_id bigint NOT NULL;
ALTER TABLE inventory_actions ADD COLUMN uom_version bigint NOT NULL CHECK(uom_version>0);
ALTER TABLE inventory_actions ADD COLUMN setting_version bigint NOT NULL CHECK(setting_version>0);
ALTER TABLE inventory_actions ADD COLUMN from_warehouse_version bigint CHECK(from_warehouse_version>0);
ALTER TABLE inventory_actions ADD COLUMN to_warehouse_version bigint CHECK(to_warehouse_version>0);
ALTER TABLE inventory_actions ADD COLUMN tracking text NOT NULL CHECK(tracking IN ('NONE','SERIAL','LOT','LOT_EXPIRY'));
ALTER TABLE inventory_actions ADD COLUMN quantity_scale integer NOT NULL CHECK(quantity_scale BETWEEN 0 AND 6);
ALTER TABLE inventory_actions ADD CONSTRAINT action_uom_fk FOREIGN KEY(workspace_id,uom_id) REFERENCES uoms(workspace_id,id);
ALTER TABLE inventory_actions ADD CONSTRAINT action_warehouse_versions CHECK((from_warehouse_id IS NULL)=(from_warehouse_version IS NULL) AND (to_warehouse_id IS NULL)=(to_warehouse_version IS NULL));
ALTER TABLE inventory_actions ADD CONSTRAINT action_warehouse_shape CHECK(
 (kind='TRANSFER' AND from_warehouse_id IS NOT NULL AND to_warehouse_id IS NOT NULL AND from_warehouse_id<>to_warehouse_id)
 OR (kind='POSITIVE_ADJUSTMENT' AND from_warehouse_id IS NULL AND to_warehouse_id IS NOT NULL)
 OR (kind='NEGATIVE_ADJUSTMENT' AND from_warehouse_id IS NOT NULL AND to_warehouse_id IS NULL));
ALTER TABLE purchase_request_lines ADD COLUMN product_version bigint NOT NULL CHECK(product_version>0);
ALTER TABLE purchase_request_lines ADD COLUMN setting_version bigint NOT NULL CHECK(setting_version>0);
ALTER TABLE purchase_request_lines ADD COLUMN uom_id bigint NOT NULL;
ALTER TABLE purchase_request_lines ADD COLUMN uom_version bigint NOT NULL CHECK(uom_version>0);
ALTER TABLE purchase_request_lines ADD CONSTRAINT pr_uom_fk FOREIGN KEY(workspace_id,uom_id) REFERENCES uoms(workspace_id,id);
ALTER TABLE purchase_order_lines ADD COLUMN product_version bigint NOT NULL CHECK(product_version>0);
ALTER TABLE purchase_order_lines ADD COLUMN setting_version bigint NOT NULL CHECK(setting_version>0);
ALTER TABLE purchase_order_lines ADD COLUMN uom_version bigint NOT NULL CHECK(uom_version>0);
ALTER TABLE procurement_receipt_lines ADD COLUMN warehouse_version bigint CHECK(warehouse_version>0);
ALTER TABLE procurement_receipt_lines ADD CONSTRAINT receipt_warehouse_version CHECK((warehouse_id IS NULL)=(warehouse_version IS NULL));
ALTER TABLE purchase_order_revisions ADD COLUMN supplier_account_version bigint NOT NULL CHECK(supplier_account_version>0);
ALTER TABLE purchase_order_revisions ADD COLUMN terms_version bigint CHECK(terms_version>0);
ALTER TABLE purchase_order_revisions ADD CONSTRAINT po_terms_version CHECK((terms_id IS NULL)=(terms_version IS NULL));
ALTER TABLE vendor_invoices ADD COLUMN supplier_account_version bigint NOT NULL CHECK(supplier_account_version>0);
ALTER TABLE supplier_payments ADD COLUMN supplier_account_version bigint NOT NULL CHECK(supplier_account_version>0);
ALTER TABLE supplier_payments ADD COLUMN bank_account_version bigint NOT NULL CHECK(bank_account_version>0);
ALTER TABLE vendor_invoices ADD COLUMN ap_account_id bigint NOT NULL;
ALTER TABLE vendor_invoices ADD COLUMN config_version bigint NOT NULL CHECK(config_version>0);
ALTER TABLE vendor_invoices ADD CONSTRAINT invoice_ap_account_fk FOREIGN KEY(workspace_id,entity_id,ap_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id);
ALTER TABLE supplier_payments ADD COLUMN config_version bigint NOT NULL CHECK(config_version>0);
ALTER TABLE purchase_request_lines ADD COLUMN product_sku text NOT NULL;
ALTER TABLE purchase_request_lines ADD COLUMN product_name text NOT NULL;
ALTER TABLE purchase_request_lines ADD COLUMN product_category text NOT NULL CHECK(product_category IN ('LAB_UNIT','LAB_SUPPLY','REPLACEMENT_PART','SERVICE_PLAN','IMPLEMENTATION_SERVICE'));
ALTER TABLE purchase_request_lines ADD COLUMN uom_code text NOT NULL;
ALTER TABLE purchase_request_lines ADD COLUMN tracking text NOT NULL CHECK(tracking IN ('NONE','SERIAL','LOT','LOT_EXPIRY'));
ALTER TABLE purchase_request_lines ADD CONSTRAINT pr_snapshot_text CHECK(length(trim(product_sku))>0 AND length(trim(product_name))>0 AND length(trim(uom_code))>0);
ALTER TABLE purchase_order_lines ADD COLUMN tax_version_version bigint NOT NULL CHECK(tax_version_version>0);
ALTER TABLE purchase_order_lines ADD COLUMN service_profile text CHECK(service_profile IN ('OPERATIONAL','PREPAID','MULTIPERIOD'));
ALTER TABLE purchase_order_lines ADD CONSTRAINT po_service_profile_shape CHECK(
 (product_category IN ('SERVICE_PLAN','IMPLEMENTATION_SERVICE') AND service_profile IS NOT NULL)
 OR (product_category NOT IN ('SERVICE_PLAN','IMPLEMENTATION_SERVICE') AND service_profile IS NULL));
ALTER TABLE procurement_receipts ADD COLUMN order_version bigint NOT NULL CHECK(order_version>0);
ALTER TABLE purchase_order_revisions ADD COLUMN business_date date NOT NULL;
