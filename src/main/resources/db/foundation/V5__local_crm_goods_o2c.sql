-- M3 local CRM and goods O2C. Applied V1-V4 are retained byte-for-byte.

-- SHARED
-- Sales mapping and explicit independent approval for newly maintained price books.
CREATE TABLE sales_posting_config (
 workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 ar_account_id bigint NOT NULL, unbilled_account_id bigint NOT NULL,
 revenue_account_id bigint NOT NULL, cogs_account_id bigint NOT NULL,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), updated_by bigint NOT NULL REFERENCES app_users(id),
 PRIMARY KEY(workspace_id,entity_id), FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,ar_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,unbilled_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,revenue_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,cogs_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 CHECK(ar_account_id<>unbilled_account_id AND ar_account_id<>revenue_account_id AND ar_account_id<>cogs_account_id
   AND unbilled_account_id<>revenue_account_id AND unbilled_account_id<>cogs_account_id AND revenue_account_id<>cogs_account_id)
);
CREATE TABLE price_book_contributors (
 workspace_id bigint NOT NULL, entity_id bigint NOT NULL, price_book_id bigint NOT NULL,
 user_id bigint NOT NULL REFERENCES app_users(id), PRIMARY KEY(workspace_id,entity_id,price_book_id,user_id),
 FOREIGN KEY(workspace_id,entity_id,price_book_id) REFERENCES price_books(workspace_id,entity_id,id)
);
-- Existing creator and retained immutable edit audits remain the contributor authority.
INSERT INTO price_book_contributors(workspace_id,entity_id,price_book_id,user_id)
 SELECT workspace_id,entity_id,id,created_by FROM price_books ON CONFLICT DO NOTHING;
INSERT INTO price_book_contributors(workspace_id,entity_id,price_book_id,user_id)
 SELECT b.workspace_id,b.entity_id,b.id,a.actor_user_id FROM price_books b JOIN audit_events a
   ON a.workspace_id=b.workspace_id AND a.entity_id=b.entity_id AND a.target_type='price_books' AND a.target_id=b.id::text
 WHERE a.actor_user_id IS NOT NULL AND a.outcome='SUCCESS' AND a.action IN ('CREATE','UPDATE') ON CONFLICT DO NOTHING;
INSERT INTO price_book_contributors(workspace_id,entity_id,price_book_id,user_id)
 SELECT l.workspace_id,l.entity_id,l.price_book_id,a.actor_user_id FROM price_book_lines l JOIN audit_events a
   ON a.workspace_id=l.workspace_id AND a.entity_id=l.entity_id AND a.target_type='price_book_lines' AND a.target_id=l.id::text
 WHERE a.actor_user_id IS NOT NULL AND a.outcome='SUCCESS' AND a.action IN ('CREATE','UPDATE') ON CONFLICT DO NOTHING;
CREATE TABLE price_book_approvals (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 price_book_id bigint NOT NULL, revision bigint NOT NULL CHECK(revision>0), payload_hash text NOT NULL CHECK(length(payload_hash)=64),
 approved_by bigint NOT NULL REFERENCES app_users(id), approved_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,price_book_id,revision),
 FOREIGN KEY(workspace_id,entity_id,price_book_id) REFERENCES price_books(workspace_id,entity_id,id)
);
CREATE FUNCTION m3_price_approval_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b price_books%ROWTYPE;
BEGIN
 SELECT * INTO b FROM price_books WHERE workspace_id=NEW.workspace_id AND entity_id=NEW.entity_id AND id=NEW.price_book_id FOR UPDATE;
 IF NOT FOUND OR b.status<>'DRAFT' OR NOT b.active OR b.revision<>NEW.revision THEN
  RAISE EXCEPTION 'Price book approval source changed' USING ERRCODE='23514';
 END IF;
 IF b.created_by=NEW.approved_by OR EXISTS(SELECT 1 FROM price_book_contributors c
   WHERE c.workspace_id=b.workspace_id AND c.entity_id=b.entity_id AND c.price_book_id=b.id AND c.user_id=NEW.approved_by) THEN
  RAISE EXCEPTION 'A price book contributor cannot approve it' USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM price_book_lines l WHERE l.workspace_id=b.workspace_id AND l.entity_id=b.entity_id AND l.price_book_id=b.id) THEN
  RAISE EXCEPTION 'A price book requires prices before approval' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER price_book_approval_source BEFORE INSERT ON price_book_approvals FOR EACH ROW EXECUTE FUNCTION m3_price_approval_guard();
CREATE TRIGGER price_book_contributor_immutable BEFORE UPDATE OR DELETE ON price_book_contributors FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER price_book_approval_immutable BEFORE UPDATE OR DELETE ON price_book_approvals FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

CREATE TABLE product_contributors (
 workspace_id bigint NOT NULL, product_id bigint NOT NULL, user_id bigint NOT NULL REFERENCES app_users(id),
 PRIMARY KEY(workspace_id,product_id,user_id), FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id)
);
INSERT INTO product_contributors(workspace_id,product_id,user_id)
 SELECT workspace_id,id,created_by FROM products ON CONFLICT DO NOTHING;
INSERT INTO product_contributors(workspace_id,product_id,user_id)
 SELECT p.workspace_id,p.id,a.actor_user_id FROM products p JOIN audit_events a
   ON a.workspace_id=p.workspace_id AND a.target_type='products' AND a.target_id=p.id::text
 WHERE a.actor_user_id IS NOT NULL AND a.outcome='SUCCESS' AND a.action IN ('CREATE','UPDATE') ON CONFLICT DO NOTHING;
CREATE TABLE product_publications (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 product_id bigint NOT NULL, product_version bigint NOT NULL CHECK(product_version>0),
 approved_by bigint NOT NULL REFERENCES app_users(id), approved_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,product_id),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id)
);
CREATE FUNCTION m3_product_publication_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p products%ROWTYPE;
BEGIN
 SELECT * INTO p FROM products WHERE workspace_id=NEW.workspace_id AND id=NEW.product_id FOR UPDATE;
 IF NOT FOUND OR NOT p.active OR p.status<>'DRAFT' OR p.version<>NEW.product_version THEN
  RAISE EXCEPTION 'Product publication source changed' USING ERRCODE='23514';
 END IF;
 IF p.created_by=NEW.approved_by OR EXISTS(SELECT 1 FROM product_contributors c
   WHERE c.workspace_id=p.workspace_id AND c.product_id=p.id AND c.user_id=NEW.approved_by) THEN
  RAISE EXCEPTION 'A product contributor cannot publish it' USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM product_entity_settings s WHERE s.workspace_id=p.workspace_id
   AND s.entity_id=NEW.entity_id AND s.product_id=p.id AND s.active) THEN
  RAISE EXCEPTION 'Product needs an active entity assignment' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER product_publication_source BEFORE INSERT ON product_publications FOR EACH ROW EXECUTE FUNCTION m3_product_publication_guard();
CREATE TRIGGER product_contributor_immutable BEFORE UPDATE OR DELETE ON product_contributors FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER product_publication_immutable BEFORE UPDATE OR DELETE ON product_publications FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE FUNCTION m3_product_source_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Product history cannot be deleted' USING ERRCODE='23514'; END IF;
 IF OLD.status='ACTIVE' AND
   (to_jsonb(NEW)-ARRAY['active','version','updated_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['active','version','updated_at']) THEN
  RAISE EXCEPTION 'Published product fields are immutable' USING ERRCODE='23514';
 END IF;
 IF ROW(NEW.uom_id,NEW.tracking,NEW.category) IS DISTINCT FROM ROW(OLD.uom_id,OLD.tracking,OLD.category)
   AND EXISTS(SELECT 1 FROM stock_movements m WHERE m.workspace_id=OLD.workspace_id AND m.product_id=OLD.id) THEN
  RAISE EXCEPTION 'Stock history freezes product unit, tracking and category' USING ERRCODE='23514';
 END IF;
 IF OLD.status='DRAFT' AND NEW.status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM product_publications p
   WHERE p.workspace_id=OLD.workspace_id AND p.product_id=OLD.id AND p.product_version=OLD.version) THEN
  RAISE EXCEPTION 'Product publication needs independent approval' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER product_source_guard BEFORE UPDATE OR DELETE ON products FOR EACH ROW EXECUTE FUNCTION m3_product_source_guard();

-- CRM
CREATE TABLE crm_command_receipts (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 command_kind text NOT NULL, intent text NOT NULL CHECK(length(btrim(intent)) BETWEEN 1 AND 200),
 payload_hash text NOT NULL, result_id bigint NOT NULL, actor_id bigint NOT NULL REFERENCES app_users(id),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(workspace_id,entity_id,id),
 UNIQUE(workspace_id,entity_id,command_kind,intent),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id)
);
CREATE TABLE crm_inquiries (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 source_key text NOT NULL CHECK(length(btrim(source_key)) BETWEEN 1 AND 200), source_hash text NOT NULL, received_at timestamptz NOT NULL,
 subject text NOT NULL CHECK(length(btrim(subject)) BETWEEN 1 AND 500), need text NOT NULL DEFAULT '',
 customer_id bigint, contact_id bigint, territory text NOT NULL DEFAULT '', product_line text NOT NULL DEFAULT '',
 status text NOT NULL DEFAULT 'NEW' CHECK(status IN ('NEW','IN_PROGRESS','WAITING','RESOLVED','CANCELLED')),
 priority text NOT NULL DEFAULT 'NORMAL' CHECK(priority IN ('LOW','NORMAL','HIGH','URGENT')),
 assignee_id bigint REFERENCES app_users(id), follow_up_due timestamptz, version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,source_key),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,customer_id) REFERENCES customers(workspace_id,id),
 FOREIGN KEY(workspace_id,contact_id) REFERENCES contacts(workspace_id,id),
 FOREIGN KEY(workspace_id,customer_id,contact_id) REFERENCES customer_contact_links(workspace_id,customer_id,contact_id)
);
CREATE TABLE crm_inquiry_events (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 inquiry_id bigint NOT NULL, version bigint NOT NULL, kind text NOT NULL, detail text NOT NULL DEFAULT '', snapshot jsonb NOT NULL, actor_id bigint NOT NULL REFERENCES app_users(id), occurred_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,inquiry_id,version),
 FOREIGN KEY(workspace_id,entity_id,inquiry_id) REFERENCES crm_inquiries(workspace_id,entity_id,id)
);
CREATE TABLE crm_opportunities (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 customer_account_id bigint NOT NULL, inquiry_id bigint, title text NOT NULL CHECK(length(btrim(title)) BETWEEN 1 AND 500),
 pipeline text NOT NULL CHECK(pipeline IN ('EQUIPMENT_SALES','SUPPLY_REORDER')),
 stage text NOT NULL CHECK(stage IN ('QUALIFIED','PROPOSAL','NEGOTIATION','WON','LOST')),
 owner_id bigint NOT NULL REFERENCES app_users(id), version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,customer_account_id) REFERENCES customer_entity_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,inquiry_id) REFERENCES crm_inquiries(workspace_id,entity_id,id)
);
CREATE TABLE crm_opportunity_events (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, opportunity_id bigint NOT NULL,
 version bigint NOT NULL, stage text NOT NULL, reason text NOT NULL, actor_id bigint NOT NULL REFERENCES app_users(id), occurred_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,opportunity_id,version),
 FOREIGN KEY(workspace_id,entity_id,opportunity_id) REFERENCES crm_opportunities(workspace_id,entity_id,id)
);
CREATE TABLE crm_lifecycle_events (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 customer_id bigint NOT NULL, lifecycle text NOT NULL, reason text NOT NULL, actor_id bigint NOT NULL REFERENCES app_users(id), occurred_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,customer_id) REFERENCES customers(workspace_id,id)
);
CREATE TABLE crm_activities (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 inquiry_id bigint, opportunity_id bigint, customer_id bigint, kind text NOT NULL CHECK(kind IN ('TASK','NOTE','CALL','EMAIL','MEETING')),
 subject text NOT NULL CHECK(length(btrim(subject)) BETWEEN 1 AND 500), assignee_id bigint NOT NULL REFERENCES app_users(id),
 due_at timestamptz, completed_at timestamptz, completed_by bigint REFERENCES app_users(id), outcome text NOT NULL DEFAULT '', substantive_response boolean NOT NULL DEFAULT false,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,customer_id,id), FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,inquiry_id) REFERENCES crm_inquiries(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,opportunity_id) REFERENCES crm_opportunities(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,customer_id) REFERENCES customers(workspace_id,id),
 CHECK(inquiry_id IS NOT NULL OR opportunity_id IS NOT NULL OR customer_id IS NOT NULL),
 CHECK((completed_at IS NULL) = (completed_by IS NULL)),
 CHECK(NOT substantive_response OR (kind IN ('CALL','EMAIL','MEETING') AND completed_at IS NOT NULL AND length(btrim(outcome))>0))
);
CREATE TABLE crm_routing_rules (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 rule_key text NOT NULL, revision bigint NOT NULL CHECK(revision>0), territory text NOT NULL DEFAULT '', product_line text NOT NULL DEFAULT '',
 priority integer NOT NULL, target_id bigint NOT NULL REFERENCES app_users(id), enabled boolean NOT NULL, actor_id bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,rule_key,revision), FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id)
);
CREATE TABLE crm_routing_evaluations (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 inquiry_id bigint NOT NULL, source_version bigint NOT NULL, territory_snapshot text NOT NULL, product_line_snapshot text NOT NULL, rule_id bigint, outcome text NOT NULL, reason text NOT NULL,
 actor_id bigint NOT NULL REFERENCES app_users(id), occurred_at timestamptz NOT NULL DEFAULT now(), UNIQUE(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,inquiry_id) REFERENCES crm_inquiries(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,rule_id) REFERENCES crm_routing_rules(workspace_id,entity_id,id)
);
CREATE TABLE crm_score_rules (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 rule_key text NOT NULL, revision bigint NOT NULL CHECK(revision>0), territory_points integer NOT NULL CHECK(territory_points BETWEEN 0 AND 100),
 need_points integer NOT NULL CHECK(need_points BETWEEN 0 AND 100), timeframe_points integer NOT NULL CHECK(timeframe_points BETWEEN 0 AND 100),
 budget_points integer NOT NULL CHECK(budget_points BETWEEN 0 AND 100), actor_id bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,rule_key,revision), FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id)
);
CREATE TABLE crm_score_evaluations (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 inquiry_id bigint NOT NULL, source_version bigint NOT NULL, rule_id bigint NOT NULL, as_of date NOT NULL,
 territory boolean NOT NULL, need boolean NOT NULL, timeframe boolean NOT NULL, budget boolean NOT NULL,
 territory_points integer NOT NULL, need_points integer NOT NULL, timeframe_points integer NOT NULL, budget_points integer NOT NULL,
 score integer NOT NULL CHECK(score BETWEEN 0 AND 100), reasons text NOT NULL, actor_id bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,inquiry_id,source_version,rule_id,as_of,territory,need,timeframe,budget),
 FOREIGN KEY(workspace_id,entity_id,inquiry_id) REFERENCES crm_inquiries(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,rule_id) REFERENCES crm_score_rules(workspace_id,entity_id,id)
);
CREATE TABLE crm_reactivation_rules (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 rule_key text NOT NULL, revision bigint NOT NULL CHECK(revision>0), dormant_days integer NOT NULL CHECK(dormant_days>0),
 actor_id bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,rule_key,revision), FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id)
);
CREATE TABLE crm_reactivation_cases (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 customer_id bigint NOT NULL, purchase_shipment_line_id bigint NOT NULL, purchase_date date NOT NULL,
 status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','RESOLVED')), follow_up_activity_id bigint,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,customer_id,id), UNIQUE(workspace_id,entity_id,customer_id,purchase_shipment_line_id), UNIQUE(workspace_id,entity_id,follow_up_activity_id),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id), FOREIGN KEY(workspace_id,customer_id) REFERENCES customers(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,customer_id,follow_up_activity_id) REFERENCES crm_activities(workspace_id,entity_id,customer_id,id)
);
CREATE UNIQUE INDEX crm_reactivation_one_open ON crm_reactivation_cases(workspace_id,entity_id,customer_id) WHERE status='OPEN';
CREATE TABLE crm_reactivation_evaluations (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 customer_id bigint NOT NULL, rule_id bigint NOT NULL, as_of date NOT NULL, purchase_shipment_line_id bigint, purchase_date date, case_id bigint,
 outcome text NOT NULL, reason text NOT NULL, actor_id bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,customer_id) REFERENCES customers(workspace_id,id), FOREIGN KEY(workspace_id,entity_id,rule_id) REFERENCES crm_reactivation_rules(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,customer_id,case_id) REFERENCES crm_reactivation_cases(workspace_id,entity_id,customer_id,id)
);
CREATE TABLE crm_reactivation_events (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 case_id bigint NOT NULL, version bigint NOT NULL, kind text NOT NULL, reason text NOT NULL,
 actor_id bigint NOT NULL REFERENCES app_users(id), occurred_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,case_id,version),
 FOREIGN KEY(workspace_id,entity_id,case_id) REFERENCES crm_reactivation_cases(workspace_id,entity_id,id)
);
CREATE FUNCTION crm_mutable_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.id IS DISTINCT FROM OLD.id OR NEW.workspace_id IS DISTINCT FROM OLD.workspace_id OR NEW.entity_id IS DISTINCT FROM OLD.entity_id
    OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.version <> OLD.version + 1 THEN
   RAISE EXCEPTION 'CRM identity and version are immutable' USING ERRCODE='23514';
 END IF;
 IF TG_TABLE_NAME='crm_inquiries' THEN
   IF NEW.source_key IS DISTINCT FROM OLD.source_key OR NEW.source_hash IS DISTINCT FROM OLD.source_hash THEN
     RAISE EXCEPTION 'Inquiry source identity is immutable' USING ERRCODE='23514';
   END IF;
 ELSIF TG_TABLE_NAME='crm_activities' THEN
   IF NEW.inquiry_id IS DISTINCT FROM OLD.inquiry_id OR NEW.opportunity_id IS DISTINCT FROM OLD.opportunity_id OR NEW.customer_id IS DISTINCT FROM OLD.customer_id OR NEW.kind IS DISTINCT FROM OLD.kind OR OLD.completed_at IS NOT NULL THEN
     RAISE EXCEPTION 'Activity source and completed activity are immutable' USING ERRCODE='23514';
   END IF;
 ELSIF TG_TABLE_NAME='crm_opportunities' THEN
   IF NEW.customer_account_id IS DISTINCT FROM OLD.customer_account_id OR NEW.inquiry_id IS DISTINCT FROM OLD.inquiry_id OR OLD.stage IN ('WON','LOST') THEN
     RAISE EXCEPTION 'Opportunity source or closed state is immutable' USING ERRCODE='23514';
   END IF;
 ELSIF TG_TABLE_NAME='crm_reactivation_cases' THEN
   IF NEW.customer_id IS DISTINCT FROM OLD.customer_id OR NEW.purchase_shipment_line_id IS DISTINCT FROM OLD.purchase_shipment_line_id OR NEW.purchase_date IS DISTINCT FROM OLD.purchase_date OR (OLD.follow_up_activity_id IS NOT NULL AND NEW.follow_up_activity_id IS DISTINCT FROM OLD.follow_up_activity_id) THEN
     RAISE EXCEPTION 'Reactivation source and follow-up are immutable' USING ERRCODE='23514';
   END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER crm_inquiries_guard BEFORE UPDATE ON crm_inquiries FOR EACH ROW EXECUTE FUNCTION crm_mutable_guard();
CREATE TRIGGER crm_activities_guard BEFORE UPDATE ON crm_activities FOR EACH ROW EXECUTE FUNCTION crm_mutable_guard();
CREATE TRIGGER crm_opportunities_guard BEFORE UPDATE ON crm_opportunities FOR EACH ROW EXECUTE FUNCTION crm_mutable_guard();
CREATE TRIGGER crm_reactivation_cases_guard BEFORE UPDATE ON crm_reactivation_cases FOR EACH ROW EXECUTE FUNCTION crm_mutable_guard();
CREATE FUNCTION crm_association_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE inquiry_customer bigint; linked_customer bigint; linked_inquiry bigint; line_customer bigint;
BEGIN
 IF TG_TABLE_NAME='crm_inquiries' THEN
   IF NEW.customer_id IS NOT NULL AND NOT EXISTS (
     SELECT 1 FROM customers c WHERE c.workspace_id=NEW.workspace_id AND c.id=NEW.customer_id AND
     (EXISTS (SELECT 1 FROM customer_entity_accounts a WHERE a.workspace_id=c.workspace_id AND a.entity_id=NEW.entity_id AND a.customer_id=c.id AND a.active)
      OR (c.created_by=NEW.created_by AND NOT EXISTS (SELECT 1 FROM customer_entity_accounts a WHERE a.workspace_id=c.workspace_id AND a.customer_id=c.id)))) THEN
     RAISE EXCEPTION 'Inquiry customer is outside entity scope' USING ERRCODE='23514';
   END IF;
   IF NEW.contact_id IS NOT NULL AND NOT EXISTS (
     SELECT 1 FROM contacts c WHERE c.workspace_id=NEW.workspace_id AND c.id=NEW.contact_id AND
     (EXISTS (SELECT 1 FROM customer_contact_links l JOIN customer_entity_accounts a ON a.workspace_id=l.workspace_id AND a.customer_id=l.customer_id AND a.entity_id=NEW.entity_id AND a.active WHERE l.workspace_id=c.workspace_id AND l.contact_id=c.id AND l.active)
      OR (c.created_by=NEW.created_by AND NOT EXISTS (SELECT 1 FROM customer_contact_links l JOIN customer_entity_accounts a ON a.workspace_id=l.workspace_id AND a.customer_id=l.customer_id WHERE l.workspace_id=c.workspace_id AND l.contact_id=c.id AND l.active)))) THEN
     RAISE EXCEPTION 'Inquiry contact is outside entity scope' USING ERRCODE='23514';
   END IF;
   IF NEW.customer_id IS NOT NULL AND NEW.contact_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM customer_contact_links l WHERE l.workspace_id=NEW.workspace_id AND l.customer_id=NEW.customer_id AND l.contact_id=NEW.contact_id AND l.active) THEN
     RAISE EXCEPTION 'Inquiry contact association is inactive' USING ERRCODE='23514';
   END IF;
 ELSIF TG_TABLE_NAME='crm_opportunities' THEN
   IF NEW.inquiry_id IS NOT NULL THEN
     SELECT i.customer_id INTO inquiry_customer FROM crm_inquiries i WHERE i.workspace_id=NEW.workspace_id AND i.entity_id=NEW.entity_id AND i.id=NEW.inquiry_id;
     SELECT a.customer_id INTO linked_customer FROM customer_entity_accounts a WHERE a.workspace_id=NEW.workspace_id AND a.entity_id=NEW.entity_id AND a.id=NEW.customer_account_id;
     IF inquiry_customer IS NOT NULL AND inquiry_customer IS DISTINCT FROM linked_customer THEN
       RAISE EXCEPTION 'Opportunity and inquiry customer mismatch' USING ERRCODE='23514';
     END IF;
   END IF;
 ELSIF TG_TABLE_NAME='crm_activities' THEN
   IF NEW.customer_id IS NOT NULL AND NOT EXISTS (
     SELECT 1 FROM customers c WHERE c.workspace_id=NEW.workspace_id AND c.id=NEW.customer_id AND
     (EXISTS (SELECT 1 FROM customer_entity_accounts a WHERE a.workspace_id=c.workspace_id AND a.entity_id=NEW.entity_id AND a.customer_id=c.id AND a.active)
      OR (c.created_by=NEW.created_by AND NOT EXISTS (SELECT 1 FROM customer_entity_accounts a WHERE a.workspace_id=c.workspace_id AND a.customer_id=c.id)))) THEN
     RAISE EXCEPTION 'Activity customer is outside entity scope' USING ERRCODE='23514';
   END IF;
   IF NEW.inquiry_id IS NOT NULL THEN
     SELECT customer_id INTO inquiry_customer FROM crm_inquiries WHERE workspace_id=NEW.workspace_id AND entity_id=NEW.entity_id AND id=NEW.inquiry_id;
   END IF;
   IF NEW.opportunity_id IS NOT NULL THEN
     SELECT a.customer_id,o.inquiry_id INTO linked_customer,linked_inquiry FROM crm_opportunities o JOIN customer_entity_accounts a ON a.workspace_id=o.workspace_id AND a.entity_id=o.entity_id AND a.id=o.customer_account_id WHERE o.workspace_id=NEW.workspace_id AND o.entity_id=NEW.entity_id AND o.id=NEW.opportunity_id;
     IF NEW.inquiry_id IS NOT NULL AND linked_inquiry IS DISTINCT FROM NEW.inquiry_id THEN
       RAISE EXCEPTION 'Activity inquiry and opportunity mismatch' USING ERRCODE='23514';
     END IF;
   END IF;
   IF (inquiry_customer IS NOT NULL AND NEW.customer_id IS NOT NULL AND inquiry_customer<>NEW.customer_id) OR (linked_customer IS NOT NULL AND NEW.customer_id IS NOT NULL AND linked_customer<>NEW.customer_id) OR (inquiry_customer IS NOT NULL AND linked_customer IS NOT NULL AND inquiry_customer<>linked_customer) THEN
     RAISE EXCEPTION 'Activity customer mismatch' USING ERRCODE='23514';
   END IF;
 ELSIF TG_TABLE_NAME='crm_reactivation_cases' THEN
   SELECT a.customer_id INTO line_customer FROM shipment_lines l JOIN shipments s ON s.workspace_id=l.workspace_id AND s.entity_id=l.entity_id AND s.id=l.shipment_id JOIN sales_orders o ON o.workspace_id=s.workspace_id AND o.entity_id=s.entity_id AND o.id=s.order_id JOIN customer_entity_accounts a ON a.workspace_id=o.workspace_id AND a.entity_id=o.entity_id AND a.id=o.customer_account_id WHERE l.workspace_id=NEW.workspace_id AND l.entity_id=NEW.entity_id AND l.id=NEW.purchase_shipment_line_id AND s.status='POSTED' AND l.active AND l.net>0 AND s.shipment_date=NEW.purchase_date;
   IF line_customer IS DISTINCT FROM NEW.customer_id THEN
     RAISE EXCEPTION 'Reactivation purchase source mismatch' USING ERRCODE='23514';
   END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER crm_inquiries_association BEFORE INSERT OR UPDATE ON crm_inquiries FOR EACH ROW EXECUTE FUNCTION crm_association_guard();
CREATE TRIGGER crm_activities_association BEFORE INSERT OR UPDATE ON crm_activities FOR EACH ROW EXECUTE FUNCTION crm_association_guard();
CREATE TRIGGER crm_opportunities_association BEFORE INSERT OR UPDATE ON crm_opportunities FOR EACH ROW EXECUTE FUNCTION crm_association_guard();
CREATE TRIGGER crm_reactivation_association BEFORE INSERT OR UPDATE ON crm_reactivation_cases FOR EACH ROW EXECUTE FUNCTION crm_association_guard();

-- COMMERCIAL
-- Commercial lineage and immutable accepted-source snapshots.
CREATE TABLE quotes (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 number text NOT NULL, origin text NOT NULL CHECK(origin IN ('LOCAL_CRM','DIRECT_ERP')),
 customer_account_id bigint NOT NULL, opportunity_id bigint, version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,number),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,customer_account_id) REFERENCES customer_entity_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,opportunity_id) REFERENCES crm_opportunities(workspace_id,entity_id,id),
 CHECK((origin='DIRECT_ERP' AND opportunity_id IS NULL) OR (origin='LOCAL_CRM' AND opportunity_id IS NOT NULL))
);
CREATE TABLE quote_revisions (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 quote_id bigint NOT NULL, revision bigint NOT NULL CHECK(revision>0), status text NOT NULL CHECK(status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','ACCEPTED','WITHDRAWN')),
 payload_hash text NOT NULL DEFAULT '', business_date date NOT NULL, valid_until date NOT NULL, currency text NOT NULL,
 terms_id bigint NOT NULL, terms_days integer NOT NULL CHECK(terms_days>=0), customer_account_version bigint NOT NULL,
 config_version bigint NOT NULL, created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 billing_address_id bigint, billing_address_version bigint, shipping_address_id bigint, shipping_address_version bigint,
 CHECK((billing_address_id IS NULL)=(billing_address_version IS NULL)),
 CHECK((shipping_address_id IS NULL)=(shipping_address_version IS NULL)),
 submitted_by bigint REFERENCES app_users(id), submitted_at timestamptz, requires_approval boolean NOT NULL DEFAULT false,
 approved_by bigint REFERENCES app_users(id), approved_at timestamptz, decision_reason text,
 accepted_by bigint REFERENCES app_users(id), accepted_at timestamptz, accepted_business_date date, accepted_reference text,
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,quote_id,revision),
 FOREIGN KEY(workspace_id,entity_id,quote_id) REFERENCES quotes(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,terms_id) REFERENCES payment_terms(workspace_id,entity_id,id),
 CHECK(valid_until>=business_date), CHECK(status NOT IN ('ACCEPTED','WITHDRAWN') OR (accepted_by IS NOT NULL AND accepted_at IS NOT NULL AND accepted_business_date IS NOT NULL AND length(btrim(accepted_reference))>0))
);
CREATE TABLE quote_lines (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 revision_id bigint NOT NULL, line_key text NOT NULL, ordinal integer NOT NULL, active boolean NOT NULL DEFAULT true,
 product_id bigint NOT NULL, product_version bigint NOT NULL, product_setting_version bigint NOT NULL,
 product_sku text NOT NULL, product_name text NOT NULL, product_category text NOT NULL, product_line text NOT NULL,
 uom_id bigint NOT NULL, uom_version bigint NOT NULL, uom_code text NOT NULL, tracking text NOT NULL, quantity_scale integer NOT NULL,
 quantity numeric(18,6) NOT NULL CHECK(quantity>0), unit_price numeric(18,6) NOT NULL CHECK(unit_price>=0),
 price_book_id bigint NOT NULL, price_book_version bigint NOT NULL, price_book_line_id bigint NOT NULL, price_book_line_version bigint NOT NULL,
 list_price numeric(18,6) NOT NULL, manual_override boolean NOT NULL, discount_pct numeric(9,4), discount_amount numeric(18,2),
 subtotal numeric(18,2) NOT NULL, discount numeric(18,2) NOT NULL, net numeric(18,2) NOT NULL, tax numeric(18,2) NOT NULL, gross numeric(18,2) NOT NULL,
 tax_version_id bigint NOT NULL, tax_version_version bigint NOT NULL, tax_rate_pct numeric(9,4) NOT NULL,
 output_account_id bigint NOT NULL, ar_account_id bigint NOT NULL, unbilled_account_id bigint NOT NULL, revenue_account_id bigint NOT NULL, cogs_account_id bigint NOT NULL, inventory_account_id bigint NOT NULL,
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,revision_id,line_key),
 FOREIGN KEY(workspace_id,entity_id,revision_id) REFERENCES quote_revisions(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id), FOREIGN KEY(workspace_id,uom_id) REFERENCES uoms(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,price_book_id) REFERENCES price_books(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,price_book_line_id) REFERENCES price_book_lines(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,output_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,ar_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,unbilled_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,revenue_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,cogs_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,inventory_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 CHECK(discount_pct IS NULL OR discount_amount IS NULL), CHECK(subtotal=net+discount AND gross=net+tax AND discount>=0 AND net>=0 AND tax>=0)
);
CREATE TABLE quote_contributors (
 workspace_id bigint NOT NULL, entity_id bigint NOT NULL, revision_id bigint NOT NULL, user_id bigint NOT NULL REFERENCES app_users(id),
 PRIMARY KEY(workspace_id,entity_id,revision_id,user_id), FOREIGN KEY(workspace_id,entity_id,revision_id) REFERENCES quote_revisions(workspace_id,entity_id,id)
);
CREATE TABLE quote_events (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, revision_id bigint NOT NULL,
 kind text NOT NULL, payload_hash text NOT NULL, actor_id bigint NOT NULL REFERENCES app_users(id), reason text NOT NULL DEFAULT '', occurred_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,revision_id) REFERENCES quote_revisions(workspace_id,entity_id,id)
);
CREATE TABLE sales_handover_requests (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, opportunity_id bigint NOT NULL,
 intent text NOT NULL, source_hash text NOT NULL, status text NOT NULL, blocked_reasons jsonb NOT NULL, created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,intent), FOREIGN KEY(workspace_id,entity_id,opportunity_id) REFERENCES crm_opportunities(workspace_id,entity_id,id)
);
CREATE TABLE sales_orders (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, number text NOT NULL,
 quote_id bigint NOT NULL, quote_revision_id bigint NOT NULL, customer_account_id bigint NOT NULL, customer_account_version bigint NOT NULL,
 currency text NOT NULL, terms_days integer NOT NULL, business_date date NOT NULL, accepted_reference text NOT NULL, source_hash text NOT NULL,
 config_version bigint NOT NULL, version bigint NOT NULL DEFAULT 1 CHECK(version>0), created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 sealed boolean NOT NULL DEFAULT false,
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,number), UNIQUE(workspace_id,entity_id,quote_id),
 FOREIGN KEY(workspace_id,entity_id,quote_id) REFERENCES quotes(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,quote_revision_id) REFERENCES quote_revisions(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,customer_account_id) REFERENCES customer_entity_accounts(workspace_id,entity_id,id)
);
CREATE TABLE sales_order_lines (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL, order_id bigint NOT NULL, quote_line_id bigint NOT NULL,
 line_key text NOT NULL, product_id bigint NOT NULL, product_version bigint NOT NULL, product_setting_version bigint NOT NULL, uom_id bigint NOT NULL, uom_version bigint NOT NULL,
 tracking text NOT NULL, quantity_scale integer NOT NULL, quantity numeric(18,6) NOT NULL CHECK(quantity>0), unit_price numeric(18,6) NOT NULL,
 subtotal numeric(18,2) NOT NULL, discount numeric(18,2) NOT NULL, net numeric(18,2) NOT NULL, tax numeric(18,2) NOT NULL, gross numeric(18,2) NOT NULL,
 tax_version_id bigint NOT NULL, tax_rate_pct numeric(9,4) NOT NULL, output_account_id bigint NOT NULL, ar_account_id bigint NOT NULL, unbilled_account_id bigint NOT NULL,
 revenue_account_id bigint NOT NULL, cogs_account_id bigint NOT NULL, inventory_account_id bigint NOT NULL,
 fulfilled_qty numeric(18,6) NOT NULL DEFAULT 0, cancelled_qty numeric(18,6) NOT NULL DEFAULT 0, version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,order_id,line_key),
 FOREIGN KEY(workspace_id,entity_id,order_id) REFERENCES sales_orders(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,quote_line_id) REFERENCES quote_lines(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id), FOREIGN KEY(workspace_id,uom_id) REFERENCES uoms(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,output_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,ar_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,unbilled_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,revenue_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,cogs_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,inventory_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 CHECK(fulfilled_qty>=0 AND cancelled_qty>=0 AND fulfilled_qty+cancelled_qty<=quantity),
 CHECK(subtotal=net+discount AND gross=net+tax AND discount>=0 AND net>=0 AND tax>=0)
);
CREATE FUNCTION commercial_order_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_sealed boolean;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Commercial source cannot be deleted' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='sales_orders' THEN
  IF TG_OP='INSERT' THEN
   IF NEW.sealed OR NEW.version<>1 THEN RAISE EXCEPTION 'Order must start unsealed' USING ERRCODE='23514'; END IF;
   IF NOT EXISTS (SELECT 1 FROM quotes q JOIN quote_revisions r ON r.workspace_id=q.workspace_id AND r.entity_id=q.entity_id AND r.quote_id=q.id WHERE q.workspace_id=NEW.workspace_id AND q.entity_id=NEW.entity_id AND q.id=NEW.quote_id AND r.id=NEW.quote_revision_id AND r.status='ACCEPTED' AND q.customer_account_id=NEW.customer_account_id AND r.customer_account_version=NEW.customer_account_version AND r.currency=NEW.currency AND r.terms_days=NEW.terms_days AND r.business_date=NEW.business_date AND r.accepted_reference=NEW.accepted_reference AND r.payload_hash=NEW.source_hash AND r.config_version=NEW.config_version) THEN
    RAISE EXCEPTION 'Order header must match accepted quote' USING ERRCODE='23514';
   END IF;
  ELSIF (to_jsonb(NEW)-ARRAY['version','sealed']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['version','sealed']) OR NEW.version<>OLD.version+1 OR NOT NEW.sealed THEN
   RAISE EXCEPTION 'Order source is immutable' USING ERRCODE='23514';
  END IF;
  IF TG_OP='UPDATE' AND NOT OLD.sealed AND NEW.sealed AND NOT EXISTS (SELECT 1 FROM sales_order_lines l WHERE l.workspace_id=NEW.workspace_id AND l.entity_id=NEW.entity_id AND l.order_id=NEW.id) THEN
   RAISE EXCEPTION 'Order requires source lines' USING ERRCODE='23514';
  END IF;
 ELSE
  SELECT sealed INTO parent_sealed FROM sales_orders WHERE workspace_id=NEW.workspace_id AND entity_id=NEW.entity_id AND id=NEW.order_id FOR UPDATE;
  IF TG_OP='INSERT' THEN
   IF parent_sealed IS DISTINCT FROM false THEN RAISE EXCEPTION 'Order lines require an unsealed parent' USING ERRCODE='23514'; END IF;
   IF NEW.fulfilled_qty<>0 OR NEW.cancelled_qty<>0 OR NEW.version<>1 OR NOT EXISTS (
    SELECT 1 FROM sales_orders o JOIN quote_lines q ON q.workspace_id=o.workspace_id AND q.entity_id=o.entity_id AND q.revision_id=o.quote_revision_id AND q.id=NEW.quote_line_id AND q.active
    WHERE o.workspace_id=NEW.workspace_id AND o.entity_id=NEW.entity_id AND o.id=NEW.order_id
    AND ROW(NEW.line_key,NEW.product_id,NEW.product_version,NEW.product_setting_version,NEW.uom_id,NEW.uom_version,NEW.tracking,NEW.quantity_scale,NEW.quantity,NEW.unit_price,NEW.subtotal,NEW.discount,NEW.net,NEW.tax,NEW.gross,NEW.tax_version_id,NEW.tax_rate_pct,NEW.output_account_id,NEW.ar_account_id,NEW.unbilled_account_id,NEW.revenue_account_id,NEW.cogs_account_id,NEW.inventory_account_id)
     IS NOT DISTINCT FROM ROW(q.line_key,q.product_id,q.product_version,q.product_setting_version,q.uom_id,q.uom_version,q.tracking,q.quantity_scale,q.quantity,q.unit_price,q.subtotal,q.discount,q.net,q.tax,q.gross,q.tax_version_id,q.tax_rate_pct,q.output_account_id,q.ar_account_id,q.unbilled_account_id,q.revenue_account_id,q.cogs_account_id,q.inventory_account_id)
   ) THEN RAISE EXCEPTION 'Order line must match accepted quote source' USING ERRCODE='23514'; END IF;
  ELSIF parent_sealed IS DISTINCT FROM true OR (to_jsonb(NEW)-ARRAY['version','fulfilled_qty','cancelled_qty']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['version','fulfilled_qty','cancelled_qty']) OR NEW.version<>OLD.version+1 THEN
   RAISE EXCEPTION 'Order line source is immutable' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER sales_orders_guard BEFORE INSERT OR UPDATE OR DELETE ON sales_orders FOR EACH ROW EXECUTE FUNCTION commercial_order_guard();
CREATE TRIGGER sales_order_lines_guard BEFORE INSERT OR UPDATE OR DELETE ON sales_order_lines FOR EACH ROW EXECUTE FUNCTION commercial_order_guard();
CREATE FUNCTION commercial_order_sealed_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM sales_orders o WHERE o.workspace_id=NEW.workspace_id AND o.entity_id=NEW.entity_id AND o.id=NEW.id AND o.sealed)
   THEN RAISE EXCEPTION 'Order must be sealed before commit' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER sales_orders_sealed_check AFTER INSERT OR UPDATE ON sales_orders DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION commercial_order_sealed_check();

CREATE TABLE sales_handover_executions (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 intent text NOT NULL, quote_id bigint NOT NULL, quote_revision_id bigint NOT NULL, order_id bigint NOT NULL,
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,intent),
 FOREIGN KEY(workspace_id,entity_id,quote_id) REFERENCES quotes(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,order_id) REFERENCES sales_orders(workspace_id,entity_id,id)
);

CREATE TABLE product_compatibilities (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 device_id bigint NOT NULL, component_id bigint NOT NULL, created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,device_id,component_id),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,device_id) REFERENCES products(workspace_id,id), FOREIGN KEY(workspace_id,component_id) REFERENCES products(workspace_id,id), CHECK(device_id<>component_id)
);
CREATE TABLE bundle_components (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 bundle_id bigint NOT NULL, component_id bigint NOT NULL, quantity numeric(18,6) NOT NULL CHECK(quantity>0),
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,bundle_id,component_id),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,bundle_id) REFERENCES products(workspace_id,id), FOREIGN KEY(workspace_id,component_id) REFERENCES products(workspace_id,id), CHECK(bundle_id<>component_id)
);
CREATE TABLE party_addresses (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 customer_account_id bigint NOT NULL, address_role text NOT NULL CHECK(address_role IN ('BILLING','SHIPPING')),
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), FOREIGN KEY(workspace_id,entity_id,customer_account_id) REFERENCES customer_entity_accounts(workspace_id,entity_id,id)
);
CREATE TABLE party_address_versions (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 address_id bigint NOT NULL, revision bigint NOT NULL CHECK(revision>0), label text NOT NULL CHECK(length(btrim(label)) BETWEEN 1 AND 500),
 valid_from date NOT NULL, valid_to date, CHECK(valid_to IS NULL OR valid_to>=valid_from),
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,address_id,revision),
 FOREIGN KEY(workspace_id,entity_id,address_id) REFERENCES party_addresses(workspace_id,entity_id,id)
);
CREATE TABLE document_address_snapshots (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 revision_id bigint NOT NULL, address_id bigint NOT NULL, address_version bigint NOT NULL, address_role text NOT NULL, label text NOT NULL,
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,revision_id,address_role),
 FOREIGN KEY(workspace_id,entity_id,revision_id) REFERENCES quote_revisions(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,address_id) REFERENCES party_addresses(workspace_id,entity_id,id)
);
ALTER TABLE quote_revisions ADD FOREIGN KEY(workspace_id,entity_id,billing_address_id) REFERENCES party_addresses(workspace_id,entity_id,id);
ALTER TABLE quote_revisions ADD FOREIGN KEY(workspace_id,entity_id,shipping_address_id) REFERENCES party_addresses(workspace_id,entity_id,id);
ALTER TABLE quote_revisions ADD FOREIGN KEY(workspace_id,entity_id,billing_address_id,billing_address_version) REFERENCES party_address_versions(workspace_id,entity_id,address_id,revision);
ALTER TABLE quote_revisions ADD FOREIGN KEY(workspace_id,entity_id,shipping_address_id,shipping_address_version) REFERENCES party_address_versions(workspace_id,entity_id,address_id,revision);
ALTER TABLE document_address_snapshots ADD FOREIGN KEY(workspace_id,entity_id,address_id,address_version) REFERENCES party_address_versions(workspace_id,entity_id,address_id,revision);

CREATE FUNCTION commercial_quote_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status text;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Commercial history cannot be deleted' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='quotes' THEN
  IF (to_jsonb(NEW)-'version') IS DISTINCT FROM (to_jsonb(OLD)-'version') OR NEW.version<>OLD.version+1 THEN
   RAISE EXCEPTION 'Quote identity is immutable' USING ERRCODE='23514';
  END IF;
 ELSIF TG_TABLE_NAME='quote_revisions' THEN
  IF OLD.status='DRAFT' AND NEW.status NOT IN ('DRAFT','SUBMITTED','APPROVED') THEN RAISE EXCEPTION 'Invalid draft quote transition' USING ERRCODE='23514'; END IF;
  IF OLD.status='DRAFT' AND NEW.status='APPROVED' AND NEW.requires_approval THEN RAISE EXCEPTION 'Independent approval required' USING ERRCODE='23514'; END IF;
  IF OLD.status='DRAFT' AND NEW.status IN ('SUBMITTED','APPROVED') AND (length(NEW.payload_hash)<>64 OR NEW.submitted_by IS NULL OR NEW.submitted_at IS NULL) THEN RAISE EXCEPTION 'Submitted quote requires source hash and actor' USING ERRCODE='23514'; END IF;
  IF OLD.status='SUBMITTED' AND NEW.status IN ('APPROVED','REJECTED') AND (NEW.approved_by IS NULL OR NEW.approved_at IS NULL OR EXISTS (SELECT 1 FROM quote_contributors c WHERE c.workspace_id=NEW.workspace_id AND c.entity_id=NEW.entity_id AND c.revision_id=NEW.id AND c.user_id=NEW.approved_by)) THEN RAISE EXCEPTION 'Independent quote decision required' USING ERRCODE='23514'; END IF;
  IF OLD.status='APPROVED' AND NEW.status='ACCEPTED' AND (NEW.accepted_business_date<NEW.business_date OR NEW.accepted_business_date>NEW.valid_until) THEN RAISE EXCEPTION 'Acceptance outside validity' USING ERRCODE='23514'; END IF;
  IF OLD.status IN ('APPROVED','ACCEPTED') AND ROW(NEW.approved_by,NEW.approved_at,NEW.decision_reason) IS DISTINCT FROM ROW(OLD.approved_by,OLD.approved_at,OLD.decision_reason) THEN RAISE EXCEPTION 'Quote approval history is immutable' USING ERRCODE='23514'; END IF;
  IF OLD.status='ACCEPTED' AND ROW(NEW.accepted_by,NEW.accepted_at,NEW.accepted_business_date,NEW.accepted_reference) IS DISTINCT FROM ROW(OLD.accepted_by,OLD.accepted_at,OLD.accepted_business_date,OLD.accepted_reference) THEN RAISE EXCEPTION 'Acceptance history is immutable' USING ERRCODE='23514'; END IF;
  IF OLD.status<>'DRAFT' AND (to_jsonb(NEW)-ARRAY['status','approved_by','approved_at','decision_reason','accepted_by','accepted_at','accepted_business_date','accepted_reference']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','approved_by','approved_at','decision_reason','accepted_by','accepted_at','accepted_business_date','accepted_reference']) THEN
   RAISE EXCEPTION 'Submitted quote source is immutable' USING ERRCODE='23514';
  END IF;
  IF OLD.status<>'DRAFT' AND NOT ((OLD.status='SUBMITTED' AND NEW.status IN ('APPROVED','REJECTED')) OR (OLD.status='APPROVED' AND NEW.status='ACCEPTED') OR (OLD.status='ACCEPTED' AND NEW.status='WITHDRAWN')) THEN
   RAISE EXCEPTION 'Invalid quote transition' USING ERRCODE='23514';
  END IF;
 ELSIF TG_TABLE_NAME='document_address_snapshots' THEN
  IF TG_OP<>'INSERT' OR NOT EXISTS (
    SELECT 1 FROM quote_revisions r JOIN party_addresses a ON a.workspace_id=r.workspace_id AND a.entity_id=r.entity_id AND a.id=NEW.address_id
    JOIN party_address_versions v ON v.workspace_id=a.workspace_id AND v.entity_id=a.entity_id AND v.address_id=a.id AND v.revision=NEW.address_version
    WHERE r.workspace_id=NEW.workspace_id AND r.entity_id=NEW.entity_id AND r.id=NEW.revision_id AND r.status IN ('SUBMITTED','APPROVED')
      AND a.address_role=NEW.address_role AND v.label=NEW.label
      AND ((NEW.address_role='BILLING' AND r.billing_address_id=a.id AND r.billing_address_version=v.revision)
        OR (NEW.address_role='SHIPPING' AND r.shipping_address_id=a.id AND r.shipping_address_version=v.revision))
  ) THEN RAISE EXCEPTION 'Address snapshot does not match submitted quote' USING ERRCODE='23514'; END IF;
 ELSE
  SELECT status INTO parent_status FROM quote_revisions WHERE workspace_id=NEW.workspace_id AND entity_id=NEW.entity_id AND id=NEW.revision_id FOR UPDATE;
  IF parent_status IS DISTINCT FROM 'DRAFT' OR (TG_OP='UPDATE' AND ROW(NEW.workspace_id,NEW.entity_id,NEW.revision_id,NEW.id,NEW.line_key) IS DISTINCT FROM ROW(OLD.workspace_id,OLD.entity_id,OLD.revision_id,OLD.id,OLD.line_key)) THEN
   RAISE EXCEPTION 'Quote line is immutable outside draft' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER quotes_guard BEFORE UPDATE OR DELETE ON quotes FOR EACH ROW EXECUTE FUNCTION commercial_quote_guard();
CREATE TRIGGER quote_revisions_guard BEFORE UPDATE OR DELETE ON quote_revisions FOR EACH ROW EXECUTE FUNCTION commercial_quote_guard();
CREATE TRIGGER quote_lines_guard BEFORE INSERT OR UPDATE OR DELETE ON quote_lines FOR EACH ROW EXECUTE FUNCTION commercial_quote_guard();
CREATE TRIGGER quote_address_guard BEFORE INSERT OR UPDATE OR DELETE ON document_address_snapshots FOR EACH ROW EXECUTE FUNCTION commercial_quote_guard();

-- FULFILLMENT
-- M3 fulfillment. Apply after the commercial order fragment.
CREATE TABLE inventory_reservations (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 order_line_id bigint NOT NULL, product_id bigint NOT NULL, warehouse_id bigint NOT NULL,
 serial_id bigint, lot_id bigint, quantity numeric(18,6) NOT NULL CHECK(quantity>0),
 released_qty numeric(18,6) NOT NULL DEFAULT 0 CHECK(released_qty>=0),
 consumed_qty numeric(18,6) NOT NULL DEFAULT 0 CHECK(consumed_qty>=0),
 intent_key text NOT NULL, intent_hash text NOT NULL, version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(released_qty+consumed_qty<=quantity), CHECK(serial_id IS NULL OR lot_id IS NULL),
 CHECK(serial_id IS NULL OR quantity=1),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,intent_key),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,order_line_id) REFERENCES sales_order_lines(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,warehouse_id) REFERENCES warehouses(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,serial_id,product_id) REFERENCES instrument_serials(workspace_id,entity_id,id,product_id),
 FOREIGN KEY(workspace_id,entity_id,lot_id,product_id) REFERENCES inventory_lots(workspace_id,entity_id,id,product_id)
);
CREATE UNIQUE INDEX inventory_reservations_active_serial ON inventory_reservations(workspace_id,entity_id,serial_id)
 WHERE serial_id IS NOT NULL AND released_qty+consumed_qty<quantity;
CREATE TABLE inventory_reservation_events (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 reservation_id bigint NOT NULL, kind text NOT NULL CHECK(kind IN ('RESERVE','RELEASE','CONSUME')),
 quantity numeric(18,6) NOT NULL CHECK(quantity>0), intent_key text NOT NULL, intent_hash text NOT NULL,
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,intent_key),
 FOREIGN KEY(workspace_id,entity_id,reservation_id) REFERENCES inventory_reservations(workspace_id,entity_id,id)
);
CREATE TABLE sales_order_cancellations (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 order_line_id bigint NOT NULL, slice_start numeric(18,6) NOT NULL CHECK(slice_start>=0),
 slice_end numeric(18,6) NOT NULL CHECK(slice_end>slice_start), reason text NOT NULL,
 intent_key text NOT NULL, intent_hash text NOT NULL, created_by bigint NOT NULL REFERENCES app_users(id),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(workspace_id,entity_id,id),
 UNIQUE(workspace_id,entity_id,intent_key,slice_start),
 FOREIGN KEY(workspace_id,entity_id,order_line_id) REFERENCES sales_order_lines(workspace_id,entity_id,id)
);
CREATE TABLE shipments (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 order_id bigint NOT NULL, number text NOT NULL, shipment_date date NOT NULL,
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','POSTED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), created_by bigint NOT NULL REFERENCES app_users(id),
 created_at timestamptz NOT NULL DEFAULT now(), posted_by bigint REFERENCES app_users(id), posted_at timestamptz,
 journal_id bigint, source_hash text NOT NULL, create_hash text NOT NULL,
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,number),
 FOREIGN KEY(workspace_id,entity_id,order_id) REFERENCES sales_orders(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,journal_id) REFERENCES journal_entries(workspace_id,entity_id,id),
 CHECK((status='DRAFT' AND posted_by IS NULL AND posted_at IS NULL AND journal_id IS NULL) OR
       (status='POSTED' AND posted_by IS NOT NULL AND posted_at IS NOT NULL))
);
CREATE TABLE shipment_lines (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 shipment_id bigint NOT NULL, order_line_id bigint NOT NULL, order_line_version bigint NOT NULL, line_key text NOT NULL, warehouse_id bigint NOT NULL,
 warehouse_version bigint NOT NULL, product_id bigint NOT NULL, slice_start numeric(18,6), slice_end numeric(18,6),
 quantity numeric(18,6) NOT NULL CHECK(quantity>0), subtotal numeric(18,2), discount numeric(18,2), net numeric(18,2),
 tax numeric(18,2), gross numeric(18,2), output_account_id bigint, ar_account_id bigint, unbilled_account_id bigint,
 cost numeric(18,2), inventory_account_id bigint, cogs_account_id bigint, revenue_account_id bigint,
 active boolean NOT NULL DEFAULT true,
 UNIQUE(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,shipment_id) REFERENCES shipments(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,order_line_id) REFERENCES sales_order_lines(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,warehouse_id) REFERENCES warehouses(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,product_id) REFERENCES products(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,output_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,ar_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,unbilled_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,inventory_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,cogs_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,revenue_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 CHECK((slice_start IS NULL AND slice_end IS NULL) OR (slice_start>=0 AND slice_end>slice_start AND slice_end-slice_start=quantity)),
 CHECK(cost IS NULL OR cost>=0)
);
CREATE UNIQUE INDEX shipment_active_line_key ON shipment_lines(workspace_id,entity_id,shipment_id,line_key) WHERE active;
CREATE TABLE shipment_line_tracking (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 shipment_line_id bigint NOT NULL, reservation_id bigint NOT NULL, quantity numeric(18,6) NOT NULL CHECK(quantity>0),
 active boolean NOT NULL DEFAULT true, UNIQUE(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,shipment_line_id) REFERENCES shipment_lines(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,reservation_id) REFERENCES inventory_reservations(workspace_id,entity_id,id)
);
CREATE UNIQUE INDEX shipment_active_reservation ON shipment_line_tracking(workspace_id,entity_id,shipment_line_id,reservation_id) WHERE active;
CREATE FUNCTION m3_reservation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'reservation cannot be deleted' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.released_qty<>0 OR NEW.consumed_qty<>0 OR NEW.version<>1 THEN RAISE EXCEPTION 'invalid initial reservation' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 IF (to_jsonb(NEW)-ARRAY['released_qty','consumed_qty','version']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['released_qty','consumed_qty','version'])
    OR NEW.released_qty<OLD.released_qty OR NEW.consumed_qty<OLD.consumed_qty OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'reservation identity or counters invalid' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER m3_reservation_update BEFORE INSERT OR UPDATE OR DELETE ON inventory_reservations FOR EACH ROW EXECUTE FUNCTION m3_reservation_guard();
CREATE FUNCTION m3_fulfillment_header_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'shipment cannot be deleted' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'DRAFT' OR NEW.version<>1 THEN
   RAISE EXCEPTION 'shipment must start as draft' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END IF;
 IF OLD.status='POSTED' OR NEW.workspace_id<>OLD.workspace_id OR NEW.entity_id<>OLD.entity_id OR NEW.id<>OLD.id
    OR NEW.order_id<>OLD.order_id OR NEW.number<>OLD.number OR NEW.create_hash<>OLD.create_hash OR NEW.created_by<>OLD.created_by OR NEW.created_at<>OLD.created_at
    OR NEW.version<>OLD.version+1 OR NEW.status NOT IN ('DRAFT','POSTED') THEN
  RAISE EXCEPTION 'shipment identity or transition invalid' USING ERRCODE='23514';
 END IF;
 IF NEW.status='POSTED' AND (NOT EXISTS(SELECT 1 FROM shipment_lines l WHERE l.workspace_id=NEW.workspace_id AND l.entity_id=NEW.entity_id AND l.shipment_id=NEW.id AND l.active)
    OR EXISTS(SELECT 1 FROM shipment_lines l WHERE l.workspace_id=NEW.workspace_id AND l.entity_id=NEW.entity_id AND l.shipment_id=NEW.id AND l.active AND
      (l.slice_start IS NULL OR l.slice_end IS NULL OR l.subtotal IS NULL OR l.discount IS NULL OR l.net IS NULL OR l.tax IS NULL OR l.gross IS NULL OR
       l.ar_account_id IS NULL OR l.unbilled_account_id IS NULL OR l.cost IS NULL OR l.inventory_account_id IS NULL OR l.cogs_account_id IS NULL OR l.revenue_account_id IS NULL))) THEN
  RAISE EXCEPTION 'posted shipment requires complete source snapshots' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER m3_shipment_update BEFORE INSERT OR UPDATE OR DELETE ON shipments FOR EACH ROW EXECUTE FUNCTION m3_fulfillment_header_guard();
CREATE FUNCTION m3_fulfillment_child_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_id bigint; parent_status text;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'shipment child cannot be deleted' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='shipment_lines' THEN parent_id:=NEW.shipment_id;
 ELSE SELECT shipment_id INTO parent_id FROM shipment_lines WHERE workspace_id=NEW.workspace_id AND entity_id=NEW.entity_id AND id=NEW.shipment_line_id; END IF;
 SELECT status INTO parent_status FROM shipments WHERE workspace_id=NEW.workspace_id AND entity_id=NEW.entity_id AND id=parent_id FOR UPDATE;
 IF parent_status IS DISTINCT FROM 'DRAFT' THEN RAISE EXCEPTION 'shipment children are immutable' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.workspace_id<>OLD.workspace_id OR NEW.entity_id<>OLD.entity_id OR
   (TG_TABLE_NAME='shipment_lines' AND (to_jsonb(NEW)->>'shipment_id') IS DISTINCT FROM (to_jsonb(OLD)->>'shipment_id')) OR
   (TG_TABLE_NAME='shipment_line_tracking' AND (to_jsonb(NEW)->>'shipment_line_id') IS DISTINCT FROM (to_jsonb(OLD)->>'shipment_line_id'))) THEN
  RAISE EXCEPTION 'shipment child identity immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER m3_shipment_line_guard BEFORE INSERT OR UPDATE OR DELETE ON shipment_lines FOR EACH ROW EXECUTE FUNCTION m3_fulfillment_child_guard();
CREATE TRIGGER m3_shipment_tracking_guard BEFORE INSERT OR UPDATE OR DELETE ON shipment_line_tracking FOR EACH ROW EXECUTE FUNCTION m3_fulfillment_child_guard();

-- RECEIVABLES
-- M3 receivables: source intervals and cash are retained as immutable business history.
CREATE TABLE sales_invoices (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 reference text NOT NULL CHECK(reference=trim(reference) AND length(reference) BETWEEN 1 AND 120),
 create_key text NOT NULL, creation_hash text NOT NULL,
 customer_account_id bigint NOT NULL, currency text NOT NULL, invoice_date date NOT NULL, due_date date NOT NULL,
 terms_days integer NOT NULL CHECK(terms_days BETWEEN 0 AND 3650), ar_account_id bigint NOT NULL,
 status text NOT NULL CHECK(status IN ('DRAFT','POSTED')), version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 journal_id bigint, posting_hash text, created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 posted_by bigint REFERENCES app_users(id), posted_at timestamptz,
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,reference),
 UNIQUE(workspace_id,entity_id,create_key),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,customer_account_id) REFERENCES customer_entity_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,ar_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,journal_id) REFERENCES journal_entries(workspace_id,entity_id,id),
 CHECK(due_date=invoice_date+terms_days),
 CHECK((status='DRAFT' AND posting_hash IS NULL AND posted_by IS NULL AND posted_at IS NULL AND journal_id IS NULL) OR
       (status='POSTED' AND posting_hash IS NOT NULL AND posted_by IS NOT NULL AND posted_at IS NOT NULL))
);
CREATE TABLE sales_invoice_lines (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 invoice_id bigint NOT NULL, shipment_line_id bigint NOT NULL, line_key text NOT NULL,
 slice_start numeric(18,6) NOT NULL CHECK(slice_start>=0), slice_end numeric(18,6) NOT NULL CHECK(slice_end>slice_start),
 subtotal numeric(18,2) NOT NULL CHECK(subtotal>=0), discount numeric(18,2) NOT NULL CHECK(discount>=0),
 net numeric(18,2) NOT NULL CHECK(net>=0), tax numeric(18,2) NOT NULL CHECK(tax>=0), gross numeric(18,2) NOT NULL CHECK(gross=net+tax),
 unbilled_account_id bigint NOT NULL, output_account_id bigint NOT NULL, active boolean NOT NULL DEFAULT true,
 CHECK(subtotal=net+discount), UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,invoice_id,line_key),
 FOREIGN KEY(workspace_id,entity_id,invoice_id) REFERENCES sales_invoices(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,shipment_line_id) REFERENCES shipment_lines(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,unbilled_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,output_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id)
);
CREATE TABLE customer_receipts (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 customer_account_id bigint NOT NULL, receipt_key text NOT NULL CHECK(receipt_key=trim(receipt_key) AND length(receipt_key) BETWEEN 1 AND 120),
 create_key text NOT NULL, creation_hash text NOT NULL,
 status text NOT NULL CHECK(status IN ('DRAFT','POSTED')), payment_date date NOT NULL, currency text NOT NULL,
 amount numeric(18,2) NOT NULL CHECK(amount>0), bank_account_id bigint NOT NULL, ar_account_id bigint NOT NULL,
 customer_account_version bigint NOT NULL, config_version bigint NOT NULL, payment_config_version bigint NOT NULL,
 journal_id bigint, posting_hash text, version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 posted_by bigint REFERENCES app_users(id), posted_at timestamptz,
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,receipt_key),
 UNIQUE(workspace_id,entity_id,create_key),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,customer_account_id) REFERENCES customer_entity_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,bank_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,ar_account_id) REFERENCES chart_accounts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,journal_id) REFERENCES journal_entries(workspace_id,entity_id,id),
 CHECK(bank_account_id<>ar_account_id),
 CHECK((status='DRAFT' AND posting_hash IS NULL AND posted_by IS NULL AND posted_at IS NULL AND journal_id IS NULL) OR
       (status='POSTED' AND posting_hash IS NOT NULL AND posted_by IS NOT NULL AND posted_at IS NOT NULL AND journal_id IS NOT NULL))
);
CREATE TABLE customer_receipt_allocations (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 receipt_id bigint NOT NULL, invoice_id bigint NOT NULL, amount numeric(18,2) NOT NULL CHECK(amount>0), active boolean NOT NULL DEFAULT true,
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,receipt_id,invoice_id),
 FOREIGN KEY(workspace_id,entity_id,receipt_id) REFERENCES customer_receipts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,invoice_id) REFERENCES sales_invoices(workspace_id,entity_id,id)
);
CREATE TABLE sales_reversals (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, workspace_id bigint NOT NULL, entity_id bigint NOT NULL,
 source_type text NOT NULL CHECK(source_type IN ('INVOICE','RECEIPT')), invoice_id bigint, receipt_id bigint,
 reversal_date date NOT NULL, reversal_key text NOT NULL CHECK(reversal_key=trim(reversal_key) AND length(reversal_key) BETWEEN 1 AND 120),
 payload_hash text NOT NULL, reason text NOT NULL CHECK(length(trim(reason)) BETWEEN 1 AND 500), journal_id bigint,
 created_by bigint NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,entity_id,id), UNIQUE(workspace_id,entity_id,reversal_key), UNIQUE(workspace_id,entity_id,invoice_id), UNIQUE(workspace_id,entity_id,receipt_id),
 CHECK((source_type='INVOICE' AND invoice_id IS NOT NULL AND receipt_id IS NULL) OR (source_type='RECEIPT' AND receipt_id IS NOT NULL AND invoice_id IS NULL)),
 FOREIGN KEY(workspace_id,entity_id) REFERENCES legal_entities(workspace_id,id),
 FOREIGN KEY(workspace_id,entity_id,invoice_id) REFERENCES sales_invoices(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,receipt_id) REFERENCES customer_receipts(workspace_id,entity_id,id),
 FOREIGN KEY(workspace_id,entity_id,journal_id) REFERENCES journal_entries(workspace_id,entity_id,id)
);
CREATE FUNCTION m3_receivables_document_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'receivables history cannot be deleted' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'DRAFT' THEN RAISE EXCEPTION 'document must begin as draft' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 IF OLD.status='POSTED' THEN RAISE EXCEPTION 'posted document is immutable' USING ERRCODE='23514'; END IF;
 IF (NEW.workspace_id,NEW.entity_id,NEW.id,NEW.created_by,NEW.created_at,NEW.create_key,NEW.creation_hash) IS DISTINCT FROM (OLD.workspace_id,OLD.entity_id,OLD.id,OLD.created_by,OLD.created_at,OLD.create_key,OLD.creation_hash) OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'document identity or version changed incorrectly' USING ERRCODE='23514';
 END IF;
 IF NEW.status='POSTED' AND (to_jsonb(NEW)-ARRAY['status','version','journal_id','posting_hash','posted_by','posted_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','version','journal_id','posting_hash','posted_by','posted_at']) THEN
  RAISE EXCEPTION 'posting cannot alter draft source' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER sales_invoice_guard BEFORE INSERT OR UPDATE OR DELETE ON sales_invoices FOR EACH ROW EXECUTE FUNCTION m3_receivables_document_guard();
CREATE TRIGGER customer_receipt_guard BEFORE INSERT OR UPDATE OR DELETE ON customer_receipts FOR EACH ROW EXECUTE FUNCTION m3_receivables_document_guard();
CREATE FUNCTION m3_receivables_child_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status text; item jsonb; old_item jsonb; parent_id bigint;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'receivables history cannot be deleted' USING ERRCODE='23514'; END IF;
 item=to_jsonb(NEW);
 IF TG_OP='UPDATE' THEN
  old_item=to_jsonb(OLD);
  IF (item->>'id',item->>'workspace_id',item->>'entity_id',item->>TG_ARGV[1]) IS DISTINCT FROM (old_item->>'id',old_item->>'workspace_id',old_item->>'entity_id',old_item->>TG_ARGV[1]) THEN
   RAISE EXCEPTION 'receivables child identity is immutable' USING ERRCODE='23514';
  END IF;
 END IF;
 parent_id=(item->>TG_ARGV[1])::bigint;
 IF TG_ARGV[0]='INVOICE' THEN
  SELECT status INTO parent_status FROM sales_invoices WHERE workspace_id=NEW.workspace_id AND entity_id=NEW.entity_id AND id=parent_id FOR UPDATE;
 ELSE
  SELECT status INTO parent_status FROM customer_receipts WHERE workspace_id=NEW.workspace_id AND entity_id=NEW.entity_id AND id=parent_id FOR UPDATE;
 END IF;
 IF parent_status IS DISTINCT FROM 'DRAFT' THEN RAISE EXCEPTION 'posted children are immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER sales_invoice_line_guard BEFORE INSERT OR UPDATE OR DELETE ON sales_invoice_lines FOR EACH ROW EXECUTE FUNCTION m3_receivables_child_guard('INVOICE','invoice_id');
CREATE TRIGGER customer_receipt_allocation_guard BEFORE INSERT OR UPDATE OR DELETE ON customer_receipt_allocations FOR EACH ROW EXECUTE FUNCTION m3_receivables_child_guard('RECEIPT','receipt_id');

-- Deferred cross-domain scoped references.
ALTER TABLE crm_reactivation_cases ADD CONSTRAINT crm_reactivation_purchase_fk FOREIGN KEY (workspace_id, entity_id, purchase_shipment_line_id) REFERENCES shipment_lines(workspace_id, entity_id, id);
ALTER TABLE crm_reactivation_evaluations ADD CONSTRAINT crm_reactivation_evaluation_purchase_fk FOREIGN KEY (workspace_id, entity_id, purchase_shipment_line_id) REFERENCES shipment_lines(workspace_id, entity_id, id);
