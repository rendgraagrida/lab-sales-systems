-- M4 asset and service sources. Historical migrations and financial postings remain unchanged.
ALTER TABLE scenario_baseline_versions DROP CONSTRAINT scenario_baseline_versions_baseline_key_check;
ALTER TABLE scenario_baseline_versions ADD CHECK (baseline_key IN ('EMPTY','O2C_GOODS','FULL_OPERATIONS'));
ALTER TABLE crm_opportunities DROP CONSTRAINT crm_opportunities_pipeline_check;
ALTER TABLE crm_opportunities ADD CHECK (pipeline IN ('EQUIPMENT_SALES','SUPPLY_REORDER','SERVICE_RENEWAL'));
ALTER TABLE product_compatibilities ADD COLUMN active boolean NOT NULL DEFAULT true, ADD COLUMN version bigint NOT NULL DEFAULT 1 CHECK(version>0);

CREATE TABLE service_coverage_types (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 code text NOT NULL, name text NOT NULL, created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,code),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id)
);
CREATE TABLE service_product_versions (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 product_id bigint NOT NULL, revision bigint NOT NULL CHECK(revision>0), category_snapshot text NOT NULL,
 coverage_type_id bigint, duration_months integer, enabled boolean NOT NULL,
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,product_id,revision),
 FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,coverage_type_id) REFERENCES service_coverage_types(workspace_id,entity_id,id),
 CHECK((category_snapshot='SERVICE_PLAN' AND coverage_type_id IS NOT NULL AND duration_months IS NOT NULL AND duration_months>0) OR
       (category_snapshot='IMPLEMENTATION_SERVICE' AND coverage_type_id IS NULL AND duration_months IS NULL))
);
CREATE TABLE service_posting_config (
 workspace_id bigint NOT NULL, entity_id bigint NOT NULL, deferred_account_id bigint NOT NULL,
 implementation_revenue_account_id bigint NOT NULL, service_revenue_account_id bigint NOT NULL,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), updated_by bigint NOT NULL REFERENCES app_users(id), updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(workspace_id,entity_id), FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,deferred_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,implementation_revenue_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,service_revenue_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 CHECK(deferred_account_id<>implementation_revenue_account_id AND deferred_account_id<>service_revenue_account_id AND implementation_revenue_account_id<>service_revenue_account_id)
);
CREATE TABLE installed_instruments (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 record_key text NOT NULL, product_id bigint NOT NULL, serial text NOT NULL, customer_account_id bigint NOT NULL,
 origin text NOT NULL CHECK(origin IN ('SHIPMENT','LEGACY')), inventory_serial_id bigint, shipment_tracking_id bigint,
 source_order_line_id bigint, device_unit_no integer, registration_key text NOT NULL, registration_hash text NOT NULL,
 legacy_evidence text, opening_source_id bigint, version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,record_key), UNIQUE(workspace_id,entity_id,serial),
 UNIQUE(workspace_id,entity_id,registration_key), UNIQUE(workspace_id,entity_id,inventory_serial_id),
 UNIQUE(workspace_id,entity_id,shipment_tracking_id), UNIQUE(workspace_id,entity_id,source_order_line_id,device_unit_no),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,customer_account_id) REFERENCES customer_entity_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,inventory_serial_id) REFERENCES instrument_serials(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,shipment_tracking_id) REFERENCES shipment_line_tracking(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,source_order_line_id) REFERENCES sales_order_lines(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,opening_source_id) REFERENCES opening_balance_sources(workspace_id,entity_id,id),
 CHECK((origin='SHIPMENT' AND inventory_serial_id IS NOT NULL AND shipment_tracking_id IS NOT NULL AND source_order_line_id IS NOT NULL AND device_unit_no IS NOT NULL AND device_unit_no>0 AND legacy_evidence IS NULL) OR
       (origin='LEGACY' AND inventory_serial_id IS NULL AND shipment_tracking_id IS NULL AND source_order_line_id IS NULL AND device_unit_no IS NULL AND legacy_evidence IS NOT NULL AND length(btrim(legacy_evidence))>0))
);
CREATE TABLE asset_operator_links (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 asset_id bigint NOT NULL, contact_id bigint NOT NULL, created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,asset_id,contact_id),
 FOREIGN KEY(workspace_id,entity_id,asset_id) REFERENCES installed_instruments(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,contact_id) REFERENCES contacts(workspace_id,id)
);
CREATE TABLE service_contracts (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 record_key text NOT NULL, customer_account_id bigint NOT NULL, coverage_type_id bigint NOT NULL,
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,record_key),
 FOREIGN KEY(workspace_id,entity_id,customer_account_id) REFERENCES customer_entity_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,coverage_type_id) REFERENCES service_coverage_types(workspace_id,entity_id,id)
);
CREATE TABLE service_contract_assets (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 contract_id bigint NOT NULL, asset_id bigint NOT NULL, created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,contract_id),
 FOREIGN KEY(workspace_id,entity_id,contract_id) REFERENCES service_contracts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,asset_id) REFERENCES installed_instruments(workspace_id,entity_id,id)
);
CREATE TABLE renewal_intents (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 contract_id bigint NOT NULL, predecessor_term_id bigint NOT NULL, target_start date NOT NULL, customer_account_id bigint NOT NULL,
 opportunity_id bigint, status text NOT NULL CHECK(status IN ('OPEN','CONVERTED')), accepted_term_id bigint,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,contract_id,target_start), UNIQUE(workspace_id,entity_id,predecessor_term_id),
 UNIQUE(workspace_id,entity_id,opportunity_id), UNIQUE(workspace_id,entity_id,accepted_term_id),
 FOREIGN KEY(workspace_id,entity_id,contract_id) REFERENCES service_contracts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,customer_account_id) REFERENCES customer_entity_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,opportunity_id) REFERENCES crm_opportunities(workspace_id,entity_id,id),
 CHECK((status='OPEN' AND accepted_term_id IS NULL) OR (status='CONVERTED' AND accepted_term_id IS NOT NULL))
);
CREATE TABLE quote_service_targets (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 quote_line_id bigint NOT NULL, unit_no integer NOT NULL CHECK(unit_no>0), service_product_version_id bigint NOT NULL,
 compatibility_id bigint NOT NULL, compatibility_version bigint NOT NULL, target_asset_id bigint, target_asset_version bigint,
 target_device_quote_line_id bigint, target_device_unit_no integer, start_basis text NOT NULL CHECK(start_basis IN ('INSTALLATION','EXPLICIT','RENEWAL')),
 coverage_start date, coverage_end date, renewal_intent_id bigint, renewal_intent_version bigint, active boolean NOT NULL DEFAULT true,
 UNIQUE(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,quote_line_id) REFERENCES quote_lines(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,service_product_version_id) REFERENCES service_product_versions(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,compatibility_id) REFERENCES product_compatibilities(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,target_asset_id) REFERENCES installed_instruments(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,target_device_quote_line_id) REFERENCES quote_lines(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,renewal_intent_id) REFERENCES renewal_intents(workspace_id,entity_id,id),
 CHECK((target_asset_id IS NOT NULL AND target_asset_version IS NOT NULL AND target_device_quote_line_id IS NULL AND target_device_unit_no IS NULL) OR
       (target_asset_id IS NULL AND target_asset_version IS NULL AND target_device_quote_line_id IS NOT NULL AND target_device_unit_no IS NOT NULL AND target_device_unit_no>0)),
 CHECK((coverage_start IS NULL AND coverage_end IS NULL) OR (coverage_start IS NOT NULL AND coverage_end IS NOT NULL AND coverage_end>coverage_start)),
 CHECK((renewal_intent_id IS NULL)=(renewal_intent_version IS NULL))
);
CREATE UNIQUE INDEX quote_service_target_active ON quote_service_targets(workspace_id,entity_id,quote_line_id,unit_no) WHERE active;

CREATE TABLE sales_service_obligations (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 order_line_id bigint NOT NULL, unit_no integer NOT NULL CHECK(unit_no>0), quote_service_target_id bigint NOT NULL,
 kind text NOT NULL CHECK(kind IN ('ONBOARDING','PLAN')), asset_id bigint, target_device_order_line_id bigint, target_device_unit_no integer,
 service_product_version_id bigint NOT NULL, coverage_type_id bigint, duration_months integer,
 compatibility_id bigint NOT NULL, compatibility_version bigint NOT NULL, start_basis text NOT NULL CHECK(start_basis IN ('INSTALLATION','EXPLICIT','RENEWAL')),
 requested_start date, requested_end date, accepted_source_hash text NOT NULL, accepted_date date NOT NULL,
 subtotal numeric(18,2) NOT NULL, discount numeric(18,2) NOT NULL, net numeric(18,2) NOT NULL, tax numeric(18,2) NOT NULL, gross numeric(18,2) NOT NULL,
 currency text NOT NULL, terms_days integer NOT NULL, ar_account_id bigint NOT NULL, unbilled_account_id bigint NOT NULL,
 deferred_account_id bigint NOT NULL, revenue_account_id bigint NOT NULL, output_account_id bigint NOT NULL, tax_version_id bigint NOT NULL,
 status text NOT NULL CHECK(status IN ('WAITING_ASSET','WAITING_INSTALLATION','READY','COMPLETED','CANCELLED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,order_line_id,unit_no), UNIQUE(workspace_id,entity_id,quote_service_target_id),
 FOREIGN KEY(workspace_id,entity_id,order_line_id) REFERENCES sales_order_lines(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,quote_service_target_id) REFERENCES quote_service_targets(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,asset_id) REFERENCES installed_instruments(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,target_device_order_line_id) REFERENCES sales_order_lines(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,service_product_version_id) REFERENCES service_product_versions(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,coverage_type_id) REFERENCES service_coverage_types(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,compatibility_id) REFERENCES product_compatibilities(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,ar_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,unbilled_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,deferred_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,revenue_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,output_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,tax_version_id) REFERENCES tax_versions(workspace_id,entity_id,id),
 CHECK(net>=0 AND discount>=0 AND tax>=0 AND subtotal=net+discount AND gross=net+tax),
 CHECK((requested_start IS NULL AND requested_end IS NULL) OR (requested_start IS NOT NULL AND requested_end IS NOT NULL AND requested_end>requested_start)),
 CHECK((target_device_order_line_id IS NULL)=(target_device_unit_no IS NULL))
);
CREATE TABLE installation_events (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 asset_id bigint NOT NULL, onboarding_obligation_id bigint, business_date date NOT NULL, completion_reference text NOT NULL,
 reason text NOT NULL, intent_key text NOT NULL, payload_hash text NOT NULL, created_by bigint NOT NULL REFERENCES app_users(id),
 occurred_at timestamptz NOT NULL DEFAULT now(), UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,asset_id),
 UNIQUE(workspace_id,entity_id,intent_key), UNIQUE(workspace_id,entity_id,onboarding_obligation_id),
 FOREIGN KEY(workspace_id,entity_id,asset_id) REFERENCES installed_instruments(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,onboarding_obligation_id) REFERENCES sales_service_obligations(workspace_id,entity_id,id)
);
CREATE TABLE service_contract_terms (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 record_key text NOT NULL, contract_asset_id bigint NOT NULL, asset_id bigint NOT NULL, coverage_type_id bigint NOT NULL,
 plan_product_id bigint NOT NULL, service_product_version_id bigint NOT NULL, source_kind text NOT NULL CHECK(source_kind IN ('ORDER','OPENING')),
 source_obligation_id bigint, opening_reference text, predecessor_term_id bigint, renewal_intent_id bigint,
 coverage_start date NOT NULL, coverage_end date NOT NULL CHECK(coverage_end>coverage_start),
 subtotal numeric(18,2) NOT NULL, discount numeric(18,2) NOT NULL, net_fee numeric(18,2) NOT NULL, tax numeric(18,2) NOT NULL, gross numeric(18,2) NOT NULL,
 currency text NOT NULL, terms_days integer NOT NULL, ar_account_id bigint NOT NULL, unbilled_account_id bigint NOT NULL,
 deferred_account_id bigint NOT NULL, revenue_account_id bigint NOT NULL, output_account_id bigint, tax_version_id bigint,
 accepted_date date NOT NULL, source_hash text NOT NULL, source_version bigint NOT NULL,
 status text NOT NULL CHECK(status IN ('VALIDATED','CANCELLED')), version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 validated_by bigint NOT NULL REFERENCES app_users(id), validated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,record_key), UNIQUE(workspace_id,entity_id,source_obligation_id),
 UNIQUE(workspace_id,entity_id,predecessor_term_id), UNIQUE(workspace_id,entity_id,renewal_intent_id),
 FOREIGN KEY(workspace_id,entity_id,contract_asset_id) REFERENCES service_contract_assets(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,asset_id) REFERENCES installed_instruments(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,coverage_type_id) REFERENCES service_coverage_types(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,plan_product_id) REFERENCES products(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,service_product_version_id) REFERENCES service_product_versions(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,source_obligation_id) REFERENCES sales_service_obligations(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,predecessor_term_id) REFERENCES service_contract_terms(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,renewal_intent_id) REFERENCES renewal_intents(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,ar_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,unbilled_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,deferred_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,revenue_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,output_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,tax_version_id) REFERENCES tax_versions(workspace_id,entity_id,id),
 CHECK(net_fee>=0 AND discount>=0 AND tax>=0 AND subtotal=net_fee+discount AND gross=net_fee+tax),
 CHECK((source_kind='ORDER' AND source_obligation_id IS NOT NULL AND opening_reference IS NULL AND output_account_id IS NOT NULL AND tax_version_id IS NOT NULL)
    OR (source_kind='OPENING' AND source_obligation_id IS NULL AND opening_reference IS NOT NULL AND length(btrim(opening_reference))>0)),
 EXCLUDE USING gist (workspace_id extensions.gist_int8_ops WITH =, entity_id extensions.gist_int8_ops WITH =,
   asset_id extensions.gist_int8_ops WITH =, coverage_type_id extensions.gist_int8_ops WITH =,
   daterange(coverage_start,coverage_end,'[)') WITH &&) WHERE (status='VALIDATED')
);
ALTER TABLE renewal_intents ADD FOREIGN KEY(workspace_id,entity_id,predecessor_term_id) REFERENCES service_contract_terms(workspace_id,entity_id,id);
ALTER TABLE renewal_intents ADD FOREIGN KEY(workspace_id,entity_id,accepted_term_id) REFERENCES service_contract_terms(workspace_id,entity_id,id);

CREATE TABLE revenue_schedules (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 term_id bigint NOT NULL, schedule_version bigint NOT NULL CHECK(schedule_version>0), source_hash text NOT NULL,
 coverage_start date NOT NULL, coverage_end date NOT NULL CHECK(coverage_end>coverage_start), net_fee numeric(18,2) NOT NULL CHECK(net_fee>=0),
 sealed boolean NOT NULL DEFAULT false, created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,term_id),
 FOREIGN KEY(workspace_id,entity_id,term_id) REFERENCES service_contract_terms(workspace_id,entity_id,id)
);
CREATE TABLE revenue_schedule_lines (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 schedule_id bigint NOT NULL, period_no integer NOT NULL CHECK(period_no>0), period_start date NOT NULL, period_end date NOT NULL CHECK(period_end>period_start),
 amount numeric(18,2) NOT NULL CHECK(amount>=0), UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,schedule_id,period_no),
 FOREIGN KEY(workspace_id,entity_id,schedule_id) REFERENCES revenue_schedules(workspace_id,entity_id,id)
);
CREATE TABLE revenue_posting_runs (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 intent_key text NOT NULL, payload_hash text NOT NULL, posting_date date NOT NULL, business_date date NOT NULL,
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,intent_key),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id)
);
CREATE TABLE revenue_recognitions (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 schedule_line_id bigint NOT NULL, run_id bigint NOT NULL, amount numeric(18,2) NOT NULL CHECK(amount>=0), posting_date date NOT NULL,
 journal_id bigint, created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,schedule_line_id),
 FOREIGN KEY(workspace_id,entity_id,schedule_line_id) REFERENCES revenue_schedule_lines(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,run_id) REFERENCES revenue_posting_runs(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,journal_id) REFERENCES journal_entries(workspace_id,entity_id,id),
 CHECK((amount=0 AND journal_id IS NULL) OR (amount>0 AND journal_id IS NOT NULL))
);
CREATE TABLE service_completions (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 obligation_id bigint NOT NULL, installation_event_id bigint, completion_date date NOT NULL, posting_date date NOT NULL,
 completion_reference text NOT NULL CHECK(completion_reference=btrim(completion_reference) AND length(completion_reference) BETWEEN 1 AND 200),
 reason text NOT NULL CHECK(reason=btrim(reason) AND length(reason) BETWEEN 1 AND 200),
 amount numeric(18,2) NOT NULL CHECK(amount>=0), journal_id bigint, intent_key text NOT NULL, payload_hash text NOT NULL,
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,obligation_id), UNIQUE(workspace_id,entity_id,intent_key),
 FOREIGN KEY(workspace_id,entity_id,obligation_id) REFERENCES sales_service_obligations(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,installation_event_id) REFERENCES installation_events(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,journal_id) REFERENCES journal_entries(workspace_id,entity_id,id),
 CHECK((amount=0 AND journal_id IS NULL) OR (amount>0 AND journal_id IS NOT NULL))
);
CREATE TABLE service_opening_balances (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 term_id bigint NOT NULL, opening_source_id bigint NOT NULL, cutoff date NOT NULL, net_fee numeric(18,2) NOT NULL CHECK(net_fee>=0),
 billed numeric(18,2) NOT NULL, earned numeric(18,2) NOT NULL, billing_reference text NOT NULL, settlement_reference text NOT NULL,
 payload_hash text NOT NULL, created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,term_id),
 FOREIGN KEY(workspace_id,entity_id,term_id) REFERENCES service_contract_terms(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,opening_source_id) REFERENCES opening_balance_sources(workspace_id,entity_id,id),
 CHECK(billed BETWEEN 0 AND net_fee AND earned BETWEEN 0 AND net_fee)
);
CREATE TABLE service_opening_periods (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 opening_balance_id bigint NOT NULL, schedule_line_id bigint NOT NULL,
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,schedule_line_id),
 FOREIGN KEY(workspace_id,entity_id,opening_balance_id) REFERENCES service_opening_balances(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,schedule_line_id) REFERENCES revenue_schedule_lines(workspace_id,entity_id,id)
);
ALTER TABLE quote_lines ALTER COLUMN cogs_account_id DROP NOT NULL, ALTER COLUMN inventory_account_id DROP NOT NULL,
 ADD COLUMN deferred_account_id bigint, ADD COLUMN service_config_version bigint, ADD COLUMN service_product_version_id bigint,
 ADD FOREIGN KEY(workspace_id,entity_id,deferred_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 ADD FOREIGN KEY(workspace_id,entity_id,service_product_version_id) REFERENCES service_product_versions(workspace_id,entity_id,id);
ALTER TABLE sales_order_lines ALTER COLUMN cogs_account_id DROP NOT NULL, ALTER COLUMN inventory_account_id DROP NOT NULL,
 ADD COLUMN deferred_account_id bigint, ADD COLUMN service_config_version bigint, ADD COLUMN service_product_version_id bigint,
 ADD COLUMN product_category text,
 ADD FOREIGN KEY(workspace_id,entity_id,deferred_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 ADD FOREIGN KEY(workspace_id,entity_id,service_product_version_id) REFERENCES service_product_versions(workspace_id,entity_id,id);
ALTER TABLE sales_order_lines DISABLE TRIGGER sales_order_lines_guard;
UPDATE sales_order_lines l SET product_category=q.product_category FROM quote_lines q
 WHERE q.workspace_id=l.workspace_id AND q.entity_id=l.entity_id AND q.id=l.quote_line_id;
ALTER TABLE sales_order_lines ENABLE TRIGGER sales_order_lines_guard;
ALTER TABLE sales_order_lines ALTER COLUMN product_category SET NOT NULL;
ALTER TABLE quote_lines ADD CHECK ((product_category IN ('SERVICE_PLAN','IMPLEMENTATION_SERVICE') AND cogs_account_id IS NULL AND inventory_account_id IS NULL AND deferred_account_id IS NOT NULL AND service_config_version IS NOT NULL AND service_product_version_id IS NOT NULL)
 OR (product_category NOT IN ('SERVICE_PLAN','IMPLEMENTATION_SERVICE') AND cogs_account_id IS NOT NULL AND inventory_account_id IS NOT NULL AND deferred_account_id IS NULL AND service_config_version IS NULL AND service_product_version_id IS NULL));
ALTER TABLE sales_order_lines ADD CHECK ((product_category IN ('SERVICE_PLAN','IMPLEMENTATION_SERVICE') AND cogs_account_id IS NULL AND inventory_account_id IS NULL AND deferred_account_id IS NOT NULL AND service_config_version IS NOT NULL AND service_product_version_id IS NOT NULL)
 OR (product_category NOT IN ('SERVICE_PLAN','IMPLEMENTATION_SERVICE') AND cogs_account_id IS NOT NULL AND inventory_account_id IS NOT NULL AND deferred_account_id IS NULL AND service_config_version IS NULL AND service_product_version_id IS NULL));
ALTER TABLE sales_invoice_lines ALTER COLUMN shipment_line_id DROP NOT NULL, ALTER COLUMN slice_start DROP NOT NULL, ALTER COLUMN slice_end DROP NOT NULL, ALTER COLUMN output_account_id DROP NOT NULL,
 ADD COLUMN source_type text NOT NULL DEFAULT 'SHIPMENT', ADD COLUMN service_obligation_id bigint, ADD COLUMN service_term_id bigint,
 ADD COLUMN fee_start numeric(18,2), ADD COLUMN fee_end numeric(18,2), ADD COLUMN deferred_account_id bigint,
 ADD FOREIGN KEY(workspace_id,entity_id,service_obligation_id) REFERENCES sales_service_obligations(workspace_id,entity_id,id),
 ADD FOREIGN KEY(workspace_id,entity_id,service_term_id) REFERENCES service_contract_terms(workspace_id,entity_id,id),
 ADD FOREIGN KEY(workspace_id,entity_id,deferred_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 ADD CHECK ((source_type='SHIPMENT' AND shipment_line_id IS NOT NULL AND slice_start IS NOT NULL AND slice_end IS NOT NULL AND service_obligation_id IS NULL AND service_term_id IS NULL AND fee_start IS NULL AND fee_end IS NULL AND deferred_account_id IS NULL)
 OR (source_type='SERVICE_OBLIGATION' AND shipment_line_id IS NULL AND slice_start IS NULL AND slice_end IS NULL AND service_obligation_id IS NOT NULL AND service_term_id IS NULL AND fee_start IS NOT NULL AND fee_end IS NOT NULL AND fee_start>=0 AND fee_end>fee_start AND deferred_account_id IS NOT NULL)
 OR (source_type='SERVICE_TERM' AND shipment_line_id IS NULL AND slice_start IS NULL AND slice_end IS NULL AND service_obligation_id IS NULL AND service_term_id IS NOT NULL AND fee_start IS NOT NULL AND fee_end IS NOT NULL AND fee_start>=0 AND fee_end>fee_start AND deferred_account_id IS NOT NULL)),
 ADD CHECK (output_account_id IS NOT NULL OR (source_type='SERVICE_TERM' AND tax=0));
CREATE INDEX sales_invoice_obligation_source ON sales_invoice_lines(workspace_id,entity_id,service_obligation_id,fee_start,fee_end) WHERE service_obligation_id IS NOT NULL;
CREATE INDEX sales_invoice_term_source ON sales_invoice_lines(workspace_id,entity_id,service_term_id,fee_start,fee_end) WHERE service_term_id IS NOT NULL;
CREATE TABLE service_balance_postings (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 obligation_id bigint, term_id bigint, effective_date date NOT NULL, event_kind text NOT NULL CHECK(event_kind IN ('INVOICE','REVERSAL','RECOGNITION','COMPLETION','OPENING','RECLASSIFICATION')),
 event_key text NOT NULL, payload_hash text NOT NULL, unbilled_delta numeric(18,2) NOT NULL, deferred_delta numeric(18,2) NOT NULL,
 journal_id bigint, invoice_line_id bigint, reversal_id bigint, recognition_id bigint, completion_id bigint, opening_balance_id bigint,
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,obligation_id) REFERENCES sales_service_obligations(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,term_id) REFERENCES service_contract_terms(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,journal_id) REFERENCES journal_entries(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,invoice_line_id) REFERENCES sales_invoice_lines(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,reversal_id) REFERENCES sales_reversals(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,recognition_id) REFERENCES revenue_recognitions(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,completion_id) REFERENCES service_completions(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,opening_balance_id) REFERENCES service_opening_balances(workspace_id,entity_id,id),
 CHECK((obligation_id IS NULL)<>(term_id IS NULL))
);
CREATE UNIQUE INDEX service_balance_obligation_event ON service_balance_postings(workspace_id,entity_id,obligation_id,event_key,effective_date) WHERE obligation_id IS NOT NULL;
CREATE UNIQUE INDEX service_balance_term_event ON service_balance_postings(workspace_id,entity_id,term_id,event_key,effective_date) WHERE term_id IS NOT NULL;

-- Refuse ambiguous legacy lineage rather than inventing installations or contracts.
DO $$ BEGIN
 IF EXISTS (
  SELECT 1 FROM shipment_lines l JOIN shipments s ON (s.workspace_id,s.entity_id,s.id)=(l.workspace_id,l.entity_id,l.shipment_id)
  JOIN products p ON (p.workspace_id,p.id)=(l.workspace_id,l.product_id)
  WHERE s.status='POSTED' AND l.active AND p.category='LAB_UNIT' AND p.tracking='SERIAL'
    AND (l.slice_start IS NULL OR l.slice_end IS NULL OR l.slice_start<>trunc(l.slice_start) OR l.slice_end<>trunc(l.slice_end)
      OR l.quantity<>l.slice_end-l.slice_start OR l.quantity<>(SELECT count(*) FROM shipment_line_tracking t
         WHERE (t.workspace_id,t.entity_id,t.shipment_line_id)=(l.workspace_id,l.entity_id,l.id) AND t.active)
      OR EXISTS(SELECT 1 FROM shipment_line_tracking t JOIN inventory_reservations r
         ON (r.workspace_id,r.entity_id,r.id)=(t.workspace_id,t.entity_id,t.reservation_id)
         LEFT JOIN instrument_serials i ON (i.workspace_id,i.entity_id,i.id)=(r.workspace_id,r.entity_id,r.serial_id)
         WHERE (t.workspace_id,t.entity_id,t.shipment_line_id)=(l.workspace_id,l.entity_id,l.id) AND t.active
         AND (t.quantity<>1 OR r.quantity<>1 OR r.serial_id IS NULL OR r.order_line_id<>l.order_line_id OR r.product_id<>l.product_id
           OR i.id IS NULL OR i.product_id<>l.product_id OR i.status<>'ISSUED'
           OR 1<>(SELECT count(*) FROM stock_movements m WHERE (m.workspace_id,m.entity_id,m.serial_id)=(t.workspace_id,t.entity_id,r.serial_id)
             AND m.source_type='SHIPMENT' AND m.source_id=s.id::text AND m.product_id=l.product_id AND m.quantity_delta=-1))))
 ) OR EXISTS (
  SELECT 1 FROM shipment_line_tracking t JOIN shipment_lines l ON (l.workspace_id,l.entity_id,l.id)=(t.workspace_id,t.entity_id,t.shipment_line_id)
  JOIN shipments s ON (s.workspace_id,s.entity_id,s.id)=(l.workspace_id,l.entity_id,l.shipment_id)
  JOIN inventory_reservations r ON (r.workspace_id,r.entity_id,r.id)=(t.workspace_id,t.entity_id,t.reservation_id)
  JOIN products p ON (p.workspace_id,p.id)=(l.workspace_id,l.product_id)
  WHERE s.status='POSTED' AND l.active AND t.active AND p.category='LAB_UNIT' AND p.tracking='SERIAL'
  GROUP BY t.workspace_id,t.entity_id,r.serial_id HAVING count(*)<>1
 ) THEN RAISE EXCEPTION 'Ambiguous historical serial shipment lineage' USING ERRCODE='23514'; END IF;
END $$;
INSERT INTO installed_instruments(workspace_id,entity_id,record_key,product_id,serial,customer_account_id,origin,
 inventory_serial_id,shipment_tracking_id,source_order_line_id,device_unit_no,registration_key,registration_hash,created_by,created_at)
SELECT x.workspace_id,x.entity_id,'shipment:'||x.tracking_id,x.product_id,x.serial,x.customer_account_id,'SHIPMENT',
 x.serial_id,x.tracking_id,x.order_line_id,x.unit_no,'shipment:'||x.tracking_id,
 encode(sha256(int4send(octet_length(convert_to(x.shipment_id::text,'UTF8')))||convert_to(x.shipment_id::text,'UTF8')
   ||int4send(octet_length(convert_to(x.tracking_id::text,'UTF8')))||convert_to(x.tracking_id::text,'UTF8')
   ||int4send(octet_length(convert_to(x.order_line_id::text,'UTF8')))||convert_to(x.order_line_id::text,'UTF8')
   ||int4send(octet_length(convert_to(x.unit_no::text,'UTF8')))||convert_to(x.unit_no::text,'UTF8')
   ||int4send(octet_length(convert_to(x.serial,'UTF8')))||convert_to(x.serial,'UTF8')),'hex'),x.posted_by,x.posted_at
FROM (
 SELECT t.workspace_id,t.entity_id,s.id shipment_id,t.id tracking_id,l.product_id,i.serial,o.customer_account_id,r.serial_id,l.order_line_id,
   (l.slice_start+row_number() OVER (PARTITION BY l.workspace_id,l.entity_id,l.id ORDER BY t.id))::integer unit_no,
   s.posted_by,s.posted_at
 FROM shipment_line_tracking t JOIN shipment_lines l ON (l.workspace_id,l.entity_id,l.id)=(t.workspace_id,t.entity_id,t.shipment_line_id)
 JOIN shipments s ON (s.workspace_id,s.entity_id,s.id)=(l.workspace_id,l.entity_id,l.shipment_id)
 JOIN sales_orders o ON (o.workspace_id,o.entity_id,o.id)=(s.workspace_id,s.entity_id,s.order_id)
 JOIN inventory_reservations r ON (r.workspace_id,r.entity_id,r.id)=(t.workspace_id,t.entity_id,t.reservation_id)
 JOIN instrument_serials i ON (i.workspace_id,i.entity_id,i.id)=(r.workspace_id,r.entity_id,r.serial_id)
 JOIN products p ON (p.workspace_id,p.id)=(l.workspace_id,l.product_id)
 WHERE s.status='POSTED' AND l.active AND t.active AND p.category='LAB_UNIT' AND p.tracking='SERIAL'
) x;

CREATE FUNCTION m4_serial_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended(NEW.workspace_id::text||':'||NEW.entity_id::text||':'||NEW.serial,0));
 IF TG_TABLE_NAME='instrument_serials' THEN
  IF TG_OP='UPDATE' AND (NEW.id,NEW.workspace_id,NEW.entity_id,NEW.product_id,NEW.serial,NEW.opening_source_id)
    IS DISTINCT FROM (OLD.id,OLD.workspace_id,OLD.entity_id,OLD.product_id,OLD.serial,OLD.opening_source_id) THEN
   RAISE EXCEPTION 'Inventory serial identity immutable' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM installed_instruments a WHERE a.workspace_id=NEW.workspace_id AND a.entity_id=NEW.entity_id
    AND a.serial=NEW.serial AND (a.origin='LEGACY' OR a.inventory_serial_id<>NEW.id OR a.product_id<>NEW.product_id OR NEW.status<>'ISSUED')) THEN
   RAISE EXCEPTION 'Serial is already an asset' USING ERRCODE='23514';
  END IF;
 ELSE
  IF TG_OP='UPDATE' THEN
   IF (to_jsonb(NEW)-'version') IS DISTINCT FROM (to_jsonb(OLD)-'version') OR NEW.version<>OLD.version+1 THEN
    RAISE EXCEPTION 'Asset identity immutable' USING ERRCODE='23514';
   END IF;
  END IF;
  IF NEW.origin='LEGACY' AND (NEW.legacy_evidence IS NULL OR length(btrim(NEW.legacy_evidence))=0 OR EXISTS(
    SELECT 1 FROM instrument_serials i WHERE i.workspace_id=NEW.workspace_id AND i.entity_id=NEW.entity_id AND i.serial=NEW.serial)) THEN
   RAISE EXCEPTION 'Legacy serial conflicts or lacks evidence' USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM products p JOIN product_entity_settings ps ON ps.workspace_id=p.workspace_id AND ps.product_id=p.id
    WHERE p.workspace_id=NEW.workspace_id AND p.id=NEW.product_id AND p.category='LAB_UNIT' AND p.tracking='SERIAL' AND ps.entity_id=NEW.entity_id) THEN
   RAISE EXCEPTION 'Asset requires an entity serial device' USING ERRCODE='23514';
  END IF;
  IF NEW.origin='SHIPMENT' AND NOT EXISTS(
    SELECT 1 FROM shipment_line_tracking t JOIN shipment_lines l ON (l.workspace_id,l.entity_id,l.id)=(t.workspace_id,t.entity_id,t.shipment_line_id)
    JOIN shipments s ON (s.workspace_id,s.entity_id,s.id)=(l.workspace_id,l.entity_id,l.shipment_id)
    JOIN sales_orders o ON (o.workspace_id,o.entity_id,o.id)=(s.workspace_id,s.entity_id,s.order_id)
    JOIN inventory_reservations r ON (r.workspace_id,r.entity_id,r.id)=(t.workspace_id,t.entity_id,t.reservation_id)
    JOIN instrument_serials i ON (i.workspace_id,i.entity_id,i.id)=(r.workspace_id,r.entity_id,r.serial_id)
    WHERE (t.workspace_id,t.entity_id,t.id)=(NEW.workspace_id,NEW.entity_id,NEW.shipment_tracking_id)
      AND t.active AND l.active AND s.status='POSTED' AND t.quantity=1 AND r.quantity=1 AND i.status='ISSUED'
      AND i.id=NEW.inventory_serial_id AND i.product_id=NEW.product_id AND i.serial=NEW.serial
      AND l.product_id=NEW.product_id AND l.order_line_id=NEW.source_order_line_id AND r.order_line_id=l.order_line_id
      AND o.customer_account_id=NEW.customer_account_id
      AND l.slice_start=trunc(l.slice_start) AND l.slice_end=trunc(l.slice_end)
      AND l.quantity=(SELECT count(*) FROM shipment_line_tracking tt WHERE
        (tt.workspace_id,tt.entity_id,tt.shipment_line_id)=(l.workspace_id,l.entity_id,l.id) AND tt.active)
      AND 1=(SELECT count(*) FROM stock_movements m WHERE (m.workspace_id,m.entity_id,m.serial_id)=(i.workspace_id,i.entity_id,i.id)
        AND m.source_type='SHIPMENT' AND m.source_id=s.id::text AND m.product_id=l.product_id AND m.quantity_delta=-1)
      AND NEW.device_unit_no=l.slice_start+(SELECT count(*) FROM shipment_line_tracking t2
        WHERE (t2.workspace_id,t2.entity_id,t2.shipment_line_id)=(t.workspace_id,t.entity_id,t.shipment_line_id) AND t2.active AND t2.id<=t.id)
  ) THEN RAISE EXCEPTION 'Asset shipment source mismatch' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER m4_inventory_serial_guard BEFORE INSERT OR UPDATE ON instrument_serials FOR EACH ROW EXECUTE FUNCTION m4_serial_guard();
CREATE TRIGGER m4_asset_serial_guard BEFORE INSERT OR UPDATE ON installed_instruments FOR EACH ROW EXECUTE FUNCTION m4_serial_guard();

CREATE FUNCTION m4_source_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_id bigint; line_id bigint; parent_status text;
BEGIN
 IF TG_TABLE_NAME='service_product_versions' THEN
  IF NOT EXISTS(SELECT 1 FROM products p JOIN product_entity_settings s ON s.workspace_id=p.workspace_id AND s.product_id=p.id
    WHERE p.workspace_id=NEW.workspace_id AND p.id=NEW.product_id AND s.entity_id=NEW.entity_id AND s.active
      AND p.category=NEW.category_snapshot AND p.tracking='NONE' AND p.status='ACTIVE' AND p.active) THEN
   RAISE EXCEPTION 'Service profile product is ineligible' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='asset_operator_links' THEN
  IF NOT EXISTS(SELECT 1 FROM installed_instruments a JOIN customer_entity_accounts c
    ON (c.workspace_id,c.entity_id,c.id)=(a.workspace_id,a.entity_id,a.customer_account_id)
    JOIN customer_contact_links l ON l.workspace_id=c.workspace_id AND l.customer_id=c.customer_id AND l.active
    JOIN contacts ct ON ct.workspace_id=l.workspace_id AND ct.id=l.contact_id AND ct.active
    WHERE (a.workspace_id,a.entity_id,a.id)=(NEW.workspace_id,NEW.entity_id,NEW.asset_id) AND ct.id=NEW.contact_id) THEN
   RAISE EXCEPTION 'Operator must be linked to asset owner' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='service_contract_assets' THEN
  IF NOT EXISTS(SELECT 1 FROM service_contracts c JOIN installed_instruments a
    ON (a.workspace_id,a.entity_id,a.id)=(c.workspace_id,c.entity_id,NEW.asset_id)
    WHERE (c.workspace_id,c.entity_id,c.id)=(NEW.workspace_id,NEW.entity_id,NEW.contract_id)
      AND c.customer_account_id=a.customer_account_id) THEN
   RAISE EXCEPTION 'Contract owner must match asset owner' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='installation_events' THEN
  IF NEW.onboarding_obligation_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sales_service_obligations o
   WHERE (o.workspace_id,o.entity_id,o.id)=(NEW.workspace_id,NEW.entity_id,NEW.onboarding_obligation_id)
    AND o.asset_id=NEW.asset_id AND o.kind='ONBOARDING' AND o.status NOT IN ('CANCELLED','COMPLETED')) THEN
   RAISE EXCEPTION 'Installation obligation mismatch' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='product_compatibilities' THEN
  IF TG_OP='UPDATE' AND ((to_jsonb(NEW)-ARRAY['active','version']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['active','version']) OR NEW.version<>OLD.version+1) THEN
   RAISE EXCEPTION 'Compatibility identity and version invalid' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='quote_service_targets' THEN
  SELECT r.status INTO parent_status FROM quote_lines l JOIN quote_revisions r ON (r.workspace_id,r.entity_id,r.id)=(l.workspace_id,l.entity_id,l.revision_id)
   WHERE (l.workspace_id,l.entity_id,l.id)=(NEW.workspace_id,NEW.entity_id,NEW.quote_line_id) FOR UPDATE OF r;
  IF parent_status IS DISTINCT FROM 'DRAFT' THEN RAISE EXCEPTION 'Targets require draft quote' USING ERRCODE='23514'; END IF;
  IF TG_OP='UPDATE' AND (NEW.id,NEW.workspace_id,NEW.entity_id,NEW.quote_line_id,NEW.unit_no)
   IS DISTINCT FROM (OLD.id,OLD.workspace_id,OLD.entity_id,OLD.quote_line_id,OLD.unit_no) THEN
   RAISE EXCEPTION 'Target identity immutable' USING ERRCODE='23514'; END IF;
  IF NEW.active AND NOT EXISTS(
   SELECT 1 FROM quote_lines l JOIN quote_revisions r ON (r.workspace_id,r.entity_id,r.id)=(l.workspace_id,l.entity_id,l.revision_id)
   JOIN quotes q ON (q.workspace_id,q.entity_id,q.id)=(r.workspace_id,r.entity_id,r.quote_id)
   JOIN service_product_versions v ON (v.workspace_id,v.entity_id,v.id)=(l.workspace_id,l.entity_id,NEW.service_product_version_id)
   JOIN product_compatibilities c ON (c.workspace_id,c.entity_id,c.id)=(l.workspace_id,l.entity_id,NEW.compatibility_id)
   LEFT JOIN installed_instruments a ON (a.workspace_id,a.entity_id,a.id)=(l.workspace_id,l.entity_id,NEW.target_asset_id)
   LEFT JOIN quote_lines d ON (d.workspace_id,d.entity_id,d.id)=(l.workspace_id,l.entity_id,NEW.target_device_quote_line_id)
   WHERE (l.workspace_id,l.entity_id,l.id)=(NEW.workspace_id,NEW.entity_id,NEW.quote_line_id) AND l.active
    AND l.product_id=v.product_id AND l.service_product_version_id=v.id AND l.quantity=trunc(l.quantity) AND NEW.unit_no<=l.quantity
    AND c.component_id=l.product_id AND c.version=NEW.compatibility_version AND c.active AND v.enabled
    AND v.revision=(SELECT max(v2.revision) FROM service_product_versions v2 WHERE
      (v2.workspace_id,v2.entity_id,v2.product_id)=(v.workspace_id,v.entity_id,v.product_id))
    AND ((NEW.start_basis='INSTALLATION' AND NEW.coverage_start IS NULL AND NEW.coverage_end IS NULL AND NEW.renewal_intent_id IS NULL)
      OR (v.category_snapshot='SERVICE_PLAN' AND NEW.start_basis='EXPLICIT' AND NEW.target_asset_id IS NOT NULL
          AND NEW.coverage_start IS NOT NULL AND NEW.coverage_end IS NOT NULL AND NEW.renewal_intent_id IS NULL)
      OR (v.category_snapshot='SERVICE_PLAN' AND NEW.start_basis='RENEWAL' AND NEW.target_asset_id IS NOT NULL
          AND EXISTS(SELECT 1 FROM renewal_intents i JOIN service_contract_terms pt
            ON (pt.workspace_id,pt.entity_id,pt.id)=(i.workspace_id,i.entity_id,i.predecessor_term_id)
            WHERE (i.workspace_id,i.entity_id,i.id)=(NEW.workspace_id,NEW.entity_id,NEW.renewal_intent_id)
             AND i.version=NEW.renewal_intent_version AND i.status='OPEN' AND i.customer_account_id=q.customer_account_id
             AND pt.asset_id=NEW.target_asset_id AND pt.coverage_type_id=v.coverage_type_id AND pt.coverage_end=i.target_start)))
    AND ((a.id IS NOT NULL AND a.version=NEW.target_asset_version AND a.customer_account_id=q.customer_account_id AND a.product_id=c.device_id)
      OR (d.id IS NOT NULL AND d.active AND d.revision_id=l.revision_id AND d.product_id=c.device_id
        AND d.product_category='LAB_UNIT' AND d.tracking='SERIAL' AND d.quantity=trunc(d.quantity) AND NEW.target_device_unit_no<=d.quantity))
  ) THEN RAISE EXCEPTION 'Service target source mismatch' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='sales_service_obligations' THEN
  IF TG_OP='INSERT' AND NOT EXISTS(
   SELECT 1 FROM sales_order_lines l JOIN sales_orders o ON (o.workspace_id,o.entity_id,o.id)=(l.workspace_id,l.entity_id,l.order_id)
   JOIN quote_revisions qr ON (qr.workspace_id,qr.entity_id,qr.id)=(o.workspace_id,o.entity_id,o.quote_revision_id)
   JOIN quote_service_targets t ON (t.workspace_id,t.entity_id,t.id)=(l.workspace_id,l.entity_id,NEW.quote_service_target_id)
   JOIN service_product_versions v ON (v.workspace_id,v.entity_id,v.id)=(l.workspace_id,l.entity_id,NEW.service_product_version_id)
   WHERE (l.workspace_id,l.entity_id,l.id)=(NEW.workspace_id,NEW.entity_id,NEW.order_line_id)
    AND t.quote_line_id=l.quote_line_id AND t.unit_no=NEW.unit_no AND t.active AND t.service_product_version_id=v.id
    AND l.service_product_version_id=v.id AND NEW.compatibility_id=t.compatibility_id AND NEW.compatibility_version=t.compatibility_version
    AND NEW.start_basis=t.start_basis AND NEW.accepted_source_hash=o.source_hash AND NEW.currency=o.currency AND NEW.terms_days=o.terms_days
    AND NEW.accepted_date=qr.accepted_business_date AND (NEW.requested_start,NEW.requested_end) IS NOT DISTINCT FROM (t.coverage_start,t.coverage_end)
    AND NEW.ar_account_id=l.ar_account_id AND NEW.unbilled_account_id=l.unbilled_account_id AND NEW.deferred_account_id=l.deferred_account_id
    AND NEW.revenue_account_id=l.revenue_account_id AND NEW.output_account_id=l.output_account_id AND NEW.tax_version_id=l.tax_version_id
    AND NEW.coverage_type_id IS NOT DISTINCT FROM v.coverage_type_id AND NEW.duration_months IS NOT DISTINCT FROM v.duration_months
    AND ((NEW.kind='PLAN' AND v.category_snapshot='SERVICE_PLAN') OR (NEW.kind='ONBOARDING' AND v.category_snapshot='IMPLEMENTATION_SERVICE'))
    AND ((t.target_asset_id IS NOT NULL AND NEW.asset_id=t.target_asset_id AND NEW.target_device_order_line_id IS NULL)
      OR (t.target_asset_id IS NULL AND EXISTS(SELECT 1 FROM sales_order_lines d
       WHERE (d.workspace_id,d.entity_id,d.id)=(l.workspace_id,l.entity_id,NEW.target_device_order_line_id)
       AND d.order_id=l.order_id AND d.quote_line_id=t.target_device_quote_line_id AND NEW.target_device_unit_no=t.target_device_unit_no)
       AND (NEW.asset_id IS NULL OR EXISTS(SELECT 1 FROM installed_instruments a WHERE
        (a.workspace_id,a.entity_id,a.id)=(NEW.workspace_id,NEW.entity_id,NEW.asset_id)
        AND a.source_order_line_id=NEW.target_device_order_line_id AND a.device_unit_no=NEW.target_device_unit_no))))
  ) THEN RAISE EXCEPTION 'Obligation must match accepted source' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='service_contract_terms' THEN
  IF TG_OP='INSERT' AND NOT EXISTS(
   SELECT 1 FROM service_contract_assets ca JOIN service_contracts c ON (c.workspace_id,c.entity_id,c.id)=(ca.workspace_id,ca.entity_id,ca.contract_id)
   JOIN installed_instruments a ON (a.workspace_id,a.entity_id,a.id)=(ca.workspace_id,ca.entity_id,ca.asset_id)
   JOIN service_product_versions v ON (v.workspace_id,v.entity_id,v.id)=(ca.workspace_id,ca.entity_id,NEW.service_product_version_id)
   WHERE (ca.workspace_id,ca.entity_id,ca.id)=(NEW.workspace_id,NEW.entity_id,NEW.contract_asset_id)
    AND ca.asset_id=NEW.asset_id AND c.coverage_type_id=NEW.coverage_type_id AND a.customer_account_id=c.customer_account_id
    AND v.product_id=NEW.plan_product_id AND v.coverage_type_id=NEW.coverage_type_id AND v.category_snapshot='SERVICE_PLAN'
    AND ((NEW.source_kind='OPENING' AND a.origin='LEGACY' AND NEW.opening_reference='FULL_OPERATIONS:1'
          AND EXISTS(SELECT 1 FROM scenario_workspaces w WHERE w.id=NEW.workspace_id AND w.initialization_state='NEW'))
      OR EXISTS(SELECT 1 FROM sales_service_obligations o JOIN sales_order_lines l
       ON (l.workspace_id,l.entity_id,l.id)=(o.workspace_id,o.entity_id,o.order_line_id)
       JOIN sales_orders so ON (so.workspace_id,so.entity_id,so.id)=(l.workspace_id,l.entity_id,l.order_id)
       WHERE (o.workspace_id,o.entity_id,o.id)=(NEW.workspace_id,NEW.entity_id,NEW.source_obligation_id)
        AND o.kind='PLAN' AND o.asset_id=NEW.asset_id AND so.customer_account_id=c.customer_account_id
        AND o.service_product_version_id=NEW.service_product_version_id AND o.coverage_type_id=NEW.coverage_type_id
        AND NEW.accepted_date=o.accepted_date AND NEW.source_hash=o.accepted_source_hash AND NEW.source_version=o.version
        AND ((o.start_basis='EXPLICIT' AND (NEW.coverage_start,NEW.coverage_end)=(o.requested_start,o.requested_end))
          OR (o.start_basis='INSTALLATION' AND EXISTS(SELECT 1 FROM installation_events ie
            WHERE (ie.workspace_id,ie.entity_id,ie.asset_id)=(o.workspace_id,o.entity_id,o.asset_id)
             AND NEW.coverage_start=ie.business_date AND NEW.coverage_end=(ie.business_date+(o.duration_months||' months')::interval)::date))
          OR (o.start_basis='RENEWAL' AND NEW.renewal_intent_id IS NOT NULL))
        AND (o.subtotal,o.discount,o.net,o.tax,o.gross,o.currency,o.terms_days,o.ar_account_id,o.unbilled_account_id,o.deferred_account_id,o.revenue_account_id,o.output_account_id,o.tax_version_id)
        IS NOT DISTINCT FROM (NEW.subtotal,NEW.discount,NEW.net_fee,NEW.tax,NEW.gross,NEW.currency,NEW.terms_days,NEW.ar_account_id,NEW.unbilled_account_id,NEW.deferred_account_id,NEW.revenue_account_id,NEW.output_account_id,NEW.tax_version_id)))
  ) THEN RAISE EXCEPTION 'Term source or owner mismatch' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER m4_profile_source BEFORE INSERT ON service_product_versions FOR EACH ROW EXECUTE FUNCTION m4_source_guard();
CREATE TRIGGER m4_operator_source BEFORE INSERT ON asset_operator_links FOR EACH ROW EXECUTE FUNCTION m4_source_guard();
CREATE TRIGGER m4_contract_asset_source BEFORE INSERT ON service_contract_assets FOR EACH ROW EXECUTE FUNCTION m4_source_guard();
CREATE TRIGGER m4_installation_source BEFORE INSERT ON installation_events FOR EACH ROW EXECUTE FUNCTION m4_source_guard();
CREATE TRIGGER m4_compatibility_source BEFORE UPDATE ON product_compatibilities FOR EACH ROW EXECUTE FUNCTION m4_source_guard();
CREATE TRIGGER m4_target_source BEFORE INSERT OR UPDATE ON quote_service_targets FOR EACH ROW EXECUTE FUNCTION m4_source_guard();
CREATE TRIGGER m4_obligation_source BEFORE INSERT ON sales_service_obligations FOR EACH ROW EXECUTE FUNCTION m4_source_guard();
CREATE TRIGGER m4_term_source BEFORE INSERT ON service_contract_terms FOR EACH ROW EXECUTE FUNCTION m4_source_guard();

CREATE FUNCTION m4_mutable_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Service history cannot be deleted' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='revenue_schedules' THEN
  IF TG_OP='INSERT' THEN
   IF NEW.sealed OR NOT EXISTS(SELECT 1 FROM service_contract_terms t WHERE (t.workspace_id,t.entity_id,t.id)=(NEW.workspace_id,NEW.entity_id,NEW.term_id)
     AND (t.source_hash,t.coverage_start,t.coverage_end,t.net_fee)=(NEW.source_hash,NEW.coverage_start,NEW.coverage_end,NEW.net_fee))
   THEN RAISE EXCEPTION 'Schedule source mismatch' USING ERRCODE='23514'; END IF;
  ELSIF OLD.sealed OR NOT NEW.sealed OR (to_jsonb(NEW)-'sealed') IS DISTINCT FROM (to_jsonb(OLD)-'sealed') THEN
   RAISE EXCEPTION 'Sealed schedule immutable' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='revenue_schedule_lines' THEN
  IF TG_OP<>'INSERT' OR NOT EXISTS(SELECT 1 FROM revenue_schedules s WHERE (s.workspace_id,s.entity_id,s.id)=(NEW.workspace_id,NEW.entity_id,NEW.schedule_id) AND NOT s.sealed FOR UPDATE) THEN
   RAISE EXCEPTION 'Schedule lines require unsealed schedule' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='service_posting_config' THEN
  IF TG_OP='UPDATE' AND (NEW.version<>OLD.version+1 OR (NEW.workspace_id,NEW.entity_id) IS DISTINCT FROM (OLD.workspace_id,OLD.entity_id)
    OR ((NEW.deferred_account_id,NEW.implementation_revenue_account_id,NEW.service_revenue_account_id) IS DISTINCT FROM
        (OLD.deferred_account_id,OLD.implementation_revenue_account_id,OLD.service_revenue_account_id) AND (
      EXISTS(SELECT 1 FROM sales_service_obligations o WHERE (o.workspace_id,o.entity_id)=(OLD.workspace_id,OLD.entity_id))
      OR EXISTS(SELECT 1 FROM service_contract_terms t WHERE (t.workspace_id,t.entity_id)=(OLD.workspace_id,OLD.entity_id))))) THEN
   RAISE EXCEPTION 'Used service configuration is immutable' USING ERRCODE='23514'; END IF;
  IF NOT EXISTS(SELECT 1 FROM chart_accounts d JOIN chart_accounts i ON (i.workspace_id,i.entity_id,i.id)=(d.workspace_id,d.entity_id,NEW.implementation_revenue_account_id)
     JOIN chart_accounts s ON (s.workspace_id,s.entity_id,s.id)=(d.workspace_id,d.entity_id,NEW.service_revenue_account_id)
     WHERE (d.workspace_id,d.entity_id,d.id)=(NEW.workspace_id,NEW.entity_id,NEW.deferred_account_id)
       AND d.active AND i.active AND s.active AND d.account_type='LIABILITY' AND i.account_type='INCOME' AND s.account_type='INCOME') THEN
   RAISE EXCEPTION 'Invalid service account types' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM procurement_posting_config p WHERE (p.workspace_id,p.entity_id)=(NEW.workspace_id,NEW.entity_id)
     AND (NEW.deferred_account_id IN (p.ap_account_id,p.grni_account_id)
       OR p.adjustment_gain_account_id IN (NEW.implementation_revenue_account_id,NEW.service_revenue_account_id)))
   OR EXISTS(SELECT 1 FROM tax_versions t WHERE (t.workspace_id,t.entity_id)=(NEW.workspace_id,NEW.entity_id) AND t.output_account_id=NEW.deferred_account_id)
   OR EXISTS(SELECT 1 FROM sales_posting_config s WHERE (s.workspace_id,s.entity_id)=(NEW.workspace_id,NEW.entity_id)
      AND s.revenue_account_id IN (NEW.implementation_revenue_account_id,NEW.service_revenue_account_id)) THEN
   RAISE EXCEPTION 'Service account conflicts with existing mapping' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='sales_posting_config' THEN
  IF EXISTS(SELECT 1 FROM service_posting_config s WHERE (s.workspace_id,s.entity_id)=(NEW.workspace_id,NEW.entity_id)
    AND NEW.revenue_account_id IN (s.implementation_revenue_account_id,s.service_revenue_account_id)) THEN
   RAISE EXCEPTION 'Sales revenue conflicts with service mapping' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='procurement_posting_config' THEN
  IF EXISTS(SELECT 1 FROM service_posting_config s WHERE (s.workspace_id,s.entity_id)=(NEW.workspace_id,NEW.entity_id)
    AND (s.deferred_account_id IN (NEW.ap_account_id,NEW.grni_account_id)
      OR NEW.adjustment_gain_account_id IN (s.implementation_revenue_account_id,s.service_revenue_account_id))) THEN
   RAISE EXCEPTION 'Procurement account conflicts with service mapping' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='tax_versions' THEN
  IF EXISTS(SELECT 1 FROM service_posting_config s WHERE (s.workspace_id,s.entity_id)=(NEW.workspace_id,NEW.entity_id)
    AND s.deferred_account_id=NEW.output_account_id) THEN
   RAISE EXCEPTION 'Output tax conflicts with deferred account' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='sales_service_obligations' THEN
  IF (to_jsonb(NEW)-ARRAY['asset_id','status','version']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['asset_id','status','version'])
    OR NEW.version<>OLD.version+1 OR (OLD.asset_id IS NOT NULL AND NEW.asset_id IS DISTINCT FROM OLD.asset_id)
    OR NOT ((OLD.status='WAITING_ASSET' AND NEW.status IN ('WAITING_INSTALLATION','READY','CANCELLED'))
       OR (OLD.status='WAITING_INSTALLATION' AND NEW.status IN ('READY','CANCELLED'))
       OR (OLD.status='READY' AND NEW.status IN ('COMPLETED','CANCELLED'))) THEN
   RAISE EXCEPTION 'Obligation source or transition invalid' USING ERRCODE='23514'; END IF;
  IF OLD.asset_id IS NULL AND NEW.asset_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM installed_instruments a
   WHERE (a.workspace_id,a.entity_id,a.id)=(OLD.workspace_id,OLD.entity_id,NEW.asset_id)
    AND a.source_order_line_id=OLD.target_device_order_line_id AND a.device_unit_no=OLD.target_device_unit_no) THEN
   RAISE EXCEPTION 'Obligation asset binding mismatch' USING ERRCODE='23514'; END IF;
  IF NEW.status='COMPLETED' AND (NEW.kind<>'ONBOARDING' OR NOT EXISTS(SELECT 1 FROM service_completions c
    WHERE (c.workspace_id,c.entity_id,c.obligation_id)=(NEW.workspace_id,NEW.entity_id,NEW.id))) THEN
   RAISE EXCEPTION 'Completion requires financial source' USING ERRCODE='23514'; END IF;
  IF NEW.status='CANCELLED' AND (EXISTS(SELECT 1 FROM service_completions c
    WHERE (c.workspace_id,c.entity_id,c.obligation_id)=(NEW.workspace_id,NEW.entity_id,NEW.id))
    OR EXISTS(SELECT 1 FROM sales_invoice_lines l JOIN sales_invoices i ON (i.workspace_id,i.entity_id,i.id)=(l.workspace_id,l.entity_id,l.invoice_id)
     WHERE (l.workspace_id,l.entity_id,l.service_obligation_id)=(NEW.workspace_id,NEW.entity_id,NEW.id) AND l.active AND i.status='POSTED'
      AND NOT EXISTS(SELECT 1 FROM sales_reversals r WHERE (r.workspace_id,r.entity_id,r.invoice_id)=(i.workspace_id,i.entity_id,i.id)
       AND r.reversal_date<=(SELECT business_date FROM scenario_workspaces WHERE id=NEW.workspace_id)))
    OR EXISTS(SELECT 1 FROM service_contract_terms t WHERE (t.workspace_id,t.entity_id,t.source_obligation_id)=(NEW.workspace_id,NEW.entity_id,NEW.id) AND t.status='VALIDATED')) THEN
   RAISE EXCEPTION 'Service obligation remains earned or billed' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='service_contract_terms' THEN
  IF (to_jsonb(NEW)-ARRAY['status','version']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','version'])
    OR OLD.status<>'VALIDATED' OR NEW.status<>'CANCELLED' OR NEW.version<>OLD.version+1 THEN
   RAISE EXCEPTION 'Term definition immutable' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM revenue_recognitions r JOIN revenue_schedule_lines l ON (l.workspace_id,l.entity_id,l.id)=(r.workspace_id,r.entity_id,r.schedule_line_id)
    JOIN revenue_schedules s ON (s.workspace_id,s.entity_id,s.id)=(l.workspace_id,l.entity_id,l.schedule_id)
    WHERE (s.workspace_id,s.entity_id,s.term_id)=(OLD.workspace_id,OLD.entity_id,OLD.id))
    OR OLD.coverage_start<=(SELECT business_date FROM scenario_workspaces WHERE id=OLD.workspace_id)
    OR EXISTS(SELECT 1 FROM sales_invoice_lines l JOIN sales_invoices i ON (i.workspace_id,i.entity_id,i.id)=(l.workspace_id,l.entity_id,l.invoice_id)
      WHERE (l.workspace_id,l.entity_id,l.service_term_id)=(OLD.workspace_id,OLD.entity_id,OLD.id) AND l.active AND i.status='POSTED'
        AND NOT EXISTS(SELECT 1 FROM sales_reversals r WHERE (r.workspace_id,r.entity_id,r.invoice_id)=(i.workspace_id,i.entity_id,i.id)
          AND r.reversal_date<=(SELECT business_date FROM scenario_workspaces WHERE id=OLD.workspace_id)))
    OR EXISTS(SELECT 1 FROM service_opening_balances b WHERE (b.workspace_id,b.entity_id,b.term_id)=(OLD.workspace_id,OLD.entity_id,OLD.id) AND (b.billed<>0 OR b.earned<>0)) THEN
   RAISE EXCEPTION 'Earned term cannot be cancelled' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='renewal_intents' THEN
  IF (to_jsonb(NEW)-ARRAY['opportunity_id','status','accepted_term_id','version']) IS DISTINCT FROM
     (to_jsonb(OLD)-ARRAY['opportunity_id','status','accepted_term_id','version']) OR NEW.version<>OLD.version+1
     OR (OLD.opportunity_id IS NOT NULL AND NEW.opportunity_id IS DISTINCT FROM OLD.opportunity_id)
     OR OLD.status='CONVERTED' OR (NEW.status='CONVERTED' AND NEW.accepted_term_id IS NULL) THEN
   RAISE EXCEPTION 'Renewal identity or transition invalid' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER m4_schedule_mutable BEFORE INSERT OR UPDATE OR DELETE ON revenue_schedules FOR EACH ROW EXECUTE FUNCTION m4_mutable_guard();
CREATE TRIGGER m4_schedule_line_mutable BEFORE INSERT OR UPDATE OR DELETE ON revenue_schedule_lines FOR EACH ROW EXECUTE FUNCTION m4_mutable_guard();
CREATE TRIGGER m4_config_mutable BEFORE INSERT OR UPDATE OR DELETE ON service_posting_config FOR EACH ROW EXECUTE FUNCTION m4_mutable_guard();
CREATE TRIGGER m4_sales_account_roles BEFORE INSERT OR UPDATE ON sales_posting_config FOR EACH ROW EXECUTE FUNCTION m4_mutable_guard();
CREATE TRIGGER m4_procurement_account_roles BEFORE INSERT OR UPDATE ON procurement_posting_config FOR EACH ROW EXECUTE FUNCTION m4_mutable_guard();
CREATE TRIGGER m4_tax_account_roles BEFORE INSERT OR UPDATE ON tax_versions FOR EACH ROW EXECUTE FUNCTION m4_mutable_guard();
CREATE TRIGGER m4_obligation_mutable BEFORE UPDATE OR DELETE ON sales_service_obligations FOR EACH ROW EXECUTE FUNCTION m4_mutable_guard();
CREATE TRIGGER m4_term_mutable BEFORE UPDATE OR DELETE ON service_contract_terms FOR EACH ROW EXECUTE FUNCTION m4_mutable_guard();
CREATE TRIGGER m4_renewal_mutable BEFORE UPDATE OR DELETE ON renewal_intents FOR EACH ROW EXECUTE FUNCTION m4_mutable_guard();

CREATE FUNCTION m4_order_snapshot_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' AND NOT EXISTS(SELECT 1 FROM quote_lines q WHERE (q.workspace_id,q.entity_id,q.id)=(NEW.workspace_id,NEW.entity_id,NEW.quote_line_id)
  AND (q.product_category,q.deferred_account_id,q.service_config_version,q.service_product_version_id)
   IS NOT DISTINCT FROM (NEW.product_category,NEW.deferred_account_id,NEW.service_config_version,NEW.service_product_version_id)) THEN
  RAISE EXCEPTION 'Order service snapshots differ from quote' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER m4_order_snapshot BEFORE INSERT ON sales_order_lines FOR EACH ROW EXECUTE FUNCTION m4_order_snapshot_guard();

CREATE FUNCTION m4_deferred_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sid bigint; tid bigint; rid bigint; b service_opening_balances%ROWTYPE; sch revenue_schedules%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='revenue_schedules' THEN sid:=NEW.id;
 ELSIF TG_TABLE_NAME='revenue_schedule_lines' THEN sid:=NEW.schedule_id;
 ELSIF TG_TABLE_NAME='service_contract_terms' THEN tid:=NEW.id;
 ELSIF TG_TABLE_NAME='service_opening_balances' THEN tid:=NEW.term_id;
 ELSIF TG_TABLE_NAME='service_opening_periods' THEN
  SELECT term_id INTO tid FROM service_opening_balances WHERE (workspace_id,entity_id,id)=(NEW.workspace_id,NEW.entity_id,NEW.opening_balance_id);
 ELSIF TG_TABLE_NAME='renewal_intents' THEN rid:=NEW.id;
 ELSIF TG_TABLE_NAME='crm_opportunities' THEN
  IF (NEW.pipeline='SERVICE_RENEWAL') IS DISTINCT FROM EXISTS(SELECT 1 FROM renewal_intents i WHERE
      (i.workspace_id,i.entity_id,i.opportunity_id)=(NEW.workspace_id,NEW.entity_id,NEW.id)) THEN
   RAISE EXCEPTION 'Renewal opportunity requires matching intent' USING ERRCODE='23514'; END IF;
 END IF;
 IF rid IS NOT NULL AND NOT EXISTS(SELECT 1 FROM renewal_intents i
   JOIN service_contract_terms t ON (t.workspace_id,t.entity_id,t.id)=(i.workspace_id,i.entity_id,i.predecessor_term_id)
   JOIN service_contract_assets ca ON (ca.workspace_id,ca.entity_id,ca.id)=(t.workspace_id,t.entity_id,t.contract_asset_id)
   JOIN service_contracts c ON (c.workspace_id,c.entity_id,c.id)=(ca.workspace_id,ca.entity_id,ca.contract_id)
   JOIN crm_opportunities o ON (o.workspace_id,o.entity_id,o.id)=(i.workspace_id,i.entity_id,i.opportunity_id)
   WHERE (i.workspace_id,i.entity_id,i.id)=(NEW.workspace_id,NEW.entity_id,rid)
    AND ca.contract_id=i.contract_id AND t.coverage_end=i.target_start AND o.pipeline='SERVICE_RENEWAL'
    AND o.customer_account_id=i.customer_account_id AND c.customer_account_id=i.customer_account_id
    AND (i.status='OPEN' OR EXISTS(SELECT 1 FROM service_contract_terms successor WHERE
      (successor.workspace_id,successor.entity_id,successor.id)=(i.workspace_id,i.entity_id,i.accepted_term_id)
      AND successor.renewal_intent_id=i.id AND successor.predecessor_term_id=i.predecessor_term_id AND successor.coverage_start=i.target_start)))
 THEN RAISE EXCEPTION 'Renewal intent source or opportunity incomplete' USING ERRCODE='23514'; END IF;
 IF sid IS NOT NULL THEN SELECT * INTO sch FROM revenue_schedules WHERE (workspace_id,entity_id,id)=(NEW.workspace_id,NEW.entity_id,sid); tid:=sch.term_id; END IF;
 IF tid IS NOT NULL THEN
  SELECT * INTO sch FROM revenue_schedules WHERE (workspace_id,entity_id,term_id)=(NEW.workspace_id,NEW.entity_id,tid);
  IF sch.id IS NULL OR NOT sch.sealed OR NOT EXISTS(SELECT 1 FROM revenue_schedule_lines WHERE (workspace_id,entity_id,schedule_id)=(NEW.workspace_id,NEW.entity_id,sch.id))
   OR (SELECT coalesce(sum(amount),0) FROM revenue_schedule_lines WHERE (workspace_id,entity_id,schedule_id)=(NEW.workspace_id,NEW.entity_id,sch.id))<>sch.net_fee
   OR EXISTS(SELECT 1 FROM (SELECT period_no,period_start,period_end,
       row_number() OVER (ORDER BY period_no) rn,lag(period_end) OVER (ORDER BY period_no) prev_end
      FROM revenue_schedule_lines WHERE (workspace_id,entity_id,schedule_id)=(NEW.workspace_id,NEW.entity_id,sch.id)) x
      WHERE period_no<>rn OR (rn=1 AND period_start<>sch.coverage_start) OR (rn>1 AND period_start<>prev_end))
   OR (SELECT max(period_end) FROM revenue_schedule_lines WHERE (workspace_id,entity_id,schedule_id)=(NEW.workspace_id,NEW.entity_id,sch.id))<>sch.coverage_end THEN
    RAISE EXCEPTION 'Schedule must be sealed and contiguous with exact fee' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM service_contract_terms t WHERE (t.workspace_id,t.entity_id,t.id)=(NEW.workspace_id,NEW.entity_id,tid) AND t.source_kind='OPENING') THEN
   SELECT * INTO b FROM service_opening_balances WHERE (workspace_id,entity_id,term_id)=(NEW.workspace_id,NEW.entity_id,tid);
   IF b.id IS NULL OR b.net_fee<>sch.net_fee OR (SELECT coalesce(sum(l.amount),0) FROM service_opening_periods p
     JOIN revenue_schedule_lines l ON (l.workspace_id,l.entity_id,l.id)=(p.workspace_id,p.entity_id,p.schedule_line_id)
     WHERE (p.workspace_id,p.entity_id,p.opening_balance_id)=(NEW.workspace_id,NEW.entity_id,b.id) AND l.schedule_id=sch.id AND l.period_end<=b.cutoff)<>b.earned
     OR EXISTS(SELECT 1 FROM service_opening_periods p JOIN revenue_schedule_lines l ON (l.workspace_id,l.entity_id,l.id)=(p.workspace_id,p.entity_id,p.schedule_line_id)
       WHERE (p.workspace_id,p.entity_id,p.opening_balance_id)=(NEW.workspace_id,NEW.entity_id,b.id) AND (l.schedule_id<>sch.id OR l.period_end>b.cutoff))
     OR EXISTS(SELECT 1 FROM revenue_schedule_lines l WHERE (l.workspace_id,l.entity_id,l.schedule_id)=(NEW.workspace_id,NEW.entity_id,sch.id)
       AND l.period_end<=b.cutoff AND NOT EXISTS(SELECT 1 FROM service_opening_periods p
        WHERE (p.workspace_id,p.entity_id,p.schedule_line_id,p.opening_balance_id)=(l.workspace_id,l.entity_id,l.id,b.id)))
     OR NOT EXISTS(SELECT 1 FROM scenario_baseline_versions bv JOIN scenario_workspaces w ON w.id=bv.workspace_id
       WHERE bv.workspace_id=NEW.workspace_id AND bv.entity_id=NEW.entity_id AND bv.baseline_key='FULL_OPERATIONS'
        AND w.initialization_state='READY' AND w.baseline_key='FULL_OPERATIONS')
     OR NOT EXISTS(SELECT 1 FROM service_contract_terms t JOIN opening_balance_sources os
       ON (os.workspace_id,os.entity_id,os.id)=(b.workspace_id,b.entity_id,b.opening_source_id)
       JOIN journal_entries j ON (j.workspace_id,j.entity_id,j.id)=(os.workspace_id,os.entity_id,os.journal_id)
       WHERE (t.workspace_id,t.entity_id,t.id)=(b.workspace_id,b.entity_id,b.term_id)
        AND t.opening_reference=os.source_key||':'||os.revision AND j.state='POSTED')
     OR EXISTS(SELECT 1 FROM service_contract_terms t JOIN opening_balance_sources os
       ON (os.workspace_id,os.entity_id,os.id)=(b.workspace_id,b.entity_id,b.opening_source_id)
       WHERE (t.workspace_id,t.entity_id,t.id)=(b.workspace_id,b.entity_id,b.term_id) AND (
        (SELECT coalesce(sum(jl.credit-jl.debit),0) FROM journal_lines jl
         WHERE (jl.workspace_id,jl.entity_id,jl.journal_id,jl.account_id)=(os.workspace_id,os.entity_id,os.journal_id,t.deferred_account_id))
          <> (SELECT coalesce(sum(greatest(ob.billed-ob.earned,0)),0) FROM service_opening_balances ob
           JOIN service_contract_terms ot ON (ot.workspace_id,ot.entity_id,ot.id)=(ob.workspace_id,ob.entity_id,ob.term_id)
           WHERE (ob.workspace_id,ob.entity_id,ob.opening_source_id)=(os.workspace_id,os.entity_id,os.id) AND ot.deferred_account_id=t.deferred_account_id)
        OR (SELECT coalesce(sum(jl.debit-jl.credit),0) FROM journal_lines jl
         WHERE (jl.workspace_id,jl.entity_id,jl.journal_id,jl.account_id)=(os.workspace_id,os.entity_id,os.journal_id,t.unbilled_account_id))
          <> (SELECT coalesce(sum(greatest(ob.earned-ob.billed,0)),0) FROM service_opening_balances ob
           JOIN service_contract_terms ot ON (ot.workspace_id,ot.entity_id,ot.id)=(ob.workspace_id,ob.entity_id,ob.term_id)
           WHERE (ob.workspace_id,ob.entity_id,ob.opening_source_id)=(os.workspace_id,os.entity_id,os.id) AND ot.unbilled_account_id=t.unbilled_account_id)))
   THEN RAISE EXCEPTION 'Opening statement or covered periods incomplete' USING ERRCODE='23514'; END IF;
  END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER m4_schedule_complete AFTER INSERT OR UPDATE ON revenue_schedules DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION m4_deferred_guard();
CREATE CONSTRAINT TRIGGER m4_schedule_lines_complete AFTER INSERT ON revenue_schedule_lines DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION m4_deferred_guard();
CREATE CONSTRAINT TRIGGER m4_term_complete AFTER INSERT OR UPDATE ON service_contract_terms DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION m4_deferred_guard();
CREATE CONSTRAINT TRIGGER m4_opening_complete AFTER INSERT ON service_opening_balances DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION m4_deferred_guard();
CREATE CONSTRAINT TRIGGER m4_opening_period_complete AFTER INSERT ON service_opening_periods DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION m4_deferred_guard();
CREATE CONSTRAINT TRIGGER m4_renewal_complete AFTER INSERT OR UPDATE ON renewal_intents DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION m4_deferred_guard();
CREATE CONSTRAINT TRIGGER m4_renewal_opportunity_complete AFTER INSERT OR UPDATE ON crm_opportunities DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION m4_deferred_guard();

CREATE TRIGGER m4_coverage_immutable BEFORE UPDATE OR DELETE ON service_coverage_types FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER m4_profile_immutable BEFORE UPDATE OR DELETE ON service_product_versions FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER m4_operator_immutable BEFORE UPDATE OR DELETE ON asset_operator_links FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER m4_installation_immutable BEFORE UPDATE OR DELETE ON installation_events FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER m4_contract_immutable BEFORE UPDATE OR DELETE ON service_contracts FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER m4_contract_asset_immutable BEFORE UPDATE OR DELETE ON service_contract_assets FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER m4_run_immutable BEFORE UPDATE OR DELETE ON revenue_posting_runs FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER m4_recognition_immutable BEFORE UPDATE OR DELETE ON revenue_recognitions FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER m4_completion_immutable BEFORE UPDATE OR DELETE ON service_completions FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER m4_opening_immutable BEFORE UPDATE OR DELETE ON service_opening_balances FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER m4_opening_period_immutable BEFORE UPDATE OR DELETE ON service_opening_periods FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER m4_balance_immutable BEFORE UPDATE OR DELETE ON service_balance_postings FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

CREATE FUNCTION m4_lineage_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='quote_revisions' THEN
  IF OLD.status='DRAFT' AND NEW.status<>'DRAFT' AND EXISTS(
   SELECT 1 FROM quote_lines l WHERE (l.workspace_id,l.entity_id,l.revision_id)=(NEW.workspace_id,NEW.entity_id,NEW.id) AND l.active
    AND ((l.product_category IN ('SERVICE_PLAN','IMPLEMENTATION_SERVICE') AND
      (l.quantity<>trunc(l.quantity) OR l.quantity<>(SELECT count(*) FROM quote_service_targets t
       WHERE (t.workspace_id,t.entity_id,t.quote_line_id)=(l.workspace_id,l.entity_id,l.id) AND t.active)))
      OR (l.product_category NOT IN ('SERVICE_PLAN','IMPLEMENTATION_SERVICE') AND EXISTS(SELECT 1 FROM quote_service_targets t
       WHERE (t.workspace_id,t.entity_id,t.quote_line_id)=(l.workspace_id,l.entity_id,l.id) AND t.active)))) THEN
   RAISE EXCEPTION 'Service target cardinality mismatch' USING ERRCODE='23514'; END IF;
  IF OLD.status='DRAFT' AND NEW.status<>'DRAFT' AND EXISTS(
   SELECT 1 FROM quote_service_targets t JOIN quote_lines l ON (l.workspace_id,l.entity_id,l.id)=(t.workspace_id,t.entity_id,t.quote_line_id)
   JOIN product_compatibilities c ON (c.workspace_id,c.entity_id,c.id)=(t.workspace_id,t.entity_id,t.compatibility_id)
   JOIN service_product_versions v ON (v.workspace_id,v.entity_id,v.id)=(t.workspace_id,t.entity_id,t.service_product_version_id)
   LEFT JOIN installed_instruments a ON (a.workspace_id,a.entity_id,a.id)=(t.workspace_id,t.entity_id,t.target_asset_id)
   LEFT JOIN renewal_intents i ON (i.workspace_id,i.entity_id,i.id)=(t.workspace_id,t.entity_id,t.renewal_intent_id)
   WHERE (l.workspace_id,l.entity_id,l.revision_id)=(NEW.workspace_id,NEW.entity_id,NEW.id) AND l.active AND t.active
    AND (NOT c.active OR c.version<>t.compatibility_version OR NOT v.enabled OR v.revision<>(SELECT max(v2.revision) FROM service_product_versions v2
       WHERE (v2.workspace_id,v2.entity_id,v2.product_id)=(v.workspace_id,v.entity_id,v.product_id))
      OR (t.target_asset_id IS NOT NULL AND a.version IS DISTINCT FROM t.target_asset_version)
      OR (t.renewal_intent_id IS NOT NULL AND (i.version IS DISTINCT FROM t.renewal_intent_version OR i.status<>'OPEN')))) THEN
   RAISE EXCEPTION 'Service target version changed' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='sales_service_obligations' THEN
  IF TG_OP='INSERT' AND NOT EXISTS(SELECT 1 FROM sales_order_lines l WHERE (l.workspace_id,l.entity_id,l.id)=(NEW.workspace_id,NEW.entity_id,NEW.order_line_id)
   AND NEW.unit_no<=l.quantity AND l.quantity=trunc(l.quantity)
   AND NEW.net=round(l.net*NEW.unit_no/l.quantity,2)-round(l.net*(NEW.unit_no-1)/l.quantity,2)
   AND NEW.discount=round(l.discount*NEW.unit_no/l.quantity,2)-round(l.discount*(NEW.unit_no-1)/l.quantity,2)
   AND NEW.tax=round(l.tax*NEW.unit_no/l.quantity,2)-round(l.tax*(NEW.unit_no-1)/l.quantity,2)) THEN
   RAISE EXCEPTION 'Obligation allocated amounts differ from source' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='service_contract_terms' THEN
  IF TG_OP='INSERT' AND NEW.predecessor_term_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM service_contract_terms p
   JOIN service_contract_assets a ON (a.workspace_id,a.entity_id,a.id)=(p.workspace_id,p.entity_id,p.contract_asset_id)
   JOIN service_contract_assets b ON (b.workspace_id,b.entity_id,b.id)=(NEW.workspace_id,NEW.entity_id,NEW.contract_asset_id)
   JOIN renewal_intents i ON (i.workspace_id,i.entity_id,i.id)=(NEW.workspace_id,NEW.entity_id,NEW.renewal_intent_id)
   WHERE (p.workspace_id,p.entity_id,p.id)=(NEW.workspace_id,NEW.entity_id,NEW.predecessor_term_id)
    AND a.contract_id=b.contract_id AND p.asset_id=NEW.asset_id AND p.coverage_type_id=NEW.coverage_type_id
    AND p.coverage_end=NEW.coverage_start AND i.predecessor_term_id=p.id AND i.target_start=NEW.coverage_start AND i.contract_id=a.contract_id) THEN
   RAISE EXCEPTION 'Renewal predecessor mismatch' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='revenue_recognitions' THEN
  IF NOT EXISTS(SELECT 1 FROM revenue_schedule_lines l JOIN revenue_schedules s ON (s.workspace_id,s.entity_id,s.id)=(l.workspace_id,l.entity_id,l.schedule_id)
   JOIN revenue_posting_runs r ON (r.workspace_id,r.entity_id,r.id)=(NEW.workspace_id,NEW.entity_id,NEW.run_id)
   WHERE (l.workspace_id,l.entity_id,l.id)=(NEW.workspace_id,NEW.entity_id,NEW.schedule_line_id) AND s.sealed
    AND NEW.amount=l.amount AND NEW.posting_date=r.posting_date AND NEW.posting_date>=l.period_end AND r.business_date>=l.period_end
    AND NOT EXISTS(SELECT 1 FROM service_opening_periods p WHERE (p.workspace_id,p.entity_id,p.schedule_line_id)=(l.workspace_id,l.entity_id,l.id))) THEN
   RAISE EXCEPTION 'Recognition source or date invalid' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='service_completions' THEN
  IF NOT EXISTS(SELECT 1 FROM sales_service_obligations o WHERE (o.workspace_id,o.entity_id,o.id)=(NEW.workspace_id,NEW.entity_id,NEW.obligation_id)
    AND o.kind='ONBOARDING' AND o.asset_id IS NOT NULL AND o.status NOT IN ('CANCELLED','COMPLETED') AND NEW.amount=o.net
    AND NEW.completion_date>=o.accepted_date AND NEW.posting_date>=NEW.completion_date
    AND ((NEW.installation_event_id IS NULL AND EXISTS(SELECT 1 FROM installation_events i
      WHERE (i.workspace_id,i.entity_id,i.asset_id)=(o.workspace_id,o.entity_id,o.asset_id) AND i.business_date<=NEW.completion_date))
     OR EXISTS(SELECT 1 FROM installation_events i
      WHERE (i.workspace_id,i.entity_id,i.id)=(o.workspace_id,o.entity_id,NEW.installation_event_id) AND i.asset_id=o.asset_id
       AND i.business_date=NEW.completion_date AND i.completion_reference=NEW.completion_reference AND i.reason=NEW.reason))) THEN
   RAISE EXCEPTION 'Completion source invalid' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='service_opening_balances' THEN
  IF NOT EXISTS(SELECT 1 FROM service_contract_terms t JOIN opening_balance_sources s
   ON (s.workspace_id,s.entity_id,s.id)=(NEW.workspace_id,NEW.entity_id,NEW.opening_source_id)
   WHERE (t.workspace_id,t.entity_id,t.id)=(NEW.workspace_id,NEW.entity_id,NEW.term_id) AND t.source_kind='OPENING'
    AND t.net_fee=NEW.net_fee AND t.currency=s.currency AND s.posting_date=NEW.cutoff
    AND length(btrim(NEW.billing_reference))>0 AND length(btrim(NEW.settlement_reference))>0) THEN
   RAISE EXCEPTION 'Opening source mismatch' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='sales_invoice_lines' THEN
  IF NEW.source_type<>'SHIPMENT' AND (NEW.fee_start IS NULL OR NEW.fee_end IS NULL OR NOT EXISTS(
    SELECT 1 FROM sales_invoices i WHERE (i.workspace_id,i.entity_id,i.id)=(NEW.workspace_id,NEW.entity_id,NEW.invoice_id)
    AND ((NEW.source_type='SERVICE_OBLIGATION' AND EXISTS(SELECT 1 FROM sales_service_obligations o
      JOIN sales_order_lines l ON (l.workspace_id,l.entity_id,l.id)=(o.workspace_id,o.entity_id,o.order_line_id)
      JOIN sales_orders so ON (so.workspace_id,so.entity_id,so.id)=(l.workspace_id,l.entity_id,l.order_id)
      WHERE (o.workspace_id,o.entity_id,o.id)=(NEW.workspace_id,NEW.entity_id,NEW.service_obligation_id)
       AND o.kind='ONBOARDING' AND o.status<>'CANCELLED' AND NEW.fee_end<=o.net AND i.customer_account_id=so.customer_account_id
       AND i.currency=o.currency AND i.terms_days=o.terms_days AND i.ar_account_id=o.ar_account_id
       AND i.invoice_date>=o.accepted_date AND NEW.net=NEW.fee_end-NEW.fee_start
       AND NEW.discount=round(o.discount*NEW.fee_end/nullif(o.net,0),2)-round(o.discount*NEW.fee_start/nullif(o.net,0),2)
       AND NEW.tax=round(o.tax*NEW.fee_end/nullif(o.net,0),2)-round(o.tax*NEW.fee_start/nullif(o.net,0),2)
       AND NEW.unbilled_account_id=o.unbilled_account_id AND NEW.deferred_account_id=o.deferred_account_id AND NEW.output_account_id=o.output_account_id))
     OR (NEW.source_type='SERVICE_TERM' AND EXISTS(SELECT 1 FROM service_contract_terms t
      JOIN service_contract_assets ca ON (ca.workspace_id,ca.entity_id,ca.id)=(t.workspace_id,t.entity_id,t.contract_asset_id)
      JOIN service_contracts c ON (c.workspace_id,c.entity_id,c.id)=(ca.workspace_id,ca.entity_id,ca.contract_id)
      WHERE (t.workspace_id,t.entity_id,t.id)=(NEW.workspace_id,NEW.entity_id,NEW.service_term_id)
       AND t.status='VALIDATED' AND NEW.fee_end<=t.net_fee AND i.customer_account_id=c.customer_account_id
       AND i.currency=t.currency AND i.terms_days=t.terms_days AND i.ar_account_id=t.ar_account_id
       AND i.invoice_date>=t.accepted_date AND NEW.net=NEW.fee_end-NEW.fee_start
       AND NEW.discount=round(t.discount*NEW.fee_end/nullif(t.net_fee,0),2)-round(t.discount*NEW.fee_start/nullif(t.net_fee,0),2)
       AND NEW.tax=round(t.tax*NEW.fee_end/nullif(t.net_fee,0),2)-round(t.tax*NEW.fee_start/nullif(t.net_fee,0),2)
       AND NEW.unbilled_account_id=t.unbilled_account_id AND NEW.deferred_account_id=t.deferred_account_id AND NEW.output_account_id IS NOT DISTINCT FROM t.output_account_id)))
  )) THEN RAISE EXCEPTION 'Service invoice source mismatch' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='service_balance_postings' THEN
  IF NEW.invoice_line_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sales_invoice_lines l
    WHERE (l.workspace_id,l.entity_id,l.id)=(NEW.workspace_id,NEW.entity_id,NEW.invoice_line_id)
      AND l.service_obligation_id IS NOT DISTINCT FROM NEW.obligation_id AND l.service_term_id IS NOT DISTINCT FROM NEW.term_id) THEN
   RAISE EXCEPTION 'Balance posting invoice source mismatch' USING ERRCODE='23514'; END IF;
  IF NEW.reversal_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sales_reversals r JOIN sales_invoice_lines l
    ON (l.workspace_id,l.entity_id,l.invoice_id)=(r.workspace_id,r.entity_id,r.invoice_id)
    WHERE (r.workspace_id,r.entity_id,r.id)=(NEW.workspace_id,NEW.entity_id,NEW.reversal_id) AND l.id=NEW.invoice_line_id) THEN
   RAISE EXCEPTION 'Balance posting reversal mismatch' USING ERRCODE='23514'; END IF;
  IF NEW.recognition_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM revenue_recognitions r
    JOIN revenue_schedule_lines l ON (l.workspace_id,l.entity_id,l.id)=(r.workspace_id,r.entity_id,r.schedule_line_id)
    JOIN revenue_schedules s ON (s.workspace_id,s.entity_id,s.id)=(l.workspace_id,l.entity_id,l.schedule_id)
    WHERE (r.workspace_id,r.entity_id,r.id)=(NEW.workspace_id,NEW.entity_id,NEW.recognition_id) AND s.term_id=NEW.term_id) THEN
   RAISE EXCEPTION 'Balance posting recognition mismatch' USING ERRCODE='23514'; END IF;
  IF NEW.completion_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM service_completions c
    WHERE (c.workspace_id,c.entity_id,c.id)=(NEW.workspace_id,NEW.entity_id,NEW.completion_id) AND c.obligation_id=NEW.obligation_id) THEN
   RAISE EXCEPTION 'Balance posting completion mismatch' USING ERRCODE='23514'; END IF;
  IF NEW.opening_balance_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM service_opening_balances b
    WHERE (b.workspace_id,b.entity_id,b.id)=(NEW.workspace_id,NEW.entity_id,NEW.opening_balance_id) AND b.term_id=NEW.term_id) THEN
   RAISE EXCEPTION 'Balance posting opening mismatch' USING ERRCODE='23514'; END IF;
  IF (NEW.event_kind='INVOICE' AND (NEW.invoice_line_id IS NULL OR NEW.reversal_id IS NOT NULL))
   OR (NEW.event_kind='REVERSAL' AND (NEW.invoice_line_id IS NULL OR NEW.reversal_id IS NULL))
   OR (NEW.event_kind='RECOGNITION' AND NEW.recognition_id IS NULL)
   OR (NEW.event_kind='COMPLETION' AND NEW.completion_id IS NULL)
   OR (NEW.event_kind='OPENING' AND NEW.opening_balance_id IS NULL) THEN
   RAISE EXCEPTION 'Balance posting lacks event lineage' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER m4_quote_cardinality BEFORE UPDATE ON quote_revisions FOR EACH ROW EXECUTE FUNCTION m4_lineage_guard();
CREATE TRIGGER m4_obligation_amounts BEFORE INSERT ON sales_service_obligations FOR EACH ROW EXECUTE FUNCTION m4_lineage_guard();
CREATE TRIGGER m4_term_predecessor BEFORE INSERT ON service_contract_terms FOR EACH ROW EXECUTE FUNCTION m4_lineage_guard();
CREATE TRIGGER m4_recognition_source BEFORE INSERT ON revenue_recognitions FOR EACH ROW EXECUTE FUNCTION m4_lineage_guard();
CREATE TRIGGER m4_completion_source BEFORE INSERT ON service_completions FOR EACH ROW EXECUTE FUNCTION m4_lineage_guard();
CREATE TRIGGER m4_opening_source BEFORE INSERT ON service_opening_balances FOR EACH ROW EXECUTE FUNCTION m4_lineage_guard();
CREATE TRIGGER m4_invoice_source BEFORE INSERT OR UPDATE ON sales_invoice_lines FOR EACH ROW EXECUTE FUNCTION m4_lineage_guard();
CREATE TRIGGER m4_balance_source BEFORE INSERT ON service_balance_postings FOR EACH ROW EXECUTE FUNCTION m4_lineage_guard();
