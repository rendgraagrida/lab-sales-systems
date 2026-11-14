-- Preserve applied V1-V6 bytes. Source eligibility applies to active invoice lines only.
-- The existing receivables child guard still forbids changing posted invoice lines.
DROP TRIGGER m4_invoice_source ON sales_invoice_lines;
CREATE TRIGGER m4_invoice_source BEFORE INSERT OR UPDATE ON sales_invoice_lines
 FOR EACH ROW WHEN (NEW.active) EXECUTE FUNCTION m4_lineage_guard();
