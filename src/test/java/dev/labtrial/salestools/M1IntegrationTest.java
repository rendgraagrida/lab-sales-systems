package dev.labtrial.salestools;

import dev.labtrial.salestools.baseline.BaselineService;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.finance.Ledger;
import dev.labtrial.salestools.finance.PostingService;
import dev.labtrial.salestools.identity.AccessControl;
import dev.labtrial.salestools.inventory.InventoryService;
import dev.labtrial.salestools.finance.FinanceController;
import dev.labtrial.salestools.finance.SupplierPaymentService;
import dev.labtrial.salestools.procurement.ProcurementService;
import dev.labtrial.salestools.procurement.VendorInvoiceService;
import dev.labtrial.salestools.identity.BootstrapService;
import dev.labtrial.salestools.identity.IdentityPrincipal;
import dev.labtrial.salestools.identity.IdentityService;
import dev.labtrial.salestools.master.CustomerService;
import dev.labtrial.salestools.master.CustomerAccountService;
import dev.labtrial.salestools.master.ContactService;
import dev.labtrial.salestools.master.ConfigurationService;
import dev.labtrial.salestools.master.SupplierService;
import dev.labtrial.salestools.master.PriceBookService;
import dev.labtrial.salestools.master.ProductService;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M1IntegrationTest {
    private static final LocalDate DAY = LocalDate.of(2027, 1, 15);
    private static final String PASSWORD = UUID.randomUUID().toString() + UUID.randomUUID();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> required("LAB_TEST_DB_URL"));
        properties.add("spring.datasource.username", () -> required("LAB_TEST_DB_USER"));
        properties.add("spring.datasource.password", () -> required("LAB_TEST_DB_PASSWORD"));
        properties.add("lab.revision", () -> required("LAB_TEST_REVISION"));
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Real isolated PostgreSQL prerequisite missing: " + name);
        return value;
    }
    @Autowired JdbcTemplate db;
    @Autowired DataSource dataSource;
    @Autowired BootstrapService bootstrap;
    @Autowired IdentityService identity;
    @Autowired CustomerService customers;
    @Autowired CustomerAccountService customerAccounts;
    @Autowired ContactService contacts;
    @Autowired ConfigurationService configuration;
    @Autowired SupplierService suppliers;
    @Autowired ProductService products;
    @Autowired PriceBookService priceBooks;
    @Autowired BaselineService baseline;
    @Autowired Ledger ledger;
    @Autowired PostingService businessPosting;
    @Autowired AccessControl access;
    @Autowired PlatformTransactionManager transactions;
    @Autowired InventoryService inventory;
    @Autowired FinanceController finance;
    @Autowired ProcurementService procurement;
    @Autowired VendorInvoiceService vendorInvoices;
    @Autowired SupplierPaymentService supplierPayments;
    @Autowired dev.labtrial.salestools.finance.SalesPostingConfigService salesPosting;
    @Autowired dev.labtrial.salestools.crm.InquiryService crmInquiries;
    @Autowired dev.labtrial.salestools.crm.ScoringService crmScoring;
    @Autowired dev.labtrial.salestools.crm.RoutingService crmRouting;
    @Autowired dev.labtrial.salestools.crm.ActivityService crmActivities;
    @Autowired dev.labtrial.salestools.order.QuoteService salesQuotes;
    @Autowired dev.labtrial.salestools.order.HandoverService salesHandover;
    @Autowired dev.labtrial.salestools.order.FulfillmentService fulfillment;
    @Autowired dev.labtrial.salestools.finance.SalesInvoiceService salesInvoices;
    @Autowired dev.labtrial.salestools.finance.CustomerReceiptService customerReceipts;
    @Autowired dev.labtrial.salestools.crm.ReactivationService reactivation;
    @Autowired dev.labtrial.salestools.crm.OpportunityService crmOpportunities;
    @Autowired Environment environment;
    private long administrator;
    private long initialWorkspace;
    private String administratorLogin;
    private List<BootstrapService.BootstrapResult> bootstrapResults;
    record Context(long workspace, long entity, long user, String login) {}

    @BeforeAll void explicitSetup() throws Exception {
        assertEquals(0, db.queryForObject("select count(*) from app_users", Integer.class), "Startup must not create accounts");
        assertEquals(0, db.queryForObject("select count(*) from scenario_workspaces", Integer.class), "Startup must not seed workspaces");
        administratorLogin = "owner-" + UUID.randomUUID();
        String code = "BOOT-" + UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<Callable<BootstrapService.BootstrapResult>>();
            for (int i = 0; i < 4; i++) jobs.add(() -> bootstrap.bootstrap(administratorLogin, PASSWORD.toCharArray(), code, "Explicit test owner", DAY));
            bootstrapResults = new ArrayList<>();
            for (var result : executor.invokeAll(jobs)) bootstrapResults.add(result.get());
        }
        administrator = bootstrapResults.get(0).userId();
        initialWorkspace = bootstrapResults.get(0).workspaceId();
    }
    @AfterEach void clearActor() { SecurityContextHolder.clearContext(); }
    private void actor(long id) {
        var user = db.queryForMap("select login,security_version from app_users where id=?", id);
        var principal = new IdentityPrincipal(id, (String) user.get("login"), ((Number) user.get("security_version")).longValue(), null, true);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }
    private Context context(String currency, String... roles) {
        actor(administrator);
        String code = "W-" + UUID.randomUUID();
        long workspace = identity.createWorkspace(initialWorkspace, code, "Independent consumer test", DAY);
        identity.entity(workspace, null, 0, "ENTITY", "Test entity", currency, true);
        long entity = db.queryForObject("select id from legal_entities where workspace_id=? and code='ENTITY'", Long.class, workspace);
        String login = "user-" + UUID.randomUUID();
        identity.createUser(workspace, login, PASSWORD);
        long user = db.queryForObject("select id from app_users where login=?", Long.class, login);
        identity.entityGrant(workspace, entity, user, 0, true);
        for (String role : roles) identity.role(workspace, user, role, 0, true);
        actor(user);
        return new Context(workspace, entity, user, login);
    }
    private DomainException denied(int status, org.junit.jupiter.api.function.Executable action) {
        DomainException error = assertThrows(DomainException.class, action);
        assertEquals(status, error.status());
        return error;
    }

    @Test void bootstrapIsSingletonAndDoesNotGrantBusinessAuthority() {
        assertEquals(1, bootstrapResults.stream().filter(result -> !result.replayed()).count());
        assertTrue(bootstrapResults.stream().allMatch(result -> result.userId() == administrator && result.workspaceId() == initialWorkspace));
        assertEquals(List.of("SYSTEM_ADMIN"), db.queryForList("select role_code from user_roles where workspace_id=? and user_id=? and active", String.class, initialWorkspace, administrator));
        assertEquals(0, db.queryForObject("select count(*) from entity_memberships where workspace_id=?", Integer.class, initialWorkspace));
        denied(409, () -> bootstrap.bootstrap("different-owner", PASSWORD.toCharArray(), "OTHER", "Different intent", DAY));
        assertEquals(1, db.queryForObject("select count(*) from setup_receipts where receipt_key='INITIAL_ADMIN'", Integer.class));
    }

    @Test void administratorIsNotAnImplicitBusinessUserAndLastAdminCannotBeRemoved() {
        var c = context("EUR", "SYSTEM_ADMIN");
        denied(403, () -> customers.list(c.workspace(), c.entity(), ""));
        actor(administrator);
        denied(409, () -> identity.role(initialWorkspace, administrator, "SYSTEM_ADMIN", 1, false));
        assertTrue(db.queryForObject("select active from user_roles where workspace_id=? and user_id=? and role_code='SYSTEM_ADMIN'", Boolean.class, initialWorkspace, administrator));
    }

    @Test void customerInputPersistenceOptimisticConflictAndCrossWorkspaceIdentity() throws Exception {
        var c = context("EUR", "INSIDE_SALES");
        String key = "USER-SUPPLIED-" + UUID.randomUUID();
        String original = "Research \u03a9 <script>alert(1)</script>";
        long customer = customers.save(c.workspace(), c.entity(), null, null, new CustomerService.Input(key, original, "BIO", "EU", "research.example", null, null));
        assertEquals(original, customers.get(c.workspace(), c.entity(), customer).name());
        assertEquals(customer, customers.list(c.workspace(), c.entity(), key).get(0).id());
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> first = () -> { actor(c.user()); try { customers.save(c.workspace(), c.entity(), customer, 1L, new CustomerService.Input(key, "First edit", "BIO", "EU", "research.example", null, null)); return 200; } catch (DomainException ex) { return ex.status(); } finally { SecurityContextHolder.clearContext(); } };
            Callable<Integer> second = () -> { actor(c.user()); try { customers.save(c.workspace(), c.entity(), customer, 1L, new CustomerService.Input(key, "Second edit", "BIO", "EU", "research.example", null, null)); return 200; } catch (DomainException ex) { return ex.status(); } finally { SecurityContextHolder.clearContext(); } };
            var results = executor.invokeAll(List.of(first, second));
            assertEquals(List.of(200, 409), results.stream().map(result -> { try { return result.get(); } catch (Exception ex) { throw new RuntimeException(ex); } }).sorted().toList());
        }
        actor(c.user());
        assertEquals(2, customers.get(c.workspace(), c.entity(), customer).version());
        var other = context("USD", "INSIDE_SALES");
        long secondId = customers.save(other.workspace(), other.entity(), null, null, new CustomerService.Input(key, "Separate workspace", "", "", "", null, null));
        assertNotEquals(customer, secondId);
        denied(404, () -> customers.get(other.workspace(), other.entity(), customer));
        assertEquals(List.of(secondId), customers.list(other.workspace(), other.entity(), key).stream().map(row -> row.id()).toList());
        actor(c.user());
        assertEquals(2, db.queryForObject("select count(*) from audit_events where workspace_id=? and target_type='customers' and target_id=?", Integer.class, c.workspace(), Long.toString(customer)));
    }

    @Test void revokedRoleAndMembershipDenyWritesFromStalePrincipals() {
        var c = context("EUR", "INSIDE_SALES");
        long customer = customers.save(c.workspace(), c.entity(), null, null, new CustomerService.Input("STALE", "Original", "", "", "", null, null));
        actor(administrator);
        identity.role(c.workspace(), c.user(), "INSIDE_SALES", 1, false);
        actor(c.user());
        denied(403, () -> customers.save(c.workspace(), c.entity(), customer, 1L, new CustomerService.Input("STALE", "Should not persist", "", "", "", null, null)));
        assertEquals("Original", db.queryForObject("select name from customers where workspace_id=? and id=?", String.class, c.workspace(), customer));
        actor(administrator);
        identity.entityGrant(c.workspace(), c.entity(), c.user(), 1, false);
        actor(c.user());
        denied(404, () -> customers.list(c.workspace(), c.entity(), ""));
    }

    @Test void privilegeHistoryRetainsActivationWindowsEvenWhenFinalStateIsUnchanged() {
        var c = context("EUR");
        actor(administrator);
        var history = List.of(false, true, false, false);
        long version = 1;
        for (boolean active : history) {
            identity.role(c.workspace(), c.user(), "FINANCE", version - 1, active);
            identity.userState(c.workspace(), c.user(), version, active);
            identity.membership(c.workspace(), c.user(), version, active);
            identity.entityGrant(c.workspace(), c.entity(), c.user(), version, active);
            identity.entity(c.workspace(), c.entity(), version, "ENTITY", "Test entity", "EUR", active);
            version++;
        }
        for (String action : List.of("UPDATE_ROLE", "UPDATE_ACTIVE", "UPDATE_MEMBERSHIP", "UPDATE_ENTITY_MEMBERSHIP")) {
            var actual = db.queryForList("""
                select (detail::jsonb->>'active')::boolean from audit_events
                where workspace_id=? and target_type='user' and target_id=? and action=?
                  and actor_user_id=? and outcome='SUCCESS'
                order by revision
                """, Boolean.class, c.workspace(), Long.toString(c.user()), action, administrator);
            assertEquals("UPDATE_ENTITY_MEMBERSHIP".equals(action) ? List.of(true, false, true, false, false) : history, actual,
                "Committed audit history must distinguish a temporary activation from repeated inactive writes");
        }
        assertEquals(List.of("FINANCE", "FINANCE", "FINANCE", "FINANCE"), db.queryForList("""
            select detail::jsonb->>'role' from audit_events
            where workspace_id=? and target_type='user' and target_id=? and action='UPDATE_ROLE' order by revision
            """, String.class, c.workspace(), Long.toString(c.user())));
        assertEquals(List.of(c.entity()), db.queryForList("""
            select distinct entity_id from audit_events
            where workspace_id=? and target_id=? and action='UPDATE_ENTITY_MEMBERSHIP'
            """, Long.class, c.workspace(), Long.toString(c.user())));
        assertEquals(history, db.queryForList("""
            select (detail::jsonb->>'active')::boolean from audit_events
            where workspace_id=? and target_type='entity' and target_id=? and action='UPDATE' order by revision
            """, Boolean.class, c.workspace(), Long.toString(c.entity())));
        assertEquals(true, db.queryForObject("""
            select (detail::jsonb->>'active')::boolean from audit_events
            where workspace_id=? and target_type='entity' and target_id=? and action='CREATE'
            """, Boolean.class, c.workspace(), Long.toString(c.entity())));
        assertFalse(db.queryForObject("select active from app_users where id=?", Boolean.class, c.user()));
        assertFalse(db.queryForObject("select active from workspace_memberships where workspace_id=? and user_id=?", Boolean.class, c.workspace(), c.user()));
        assertFalse(db.queryForObject("select active from user_roles where workspace_id=? and user_id=? and role_code='FINANCE'", Boolean.class, c.workspace(), c.user()));
        assertFalse(db.queryForObject("select active from entity_memberships where workspace_id=? and entity_id=? and user_id=?", Boolean.class, c.workspace(), c.entity(), c.user()));
        assertFalse(db.queryForObject("select active from legal_entities where workspace_id=? and id=?", Boolean.class, c.workspace(), c.entity()));
    }

    @Test void explicitEmptyDoesNotInjectFinancialBalancesAndConflictingBaselineIsRejected() {
        var c = context("EUR", "FINANCE");
        long receipt = baseline.initialize(c.workspace(), c.entity(), "EMPTY", 1);
        assertEquals(receipt, baseline.initialize(c.workspace(), c.entity(), "EMPTY", 1));
        assertEquals(0, db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace()));
        assertEquals(0, db.queryForObject("select count(*) from inventory_balances where workspace_id=?", Integer.class, c.workspace()));
        assertEquals("READY", db.queryForObject("select initialization_state from scenario_workspaces where id=?", String.class, c.workspace()));
        denied(409, () -> baseline.initialize(c.workspace(), c.entity(), "O2C_GOODS", 2));
    }

    @Test void canonicalGoodsOpeningReconcilesAndConcurrentReplayIsExactlyOneEffect() throws Exception {
        var c = context("EUR", "FINANCE");
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Long> init = () -> { actor(c.user()); try { return baseline.initialize(c.workspace(), c.entity(), "O2C_GOODS", 1); } finally { SecurityContextHolder.clearContext(); } };
            var results = executor.invokeAll(List.of(init, init));
            assertEquals(results.get(0).get(), results.get(1).get());
        }
        assertEquals(new BigDecimal("13100.00"), db.queryForObject("select sum(value) from inventory_valuation_pools where workspace_id=?", BigDecimal.class, c.workspace()));
        var balances = db.queryForMap("select sum(l.debit) as debit,sum(l.credit) as credit from journal_lines l where workspace_id=?", c.workspace());
        assertEquals(new BigDecimal("14900.00"), balances.get("debit"));
        assertEquals(balances.get("debit"), balances.get("credit"));
        assertEquals(1, db.queryForObject("select count(*) from journal_entries where workspace_id=? and state='POSTED'", Integer.class, c.workspace()));
        assertEquals(List.of("SIM-RGA-TR120-00002"), db.queryForList("select serial from instrument_serials where workspace_id=?", String.class, c.workspace()));
        assertEquals(List.of("SIM-LOT-CS080-001"), db.queryForList("select lot from inventory_lots where workspace_id=?", String.class, c.workspace()));
        assertEquals(15, db.queryForObject("select count(*) from products where workspace_id=?", Integer.class, c.workspace()));
        assertTrue(db.queryForObject("select source_hash ~ '^[0-9a-f]{64}$' and payload_hash ~ '^[0-9a-f]{64}$' from scenario_baseline_versions where workspace_id=?", Boolean.class, c.workspace()));
    }

    @Test void invalidGoodsTargetOrExistingDataDoesNotBecomePartiallyReady() {
        var usd = context("USD", "FINANCE");
        denied(400, () -> baseline.initialize(usd.workspace(), usd.entity(), "O2C_GOODS", 1));
        assertEquals("NEW", db.queryForObject("select initialization_state from scenario_workspaces where id=?", String.class, usd.workspace()));
        assertEquals(0, db.queryForObject("select count(*) from products where workspace_id=?", Integer.class, usd.workspace()));
        var c = context("EUR", "FINANCE", "INSIDE_SALES");
        customers.save(c.workspace(), c.entity(), null, null, new CustomerService.Input("USER-DATA", "Must be preserved", "", "", "", null, null));
        denied(409, () -> baseline.initialize(c.workspace(), c.entity(), "O2C_GOODS", 1));
        assertEquals("Must be preserved", customers.list(c.workspace(), c.entity(), "USER-DATA").get(0).name());
        assertEquals("NEW", db.queryForObject("select initialization_state from scenario_workspaces where id=?", String.class, c.workspace()));
    }

    @Test void openingPayloadConflictClosedPeriodAndPostedRewriteAreRejected() throws Exception {
        var c = context("EUR", "FINANCE");
        baseline.initialize(c.workspace(), c.entity(), "O2C_GOODS", 1);
        long bank = db.queryForObject("select id from chart_accounts where workspace_id=? and entity_id=? and code='BANK'", Long.class, c.workspace(), c.entity());
        long equity = db.queryForObject("select id from chart_accounts where workspace_id=? and entity_id=? and code='EQUITY'", Long.class, c.workspace(), c.entity());
        var opening = new Ledger.Opening("MANUAL-OPEN", 1, DAY, "EUR", "Explicit synthetic opening", List.of(new Ledger.Line(bank,new BigDecimal("1000.00"),BigDecimal.ZERO),new Ledger.Line(equity,BigDecimal.ZERO,new BigDecimal("1000.00"))));
        var posted = ledger.postOpening(c.workspace(), c.entity(), opening);
        assertFalse(posted.replayed());
        assertEquals(posted.journalId(), ledger.postOpening(c.workspace(), c.entity(), opening).journalId());
        var changed = new Ledger.Opening("MANUAL-OPEN", 1, DAY, "EUR", "Conflicting source payload", opening.lines());
        denied(409, () -> ledger.postOpening(c.workspace(), c.entity(), changed));
        try (Connection connection = dataSource.getConnection()) {
            sqlDenied(connection, "update app.journal_entries set description='rewritten' where id=" + posted.journalId());
            sqlDenied(connection, "insert into app.journal_lines(workspace_id,entity_id,journal_id,account_id,debit,credit) values (" + c.workspace() + "," + c.entity() + "," + posted.journalId() + "," + bank + ",1,0)");
        }
        db.update("update fiscal_periods set status='CLOSED',version=version+1 where workspace_id=? and entity_id=?", c.workspace(), c.entity());
        denied(409, () -> ledger.postOpening(c.workspace(), c.entity(), new Ledger.Opening("CLOSED-PERIOD", 1, DAY, "EUR", "Must fail", opening.lines())));
        assertEquals(2, db.queryForObject("select count(*) from journal_entries where workspace_id=? and state='POSTED'", Integer.class, c.workspace()));
    }

    @Test void openingAmountBoundsRejectExtremeExponentsWithoutAnyPartialSourceJournalOrAudit() {
        var c = context("EUR", "FINANCE");
        baseline.initialize(c.workspace(), c.entity(), "O2C_GOODS", 1);
        long bank = db.queryForObject("select id from chart_accounts where workspace_id=? and entity_id=? and code='BANK'", Long.class, c.workspace(), c.entity());
        long equity = db.queryForObject("select id from chart_accounts where workspace_id=? and entity_id=? and code='EQUITY'", Long.class, c.workspace(), c.entity());
        int sources = db.queryForObject("select count(*) from opening_balance_sources where workspace_id=?", Integer.class, c.workspace());
        int journals = db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace());
        int audits = db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace());
        assertTimeout(Duration.ofSeconds(5), () -> {
            for (BigDecimal amount : List.of(new BigDecimal("1E+2147483647"), new BigDecimal("1E+16"), new BigDecimal("0.001"))) {
                var opening = new Ledger.Opening("INVALID-AMOUNT", 1, DAY, "EUR", "Rejected synthetic amount",
                        List.of(new Ledger.Line(bank, amount, BigDecimal.ZERO), new Ledger.Line(equity, BigDecimal.ZERO, amount)));
                denied(400, () -> ledger.postOpening(c.workspace(), c.entity(), opening));
            }
        });
        assertEquals(sources, db.queryForObject("select count(*) from opening_balance_sources where workspace_id=?", Integer.class, c.workspace()));
        assertEquals(journals, db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace()));
        assertEquals(audits, db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace()));
        BigDecimal maximum = new BigDecimal("9999999999999999.99");
        var boundary = ledger.postOpening(c.workspace(), c.entity(), new Ledger.Opening("MAXIMUM-VALID-AMOUNT", 1, DAY, "EUR", "Valid numeric boundary",
                List.of(new Ledger.Line(bank, maximum, BigDecimal.ZERO), new Ledger.Line(equity, BigDecimal.ZERO, maximum))));
        assertEquals(maximum, db.queryForObject("select sum(debit) from journal_lines where journal_id=?", BigDecimal.class, boundary.journalId()));
    }

    @Test void contactIdentityIsManyToManyAndEntityRevokeHidesBothMasterAndLinks() {
        var c = context("EUR", "INSIDE_SALES", "FINANCE");
        long first = customers.save(c.workspace(), c.entity(), null, null, new CustomerService.Input("A", "First company", "", "", "", null, null));
        long second = customers.save(c.workspace(), c.entity(), null, null, new CustomerService.Input("B", "Second company", "", "", "", null, null));
        customerAccounts.assign(c.workspace(), c.entity(), first, 1, "A-EU");
        customerAccounts.assign(c.workspace(), c.entity(), second, 1, "B-EU");
        long contact = contacts.save(c.workspace(), c.entity(), null, null, "CONTACT", "One", "Person", "one@example.example", "LEAD");
        contacts.link(c.workspace(), c.entity(), first, 1, contact, 1, null, "Procurement", true);
        contacts.link(c.workspace(), c.entity(), second, 1, contact, 1, null, "Lab manager", true);
        assertEquals(contact, contacts.links(c.workspace(), c.entity(), first).get(0).contactId());
        assertEquals(contact, contacts.links(c.workspace(), c.entity(), second).get(0).contactId());
        assertEquals(1, db.queryForObject("select count(*) from contacts where workspace_id=? and id=?", Integer.class, c.workspace(), contact));
        assertEquals("PENDING", customerAccounts.get(c.workspace(), c.entity(), first).creditStatus());
        actor(administrator);
        identity.role(c.workspace(), c.user(), "FINANCE", 1, false);
        actor(c.user());
        denied(403, () -> customerAccounts.get(c.workspace(), c.entity(), first));
        assertEquals("First company", customers.get(c.workspace(), c.entity(), first).name());
        actor(administrator);
        identity.entityGrant(c.workspace(), c.entity(), c.user(), 1, false);
        actor(c.user());
        denied(404, () -> contacts.get(c.workspace(), c.entity(), contact));
        denied(404, () -> customers.get(c.workspace(), c.entity(), first));
    }

    @Test void ownContactRemainsVisibleWhileLinkedToOwnDraftAndThenFollowsEntityAssignment() {
        var c = context("EUR", "INSIDE_SALES", "FINANCE");
        long customer = customers.save(c.workspace(), c.entity(), null, null, new CustomerService.Input("OWN-DRAFT", "Unassigned draft company", "", "", "", null, null));
        long contact = contacts.save(c.workspace(), c.entity(), null, null, "OWN-PERSON", "Draft", "Person", "draft@example.example", "LEAD");
        contacts.link(c.workspace(), c.entity(), customer, 1, contact, 1, null, "Draft contact", true);
        assertEquals(contact, contacts.get(c.workspace(), c.entity(), contact).id());
        assertTrue(contacts.list(c.workspace(), c.entity(), "OWN-PERSON").stream().anyMatch(person -> person.id() == contact));
        actor(administrator);
        identity.entity(c.workspace(), null, 0, "SECOND", "Other assigned entity", "USD", true);
        long other = db.queryForObject("select id from legal_entities where workspace_id=? and code='SECOND'", Long.class, c.workspace());
        identity.entityGrant(c.workspace(), other, c.user(), 0, true);
        actor(c.user());
        customerAccounts.assign(c.workspace(), other, customer, 1, "OWN-DRAFT-US");
        actor(administrator);
        identity.entityGrant(c.workspace(), other, c.user(), 1, false);
        actor(c.user());
        denied(404, () -> contacts.get(c.workspace(), c.entity(), contact));
        assertFalse(contacts.list(c.workspace(), c.entity(), "OWN-PERSON").stream().anyMatch(person -> person.id() == contact));
        actor(administrator);
        identity.entityGrant(c.workspace(), other, c.user(), 2, true);
        actor(c.user());
        assertEquals(contact, contacts.get(c.workspace(), other, contact).id());
    }

    @Test void workspaceAdminCannotResetAnIdentityOutsideTheirOtherWorkspaceAuthority() {
        var limitedAdmin = context("EUR", "SYSTEM_ADMIN");
        actor(limitedAdmin.user());
        long targetVersion = db.queryForObject("select version from app_users where id=?", Long.class, administrator);
        denied(403, () -> identity.resetPassword(limitedAdmin.workspace(), administrator, targetVersion, UUID.randomUUID().toString()));
        assertEquals(targetVersion, db.queryForObject("select version from app_users where id=?", Long.class, administrator));
    }

    @Test void runtimeCannotPerformDdlEscalateRewriteAuditOrResetSequences() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String owner = db.queryForObject("select r.rolname from pg_namespace n join pg_roles r on r.oid=n.nspowner where n.nspname='app'", String.class);
            assertNotEquals(owner, db.queryForObject("select current_user", String.class));
            for (String statement : List.of("create table app.no_ddl(id integer)", "create schema no_schema", "create function app.no_function() returns integer language sql as 'select 1'", "create role no_role", "set role \"" + owner + "\"", "alter table app.app_users add column no_alter integer", "drop table app.app_users", "update app.audit_events set detail='rewritten'", "delete from app.audit_events", "truncate app.audit_events", "select setval(pg_get_serial_sequence('app.app_users','id'),1)")) sqlDenied(connection, statement);
            try (var sql = connection.createStatement()) {
                sql.execute("grant select on app.app_users to public");
            }
            assertFalse(db.queryForObject("""
                select exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace
                    cross join lateral aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a
                    where n.nspname='app' and c.relname='app_users' and a.grantee=0 and a.privilege_type='SELECT')
                """, Boolean.class), "GRANT without grant option must not confer any effective public read");
        }
    }

    @Test void loginCsrfLogoutAndPasswordRevocationUseActualHttpSessions() throws Exception {
        var c = context("EUR", "INSIDE_SALES");
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build();
        String loginPage = get(client, "/login").body();
        var noCsrf = post(client, "/login", Map.of("username", c.login(), "password", PASSWORD));
        assertEquals(403, noCsrf.statusCode());
        var login = post(client, "/login", Map.of("username", c.login(), "password", PASSWORD, "_csrf", csrf(loginPage)));
        assertEquals(302, login.statusCode());
        var workspaces = get(client, "/workspaces");
        assertEquals(200, workspaces.statusCode());
        assertTrue(workspaces.body().contains("/w/" + c.workspace()));
        String form = get(client, "/w/" + c.workspace() + "/e/" + c.entity() + "/masters/customers/new").body();
        String savePath = "/w/" + c.workspace() + "/e/" + c.entity() + "/masters/customers/save";
        assertEquals(302, post(client, savePath, Map.of("_csrf", csrf(form), "code", "VALID-BEFORE-REVOKE", "name", "Authorized request")).statusCode());
        actor(administrator);
        identity.resetPassword(c.workspace(), c.user(), 1, UUID.randomUUID().toString());
        int auditCount = db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace());
        var stale = post(client, savePath, Map.of("_csrf", csrf(form), "code", "NEVER-COMMITTED", "name", "Stale request"));
        assertEquals(401, stale.statusCode());
        assertEquals(0, db.queryForObject("select count(*) from customers where workspace_id=? and code='NEVER-COMMITTED'", Integer.class, c.workspace()));
        assertEquals(auditCount, db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace()));
        assertEquals(302, get(client, "/workspaces").statusCode());
        String freshPassword = UUID.randomUUID().toString();
        actor(administrator);
        identity.resetPassword(c.workspace(), c.user(), 2, freshPassword);
        String newLogin = get(client, "/login").body();
        assertEquals(302, post(client, "/login", Map.of("username", c.login(), "password", freshPassword, "_csrf", csrf(newLogin))).statusCode());
        String logoutToken = csrf(get(client, "/workspaces").body());
        assertEquals(302, post(client, "/logout", Map.of("_csrf", logoutToken)).statusCode());
        assertEquals(302, get(client, "/workspaces").statusCode());
    }

    @Test void csvExportUsesItsStaticRouteNeutralizesFormulasAndDoesNotLeakAnotherEntity() throws Exception {
        var c = context("EUR", "INSIDE_SALES", "FINANCE", "OPERATIONS", "SALES_MANAGER");
        baseline.initialize(c.workspace(), c.entity(), "O2C_GOODS", 1);
        String privateErp = "PRIVATE-ERP-" + UUID.randomUUID();
        long customer = customers.save(c.workspace(), c.entity(), null, null,
                new CustomerService.Input("=SUM(1,1)", "Synthetic, \"\u03a9\"", "", "", "", null, null));
        customerAccounts.assign(c.workspace(), c.entity(), customer, 1, privateErp);
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build();
        assertEquals(302, post(client, "/login", Map.of("username", c.login(), "password", PASSWORD, "_csrf", csrf(get(client, "/login").body()))).statusCode());
        String base = "/w/" + c.workspace() + "/e/" + c.entity();
        var csv = get(client, base + "/masters/customers/export?q=" + encode("=SUM(1,1)"));
        assertEquals(200, csv.statusCode());
        assertTrue(csv.headers().firstValue("content-type").orElse("").startsWith("text/csv"));
        assertTrue(csv.headers().firstValue("content-disposition").orElse("").contains("attachment;"));
        assertTrue(csv.body().contains("\"'=SUM(1,1)\",\"Synthetic, \"\"\u03a9\"\"\",\"Active\""));
        assertFalse(csv.body().contains(privateErp));
        for (String kind : List.of("contacts", "suppliers", "products", "price-books")) {
            var exported = get(client, base + "/masters/" + kind + "/export");
            assertEquals(200, exported.statusCode(), kind);
            assertTrue(exported.headers().firstValue("content-type").orElse("").startsWith("text/csv"), kind);
        }
        actor(administrator);
        identity.entity(c.workspace(), null, 0, "READER-ENTITY", "Reader isolated entity", "USD", true);
        long other = db.queryForObject("select id from legal_entities where workspace_id=? and code='READER-ENTITY'", Long.class, c.workspace());
        String readerLogin = "csv-reader-" + UUID.randomUUID();
        identity.createUser(c.workspace(), readerLogin, PASSWORD);
        long reader = db.queryForObject("select id from app_users where login=?", Long.class, readerLogin);
        identity.role(c.workspace(), reader, "INSIDE_SALES", 0, true);
        identity.entityGrant(c.workspace(), other, reader, 0, true);
        HttpClient readerClient = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build();
        assertEquals(302, post(readerClient, "/login", Map.of("username", readerLogin, "password", PASSWORD, "_csrf", csrf(get(readerClient, "/login").body()))).statusCode());
        String otherBase = "/w/" + c.workspace() + "/e/" + other;
        var hidden = get(readerClient, otherBase + "/masters/customers/export?q=" + encode("=SUM(1,1)"));
        assertEquals(200, hidden.statusCode());
        assertFalse(hidden.body().contains("=SUM(1,1)"));
        assertFalse(hidden.body().contains(privateErp));
        assertEquals(404, get(readerClient, otherBase + "/masters/customers/" + customer).statusCode());
    }

    @Test void editingInactiveAdministrativeRecordsDoesNotReactivateTheirState() throws Exception {
        var c = context("EUR", "FINANCE");
        actor(administrator);
        identity.entity(c.workspace(), c.entity(), 1, "ENTITY", "Inactive entity", "EUR", false);
        identity.userState(c.workspace(), c.user(), 1, false);
        identity.membership(c.workspace(), c.user(), 1, false);
        identity.role(c.workspace(), c.user(), "FINANCE", 1, false);
        identity.entityGrant(c.workspace(), c.entity(), c.user(), 1, false);
        HttpClient admin = signedIn(administratorLogin);
        String adminPath = "/w/" + c.workspace() + "/admin";
        var page = get(admin, adminPath);
        assertEquals(200, page.statusCode());
        var edit = renderedForm(page.body(), "entity-edit-" + c.entity());
        assertEquals("false", edit.get("active"), "Editing an inactive entity must start with its persisted state.");
        edit.put("name", "Renamed without reactivation");
        assertEquals(302, post(admin, adminPath + "/entity-edit", edit).statusCode());
        assertEquals("Renamed without reactivation", db.queryForObject("select name from legal_entities where id=?", String.class, c.entity()));
        assertFalse(db.queryForObject("select active from legal_entities where id=?", Boolean.class, c.entity()));
        for (var row : List.of(
                Map.entry("user-state-" + c.user(), "user-active"),
                Map.entry("membership-state-" + c.user(), "membership"),
                Map.entry("role-state-" + c.user() + "-FINANCE", "role"),
                Map.entry("entity-grant-" + c.user() + "-" + c.entity(), "entity-grant"))) {
            var current = renderedForm(get(admin, adminPath).body(), row.getKey());
            assertEquals("false", current.get("active"), "The rendered action must not silently restore " + row.getValue());
            assertEquals(302, post(admin, adminPath + "/" + row.getValue(), current).statusCode());
        }
        assertFalse(db.queryForObject("select active from app_users where id=?", Boolean.class, c.user()));
        assertFalse(db.queryForObject("select active from workspace_memberships where workspace_id=? and user_id=?", Boolean.class, c.workspace(), c.user()));
        assertFalse(db.queryForObject("select active from user_roles where workspace_id=? and user_id=? and role_code='FINANCE'", Boolean.class, c.workspace(), c.user()));
        assertFalse(db.queryForObject("select active from entity_memberships where workspace_id=? and entity_id=? and user_id=?", Boolean.class, c.workspace(), c.entity(), c.user()));
        HttpClient disabled = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build();
        var deniedLogin = post(disabled, "/login", Map.of("username", c.login(), "password", PASSWORD, "_csrf", csrf(get(disabled, "/login").body())));
        assertEquals(302, deniedLogin.statusCode());
        assertTrue(deniedLogin.headers().firstValue("location").orElse("").contains("/login?error"));
        assertTrue(get(disabled, "/workspaces").headers().firstValue("location").orElse("").contains("/login"), "The unchanged disabled account still cannot enter the application.");
    }

    @Test void missingBlankAndMalformedRequiredValuesReturn400OnTheOriginalForm() throws Exception {
        var c = context("EUR", "FINANCE");
        HttpClient client = signedIn(c.login());
        String path = "/w/" + c.workspace() + "/e/" + c.entity() + "/finance/periods/new";
        for (String start : List.of("<absent>", "", "not-a-date")) {
            var values = renderedForm(get(client, path).body(), "period-new");
            values.put("code", "RETAIN-CODE");
            values.put("endsOn", "2027-12-31");
            if ("<absent>".equals(start)) values.remove("startsOn"); else values.put("startsOn", start);
            var failed = post(client, path, values);
            assertEquals(400, failed.statusCode(), "Missing, blank and malformed input are client errors, not server failures.");
            var retained = renderedForm(failed.body(), "period-new");
            assertEquals("RETAIN-CODE", retained.get("code"));
            assertEquals("2027-12-31", retained.get("endsOn"));
            assertLinkedFieldError(failed.body(), "period-new-startsOn-0");
        }
        assertEquals(0, db.queryForObject("select count(*) from fiscal_periods where workspace_id=?", Integer.class, c.workspace()));
        assertEquals(0, db.queryForObject("select count(*) from audit_events where workspace_id=? and target_type='PERIOD'", Integer.class, c.workspace()));
    }

    @Test void rejectedPeriodDatesRemainEditableAndOnlyAnExplicitCorrectedPostSaves() throws Exception {
        var c = context("EUR", "FINANCE");
        HttpClient client = signedIn(c.login());
        String path = "/w/" + c.workspace() + "/e/" + c.entity() + "/finance/periods/new";
        var values = renderedForm(get(client, path).body(), "period-new");
        values.put("code", "FIX-DATES");
        values.put("startsOn", "2027-12-31");
        values.put("endsOn", "2027-01-01");
        var failed = post(client, path, values);
        assertEquals(400, failed.statusCode());
        assertLinkedFieldError(failed.body(), "period-new-startsOn-0");
        assertLinkedFieldError(failed.body(), "period-new-endsOn-0");
        var corrected = renderedForm(failed.body(), "period-new");
        assertEquals("2027-12-31", corrected.get("startsOn"));
        assertEquals("2027-01-01", corrected.get("endsOn"));
        assertEquals(0, db.queryForObject("select count(*) from fiscal_periods where workspace_id=?", Integer.class, c.workspace()));
        corrected.put("startsOn", "2027-01-01");
        corrected.put("endsOn", "2027-12-31");
        assertEquals(302, post(client, path, corrected).statusCode());
        assertEquals(1, db.queryForObject("select count(*) from fiscal_periods where workspace_id=? and code='FIX-DATES' and starts_on=date '2027-01-01' and ends_on=date '2027-12-31'", Integer.class, c.workspace()));
        assertEquals(1, db.queryForObject("select count(*) from audit_events where workspace_id=? and target_type='PERIOD'", Integer.class, c.workspace()));
    }

    @Test void failedValidationCannotPromoteAStaleVersionToOverwriteANewerCustomer() throws Exception {
        var c = context("EUR", "INSIDE_SALES");
        long id = customers.save(c.workspace(), c.entity(), null, null, new CustomerService.Input("STALE", "Initial name", "", "", "", null, null));
        HttpClient client = signedIn(c.login());
        String base = "/w/" + c.workspace() + "/e/" + c.entity() + "/masters/customers";
        var stale = renderedForm(get(client, base + "/" + id + "/edit").body(), "customer-save");
        actor(c.user());
        customers.save(c.workspace(), c.entity(), id, 1L, new CustomerService.Input("STALE", "Newer committed name", "", "", "", null, null));
        stale.put("name", "");
        var failed = post(client, base + "/save", stale);
        assertEquals(400, failed.statusCode());
        assertLinkedFieldError(failed.body(), "customer-save-name-0");
        var correction = renderedForm(failed.body(), "customer-save");
        assertEquals("1", correction.get("version"), "Redisplay must retain the submitted optimistic-lock token, not the freshly read database version.");
        assertEquals("", correction.get("name"));
        correction.put("name", "Must not replace the newer value");
        assertEquals(409, post(client, base + "/save", correction).statusCode());
        assertEquals("Newer committed name", db.queryForObject("select name from customers where id=?", String.class, id));
        assertEquals(2L, db.queryForObject("select version from customers where id=?", Long.class, id));
    }

    @Test void invalidPasswordsAreNeverRedisplayedAndErrorsStayOnTheSubmittedAdminForm() throws Exception {
        var c = context("EUR", "INSIDE_SALES");
        HttpClient admin = signedIn(administratorLogin);
        String path = "/w/" + c.workspace() + "/admin";
        var values = renderedForm(get(admin, path).body(), "user-new");
        String login = "not-created-" + UUID.randomUUID();
        String secret = "SHORT-" + UUID.randomUUID().toString().substring(0, 5);
        values.put("login", login);
        values.put("password", secret);
        var failed = post(admin, path + "/create-user", values);
        assertEquals(400, failed.statusCode());
        assertFalse(failed.body().contains(secret));
        assertEquals(login, renderedForm(failed.body(), "user-new").get("login"));
        assertEquals("", renderedForm(failed.body(), "user-new").get("password"));
        assertEquals("", renderedForm(failed.body(), "membership-new").get("login"), "Rejected values must not bleed into another form using the same input name.");
        assertLinkedFieldError(failed.body(), "user-new-password-0");
        assertEquals(0, db.queryForObject("select count(*) from app_users where login=?", Integer.class, login));
        HttpClient reader = signedIn(c.login());
        var forbidden = post(reader, path + "/create-user", Map.of("_csrf", csrf(get(reader, "/workspaces").body()), "_form", "user-new", "login", login, "password", secret));
        assertEquals(403, forbidden.statusCode());
        assertFalse(forbidden.body().contains(administratorLogin), "Recovery must not expose the administrator read model to a non-admin.");
        assertFalse(forbidden.body().contains(secret));
    }

    @Test void customerParentConcurrentVersionPreservesStaleTokenOnValidationFailureAndRejectsStaleResubmit() throws Exception {
        var c = context("EUR", "INSIDE_SALES");
        String pCode = "PARENT-" + UUID.randomUUID().toString().substring(0, 8);
        String pOriginalName = "Parent Initial " + UUID.randomUUID();
        long pId = customers.save(c.workspace(), c.entity(), null, null,
                new CustomerService.Input(pCode, pOriginalName, "BIO", "EU", "parent.example", null, null));
        assertEquals(1L, customers.get(c.workspace(), c.entity(), pId).version());

        String cCode = "CHILD-" + UUID.randomUUID().toString().substring(0, 8);
        String cOriginalName = "Child Initial " + UUID.randomUUID();
        long cId = customers.save(c.workspace(), c.entity(), null, null,
                new CustomerService.Input(cCode, cOriginalName, "BIO", "EU", "child.example", pId, 1L));
        assertEquals(1L, customers.get(c.workspace(), c.entity(), cId).version());
        assertEquals(pId, customers.get(c.workspace(), c.entity(), cId).parentId());

        HttpClient client = signedIn(c.login());
        String base = "/w/" + c.workspace() + "/e/" + c.entity() + "/masters/customers";

        var editPage = get(client, base + "/" + cId + "/edit");
        assertEquals(200, editPage.statusCode());
        var form = renderedForm(editPage.body(), "customer-save");
        String staleToken = pId + ":1";
        assertEquals(staleToken, form.get("parent"));
        assertEquals("1", form.get("version"));
        assertEquals(Long.toString(cId), form.get("id"));

        actor(c.user());
        customers.save(c.workspace(), c.entity(), pId, 1L,
                new CustomerService.Input(pCode, "Parent Updated " + UUID.randomUUID(), "BIO", "EU", "parent.example", null, null));
        assertEquals(2L, customers.get(c.workspace(), c.entity(), pId).version());

        form.put("name", "");
        var failed = post(client, base + "/save", form);
        assertEquals(400, failed.statusCode());
        assertLinkedFieldError(failed.body(), "customer-save-name-0");

        var retained = renderedForm(failed.body(), "customer-save");
        assertEquals(staleToken, retained.get("parent"), "Redisplay must retain the exact submitted parent reference token");
        assertEquals("1", retained.get("version"), "Redisplay must retain the original optimistic lock version");
        assertEquals(Long.toString(cId), retained.get("id"));
        assertEquals("", retained.get("name"));
        assertUnavailableReviewNotice(failed.body(), "parent", staleToken);

        retained.put("name", "Child Corrected " + UUID.randomUUID());
        var conflict = post(client, base + "/save", retained);
        assertEquals(409, conflict.statusCode(), "Submitting with stale parent reference must yield 409 Conflict");

        actor(c.user());
        var dbCustomer = customers.get(c.workspace(), c.entity(), cId);
        assertEquals(pId, dbCustomer.parentId(), "Customer parent relation must remain intact");
        assertEquals(cOriginalName, dbCustomer.name(), "Customer name must not be partially updated");
        assertEquals(1L, dbCustomer.version(), "Customer version must remain unchanged");

        var freshPage = get(client, base + "/" + cId + "/edit");
        assertEquals(200, freshPage.statusCode());
        var freshForm = renderedForm(freshPage.body(), "customer-save");
        assertEquals("1", freshForm.get("version"));
        String currentToken = pId + ":2";
        freshForm.put("parent", currentToken);
        String updatedName = "Child Successfully Saved " + UUID.randomUUID();
        freshForm.put("name", updatedName);
        var saved = post(client, base + "/save", freshForm);
        assertEquals(302, saved.statusCode());

        actor(c.user());
        var savedCustomer = customers.get(c.workspace(), c.entity(), cId);
        assertEquals(pId, savedCustomer.parentId());
        assertEquals(updatedName, savedCustomer.name());
        assertEquals(2L, savedCustomer.version());

        var nonePage = get(client, base + "/" + cId + "/edit");
        assertEquals(200, nonePage.statusCode());
        var noneForm = renderedForm(nonePage.body(), "customer-save");
        assertEquals("2", noneForm.get("version"));
        noneForm.put("parent", "");
        var savedNone = post(client, base + "/save", noneForm);
        assertEquals(302, savedNone.statusCode());

        actor(c.user());
        var clearedCustomer = customers.get(c.workspace(), c.entity(), cId);
        assertNull(clearedCustomer.parentId(), "Customer parent relation must be cleared when None is explicitly submitted");
        assertEquals(3L, clearedCustomer.version());
    }

    @Test void customerPaymentTermsConcurrentVersionPreservesStaleTokenOnValidationFailureAndRejectsStaleResubmit() throws Exception {
        var c = context("EUR", "INSIDE_SALES", "FINANCE");
        String cCode = "CUST-ACC-" + UUID.randomUUID().toString().substring(0, 8);
        long cId = customers.save(c.workspace(), c.entity(), null, null,
                new CustomerService.Input(cCode, "Customer Account Test", "HEALTH", "EU", "account.example", null, null));
        String originalErp = "ERP-C-" + UUID.randomUUID().toString().substring(0, 8);
        customerAccounts.assign(c.workspace(), c.entity(), cId, 1L, originalErp);

        actor(c.user());
        String tCode = "TERM-" + UUID.randomUUID().toString().substring(0, 6);
        long termsId = configuration.save(c.workspace(), c.entity(), ConfigurationService.Kind.TERMS, null, null, tCode, "Net 30 Initial", 30, null);
        assertEquals(1L, configuration.get(c.workspace(), c.entity(), ConfigurationService.Kind.TERMS, termsId).version());

        actor(c.user());
        customerAccounts.save(c.workspace(), c.entity(), cId, 1L, 1L, originalErp, "APPROVED", termsId, 1L, true);
        var initialAccount = customerAccounts.get(c.workspace(), c.entity(), cId);
        assertEquals(2L, initialAccount.version());
        assertEquals(termsId, initialAccount.termsId());

        HttpClient client = signedIn(c.login());
        String detailPath = "/w/" + c.workspace() + "/e/" + c.entity() + "/masters/customers/" + cId;

        var page = get(client, detailPath);
        assertEquals(200, page.statusCode());
        var form = renderedForm(page.body(), "customer-account");
        String staleToken = termsId + ":1";
        assertEquals(staleToken, form.get("terms"));
        assertEquals("1", form.get("customerVersion"));
        assertEquals("2", form.get("accountVersion"));
        assertEquals(originalErp, form.get("erpCode"));
        assertEquals("APPROVED", form.get("credit"));
        assertEquals("true", form.get("active"));

        actor(c.user());
        configuration.save(c.workspace(), c.entity(), ConfigurationService.Kind.TERMS, termsId, 1L, tCode, "Net 30 Extended", 35, null);
        assertEquals(2L, configuration.get(c.workspace(), c.entity(), ConfigurationService.Kind.TERMS, termsId).version());

        form.put("erpCode", "");
        var failed = post(client, detailPath + "/account", form);
        assertEquals(400, failed.statusCode());
        assertLinkedFieldError(failed.body(), "customer-account-erpCode-0");

        var retained = renderedForm(failed.body(), "customer-account");
        assertEquals(staleToken, retained.get("terms"), "Redisplay must retain the exact submitted payment terms token");
        assertEquals("1", retained.get("customerVersion"), "Redisplay must retain the submitted customer version");
        assertEquals("2", retained.get("accountVersion"), "Redisplay must retain the submitted account version");
        assertEquals("", retained.get("erpCode"));
        assertEquals("APPROVED", retained.get("credit"));
        assertEquals("true", retained.get("active"));
        assertUnavailableReviewNotice(failed.body(), "terms", staleToken);

        retained.put("erpCode", "ERP-C-FIXED");
        var conflict = post(client, detailPath + "/account", retained);
        assertEquals(409, conflict.statusCode(), "Submitting with stale payment terms reference must yield 409 Conflict");

        actor(c.user());
        var dbAccount = customerAccounts.get(c.workspace(), c.entity(), cId);
        assertEquals(originalErp, dbAccount.erpCode(), "ERP code must remain unchanged");
        assertEquals(termsId, dbAccount.termsId(), "Terms reference must remain unchanged");
        assertEquals(2L, dbAccount.version(), "Account version must remain unchanged");
        assertEquals(1L, customers.get(c.workspace(), c.entity(), cId).version(), "Customer version must remain unchanged");

        var freshPage = get(client, detailPath);
        assertEquals(200, freshPage.statusCode());
        var freshForm = renderedForm(freshPage.body(), "customer-account");
        assertEquals("1", freshForm.get("customerVersion"));
        assertEquals("2", freshForm.get("accountVersion"));
        String currentToken = termsId + ":2";
        freshForm.put("terms", currentToken);
        freshForm.put("erpCode", "ERP-C-UPDATED");
        var saved = post(client, detailPath + "/account", freshForm);
        assertEquals(302, saved.statusCode());

        actor(c.user());
        var updatedAccount = customerAccounts.get(c.workspace(), c.entity(), cId);
        assertEquals("ERP-C-UPDATED", updatedAccount.erpCode());
        assertEquals(termsId, updatedAccount.termsId());
        assertEquals(3L, updatedAccount.version());

        var nonePage = get(client, detailPath);
        assertEquals(200, nonePage.statusCode());
        var noneForm = renderedForm(nonePage.body(), "customer-account");
        assertEquals("3", noneForm.get("accountVersion"));
        noneForm.put("terms", "");
        var savedNone = post(client, detailPath + "/account", noneForm);
        assertEquals(302, savedNone.statusCode());

        actor(c.user());
        var clearedAccount = customerAccounts.get(c.workspace(), c.entity(), cId);
        assertNull(clearedAccount.termsId(), "Customer terms must be cleared when None is explicitly submitted");
        assertEquals(4L, clearedAccount.version());
    }

    @Test void supplierPaymentTermsConcurrentVersionPreservesStaleTokenOnValidationFailureAndRejectsStaleResubmit() throws Exception {
        var c = context("EUR", "OPERATIONS", "FINANCE");
        String sCode = "SUP-" + UUID.randomUUID().toString().substring(0, 8);
        long sId = suppliers.save(c.workspace(), c.entity(), null, null, sCode, "Supplier Account Test", "sup-acc@example.example");
        String originalErp = "ERP-S-" + UUID.randomUUID().toString().substring(0, 8);
        suppliers.assign(c.workspace(), c.entity(), sId, 1L, originalErp);

        actor(c.user());
        String tCode = "TERM-S-" + UUID.randomUUID().toString().substring(0, 5);
        long termsId = configuration.save(c.workspace(), c.entity(), ConfigurationService.Kind.TERMS, null, null, tCode, "Net 45 Initial", 45, null);
        assertEquals(1L, configuration.get(c.workspace(), c.entity(), ConfigurationService.Kind.TERMS, termsId).version());

        actor(c.user());
        suppliers.saveAccount(c.workspace(), c.entity(), sId, 1L, 1L, originalErp, termsId, 1L, true);
        var initialAccount = suppliers.account(c.workspace(), c.entity(), sId);
        assertEquals(2L, initialAccount.version());
        assertEquals(termsId, initialAccount.termsId());

        HttpClient client = signedIn(c.login());
        String detailPath = "/w/" + c.workspace() + "/e/" + c.entity() + "/masters/suppliers/" + sId;

        var page = get(client, detailPath);
        assertEquals(200, page.statusCode());
        var form = renderedForm(page.body(), "supplier-account");
        String staleToken = termsId + ":1";
        assertEquals(staleToken, form.get("terms"));
        assertEquals("1", form.get("supplierVersion"));
        assertEquals("2", form.get("accountVersion"));
        assertEquals(originalErp, form.get("erpCode"));
        assertEquals("true", form.get("active"));

        actor(c.user());
        configuration.save(c.workspace(), c.entity(), ConfigurationService.Kind.TERMS, termsId, 1L, tCode, "Net 45 Extended", 50, null);
        assertEquals(2L, configuration.get(c.workspace(), c.entity(), ConfigurationService.Kind.TERMS, termsId).version());

        form.put("erpCode", "");
        var failed = post(client, detailPath + "/account", form);
        assertEquals(400, failed.statusCode());
        assertLinkedFieldError(failed.body(), "supplier-account-erpCode-0");

        var retained = renderedForm(failed.body(), "supplier-account");
        assertEquals(staleToken, retained.get("terms"), "Redisplay must retain the exact submitted supplier payment terms token");
        assertEquals("1", retained.get("supplierVersion"), "Redisplay must retain the submitted supplier version");
        assertEquals("2", retained.get("accountVersion"), "Redisplay must retain the submitted account version");
        assertEquals("", retained.get("erpCode"));
        assertEquals("true", retained.get("active"));
        assertUnavailableReviewNotice(failed.body(), "terms", staleToken);

        retained.put("erpCode", "ERP-S-FIXED");
        var conflict = post(client, detailPath + "/account", retained);
        assertEquals(409, conflict.statusCode(), "Submitting with stale supplier terms reference must yield 409 Conflict");

        actor(c.user());
        var dbAccount = suppliers.account(c.workspace(), c.entity(), sId);
        assertEquals(originalErp, dbAccount.erpCode(), "ERP code must remain unchanged");
        assertEquals(termsId, dbAccount.termsId(), "Terms reference must remain unchanged");
        assertEquals(2L, dbAccount.version(), "Account version must remain unchanged");
        assertEquals(1L, suppliers.get(c.workspace(), c.entity(), sId).version(), "Supplier version must remain unchanged");

        var freshPage = get(client, detailPath);
        assertEquals(200, freshPage.statusCode());
        var freshForm = renderedForm(freshPage.body(), "supplier-account");
        assertEquals("1", freshForm.get("supplierVersion"));
        assertEquals("2", freshForm.get("accountVersion"));
        String currentToken = termsId + ":2";
        freshForm.put("terms", currentToken);
        freshForm.put("erpCode", "ERP-S-UPDATED");
        var saved = post(client, detailPath + "/account", freshForm);
        assertEquals(302, saved.statusCode());

        actor(c.user());
        var updatedAccount = suppliers.account(c.workspace(), c.entity(), sId);
        assertEquals("ERP-S-UPDATED", updatedAccount.erpCode());
        assertEquals(termsId, updatedAccount.termsId());
        assertEquals(3L, updatedAccount.version());

        var nonePage = get(client, detailPath);
        assertEquals(200, nonePage.statusCode());
        var noneForm = renderedForm(nonePage.body(), "supplier-account");
        assertEquals("3", noneForm.get("accountVersion"));
        noneForm.put("terms", "");
        var savedNone = post(client, detailPath + "/account", noneForm);
        assertEquals(302, savedNone.statusCode());

        actor(c.user());
        var clearedAccount = suppliers.account(c.workspace(), c.entity(), sId);
        assertNull(clearedAccount.termsId(), "Supplier terms must be cleared when None is explicitly submitted");
        assertEquals(4L, clearedAccount.version());
    }

    @Test void existingPriceLineRetainsStaleProductAndInvalidAmountUntilExplicitRefresh() throws Exception {
        var c = context("EUR", "OPERATIONS", "SALES_MANAGER");
        long unit = configuration.save(c.workspace(), c.entity(), ConfigurationService.Kind.UOM,
                null, null, "EA", "Each", null, 0);
        long product = products.save(c.workspace(), c.entity(), null, null,
                new ProductService.Input("LINE-A", "Original product", "LAB_SUPPLY", "TRANSFER", unit, 1, "NONE"));
        long otherProduct = products.save(c.workspace(), c.entity(), null, null,
                new ProductService.Input("LINE-B", "Unaffected product", "LAB_SUPPLY", "TRANSFER", unit, 1, "NONE"));
        products.assign(c.workspace(), c.entity(), product, 1, null, true);
        products.assign(c.workspace(), c.entity(), otherProduct, 1, null, true);
        long book = priceBooks.save(c.workspace(), c.entity(), null, null,
                "INPUT-BOOK", "User price book", "EUR", DAY, DAY.plusYears(1));
        priceBooks.line(c.workspace(), c.entity(), book, 1, product, 1, null, new BigDecimal("12.50"));
        priceBooks.line(c.workspace(), c.entity(), book, 2, otherProduct, 1, null, new BigDecimal("6.25"));

        HttpClient client = signedIn(c.login());
        String detailPath = "/w/" + c.workspace() + "/e/" + c.entity() + "/masters/price-books/" + book;
        String formId = "price-line-" + product;
        var page = get(client, detailPath);
        assertEquals(200, page.statusCode());
        var form = renderedForm(page.body(), formId);
        assertEquals(product + ":1", form.get("product"));
        assertEquals("3", form.get("bookVersion"));
        assertEquals("1", form.get("lineVersion"));

        actor(c.user());
        products.save(c.workspace(), c.entity(), product, 1L,
                new ProductService.Input("LINE-A", "Concurrently updated product", "LAB_SUPPLY", "TRANSFER", unit, 1, "NONE"));
        var beforeBook = priceBooks.get(c.workspace(), c.entity(), book);
        var beforeLines = priceBooks.lines(c.workspace(), c.entity(), book);
        int beforeAudits = db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace());
        String invalidAmount = "10000000000000000";
        form.put("amount", invalidAmount);
        var failed = post(client, detailPath + "/line", form);
        assertEquals(400, failed.statusCode());
        assertLinkedFieldError(failed.body(), formId + "-amount-0");
        var retained = renderedForm(failed.body(), formId);
        assertEquals(product + ":1", retained.get("product"), "Validation must not refresh a stale hidden product reference");
        assertEquals(invalidAmount, retained.get("amount"));
        assertEquals("3", retained.get("bookVersion"));
        assertEquals("1", retained.get("lineVersion"));
        var unaffected = renderedForm(failed.body(), "price-line-" + otherProduct);
        assertEquals(otherProduct + ":1", unaffected.get("product"));
        assertEquals("6.25", unaffected.get("amount"));

        actor(c.user());
        assertEquals(beforeBook, priceBooks.get(c.workspace(), c.entity(), book));
        assertEquals(beforeLines, priceBooks.lines(c.workspace(), c.entity(), book));
        assertEquals(beforeAudits, db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace()));
        retained.put("amount", "17.25");
        assertEquals(409, post(client, detailPath + "/line", retained).statusCode());
        actor(c.user());
        assertEquals(beforeBook, priceBooks.get(c.workspace(), c.entity(), book));
        assertEquals(beforeLines, priceBooks.lines(c.workspace(), c.entity(), book));
        assertEquals(beforeAudits, db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace()));

        var refreshed = get(client, detailPath);
        assertEquals(200, refreshed.statusCode());
        var explicit = renderedForm(refreshed.body(), formId);
        assertEquals(product + ":2", explicit.get("product"));
        explicit.put("amount", "17.25");
        assertEquals(302, post(client, detailPath + "/line", explicit).statusCode());
        actor(c.user());
        var actual = priceBooks.lines(c.workspace(), c.entity(), book);
        var changed = actual.stream().filter(line -> line.productId() == product).findFirst().orElseThrow();
        var unchanged = actual.stream().filter(line -> line.productId() == otherProduct).findFirst().orElseThrow();
        assertEquals(new BigDecimal("17.25"), changed.amount());
        assertEquals(2L, changed.version());
        assertEquals(new BigDecimal("6.25"), unchanged.amount());
        assertEquals(1L, unchanged.version());
        assertEquals(4L, priceBooks.get(c.workspace(), c.entity(), book).version());
        assertEquals(beforeAudits + 1, db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace()));
    }

    @Test void existingPriceLineRetainsRejectedInputWhenItsProductIsDeactivated() throws Exception {
        var c = context("EUR", "OPERATIONS", "SALES_MANAGER");
        long unit = configuration.save(c.workspace(), c.entity(), ConfigurationService.Kind.UOM,
                null, null, "EA", "Each", null, 0);
        long product = products.save(c.workspace(), c.entity(), null, null,
                new ProductService.Input("INACTIVE-LINE", "Historical line product", "LAB_SUPPLY", "TRANSFER", unit, 1, "NONE"));
        products.assign(c.workspace(), c.entity(), product, 1, null, true);
        long book = priceBooks.save(c.workspace(), c.entity(), null, null,
                "INACTIVE-BOOK", "Existing product deactivation", "EUR", DAY, DAY.plusYears(1));
        priceBooks.line(c.workspace(), c.entity(), book, 1, product, 1, null, new BigDecimal("12.50"));
        HttpClient client = signedIn(c.login());
        String path = "/w/" + c.workspace() + "/e/" + c.entity() + "/masters/price-books/" + book;
        String formId = "price-line-" + product;
        var initial = get(client, path);
        assertEquals(200, initial.statusCode());
        var submitted = renderedForm(initial.body(), formId);

        actor(c.user());
        products.deactivate(c.workspace(), c.entity(), product, 1);
        var beforeBook = priceBooks.get(c.workspace(), c.entity(), book);
        var beforeLines = priceBooks.lines(c.workspace(), c.entity(), book);
        int beforeAudits = db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace());
        submitted.put("amount", "10000000000000000");
        var failed = post(client, path + "/line", submitted);
        assertEquals(400, failed.statusCode());
        assertLinkedFieldError(failed.body(), formId + "-amount-0");
        var retained = renderedForm(failed.body(), formId);
        assertEquals(product + ":1", retained.get("product"));
        assertEquals("2", retained.get("bookVersion"));
        assertEquals("1", retained.get("lineVersion"));
        assertEquals("10000000000000000", retained.get("amount"));
        retained.put("amount", "17.25");
        assertEquals(409, post(client, path + "/line", retained).statusCode());
        actor(c.user());
        assertEquals(beforeBook, priceBooks.get(c.workspace(), c.entity(), book));
        assertEquals(beforeLines, priceBooks.lines(c.workspace(), c.entity(), book));
        assertEquals(beforeAudits, db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace()));
        assertFalse(products.get(c.workspace(), c.entity(), product).active(), "Recovery must not reactivate the product");
        var refreshed = get(client, path);
        assertEquals(200, refreshed.statusCode());
        assertThrows(AssertionError.class, () -> renderedForm(refreshed.body(), formId),
                "A fresh page must not offer an editable price line for an unavailable product");
        assertEquals(new BigDecimal("12.50"), priceBooks.lines(c.workspace(), c.entity(), book).get(0).amount());
    }

    @Test void foreignOrUnavailableReferenceTokenDoesNotLeakMetadataAndCannotBypassAuthorization() throws Exception {
        var c1 = context("EUR", "INSIDE_SALES");
        var c2 = context("USD", "INSIDE_SALES");

        String confidentialName = "Confidential Parent " + UUID.randomUUID();
        long foreignParentId = customers.save(c2.workspace(), c2.entity(), null, null,
                new CustomerService.Input("FOREIGN-P", confidentialName, "SECRET", "US", "confidential.example", null, null));
        assertEquals(1L, customers.get(c2.workspace(), c2.entity(), foreignParentId).version());

        actor(c1.user());
        String localCustomerName = "Local Customer " + UUID.randomUUID();
        long customerId = customers.save(c1.workspace(), c1.entity(), null, null,
                new CustomerService.Input("LOCAL-C", localCustomerName, "COMMERCIAL", "EU", "local.example", null, null));
        assertEquals(1L, customers.get(c1.workspace(), c1.entity(), customerId).version());

        HttpClient client = signedIn(c1.login());
        String base = "/w/" + c1.workspace() + "/e/" + c1.entity() + "/masters/customers";
        var editPage = get(client, base + "/" + customerId + "/edit");
        assertEquals(200, editPage.statusCode());
        var form = renderedForm(editPage.body(), "customer-save");

        String foreignToken = foreignParentId + ":1";
        form.put("parent", foreignToken);
        form.put("name", "");

        var failed = post(client, base + "/save", form);
        assertEquals(400, failed.statusCode());
        assertLinkedFieldError(failed.body(), "customer-save-name-0");

        var retained = renderedForm(failed.body(), "customer-save");
        assertEquals(foreignToken, retained.get("parent"), "Redisplay must retain the exact submitted foreign token");
        assertEquals("1", retained.get("version"));
        assertEquals(Long.toString(customerId), retained.get("id"));

        assertFalse(failed.body().contains(confidentialName), "Foreign entity name must never leak in redisplayed form");
        assertUnavailableReviewNotice(failed.body(), "parent", foreignToken);

        retained.put("name", "Fixed Local Name");
        var denied = post(client, base + "/save", retained);
        assertEquals(404, denied.statusCode(), "Foreign reference must be rejected without granting authority or linking");

        actor(c1.user());
        var dbCustomer = customers.get(c1.workspace(), c1.entity(), customerId);
        assertNull(dbCustomer.parentId(), "Foreign customer must not become parent");
        assertEquals(localCustomerName, dbCustomer.name(), "Customer name must remain unchanged");
        assertEquals(1L, dbCustomer.version(), "Customer version must remain 1");
    }

    @Test void concurrentBusinessPostingAndReversalHaveOneEffectAndRejectChangedIntent() throws Exception {
        var c = context("EUR", "FINANCE");
        baseline.initialize(c.workspace(), c.entity(), "O2C_GOODS", 1);
        long bank = db.queryForObject("select id from chart_accounts where workspace_id=? and entity_id=? and code='BANK'", Long.class, c.workspace(), c.entity());
        long equity = db.queryForObject("select id from chart_accounts where workspace_id=? and entity_id=? and code='EQUITY'", Long.class, c.workspace(), c.entity());
        var lines = List.of(new Ledger.Line(bank, new BigDecimal("17.25"), BigDecimal.ZERO),
                new Ledger.Line(equity, BigDecimal.ZERO, new BigDecimal("17.25")));
        var transaction = new TransactionTemplate(transactions);
        List<PostingService.Result> posted;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<PostingService.Result> post = () -> {
                actor(c.user());
                try {
                    return transaction.execute(ignored -> businessPosting.post(
                            access.requireContext(c.workspace(), c.entity(), true, "FINANCE"),
                            "SYNTHETIC_TEST", "source-1", "BUSINESS-INTENT-1", DAY, "EUR", "Explicit synthetic intent", lines));
                } finally { SecurityContextHolder.clearContext(); }
            };
            posted = new ArrayList<>();
            for (var future : executor.invokeAll(List.of(post, post))) posted.add(future.get());
        }
        assertEquals(posted.get(0).journalId(), posted.get(1).journalId());
        assertEquals(1, posted.stream().filter(result -> !result.replayed()).count());
        assertEquals(new BigDecimal("17.25"), db.queryForObject(
                "select sum(debit) from journal_lines where journal_id=?", BigDecimal.class, posted.get(0).journalId()));
        denied(409, () -> transaction.execute(ignored -> businessPosting.post(
                access.requireContext(c.workspace(), c.entity(), true, "FINANCE"),
                "SYNTHETIC_TEST", "source-1", "BUSINESS-INTENT-1", DAY, "EUR", "Changed intent", lines)));
        long journal = posted.get(0).journalId();
        var reverse = transaction.execute(ignored -> businessPosting.reverse(
                access.requireContext(c.workspace(), c.entity(), true, "FINANCE"),
                journal, "REVERSE-INTENT-1", DAY.plusDays(1), "Whole original reversal"));
        assertEquals(reverse.journalId(), transaction.execute(ignored -> businessPosting.reverse(
                access.requireContext(c.workspace(), c.entity(), true, "FINANCE"),
                journal, "REVERSE-INTENT-1", DAY.plusDays(1), "Whole original reversal")).journalId());
        denied(409, () -> transaction.execute(ignored -> businessPosting.reverse(
                access.requireContext(c.workspace(), c.entity(), true, "FINANCE"),
                journal, "REVERSE-INTENT-2", DAY.plusDays(1), "Duplicate reversal")));
        assertEquals(new BigDecimal("0.00"), db.queryForObject(
                "select sum(debit-credit) from journal_lines where account_id=? and journal_id in (?,?)",
                BigDecimal.class, bank, journal, reverse.journalId()));
        assertEquals(1, db.queryForObject("select count(*) from journal_entries where reverse_of=?", Integer.class, journal));
    }

    @Test void zeroValueBusinessEffectRequiresAnOpenPeriodWithoutCreatingZeroJournal() {
        var c = context("EUR", "FINANCE");
        baseline.initialize(c.workspace(), c.entity(), "O2C_GOODS", 1);
        int before = db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace());
        var transaction = new TransactionTemplate(transactions);
        var zero = transaction.execute(ignored -> businessPosting.post(
                access.requireContext(c.workspace(), c.entity(), true, "FINANCE"),
                "SYNTHETIC_TEST", "zero-source", "ZERO-INTENT", DAY, "EUR", "Explicit zero effect", List.of()));
        assertNull(zero.journalId());
        assertEquals(before, db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace()));
        db.update("update fiscal_periods set status='CLOSED',version=version+1 where workspace_id=? and entity_id=?", c.workspace(), c.entity());
        denied(409, () -> transaction.execute(ignored -> businessPosting.post(
                access.requireContext(c.workspace(), c.entity(), true, "FINANCE"),
                "SYNTHETIC_TEST", "zero-source-closed", "ZERO-CLOSED", DAY, "EUR", "Must not bypass period", List.of())));
        assertEquals(before, db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace()));
    }

    @Test void warehouseTransferPreservesPoolValuePostsOnceAndPreventsBackdating() throws Exception {
        var c = context("EUR", "FINANCE", "OPERATIONS");
        baseline.initialize(c.workspace(), c.entity(), "O2C_GOODS", 1);
        var source = db.queryForMap("""
                select p.id,p.version,p.uom_id,u.version as uom_version,s.version as setting_version,
                       b.warehouse_id,w.version as warehouse_version,b.on_hand,l.id as lot_id,l.lot
                  from products p
                  join product_entity_settings s on s.workspace_id=p.workspace_id and s.product_id=p.id and s.entity_id=?
                  join uoms u on u.workspace_id=p.workspace_id and u.id=p.uom_id
                  join inventory_balances b on b.workspace_id=p.workspace_id and b.entity_id=s.entity_id and b.product_id=p.id
                  join warehouses w on w.workspace_id=b.workspace_id and w.entity_id=b.entity_id and w.id=b.warehouse_id
                  join inventory_lots l on l.workspace_id=b.workspace_id and l.entity_id=b.entity_id and l.product_id=b.product_id and l.warehouse_id=b.warehouse_id
                 where p.workspace_id=? and p.tracking='LOT' and b.on_hand-b.reserved>=2 order by p.id limit 1
                """, c.entity(), c.workspace());
        long product = ((Number) source.get("id")).longValue();
        long from = ((Number) source.get("warehouse_id")).longValue();
        long to = configuration.save(c.workspace(), c.entity(), ConfigurationService.Kind.WAREHOUSE,
                null, null, "TRANSFER-DESTINATION", "Synthetic transfer destination", null, null);
        var before = db.queryForMap("select quantity,value,pool_sequence from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?",
                c.workspace(), c.entity(), product);
        int journals = db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace());
        var input = new InventoryService.ActionInput("TRANSFER-ONE", "TRANSFER", product,
                ((Number) source.get("version")).longValue(), ((Number) source.get("uom_id")).longValue(),
                ((Number) source.get("uom_version")).longValue(), ((Number) source.get("setting_version")).longValue(),
                from, ((Number) source.get("warehouse_version")).longValue(), to, 1L, new BigDecimal("2"),
                null, DAY.plusDays(1), "Explicit synthetic warehouse transfer",
                List.of(new InventoryService.TrackingInput(null, (String) source.get("lot"), null, new BigDecimal("2"))));
        long action = inventory.saveAction(c.workspace(), c.entity(), null, null, input);
        long version = inventory.action(c.workspace(), c.entity(), action).version();
        List<InventoryService.ActionResult> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<InventoryService.ActionResult> post = () -> {
                actor(c.user());
                try { return inventory.postAction(c.workspace(), c.entity(), action, version); }
                finally { SecurityContextHolder.clearContext(); }
            };
            for (var future : executor.invokeAll(List.of(post, post))) results.add(future.get());
        }
        assertEquals(1, results.stream().filter(result -> !result.replayed()).count());
        assertTrue(results.stream().allMatch(result -> result.journalId() == null));
        var after = db.queryForMap("select quantity,value,pool_sequence,last_posting_date from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?",
                c.workspace(), c.entity(), product);
        assertEquals(before.get("quantity"), after.get("quantity"));
        assertEquals(before.get("value"), after.get("value"));
        assertEquals(((Number) before.get("pool_sequence")).longValue()+1, ((Number) after.get("pool_sequence")).longValue());
        assertEquals(DAY.plusDays(1), db.queryForObject("select last_posting_date from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?",
                LocalDate.class, c.workspace(), c.entity(), product));
        assertEquals(new BigDecimal("2.000000"), db.queryForObject("select on_hand from inventory_balances where workspace_id=? and entity_id=? and product_id=? and warehouse_id=?",
                BigDecimal.class, c.workspace(), c.entity(), product, to));
        assertEquals(((BigDecimal) source.get("on_hand")).subtract(new BigDecimal("2")), db.queryForObject(
                "select on_hand from inventory_balances where workspace_id=? and entity_id=? and product_id=? and warehouse_id=?",
                BigDecimal.class, c.workspace(), c.entity(), product, from));
        assertEquals(new BigDecimal("2.000000"), db.queryForObject(
                "select quantity from inventory_lot_balances where workspace_id=? and entity_id=? and lot_id=? and warehouse_id=?",
                BigDecimal.class, c.workspace(), c.entity(), ((Number) source.get("lot_id")).longValue(), to));
        assertEquals(new BigDecimal("0.000000"), db.queryForObject(
                "select sum(quantity_delta) from stock_movements where workspace_id=? and entity_id=? and source_type='INVENTORY_ACTION' and source_id=?",
                BigDecimal.class, c.workspace(), c.entity(), Long.toString(action)));
        assertEquals(new BigDecimal("0.00"), db.queryForObject(
                "select sum(value_delta) from stock_movements where workspace_id=? and entity_id=? and source_type='INVENTORY_ACTION' and source_id=?",
                BigDecimal.class, c.workspace(), c.entity(), Long.toString(action)));
        assertEquals(2, db.queryForObject(
                "select count(*) from stock_movements where workspace_id=? and entity_id=? and source_type='INVENTORY_ACTION' and source_id=?",
                Integer.class, c.workspace(), c.entity(), Long.toString(action)));
        assertEquals(journals, db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace()));
        var backdated = new InventoryService.ActionInput("TRANSFER-BACKDATED", "TRANSFER", input.productId(),
                input.productVersion(), input.uomId(), input.uomVersion(), input.settingVersion(),
                input.fromWarehouseId(), input.fromWarehouseVersion(), input.toWarehouseId(), input.toWarehouseVersion(),
                BigDecimal.ONE, null, DAY, "Must not cross pool watermark",
                List.of(new InventoryService.TrackingInput(null, (String) source.get("lot"), null, BigDecimal.ONE)));
        long rejected = inventory.saveAction(c.workspace(), c.entity(), null, null, backdated);
        denied(409, () -> inventory.postAction(c.workspace(), c.entity(), rejected, 1));
        assertEquals("DRAFT", inventory.action(c.workspace(), c.entity(), rejected).status());
        assertEquals(0, db.queryForObject("select count(*) from stock_movements where workspace_id=? and entity_id=? and source_type='INVENTORY_ACTION' and source_id=?",
                Integer.class, c.workspace(), c.entity(), Long.toString(rejected)));
    }

    @Test void arbitraryZeroValueStockRequiresIndependentCheckerAndOperationsCannotSeeValuation() {
        var c = context("EUR", "FINANCE", "OPERATIONS");
        baseline.initialize(c.workspace(), c.entity(), "O2C_GOODS", 1);
        var unit = configuration.list(c.workspace(), c.entity(), ConfigurationService.Kind.UOM, "").get(0);
        var warehouse = configuration.list(c.workspace(), c.entity(), ConfigurationService.Kind.WAREHOUSE, "").get(0);
        long product = products.save(c.workspace(), c.entity(), null, null,
                new ProductService.Input("ARBITRARY-" + UUID.randomUUID(), "Synthetic new component",
                        "REPLACEMENT_PART", "TRANSFER", unit.id(), unit.version(), "NONE"));
        products.assign(c.workspace(), c.entity(), product, 1, null, true);
        var setting = products.setting(c.workspace(), c.entity(), product);
        int journals = db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace());
        var input = new InventoryService.ActionInput("ZERO-POSITIVE", "POSITIVE_ADJUSTMENT", product, 1,
                unit.id(), unit.version(), setting.version(), null, null, warehouse.id(), warehouse.version(),
                new BigDecimal("3"), new BigDecimal("0.00"), DAY.plusDays(1), "Approved zero-cost synthetic stock", List.of());
        long action = inventory.saveAction(c.workspace(), c.entity(), null, null, input);
        var editor = peer(c, "OPERATIONS", "FINANCE");
        inventory.saveAction(c.workspace(), c.entity(), action, 1L, input);
        actor(c.user());
        inventory.submitAction(c.workspace(), c.entity(), action, 2);
        denied(403, () -> inventory.postAction(c.workspace(), c.entity(), action, 3));
        actor(editor.user());
        denied(403, () -> inventory.postAction(c.workspace(), c.entity(), action, 3));
        var checker = peer(c, "FINANCE");
        var result = inventory.postAction(c.workspace(), c.entity(), action, 3);
        assertNull(result.journalId());
        assertFalse(result.replayed());
        assertTrue(inventory.postAction(c.workspace(), c.entity(), action, 3).replayed());
        assertEquals(checker.user(), db.queryForObject("select approved_by from inventory_actions where id=?", Long.class, action));
        assertEquals(new BigDecimal("3.000000"), db.queryForObject(
                "select quantity from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?",
                BigDecimal.class, c.workspace(), c.entity(), product));
        assertEquals(new BigDecimal("0.00"), db.queryForObject(
                "select value from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?",
                BigDecimal.class, c.workspace(), c.entity(), product));
        assertEquals(journals, db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace()));
        var operations = peer(c, "OPERATIONS");
        var visibleBalance = inventory.balances(c.workspace(), c.entity(), "ARBITRARY-").stream()
                .filter(row -> row.productId() == product && row.warehouseId() == warehouse.id()).findFirst().orElseThrow();
        assertEquals(new BigDecimal("3.000000"), visibleBalance.onHand());
        assertNull(visibleBalance.poolValue());
        assertNull(inventory.movements(c.workspace(), c.entity(), product).get(0).value());
        var issue = new InventoryService.ActionInput("ZERO-ISSUE", "NEGATIVE_ADJUSTMENT", product, 1,
                unit.id(), unit.version(), setting.version(), warehouse.id(), warehouse.version(), null, null,
                BigDecimal.ONE, null, DAY.plusDays(1), "Synthetic operational stock adjustment", List.of());
        long outgoing = inventory.saveAction(c.workspace(), c.entity(), null, null, issue);
        assertNull(inventory.postAction(c.workspace(), c.entity(), outgoing, 1).journalId());
        assertEquals(operations.user(), db.queryForObject("select posted_by from inventory_actions where id=?", Long.class, outgoing));
        assertEquals(new BigDecimal("2.000000"), db.queryForObject(
                "select quantity from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?",
                BigDecimal.class, c.workspace(), c.entity(), product));
        assertEquals(journals, db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace()));
    }

    @Test void supplierMismatchCannotConsumeEntitlementAndCashCorrectionsRestoreAsOfBalances() {
        var fixture = purchase(new BigDecimal("10"), new BigDecimal("55.00"), null, null);
        var c = fixture.creator();
        var actual = new VendorInvoiceService.Line("ACTUAL-1", fixture.receiptLine(), new BigDecimal("10"),
                new BigDecimal("55.00"), null, null, new BigDecimal("550.00"), new BigDecimal("0.00"),
                new BigDecimal("550.00"), new BigDecimal("38.50"), new BigDecimal("588.51"));
        int before = db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace());
        long invoice = vendorInvoices.save(c.workspace(), c.entity(), null, 0,
                new VendorInvoiceService.Draft("Synthetic Supplier Ref", DAY.plusDays(2), 1, 1, List.of(actual)));
        assertTrue(vendorInvoices.post(c.workspace(), c.entity(), invoice, 1).held());
        assertEquals("HELD_FOR_REVIEW", vendorInvoices.detail(c.workspace(), c.entity(), invoice).get("status"));
        assertEquals(0, db.queryForObject("select count(*) from invoice_source_allocations where workspace_id=? and entity_id=?", Integer.class, c.workspace(), c.entity()));
        assertEquals(before, db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace()));
        assertEquals(new BigDecimal("550.00"), db.queryForObject(
                "select value from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?",
                BigDecimal.class, c.workspace(), c.entity(), fixture.product()));
        var corrected = new VendorInvoiceService.Line("ACTUAL-1", fixture.receiptLine(), new BigDecimal("10"),
                new BigDecimal("55.00"), null, null, new BigDecimal("550.00"), new BigDecimal("0.00"),
                new BigDecimal("550.00"), new BigDecimal("38.50"), new BigDecimal("588.50"));
        vendorInvoices.save(c.workspace(), c.entity(), invoice, 2,
                new VendorInvoiceService.Draft("Synthetic Supplier Ref", DAY.plusDays(2), 1, 1, List.of(corrected)));
        assertFalse(vendorInvoices.post(c.workspace(), c.entity(), invoice, 3).held());
        assertTrue(vendorInvoices.post(c.workspace(), c.entity(), invoice, 3).replayed());
        assertEquals(DAY.plusDays(32), db.queryForObject("select due_date from vendor_invoices where id=?", LocalDate.class, invoice));
        assertEquals(new BigDecimal("588.50"), supplierPayments.aging(c.workspace(), c.entity(), DAY.plusDays(2)).get(0).get("open_amount"));
        var payment = new SupplierPaymentService.Draft("SYNTHETIC-PAYMENT", fixture.supplierAccount(), 1, 1, 1,
                DAY.plusDays(3), "EUR", new BigDecimal("588.50"),
                List.of(new SupplierPaymentService.Allocation(invoice, new BigDecimal("588.50"))));
        long cash = supplierPayments.save(c.workspace(), c.entity(), null, 0, payment);
        assertFalse(supplierPayments.post(c.workspace(), c.entity(), cash, 1));
        assertTrue(supplierPayments.post(c.workspace(), c.entity(), cash, 1));
        assertEquals(new BigDecimal("0.00"), supplierPayments.aging(c.workspace(), c.entity(), DAY.plusDays(3)).get(0).get("open_amount"));
        denied(409, () -> vendorInvoices.reverse(c.workspace(), c.entity(), invoice, DAY.plusDays(3), "Cash still active", "EARLY-CORRECTION"));
        supplierPayments.reverse(c.workspace(), c.entity(), cash, DAY.plusDays(4), "Synthetic whole cash correction", "CASH-REVERSAL");
        supplierPayments.reverse(c.workspace(), c.entity(), cash, DAY.plusDays(4), "Synthetic whole cash correction", "CASH-REVERSAL");
        vendorInvoices.reverse(c.workspace(), c.entity(), invoice, DAY.plusDays(5), "Synthetic invoice correction", "INVOICE-REVERSAL");
        assertEquals(new BigDecimal("588.50"), supplierPayments.aging(c.workspace(), c.entity(), DAY.plusDays(2)).get(0).get("open_amount"));
        assertEquals(new BigDecimal("0.00"), supplierPayments.aging(c.workspace(), c.entity(), DAY.plusDays(3)).get(0).get("open_amount"));
        assertEquals(new BigDecimal("588.50"), supplierPayments.aging(c.workspace(), c.entity(), DAY.plusDays(4)).get(0).get("open_amount"));
        assertTrue(supplierPayments.aging(c.workspace(), c.entity(), DAY.plusDays(5)).stream().noneMatch(row -> ((Number) row.get("id")).longValue() == invoice));
        assertEquals(new BigDecimal("10.000000"), db.queryForObject(
                "select quantity from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?",
                BigDecimal.class, c.workspace(), c.entity(), fixture.product()));
        assertEquals(new BigDecimal("550.00"), db.queryForObject(
                "select value from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?",
                BigDecimal.class, c.workspace(), c.entity(), fixture.product()));
    }

    @Test void payablePageClampsDaysOverdueBeforeAndOnDueDateAndCountsAfterwards() throws Exception {
        var fixture = purchase(new BigDecimal("10"), new BigDecimal("55.00"), null, null);
        var c = fixture.creator();
        var line = new VendorInvoiceService.Line("AP-BOUNDARY-LINE", fixture.receiptLine(), new BigDecimal("10"),
                new BigDecimal("55.00"), null, null, new BigDecimal("550.00"), new BigDecimal("0.00"),
                new BigDecimal("550.00"), new BigDecimal("38.50"), new BigDecimal("588.50"));
        long invoice = vendorInvoices.save(c.workspace(), c.entity(), null, 0,
                new VendorInvoiceService.Draft("AP-BOUNDARY", DAY.plusDays(2), 1, 1, List.of(line)));
        assertFalse(vendorInvoices.post(c.workspace(), c.entity(), invoice, 1).held());
        LocalDate due = DAY.plusDays(32);
        assertEquals(due, db.queryForObject("select due_date from vendor_invoices where id=?", LocalDate.class, invoice));
        var client = signedIn(c.login());
        for (var boundary : List.of(Map.entry(DAY.plusDays(2), "0"), Map.entry(due, "0"), Map.entry(due.plusDays(1), "1"))) {
            var response = get(client, "/w/" + c.workspace() + "/e/" + c.entity() + "/finance/ap?asOf=" + boundary.getKey());
            assertEquals(200, response.statusCode(), "Posted invoice must render at " + boundary.getKey());
            assertTrue(response.body().contains("AP-BOUNDARY"));
            var overdue = Pattern.compile("<td\\b[^>]*>\\s*" + due + "\\s*</td>\\s*<td\\b[^>]*>\\s*(-?\\d+)\\s*</td>")
                    .matcher(response.body());
            assertTrue(overdue.find(), "The invoice due date and displayed overdue value must be visible.");
            assertEquals(boundary.getValue(), overdue.group(1), "Overdue boundary at " + boundary.getKey());
            assertTrue(response.body().contains("588.50 EUR"), "Viewing aging must retain the unpaid invoice balance.");
        }
    }

    @Test void bankAccountCannotBeMappedToInventoryControlAccountInEitherDirection() {
        var fixture = purchase(new BigDecimal("1"), new BigDecimal("10.00"), null, null);
        var c = fixture.creator();
        long inventoryAccount = db.queryForObject("select id from chart_accounts where workspace_id=? and entity_id=? and code='INVENTORY'", Long.class, c.workspace(), c.entity());
        long bankAccount = db.queryForObject("select id from chart_accounts where workspace_id=? and entity_id=? and code='BANK'", Long.class, c.workspace(), c.entity());
        denied(400, () -> finance.paymentConfig(c.workspace(), c.entity(), 1, inventoryAccount));
        denied(400, () -> finance.procurementConfig(c.workspace(), c.entity(), 1,
                bankAccount + ":1", accountToken(c, "AP"), accountToken(c, "GRNI"),
                accountToken(c, "GAIN"), accountToken(c, "LOSS"), accountToken(c, "SERVICE")));
    }

    @Test void taxBankAndProcurementMappingsRejectConflictingRolesWithoutPartialChanges() {
        var fixture = purchase(BigDecimal.ONE, new BigDecimal("10.00"), null, null);
        var c = fixture.creator();
        long inventoryAccount = Long.parseLong(accountToken(c, "INVENTORY").split(":")[0]);
        long bankAccount = Long.parseLong(accountToken(c, "BANK").split(":")[0]);
        long inputTax = Long.parseLong(accountToken(c, "TAX_INPUT").split(":")[0]);
        long outputTax = Long.parseLong(accountToken(c, "TAX_OUTPUT").split(":")[0]);
        long ap = Long.parseLong(accountToken(c, "AP").split(":")[0]);
        long grni = Long.parseLong(accountToken(c, "GRNI").split(":")[0]);
        long taxCode = db.queryForObject("select id from tax_codes where workspace_id=? and entity_id=? and code='SIM-VAT7'", Long.class, c.workspace(), c.entity());
        int audits = db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace());
        for (long conflictingInput : List.of(inventoryAccount, bankAccount)) {
            denied(400, () -> finance.tax(c.workspace(), c.entity(), "BAD-INPUT", "Conflicting input tax",
                    DAY, null, new BigDecimal("7"), false, conflictingInput, outputTax));
            denied(400, () -> finance.taxVersion(c.workspace(), c.entity(), taxCode, 1,
                    DAY.plusYears(1), null, new BigDecimal("7"), false, conflictingInput, outputTax));
        }
        for (long conflictingOutput : List.of(ap, grni))
            denied(400, () -> finance.tax(c.workspace(), c.entity(), "BAD-OUTPUT", "Conflicting output tax",
                    DAY, null, new BigDecimal("7"), false, inputTax, conflictingOutput));
        denied(400, () -> finance.paymentConfig(c.workspace(), c.entity(), 1, inputTax));
        denied(400, () -> finance.procurementConfig(c.workspace(), c.entity(), 1,
                accountToken(c, "TAX_INPUT"), accountToken(c, "AP"), accountToken(c, "GRNI"),
                accountToken(c, "GAIN"), accountToken(c, "LOSS"), accountToken(c, "SERVICE")));
        denied(400, () -> finance.procurementConfig(c.workspace(), c.entity(), 1,
                accountToken(c, "INVENTORY"), accountToken(c, "TAX_OUTPUT"), accountToken(c, "GRNI"),
                accountToken(c, "GAIN"), accountToken(c, "LOSS"), accountToken(c, "SERVICE")));
        denied(400, () -> finance.procurementConfig(c.workspace(), c.entity(), 1,
                accountToken(c, "INVENTORY"), accountToken(c, "AP"), accountToken(c, "TAX_OUTPUT"),
                accountToken(c, "GAIN"), accountToken(c, "LOSS"), accountToken(c, "SERVICE")));
        assertEquals(1, db.queryForObject("select count(*) from tax_codes where workspace_id=?", Integer.class, c.workspace()));
        assertEquals(1L, db.queryForObject("select version from tax_codes where id=?", Long.class, taxCode));
        assertEquals(1L, db.queryForObject("select version from payment_posting_config where workspace_id=? and entity_id=?", Long.class, c.workspace(), c.entity()));
        assertEquals(1L, db.queryForObject("select version from procurement_posting_config where workspace_id=? and entity_id=?", Long.class, c.workspace(), c.entity()));
        assertEquals(audits, db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace()));
    }

    @Test void legacyTaxRoleConflictRejectsCashSaveAndPostWithoutChangingBalances() {
        var fixture = purchase(BigDecimal.ONE, new BigDecimal("10.00"), null, null);
        var c = fixture.creator();
        var line = new VendorInvoiceService.Line("CASH-ROLE-LINE", fixture.receiptLine(), BigDecimal.ONE,
                new BigDecimal("10.00"), null, null, new BigDecimal("10.00"), new BigDecimal("0.00"),
                new BigDecimal("10.00"), new BigDecimal("0.70"), new BigDecimal("10.70"));
        long invoice = vendorInvoices.save(c.workspace(), c.entity(), null, 0,
                new VendorInvoiceService.Draft("CASH-ROLE", DAY.plusDays(2), 1, 1, List.of(line)));
        assertFalse(vendorInvoices.post(c.workspace(), c.entity(), invoice, 1).held());
        var draft = new SupplierPaymentService.Draft("ROLE-CASH", fixture.supplierAccount(), 1, 1, 1,
                DAY.plusDays(3), "EUR", new BigDecimal("10.70"),
                List.of(new SupplierPaymentService.Allocation(invoice, new BigDecimal("10.70"))));
        long payment = supplierPayments.save(c.workspace(), c.entity(), null, 0, draft);
        long bank = Long.parseLong(accountToken(c, "BANK").split(":")[0]);
        long output = Long.parseLong(accountToken(c, "TAX_OUTPUT").split(":")[0]);
        long legacyTax = db.queryForObject("insert into tax_codes(workspace_id,entity_id,code,name,created_by) values (?,?, 'LEGACY-BANK-TAX','Legacy conflicting role',?) returning id",
                Long.class, c.workspace(), c.entity(), c.user());
        db.update("insert into tax_versions(workspace_id,entity_id,tax_code_id,valid_from,rate_pct,price_inclusive,rounding_mode,input_account_id,output_account_id,created_by) values (?,?,?,?,7,false,'HALF_UP',?,?,?)",
                c.workspace(), c.entity(), legacyTax, DAY, bank, output, c.user());
        int journals = db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace());
        int audits = db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace());
        denied(400, () -> supplierPayments.post(c.workspace(), c.entity(), payment, 1));
        var another = new SupplierPaymentService.Draft("ROLE-CASH-SECOND", fixture.supplierAccount(), 1, 1, 1,
                DAY.plusDays(3), "EUR", new BigDecimal("10.70"), draft.allocations());
        denied(400, () -> supplierPayments.save(c.workspace(), c.entity(), null, 0, another));
        assertEquals("DRAFT", db.queryForObject("select status from supplier_payments where id=?", String.class, payment));
        assertEquals(1, db.queryForObject("select count(*) from supplier_payments where workspace_id=?", Integer.class, c.workspace()));
        assertEquals(new BigDecimal("10.70"), supplierPayments.aging(c.workspace(), c.entity(), DAY.plusDays(3)).get(0).get("open_amount"));
        assertEquals(journals, db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace()));
        assertEquals(audits, db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace()));
        assertEquals(new BigDecimal("10.00"), db.queryForObject("select value from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?", BigDecimal.class, c.workspace(), c.entity(), fixture.product()));
    }

    @Test void legacyReceiptTaxSnapshotCannotAllocateOrPostIntoBank() {
        var fixture = purchase(BigDecimal.ONE, new BigDecimal("10.00"), null, null);
        var c = fixture.creator();
        var line = new VendorInvoiceService.Line("RECEIPT-ROLE-LINE", fixture.receiptLine(), BigDecimal.ONE,
                new BigDecimal("10.00"), null, null, new BigDecimal("10.00"), new BigDecimal("0.00"),
                new BigDecimal("10.00"), new BigDecimal("0.70"), new BigDecimal("10.70"));
        long invoice = vendorInvoices.save(c.workspace(), c.entity(), null, 0,
                new VendorInvoiceService.Draft("RECEIPT-ROLE", DAY.plusDays(2), 1, 1, List.of(line)));
        long inputTax = Long.parseLong(accountToken(c, "TAX_INPUT").split(":")[0]);
        db.update("update payment_posting_config set bank_account_id=? where workspace_id=? and entity_id=?", inputTax, c.workspace(), c.entity());
        int journals = db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace());
        int audits = db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace());
        denied(409, () -> vendorInvoices.post(c.workspace(), c.entity(), invoice, 1));
        assertEquals("DRAFT", vendorInvoices.detail(c.workspace(), c.entity(), invoice).get("status"));
        assertEquals(0, db.queryForObject("select count(*) from invoice_source_allocations where workspace_id=?", Integer.class, c.workspace()));
        assertEquals(journals, db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace()));
        assertEquals(audits, db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace()));
        assertEquals(new BigDecimal("10.00"), db.queryForObject("select value from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?", BigDecimal.class, c.workspace(), c.entity(), fixture.product()));
    }

    @Test void legacyBankInventoryConflictRejectsReceiptAndAdjustmentWithoutEffects() {
        for (boolean receiptOnly : List.of(false, true)) {
            var fixture = purchase(BigDecimal.ONE, new BigDecimal("10.00"), null, null, !receiptOnly);
            var c = fixture.creator();
            var warehouse = configuration.list(c.workspace(), c.entity(), ConfigurationService.Kind.WAREHOUSE, "").get(0);
            long action = receiptOnly ? 0 : stockAction(fixture, "LEGACY-ISSUE", "NEGATIVE_ADJUSTMENT",
                    warehouse.id(), null, BigDecimal.ONE, null, DAY.plusDays(2));
            long inventoryAccount = Long.parseLong(accountToken(c, "INVENTORY").split(":")[0]);
            db.update("update payment_posting_config set bank_account_id=? where workspace_id=? and entity_id=?", inventoryAccount, c.workspace(), c.entity());
            int journals = db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace());
            int audits = db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace());
            var pools = db.queryForList("select * from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?", c.workspace(), c.entity(), fixture.product());
            var movements = db.queryForList("select * from stock_movements where workspace_id=? and entity_id=? and product_id=? order by id", c.workspace(), c.entity(), fixture.product());
            if (receiptOnly) {
                denied(409, () -> procurement.postReceipt(c.workspace(), c.entity(), fixture.receipt(), 1, "LEGACY-RECEIPT"));
                assertEquals("DRAFT", db.queryForObject("select status from procurement_receipts where id=?", String.class, fixture.receipt()));
            } else {
                denied(409, () -> inventory.postAction(c.workspace(), c.entity(), action, 1));
                assertEquals("DRAFT", inventory.action(c.workspace(), c.entity(), action).status());
            }
            assertEquals(pools, db.queryForList("select * from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?", c.workspace(), c.entity(), fixture.product()));
            assertEquals(movements, db.queryForList("select * from stock_movements where workspace_id=? and entity_id=? and product_id=? order by id", c.workspace(), c.entity(), fixture.product()));
            assertEquals(journals, db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace()));
            assertEquals(audits, db.queryForObject("select count(*) from audit_events where workspace_id=?", Integer.class, c.workspace()));
        }
    }

    @Test void weightedAverageReceiptTransferAndIssuePreserveCostAndStockToGl() {
        var fixture = purchase(new BigDecimal("10"), new BigDecimal("65.00"), null, null, false);
        var c = fixture.creator();
        var from = configuration.list(c.workspace(), c.entity(), ConfigurationService.Kind.WAREHOUSE, "").get(0);
        long initial = stockAction(fixture, "COST-OPENING", "POSITIVE_ADJUSTMENT", null, from.id(),
                new BigDecimal("20"), new BigDecimal("1000.00"), DAY);
        inventory.submitAction(c.workspace(), c.entity(), initial, 1);
        actor(fixture.checker().user());
        inventory.postAction(c.workspace(), c.entity(), initial, 2);
        actor(c.user());
        procurement.postReceipt(c.workspace(), c.entity(), fixture.receipt(), 1, "COST-RECEIPT");
        assertEquals(new BigDecimal("30.000000"), db.queryForObject("select quantity from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?", BigDecimal.class, c.workspace(), c.entity(), fixture.product()));
        assertEquals(new BigDecimal("1650.00"), db.queryForObject("select value from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?", BigDecimal.class, c.workspace(), c.entity(), fixture.product()));
        long destination = configuration.save(c.workspace(), c.entity(), ConfigurationService.Kind.WAREHOUSE, null, null, "COST-DEST", "Cost transfer destination", null, null);
        int journals = db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace());
        long transfer = stockAction(fixture, "COST-TRANSFER", "TRANSFER", from.id(), destination,
                new BigDecimal("5"), null, DAY.plusDays(2));
        assertNull(inventory.postAction(c.workspace(), c.entity(), transfer, 1).journalId());
        assertEquals(journals, db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace()));
        assertEquals(new BigDecimal("275.00"), db.queryForObject("select value_delta from stock_movements where workspace_id=? and entity_id=? and source_type='INVENTORY_ACTION' and source_id=? and quantity_delta>0", BigDecimal.class, c.workspace(), c.entity(), Long.toString(transfer)));
        long issue = stockAction(fixture, "COST-ISSUE", "NEGATIVE_ADJUSTMENT", from.id(), null,
                new BigDecimal("7"), null, DAY.plusDays(2));
        var posted = inventory.postAction(c.workspace(), c.entity(), issue, 1);
        assertEquals(new BigDecimal("385.00"), db.queryForObject("select sum(debit) from journal_lines where journal_id=?", BigDecimal.class, posted.journalId()));
        assertEquals(new BigDecimal("23.000000"), db.queryForObject("select quantity from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?", BigDecimal.class, c.workspace(), c.entity(), fixture.product()));
        assertEquals(new BigDecimal("1265.00"), db.queryForObject("select value from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?", BigDecimal.class, c.workspace(), c.entity(), fixture.product()));
        long inventoryAccount = Long.parseLong(accountToken(c, "INVENTORY").split(":")[0]);
        assertEquals(db.queryForObject("select sum(value) from inventory_valuation_pools where workspace_id=? and entity_id=?", BigDecimal.class, c.workspace(), c.entity()),
                db.queryForObject("select sum(debit-credit) from journal_lines where workspace_id=? and entity_id=? and account_id=?", BigDecimal.class, c.workspace(), c.entity(), inventoryAccount));
    }

    @Test void competingReceiptsCannotConsumeTheSameOrderCapacity() throws Exception {
        var fixture = purchase(new BigDecimal("10"), new BigDecimal("55.00"), null, null, false);
        var c = fixture.creator();
        var line = db.queryForMap("select order_line_id,warehouse_id,warehouse_version from procurement_receipt_lines where id=?", fixture.receiptLine());
        long second = procurement.saveReceipt(c.workspace(), c.entity(), null, null,
                new ProcurementService.ReceiptInput(fixture.order(), 3, "GOODS", DAY.plusDays(1),
                        List.of(new ProcurementService.ReceiptLineInput("COMPETING-RECEIPT",
                                ((Number) line.get("order_line_id")).longValue(), new BigDecimal("10"),
                                ((Number) line.get("warehouse_id")).longValue(), ((Number) line.get("warehouse_version")).longValue(), List.of()))));
        assertEquals(List.of(200, 409), competing(c,
                () -> procurement.postReceipt(c.workspace(), c.entity(), fixture.receipt(), 1, "RECEIPT-A"),
                () -> procurement.postReceipt(c.workspace(), c.entity(), second, 1, "RECEIPT-B")));
        assertEquals(1, db.queryForObject("select count(*) from procurement_receipts where workspace_id=? and status='POSTED'", Integer.class, c.workspace()));
        assertEquals(new BigDecimal("10.000000"), db.queryForObject("select quantity from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?", BigDecimal.class, c.workspace(), c.entity(), fixture.product()));
        assertEquals(new BigDecimal("550.00"), db.queryForObject("select value from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?", BigDecimal.class, c.workspace(), c.entity(), fixture.product()));
    }

    @Test void competingIssuesCannotOverdrawTheSameValuationPool() throws Exception {
        var fixture = purchase(new BigDecimal("10"), new BigDecimal("55.00"), null, null);
        var c = fixture.creator();
        var warehouse = configuration.list(c.workspace(), c.entity(), ConfigurationService.Kind.WAREHOUSE, "").get(0);
        long first = stockAction(fixture, "ISSUE-A", "NEGATIVE_ADJUSTMENT", warehouse.id(), null, new BigDecimal("6"), null, DAY.plusDays(2));
        long second = stockAction(fixture, "ISSUE-B", "NEGATIVE_ADJUSTMENT", warehouse.id(), null, new BigDecimal("6"), null, DAY.plusDays(2));
        assertEquals(List.of(200, 409), competing(c,
                () -> inventory.postAction(c.workspace(), c.entity(), first, 1),
                () -> inventory.postAction(c.workspace(), c.entity(), second, 1)));
        assertEquals(new BigDecimal("4.000000"), db.queryForObject("select quantity from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?", BigDecimal.class, c.workspace(), c.entity(), fixture.product()));
        assertEquals(new BigDecimal("220.00"), db.queryForObject("select value from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?", BigDecimal.class, c.workspace(), c.entity(), fixture.product()));
        assertEquals(1, db.queryForObject("select count(*) from inventory_actions where workspace_id=? and status='POSTED'", Integer.class, c.workspace()));
    }

    @Test void competingPaymentsCannotSpendTheSameInvoiceBalance() throws Exception {
        var fixture = purchase(BigDecimal.ONE, new BigDecimal("10.00"), null, null);
        var c = fixture.creator();
        var line = new VendorInvoiceService.Line("PAY-RACE", fixture.receiptLine(), BigDecimal.ONE,
                new BigDecimal("10.00"), null, null, new BigDecimal("10.00"), new BigDecimal("0.00"),
                new BigDecimal("10.00"), new BigDecimal("0.70"), new BigDecimal("10.70"));
        long invoice = vendorInvoices.save(c.workspace(), c.entity(), null, 0,
                new VendorInvoiceService.Draft("PAY-RACE", DAY.plusDays(2), 1, 1, List.of(line)));
        vendorInvoices.post(c.workspace(), c.entity(), invoice, 1);
        var allocation = List.of(new SupplierPaymentService.Allocation(invoice, new BigDecimal("10.70")));
        long first = supplierPayments.save(c.workspace(), c.entity(), null, 0,
                new SupplierPaymentService.Draft("PAY-A", fixture.supplierAccount(), 1, 1, 1, DAY.plusDays(3), "EUR", new BigDecimal("10.70"), allocation));
        long second = supplierPayments.save(c.workspace(), c.entity(), null, 0,
                new SupplierPaymentService.Draft("PAY-B", fixture.supplierAccount(), 1, 1, 1, DAY.plusDays(3), "EUR", new BigDecimal("10.70"), allocation));
        assertEquals(List.of(200, 409), competing(c,
                () -> supplierPayments.post(c.workspace(), c.entity(), first, 1),
                () -> supplierPayments.post(c.workspace(), c.entity(), second, 1)));
        assertEquals(1, db.queryForObject("select count(*) from supplier_payments where workspace_id=? and status='POSTED'", Integer.class, c.workspace()));
        assertEquals(new BigDecimal("0.00"), supplierPayments.aging(c.workspace(), c.entity(), DAY.plusDays(3)).get(0).get("open_amount"));
    }

    @Test void middleInvoiceRebillReusesExactReleasedRoundingIntervalWithoutRewritingOtherInvoices() {
        var fixture = purchase(new BigDecimal("3"), new BigDecimal("0.50"), null, new BigDecimal("0.50"));
        var c = fixture.creator();
        long first = partialInvoice(fixture, "PART-A", DAY.plusDays(2), "0.17", "0.33", "0.02", "0.35");
        long middle = partialInvoice(fixture, "PART-B", DAY.plusDays(2), "0.16", "0.34", "0.03", "0.37");
        long last = partialInvoice(fixture, "PART-C", DAY.plusDays(2), "0.17", "0.33", "0.02", "0.35");
        var firstBefore = db.queryForList("select slice_start,slice_end,subtotal,discount,net,tax,gross from invoice_source_allocations where invoice_line_id in (select id from vendor_invoice_lines where invoice_id=?) order by id", first);
        var lastBefore = db.queryForList("select slice_start,slice_end,subtotal,discount,net,tax,gross from invoice_source_allocations where invoice_line_id in (select id from vendor_invoice_lines where invoice_id=?) order by id", last);
        vendorInvoices.reverse(c.workspace(), c.entity(), middle, DAY.plusDays(3), "Correct middle interval only", "MIDDLE-REVERSAL");
        var replacement = new VendorInvoiceService.Line("REPLACEMENT-LINE", fixture.receiptLine(), BigDecimal.ONE,
                new BigDecimal("0.50"), null, new BigDecimal("0.16"), new BigDecimal("0.50"), new BigDecimal("0.16"),
                new BigDecimal("0.34"), new BigDecimal("0.03"), new BigDecimal("0.37"));
        long rebill = vendorInvoices.save(c.workspace(), c.entity(), null, 0,
                new VendorInvoiceService.Draft("PART-D", DAY.plusDays(2), 1, 1, List.of(replacement)));
        assertTrue(vendorInvoices.post(c.workspace(), c.entity(), rebill, 1).held(),
                "A future reversal must not release capacity into an earlier business date");
        vendorInvoices.save(c.workspace(), c.entity(), rebill, 2,
                new VendorInvoiceService.Draft("PART-D", DAY.plusDays(3), 1, 1, List.of(replacement)));
        assertFalse(vendorInvoices.post(c.workspace(), c.entity(), rebill, 3).held());
        var slice = db.queryForMap("select slice_start,slice_end,subtotal,discount,net,tax,gross from invoice_source_allocations where invoice_line_id in (select id from vendor_invoice_lines where invoice_id=?)", rebill);
        assertEquals(new BigDecimal("1.000000"), slice.get("slice_start"));
        assertEquals(new BigDecimal("2.000000"), slice.get("slice_end"));
        assertEquals(new BigDecimal("0.34"), slice.get("net"));
        assertEquals(new BigDecimal("0.03"), slice.get("tax"));
        assertEquals(new BigDecimal("0.37"), slice.get("gross"));
        assertEquals(firstBefore, db.queryForList("select slice_start,slice_end,subtotal,discount,net,tax,gross from invoice_source_allocations where invoice_line_id in (select id from vendor_invoice_lines where invoice_id=?) order by id", first));
        assertEquals(lastBefore, db.queryForList("select slice_start,slice_end,subtotal,discount,net,tax,gross from invoice_source_allocations where invoice_line_id in (select id from vendor_invoice_lines where invoice_id=?) order by id", last));
        assertEquals("POSTED", vendorInvoices.detail(c.workspace(), c.entity(), middle).get("status"));
        assertEquals(new BigDecimal("1.07"), supplierPayments.aging(c.workspace(), c.entity(), DAY.plusDays(2)).stream()
                .map(row -> (BigDecimal) row.get("open_amount")).reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(new BigDecimal("1.07"), supplierPayments.aging(c.workspace(), c.entity(), DAY.plusDays(3)).stream()
                .map(row -> (BigDecimal) row.get("open_amount")).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private long partialInvoice(PurchaseFixture fixture, String reference, LocalDate date,
                                String discount, String net, String tax, String gross) {
        var c = fixture.creator();
        var line = new VendorInvoiceService.Line("PARTIAL-LINE", fixture.receiptLine(), BigDecimal.ONE,
                new BigDecimal("0.50"), null, new BigDecimal(discount), new BigDecimal("0.50"),
                new BigDecimal(discount), new BigDecimal(net), new BigDecimal(tax), new BigDecimal(gross));
        long id = vendorInvoices.save(c.workspace(), c.entity(), null, 0,
                new VendorInvoiceService.Draft(reference, date, 1, 1, List.of(line)));
        assertFalse(vendorInvoices.post(c.workspace(), c.entity(), id, 1).held());
        return id;
    }

    @Test void finalInventoryIssueConsumesExactResidualValueWithoutLeavingStrandedCents() {
        var fixture = purchase(new BigDecimal("3"), new BigDecimal("0.333333"), null, null);
        var c = fixture.creator();
        var product = products.get(c.workspace(), c.entity(), fixture.product());
        var unit = configuration.get(c.workspace(), c.entity(), ConfigurationService.Kind.UOM, product.uomId());
        var setting = products.setting(c.workspace(), c.entity(), product.id());
        var warehouse = configuration.list(c.workspace(), c.entity(), ConfigurationService.Kind.WAREHOUSE, "").get(0);
        assertEquals(new BigDecimal("1.00"), db.queryForObject(
                "select value from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?",
                BigDecimal.class, c.workspace(), c.entity(), product.id()));
        String[] costs = {"0.33", "0.34", "0.33"};
        for (int i=0; i<costs.length; i++) {
            long action = inventory.saveAction(c.workspace(), c.entity(), null, null,
                    new InventoryService.ActionInput("RESIDUAL-" + i, "NEGATIVE_ADJUSTMENT", product.id(), product.version(),
                            unit.id(), unit.version(), setting.version(), warehouse.id(), warehouse.version(), null, null,
                            BigDecimal.ONE, null, DAY.plusDays(2), "Synthetic residual-cost issue", List.of()));
            var posted = inventory.postAction(c.workspace(), c.entity(), action, 1);
            assertEquals(new BigDecimal(costs[i]), db.queryForObject("select sum(debit) from journal_lines where journal_id=?",
                    BigDecimal.class, posted.journalId()));
            assertEquals(new BigDecimal(costs[i]).negate(), db.queryForObject(
                    "select sum(value_delta) from stock_movements where workspace_id=? and entity_id=? and source_type='INVENTORY_ACTION' and source_id=?",
                    BigDecimal.class, c.workspace(), c.entity(), Long.toString(action)));
        }
        assertEquals(new BigDecimal("0.000000"), db.queryForObject(
                "select quantity from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?",
                BigDecimal.class, c.workspace(), c.entity(), product.id()));
        assertEquals(new BigDecimal("0.00"), db.queryForObject(
                "select value from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?",
                BigDecimal.class, c.workspace(), c.entity(), product.id()));
    }

    @Test void zeroValueInvoiceCorrectionIsTypedReplaySafeAndReleasesStockEntitlementWithoutZeroGl() {
        var fixture = purchase(BigDecimal.ONE, BigDecimal.ZERO, null, null);
        var c = fixture.creator();
        int before = db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace());
        var zero = new BigDecimal("0.00");
        var line = new VendorInvoiceService.Line("FREE-LINE", fixture.receiptLine(), BigDecimal.ONE,
                zero, null, null, zero, zero, zero, zero, zero);
        long invoice = vendorInvoices.save(c.workspace(), c.entity(), null, 0,
                new VendorInvoiceService.Draft("FREE-ORIGINAL", DAY.plusDays(2), 1, 1, List.of(line)));
        assertFalse(vendorInvoices.post(c.workspace(), c.entity(), invoice, 1).held());
        assertTrue(vendorInvoices.post(c.workspace(), c.entity(), invoice, 1).replayed());
        assertNull(vendorInvoices.detail(c.workspace(), c.entity(), invoice).get("journal_id"));
        vendorInvoices.reverse(c.workspace(), c.entity(), invoice, DAY.plusDays(3), "Free invoice correction", "FREE-REVERSAL");
        vendorInvoices.reverse(c.workspace(), c.entity(), invoice, DAY.plusDays(3), "Free invoice correction", "FREE-REVERSAL");
        denied(409, () -> vendorInvoices.reverse(c.workspace(), c.entity(), invoice, DAY.plusDays(3), "Changed reversal payload", "FREE-REVERSAL"));
        var correction = db.queryForMap("select journal_id,source_type,invoice_id,payment_id from procurement_reversals where workspace_id=? and entity_id=? and reversal_key='FREE-REVERSAL'",
                c.workspace(), c.entity());
        assertNull(correction.get("journal_id"));
        assertEquals("INVOICE", correction.get("source_type"));
        assertEquals(invoice, ((Number) correction.get("invoice_id")).longValue());
        assertNull(correction.get("payment_id"));
        long rebill = vendorInvoices.save(c.workspace(), c.entity(), null, 0,
                new VendorInvoiceService.Draft("FREE-REBILL", DAY.plusDays(3), 1, 1, List.of(line)));
        assertFalse(vendorInvoices.post(c.workspace(), c.entity(), rebill, 1).held());
        assertEquals(before, db.queryForObject("select count(*) from journal_entries where workspace_id=?", Integer.class, c.workspace()));
        assertEquals(new BigDecimal("1.000000"), db.queryForObject(
                "select quantity from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?",
                BigDecimal.class, c.workspace(), c.entity(), fixture.product()));
        assertEquals(zero, db.queryForObject(
                "select value from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?",
                BigDecimal.class, c.workspace(), c.entity(), fixture.product()));
    }

    private long stockAction(PurchaseFixture fixture, String key, String kind, Long from, Long to,
                             BigDecimal quantity, BigDecimal value, LocalDate date) {
        var c = fixture.creator();
        var product = products.get(c.workspace(), c.entity(), fixture.product());
        var unit = configuration.get(c.workspace(), c.entity(), ConfigurationService.Kind.UOM, product.uomId());
        var setting = products.setting(c.workspace(), c.entity(), product.id());
        Long fromVersion = from == null ? null : configuration.get(c.workspace(), c.entity(), ConfigurationService.Kind.WAREHOUSE, from).version();
        Long toVersion = to == null ? null : configuration.get(c.workspace(), c.entity(), ConfigurationService.Kind.WAREHOUSE, to).version();
        return inventory.saveAction(c.workspace(), c.entity(), null, null,
                new InventoryService.ActionInput(key, kind, product.id(), product.version(), unit.id(), unit.version(),
                        setting.version(), from, fromVersion, to, toVersion, quantity, value, date, "Synthetic controlled stock effect", List.of()));
    }
    private List<Integer> competing(Context c, Runnable first, Runnable second) throws Exception {
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var jobs = new ArrayList<Callable<Integer>>();
        for (var effect : List.of(first, second)) jobs.add(() -> {
            actor(c.user());
            try {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                try { effect.run(); return 200; }
                catch (DomainException ex) { return ex.status(); }
            } finally { SecurityContextHolder.clearContext(); }
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Integer>();
            for (var future : executor.invokeAll(jobs)) results.add(future.get());
            return results.stream().sorted().toList();
        }
    }
    private record PurchaseFixture(Context creator, Context checker, long product, long supplierAccount, long receiptLine, long receipt, long order) {}
    private PurchaseFixture purchase(BigDecimal quantity, BigDecimal unitCost, BigDecimal discountPct, BigDecimal discountAmount) {
        return purchase(quantity, unitCost, discountPct, discountAmount, true);
    }
    private PurchaseFixture purchase(BigDecimal quantity, BigDecimal unitCost, BigDecimal discountPct, BigDecimal discountAmount, boolean postReceipt) {
        var c = context("EUR", "OPERATIONS", "FINANCE");
        baseline.initialize(c.workspace(), c.entity(), "O2C_GOODS", 1);
        for (var account : List.of(new String[]{"AP", "LIABILITY"}, new String[]{"GRNI", "LIABILITY"},
                new String[]{"GAIN", "INCOME"}, new String[]{"LOSS", "EXPENSE"}, new String[]{"SERVICE", "EXPENSE"}))
            finance.account(c.workspace(), c.entity(), account[0], "Synthetic " + account[0], account[1]);
        finance.procurementConfig(c.workspace(), c.entity(), 0, accountToken(c, "INVENTORY"), accountToken(c, "AP"),
                accountToken(c, "GRNI"), accountToken(c, "GAIN"), accountToken(c, "LOSS"), accountToken(c, "SERVICE"));
        var unit = configuration.list(c.workspace(), c.entity(), ConfigurationService.Kind.UOM, "").get(0);
        var warehouse = configuration.list(c.workspace(), c.entity(), ConfigurationService.Kind.WAREHOUSE, "").get(0);
        long product = products.save(c.workspace(), c.entity(), null, null,
                new ProductService.Input("P2P-" + UUID.randomUUID(), "Arbitrary synthetic purchased component",
                        "REPLACEMENT_PART", "TRANSFER", unit.id(), unit.version(), "NONE"));
        products.assign(c.workspace(), c.entity(), product, 1, null, true);
        long setting = products.setting(c.workspace(), c.entity(), product).version();
        long request = procurement.saveRequest(c.workspace(), c.entity(), null, null,
                new ProcurementService.RequestInput(DAY, "Explicit synthetic purchase",
                        List.of(new ProcurementService.RequestLineInput("REQUEST-LINE", product, 1, setting, unit.version(), quantity))));
        procurement.submitRequest(c.workspace(), c.entity(), request, 1);
        denied(403, () -> procurement.decideRequest(c.workspace(), c.entity(), request, 2, true, ""));
        var checker = peer(c, "FINANCE");
        procurement.decideRequest(c.workspace(), c.entity(), request, 2, true, "");
        actor(c.user());
        long requestLine = procurement.requestLines(c.workspace(), c.entity(), request).get(0).id();
        var supplier = procurement.supplierChoices(c.workspace(), c.entity()).get(0);
        long supplierId = ((Number) supplier.get("id")).longValue();
        long supplierVersion = ((Number) supplier.get("version")).longValue();
        var tax = procurement.taxChoices(c.workspace(), c.entity()).get(0);
        long order = procurement.saveOrder(c.workspace(), c.entity(), null, null,
                new ProcurementService.OrderInput(supplierId, supplierVersion, 1, DAY,
                        List.of(new ProcurementService.OrderLineInput("ORDER-LINE", requestLine, null, quantity, unitCost,
                                discountPct, discountAmount, ((Number) tax.get("id")).longValue(), ((Number) tax.get("version")).longValue(), null))));
        procurement.submitOrder(c.workspace(), c.entity(), order, 1);
        denied(403, () -> procurement.decideOrder(c.workspace(), c.entity(), order, 2, true, ""));
        actor(checker.user());
        procurement.decideOrder(c.workspace(), c.entity(), order, 2, true, "");
        actor(c.user());
        long orderLine = ((Number) procurement.orderLines(c.workspace(), c.entity(), order).get(0).get("id")).longValue();
        long receipt = procurement.saveReceipt(c.workspace(), c.entity(), null, null,
                new ProcurementService.ReceiptInput(order, 3, "GOODS", DAY.plusDays(1),
                        List.of(new ProcurementService.ReceiptLineInput("RECEIPT-LINE", orderLine, quantity,
                                warehouse.id(), warehouse.version(), List.of()))));
        if (postReceipt) {
            assertFalse(procurement.postReceipt(c.workspace(), c.entity(), receipt, 1, "SYNTHETIC-RECEIPT").replayed());
            assertTrue(procurement.postReceipt(c.workspace(), c.entity(), receipt, 1, "SYNTHETIC-RECEIPT").replayed());
        }
        long receiptLine = ((Number) procurement.receiptLines(c.workspace(), c.entity(), receipt).get(0).get("id")).longValue();
        return new PurchaseFixture(c, checker, product, supplierId, receiptLine, receipt, order);
    }
    private String accountToken(Context c, String code) {
        var row = db.queryForMap("select id,version from chart_accounts where workspace_id=? and entity_id=? and code=?", c.workspace(), c.entity(), code);
        return row.get("id") + ":" + row.get("version");
    }

    private Context peer(Context c, String... roles) {
        actor(administrator);
        String login = "peer-" + UUID.randomUUID();
        identity.createUser(c.workspace(), login, PASSWORD);
        long user = db.queryForObject("select id from app_users where login=?", Long.class, login);
        identity.entityGrant(c.workspace(), c.entity(), user, 0, true);
        for (String role : roles) identity.role(c.workspace(), user, role, 0, true);
        actor(user);
        return new Context(c.workspace(), c.entity(), user, login);
    }

    private HttpClient signedIn(String login) throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build();
        assertEquals(302, post(client, "/login", Map.of("username", login, "password", PASSWORD, "_csrf", csrf(get(client, "/login").body()))).statusCode());
        return client;
    }
    private static void assertLinkedFieldError(String html, String id) {
        var controls = Pattern.compile("<(?:input|select)\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (controls.find()) {
            var attributes = htmlAttributes(controls.group());
            if (!id.equals(attributes.get("id"))) continue;
            assertEquals("true", attributes.get("aria-invalid"));
            assertEquals(id + "-error", attributes.get("aria-describedby"));
            assertTrue(html.contains("id=\"" + id + "-error\""), "The described inline error must exist.");
            assertTrue(html.contains("href=\"#" + id + "\""), "The keyboard summary must link to the actual field.");
            return;
        }
        throw new AssertionError("The original form field is missing: " + id);
    }
    private static void assertUnavailableReviewNotice(String html, String fieldName, String expectedToken) {
        var selects = Pattern.compile("<select\\b([^>]*)>(.*?)</select>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (selects.find()) {
            var attributes = htmlAttributes(selects.group(1));
            if (!fieldName.equals(attributes.get("name"))) continue;
            assertEquals("true", attributes.get("aria-invalid"), "A changed or unavailable choice must be visibly identified.");
            String noticeId = attributes.get("aria-describedby");
            assertNotNull(noticeId, "The reference control must describe the review notice.");
            var notices = Pattern.compile("<p\\b([^>]*)>.*?</p>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
            boolean linkedNotice = false;
            while (notices.find()) {
                var notice = htmlAttributes(notices.group(1));
                if (noticeId.equals(notice.get("id")) && "note".equals(notice.get("role"))) linkedNotice = true;
            }
            assertTrue(linkedNotice, "The field must link to its own visible review notice, not a generic page error.");
            var options = Pattern.compile("<option\\b([^>]*)>.*?</option>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(selects.group(2));
            var selected = new ArrayList<String>();
            while (options.find()) {
                var option = htmlAttributes(options.group(1));
                if (option.containsKey("selected")) selected.add(option.getOrDefault("value", ""));
            }
            assertEquals(List.of(expectedToken), selected, "Exactly the submitted token must be selected, never None or a fresh version.");
            return;
        }
        throw new AssertionError("The submitted reference control is missing: " + fieldName);
    }
    private static Map<String,String> renderedForm(String html, String identity) {
        var forms = Pattern.compile("<form\\b[^>]*>.*?</form>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (forms.find()) {
            String form = forms.group();
            var values = new java.util.LinkedHashMap<String,String>();
            var inputs = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(form);
            while (inputs.find()) {
                var attributes = htmlAttributes(inputs.group());
                String name = attributes.get("name"), type = attributes.getOrDefault("type", "text");
                if (name == null || attributes.containsKey("disabled") || (List.of("checkbox", "radio").contains(type) && !attributes.containsKey("checked"))) continue;
                values.put(name, attributes.getOrDefault("value", ""));
            }
            if (!identity.equals(values.get("_form"))) continue;
            var selects = Pattern.compile("<select\\b([^>]*)>(.*?)</select>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(form);
            while (selects.find()) {
                String name = htmlAttributes(selects.group(1)).get("name");
                if (name == null) continue;
                String value = null;
                var options = Pattern.compile("<option\\b([^>]*)>(.*?)</option>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(selects.group(2));
                while (options.find()) {
                    var attributes = htmlAttributes(options.group(1));
                    String option = attributes.getOrDefault("value", org.springframework.web.util.HtmlUtils.htmlUnescape(options.group(2)));
                    if (value == null || attributes.containsKey("selected")) value = option;
                    if (attributes.containsKey("selected")) break;
                }
                values.put(name, value == null ? "" : value);
            }
            return values;
        }
        throw new AssertionError("The actual rendered consumer form is missing: " + identity);
    }
    private static Map<String,String> htmlAttributes(String tag) {
        var attributes = new java.util.LinkedHashMap<String,String>();
        var pattern = Pattern.compile("([\\w:-]+)(?:\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+))?").matcher(tag);
        while (pattern.find()) {
            String value = pattern.group(2);
            if (value == null) value = "";
            else if (value.startsWith("\"") || value.startsWith("'")) value = value.substring(1, value.length() - 1);
            attributes.put(pattern.group(1).toLowerCase(java.util.Locale.ROOT), org.springframework.web.util.HtmlUtils.htmlUnescape(value));
        }
        return attributes;
    }

    @Test void priceBookApprovalRejectsEveryContributorAndPreservesApprovedPrices() {
        var maker=context("EUR","SALES_MANAGER","FINANCE");
        baseline.initialize(maker.workspace(),maker.entity(),"O2C_GOODS",1);
        long product=db.queryForObject("select id from products where workspace_id=? and sku='RGA-SUP-CS080'",Long.class,maker.workspace());
        long productVersion=products.get(maker.workspace(),maker.entity(),product).version();
        long book=priceBooks.save(maker.workspace(),maker.entity(),null,null,"INDEPENDENT","Independent reviewed prices","EUR",DAY,null);
        priceBooks.line(maker.workspace(),maker.entity(),book,1,product,productVersion,null,new BigDecimal("125.00"));
        var editor=peer(maker,"SALES_MANAGER");
        actor(editor.user());
        priceBooks.line(maker.workspace(),maker.entity(),book,2,product,productVersion,1L,new BigDecimal("127.50"));
        long approvedVersion=priceBooks.get(maker.workspace(),maker.entity(),book).version();
        denied(403,()->priceBooks.approve(maker.workspace(),maker.entity(),book,approvedVersion));
        actor(maker.user());
        denied(403,()->priceBooks.approve(maker.workspace(),maker.entity(),book,approvedVersion));
        assertEquals("DRAFT",priceBooks.get(maker.workspace(),maker.entity(),book).status());
        assertEquals(0,db.queryForObject("select count(*) from price_book_approvals where workspace_id=? and price_book_id=?",Integer.class,maker.workspace(),book));
        var checker=peer(maker,"SALES_MANAGER");
        actor(checker.user());
        priceBooks.approve(maker.workspace(),maker.entity(),book,approvedVersion);
        assertEquals("APPROVED",priceBooks.get(maker.workspace(),maker.entity(),book).status());
        assertEquals(checker.user(),db.queryForObject("select approved_by from price_book_approvals where workspace_id=? and price_book_id=?",Long.class,maker.workspace(),book));
        assertEquals(new BigDecimal("127.50"),priceBooks.lines(maker.workspace(),maker.entity(),book).get(0).amount());
        denied(409,()->priceBooks.line(maker.workspace(),maker.entity(),book,approvedVersion+1,product,productVersion,2L,new BigDecimal("1.00")));
        assertEquals(new BigDecimal("127.50"),priceBooks.lines(maker.workspace(),maker.entity(),book).get(0).amount());
    }

    @Test void salesControlMappingsRejectBankTaxInventoryAndUntypedOpeningsAtomically() {
        var fixture=purchase(BigDecimal.ONE,new BigDecimal("10.00"),null,null);
        var c=fixture.creator();
        for(var account:List.of(new String[]{"AR","ASSET"},new String[]{"UNBILLED","ASSET"},new String[]{"GOODS_REVENUE","INCOME"},new String[]{"COGS","EXPENSE"}))
            finance.account(c.workspace(),c.entity(),account[0],account[0],account[1]);
        var ar=salesReference(c,"AR"); var unbilled=salesReference(c,"UNBILLED");
        var revenue=salesReference(c,"GOODS_REVENUE"); var cogs=salesReference(c,"COGS");
        for(String conflict:List.of("BANK","INVENTORY","TAX_INPUT"))
            denied(400,()->salesPosting.save(c.workspace(),c.entity(),new dev.labtrial.salestools.finance.SalesPostingConfigService.Draft(0,salesReference(c,conflict),unbilled,revenue,cogs)));
        assertEquals(0,db.queryForObject("select count(*) from sales_posting_config where workspace_id=?",Integer.class,c.workspace()));
        salesPosting.save(c.workspace(),c.entity(),new dev.labtrial.salestools.finance.SalesPostingConfigService.Draft(0,ar,unbilled,revenue,cogs));
        denied(400,()->finance.paymentConfig(c.workspace(),c.entity(),1,ar.id()));
        long output=Long.parseLong(accountToken(c,"TAX_OUTPUT").split(":")[0]);
        denied(400,()->finance.tax(c.workspace(),c.entity(),"BAD-SALES-TAX","AR cannot be Input Tax",DAY,null,new BigDecimal("7"),false,ar.id(),output));
        int journals=db.queryForObject("select count(*) from journal_entries where workspace_id=?",Integer.class,c.workspace());
        long equity=Long.parseLong(accountToken(c,"EQUITY").split(":")[0]);
        for(var control:List.of(ar,unbilled))
            denied(400,()->ledger.postOpening(c.workspace(),c.entity(),new Ledger.Opening("UNTYPED-"+control.id(),1,DAY,"EUR","Rejected untyped sales opening",List.of(new Ledger.Line(control.id(),BigDecimal.ONE,BigDecimal.ZERO),new Ledger.Line(equity,BigDecimal.ZERO,BigDecimal.ONE)))));
        assertEquals(journals,db.queryForObject("select count(*) from journal_entries where workspace_id=?",Integer.class,c.workspace()));
        assertEquals(1L,db.queryForObject("select version from sales_posting_config where workspace_id=?",Long.class,c.workspace()));
    }

    private dev.labtrial.salestools.finance.SalesPostingConfigService.Reference salesReference(Context c,String code) {
        String[] token=accountToken(c,code).split(":");
        return new dev.labtrial.salestools.finance.SalesPostingConfigService.Reference(Long.parseLong(token[0]),Long.parseLong(token[1]));
    }

    @Test void sharedInquiryClaimHasOneWinnerAndScoreReplayKeepsItsOriginalRuleAndReasons() throws Exception {
        var c=context("EUR","INSIDE_SALES","SALES_MANAGER");
        var received=java.time.OffsetDateTime.parse("2027-01-15T09:00:00Z");
        long inquiry=crmInquiries.save(c.workspace(),c.entity(),null,null,new dev.labtrial.salestools.crm.InquiryService.Draft(
                "INQUIRY-"+UUID.randomUUID(),received,"New research requirement","Synthetic compatible supply need",
                null,null,null,null,null,"EU","TRANSFER","HIGH",received.plusDays(2)));
        assertEquals(List.of(200,409),competing(c,
            ()->crmInquiries.assign(c.workspace(),c.entity(),inquiry,1,null,"",true),
            ()->crmInquiries.assign(c.workspace(),c.entity(),inquiry,1,null,"",true)));
        actor(c.user());
        var claimed=crmInquiries.get(c.workspace(),c.entity(),inquiry);
        assertEquals(c.user(),claimed.assigneeId());
        assertNull(claimed.customerId());
        assertEquals(0,db.queryForObject("select count(*) from crm_activities where workspace_id=?",Integer.class,c.workspace()));
        long rule=crmScoring.createRule(c.workspace(),c.entity(),"BASE",20,30,25,25,"BASE-RULE-ONE");
        var input=new dev.labtrial.salestools.crm.ScoringService.Input(true,true,true,false);
        var scored=crmScoring.evaluate(c.workspace(),c.entity(),inquiry,claimed.version(),rule,DAY,input);
        assertEquals(75,scored.score());
        assertEquals(0,scored.budgetPoints());
        assertTrue(scored.reasons().contains("Budget"));
        long updatedRule=crmScoring.createRule(c.workspace(),c.entity(),"BASE",10,10,10,70,"BASE-RULE-TWO");
        var replay=crmScoring.evaluate(c.workspace(),c.entity(),inquiry,claimed.version(),rule,DAY,input);
        assertEquals(scored.id(),replay.id());
        assertEquals(75,replay.score());
        assertEquals(30,crmScoring.evaluate(c.workspace(),c.entity(),inquiry,claimed.version(),updatedRule,DAY,input).score());
        assertEquals(2,crmScoring.history(c.workspace(),c.entity(),inquiry).size());
        assertEquals(0,db.queryForObject("select count(*) from crm_opportunities where workspace_id=?",Integer.class,c.workspace()));
        assertEquals(0,db.queryForObject("select count(*) from sales_orders where workspace_id=?",Integer.class,c.workspace()));
    }

    @Test void routingUsesStableTieBreakAndRevokedOwnerBecomesUnassignedNotAnotherTeam() {
        var manager=context("EUR","SALES_MANAGER");
        var a=peer(manager,"INSIDE_SALES");
        var b=peer(manager,"INSIDE_SALES");
        actor(manager.user());
        long inquiry=crmInquiries.save(manager.workspace(),manager.entity(),null,null,new dev.labtrial.salestools.crm.InquiryService.Draft(
                "ROUTE-"+UUID.randomUUID(),java.time.OffsetDateTime.parse("2027-01-15T09:00:00Z"),"Routing boundary","",
                null,null,null,null,null,"EU","TRANSFER","NORMAL",null));
        crmRouting.createRule(manager.workspace(),manager.entity(),"Z-LATER","EU","TRANSFER",10,b.user(),true,"ROUTE-Z");
        long chosen=crmRouting.createRule(manager.workspace(),manager.entity(),"A-FIRST","EU","TRANSFER",10,a.user(),true,"ROUTE-A");
        var routed=crmRouting.route(manager.workspace(),manager.entity(),inquiry,1);
        assertEquals(chosen,routed.ruleId());
        assertEquals(a.user(),routed.assigneeId());
        assertEquals(1,crmInquiries.list(manager.workspace(),manager.entity(),"team","").size());
        actor(administrator);
        identity.role(manager.workspace(),a.user(),"INSIDE_SALES",1,false);
        actor(manager.user());
        assertTrue(crmInquiries.list(manager.workspace(),manager.entity(),"team","").isEmpty());
        var unavailable=crmRouting.route(manager.workspace(),manager.entity(),inquiry,2);
        assertNull(unavailable.assigneeId());
        assertEquals(chosen,unavailable.ruleId());
        assertTrue(unavailable.reason().contains("unavailable"));
        assertEquals(1,crmInquiries.list(manager.workspace(),manager.entity(),"unassigned","").size());
    }

    @Test void firstSimulatedResponseRequiresCompletedCommunicationNotClaimOrTask() {
        var c=context("EUR","INSIDE_SALES");
        var received=java.time.OffsetDateTime.parse("2027-01-15T09:00:00Z");
        long inquiry=crmInquiries.save(c.workspace(),c.entity(),null,null,new dev.labtrial.salestools.crm.InquiryService.Draft(
                "RESPONSE-"+UUID.randomUUID(),received,"Research inquiry","",null,null,null,null,null,"EU","TRANSFER","NORMAL",null));
        crmInquiries.assign(c.workspace(),c.entity(),inquiry,1,null,"",true);
        assertNull(crmInquiries.firstResponse(c.workspace(),c.entity(),inquiry));
        long task=crmActivities.create(c.workspace(),c.entity(),new dev.labtrial.salestools.crm.ActivityService.Draft(
                inquiry,2L,null,null,null,null,"TASK","Prepare a response",c.user(),received.plusDays(1),"PREPARE-RESPONSE"));
        denied(400,()->crmActivities.complete(c.workspace(),c.entity(),task,1,"Task is not communication",true));
        assertNull(crmInquiries.firstResponse(c.workspace(),c.entity(),inquiry));
        crmActivities.complete(c.workspace(),c.entity(),task,1,"Response preparation complete",false);
        long email=crmActivities.create(c.workspace(),c.entity(),new dev.labtrial.salestools.crm.ActivityService.Draft(
                inquiry,2L,null,null,null,null,"EMAIL","Simulated substantive reply",c.user(),received.plusDays(1),"SUBSTANTIVE-EMAIL"));
        crmActivities.complete(c.workspace(),c.entity(),email,1,"Confirmed compatible supply requirements",true);
        var response=crmInquiries.firstResponse(c.workspace(),c.entity(),inquiry);
        assertEquals(email,response.activityId());
        assertEquals(c.user(),response.actorId());
        assertEquals("EMAIL",response.kind());
        assertEquals("Confirmed compatible supply requirements",response.outcome());
        denied(409,()->crmActivities.edit(c.workspace(),c.entity(),email,2,"Overwrite historical reply",c.user(),received));
        assertEquals("Confirmed compatible supply requirements",crmInquiries.firstResponse(c.workspace(),c.entity(),inquiry).outcome());
    }

    @Test void newProductPublicationRequiresIndependentCheckerAndFreezesStockIdentity() {
        var maker=context("EUR","SALES_MANAGER","FINANCE","OPERATIONS");
        baseline.initialize(maker.workspace(),maker.entity(),"O2C_GOODS",1);
        var unit=configuration.list(maker.workspace(),maker.entity(),ConfigurationService.Kind.UOM,"").get(0);
        long product=products.save(maker.workspace(),maker.entity(),null,null,new ProductService.Input(
                "PUBLISH-"+UUID.randomUUID(),"User supplied goods","LAB_SUPPLY","TRANSFER",unit.id(),unit.version(),"NONE"));
        products.assign(maker.workspace(),maker.entity(),product,1,null,true);
        denied(403,()->products.publish(maker.workspace(),maker.entity(),product,1));
        var editor=peer(maker,"SALES_MANAGER");
        products.save(maker.workspace(),maker.entity(),product,1L,new ProductService.Input(
                products.get(maker.workspace(),maker.entity(),product).sku(),"Edited new goods","LAB_SUPPLY","TRANSFER",unit.id(),unit.version(),"NONE"));
        denied(403,()->products.publish(maker.workspace(),maker.entity(),product,2));
        var checker=peer(maker,"SALES_MANAGER");
        products.publish(maker.workspace(),maker.entity(),product,2);
        assertEquals("ACTIVE",products.get(maker.workspace(),maker.entity(),product).status());
        assertEquals(checker.user(),db.queryForObject("select approved_by from product_publications where workspace_id=? and product_id=?",Long.class,maker.workspace(),product));
        denied(409,()->products.save(maker.workspace(),maker.entity(),product,3L,new ProductService.Input(
                products.get(maker.workspace(),maker.entity(),product).sku(),"Change published identity","LAB_SUPPLY","TRANSFER",unit.id(),unit.version(),"SERIAL")));
        assertEquals("NONE",products.get(maker.workspace(),maker.entity(),product).tracking());
        assertEquals("Edited new goods",products.get(maker.workspace(),maker.entity(),product).name());
    }

    private record SalesFixture(Context seller,long order,long quote,long orderLine,long warehouse,long product,long customerAccount) {}

    private SalesFixture salesFixture(BigDecimal quantity,BigDecimal price,BigDecimal discountAmount,BigDecimal taxRate) {
        var purchased=purchase(quantity.add(BigDecimal.TEN),new BigDecimal("60.00"),null,null);
        var c=peer(purchased.creator(),"INSIDE_SALES","SALES_MANAGER","OPERATIONS","FINANCE");
        products.publish(c.workspace(),c.entity(),purchased.product(),1);
        for(var account:List.of(new String[]{"AR","ASSET"},new String[]{"UNBILLED","ASSET"},new String[]{"GOODS_REVENUE","INCOME"},new String[]{"COGS","EXPENSE"}))
            finance.account(c.workspace(),c.entity(),account[0],account[0],account[1]);
        salesPosting.save(c.workspace(),c.entity(),new dev.labtrial.salestools.finance.SalesPostingConfigService.Draft(
                0,salesReference(c,"AR"),salesReference(c,"UNBILLED"),salesReference(c,"GOODS_REVENUE"),salesReference(c,"COGS")));
        finance.tax(c.workspace(),c.entity(),"GOODS-TAX","Synthetic goods tax",DAY,null,taxRate,false,
                salesReference(c,"TAX_INPUT").id(),salesReference(c,"TAX_OUTPUT").id());
        long tax=db.queryForObject("select v.id from tax_versions v join tax_codes t on t.workspace_id=v.workspace_id and t.entity_id=v.entity_id and t.id=v.tax_code_id where t.workspace_id=? and t.entity_id=? and t.code='GOODS-TAX'",Long.class,c.workspace(),c.entity());
        long book=priceBooks.save(c.workspace(),c.entity(),null,null,"GOODS-BOOK","New goods prices","EUR",DAY,null);
        priceBooks.line(c.workspace(),c.entity(),book,1,purchased.product(),2,null,price);
        var checker=peer(c,"SALES_MANAGER");
        priceBooks.approve(c.workspace(),c.entity(),book,2);
        actor(c.user());
        long customer=((Number)salesQuotes.customers(c.workspace(),c.entity()).get(0).get("id")).longValue();
        var product=products.get(c.workspace(),c.entity(),purchased.product());
        var unit=configuration.get(c.workspace(),c.entity(),ConfigurationService.Kind.UOM,product.uomId());
        long quote=salesQuotes.save(c.workspace(),c.entity(),null,null,new dev.labtrial.salestools.order.QuoteService.Draft(
                "DIRECT_ERP",customer,1,null,DAY.plusDays(2),DAY.plusDays(30),1,null,null,
                List.of(new dev.labtrial.salestools.order.QuoteService.LineInput("GOODS-LINE",product.id(),product.version(),
                        products.setting(c.workspace(),c.entity(),product.id()).version(),unit.version(),book,3,1,
                        quantity,null,null,discountAmount,tax,1))));
        salesQuotes.submit(c.workspace(),c.entity(),quote,1);
        salesQuotes.accept(c.workspace(),c.entity(),quote,2,DAY.plusDays(2),"SYNTHETIC-CUSTOMER-PO");
        var commercial=salesQuotes.quote(c.workspace(),c.entity(),quote);
        long order=salesHandover.execute(c.workspace(),c.entity(),quote,((Number)commercial.get("revision_id")).longValue(),
                ((Number)commercial.get("version")).longValue(),"BOOK-"+UUID.randomUUID()).orderId();
        long line=db.queryForObject("select id from sales_order_lines where workspace_id=? and entity_id=? and order_id=?",Long.class,c.workspace(),c.entity(),order);
        long warehouse=configuration.list(c.workspace(),c.entity(),ConfigurationService.Kind.WAREHOUSE,"").get(0).id();
        return new SalesFixture(c,order,quote,line,warehouse,product.id(),customer);
    }

    private void reserveGoods(SalesFixture f,BigDecimal quantity) {
        var c=f.seller(); actor(c.user());
        long ov=db.queryForObject("select version from sales_orders where id=?",Long.class,f.order());
        long lv=db.queryForObject("select version from sales_order_lines where id=?",Long.class,f.orderLine());
        long wv=configuration.get(c.workspace(),c.entity(),ConfigurationService.Kind.WAREHOUSE,f.warehouse()).version();
        fulfillment.reserve(c.workspace(),c.entity(),f.order(),ov,f.orderLine(),lv,f.warehouse(),wv,null,null,quantity,"RESERVE-"+UUID.randomUUID());
    }

    private long shipGoods(SalesFixture f,BigDecimal quantity,LocalDate date) {
        var c=f.seller(); actor(c.user());
        long ov=db.queryForObject("select version from sales_orders where id=?",Long.class,f.order());
        long lv=db.queryForObject("select version from sales_order_lines where id=?",Long.class,f.orderLine());
        long wv=configuration.get(c.workspace(),c.entity(),ConfigurationService.Kind.WAREHOUSE,f.warehouse()).version();
        long shipment=fulfillment.saveShipment(c.workspace(),c.entity(),null,null,
                new dev.labtrial.salestools.order.FulfillmentService.ShipmentDraft("SHIP-"+UUID.randomUUID(),f.order(),ov,date,
                        List.of(new dev.labtrial.salestools.order.FulfillmentService.ShipmentLineDraft("GOODS",f.orderLine(),lv,f.warehouse(),wv,quantity))));
        fulfillment.postShipment(c.workspace(),c.entity(),shipment,1);
        return db.queryForObject("select id from shipment_lines where workspace_id=? and entity_id=? and shipment_id=? and active",Long.class,c.workspace(),c.entity(),shipment);
    }

    private long invoiceGoods(SalesFixture f,long shipmentLine,String start,String end,LocalDate date) {
        var c=f.seller(); actor(c.user());
        long invoice=salesInvoices.save(c.workspace(),c.entity(),null,0,new dev.labtrial.salestools.finance.SalesInvoiceService.Draft(
                "INVOICE-"+UUID.randomUUID(),date,List.of(new dev.labtrial.salestools.finance.SalesInvoiceService.Line(
                        "GOODS",shipmentLine,new BigDecimal(start),new BigDecimal(end)))));
        salesInvoices.post(c.workspace(),c.entity(),invoice,1);
        return invoice;
    }

    @Test void partialShipmentCancellationPreservesOpenQuantityReservationsAndSingleOrderLineage() {
        var f=salesFixture(new BigDecimal("10"),new BigDecimal("125.00"),null,new BigDecimal("7"));
        var c=f.seller();
        reserveGoods(f,new BigDecimal("7"));
        shipGoods(f,new BigDecimal("3"),DAY.plusDays(3));
        long ov=db.queryForObject("select version from sales_orders where id=?",Long.class,f.order());
        long lv=db.queryForObject("select version from sales_order_lines where id=?",Long.class,f.orderLine());
        fulfillment.cancel(c.workspace(),c.entity(),f.order(),ov,f.orderLine(),lv,new BigDecimal("4"),"Customer reduced remaining requirement","CANCEL-FOUR");
        var line=db.queryForMap("select fulfilled_qty,cancelled_qty from sales_order_lines where id=?",f.orderLine());
        assertEquals(new BigDecimal("3.000000"),line.get("fulfilled_qty"));
        assertEquals(new BigDecimal("4.000000"),line.get("cancelled_qty"));
        assertEquals(new BigDecimal("3.000000"),db.queryForObject("select sum(quantity-released_qty-consumed_qty) from inventory_reservations where order_line_id=?",BigDecimal.class,f.orderLine()));
        shipGoods(f,new BigDecimal("3"),DAY.plusDays(4));
        assertEquals(new BigDecimal("6.000000"),db.queryForObject("select fulfilled_qty from sales_order_lines where id=?",BigDecimal.class,f.orderLine()));
        assertEquals(new BigDecimal("0.000000"),db.queryForObject("select sum(quantity-released_qty-consumed_qty) from inventory_reservations where order_line_id=?",BigDecimal.class,f.orderLine()));
        var quote=salesQuotes.quote(c.workspace(),c.entity(),f.quote());
        assertEquals(f.order(),salesHandover.execute(c.workspace(),c.entity(),f.quote(),((Number)quote.get("revision_id")).longValue(),1,"RETRY-NEW-INTENT").orderId());
        assertEquals(1,db.queryForObject("select count(*) from sales_orders where workspace_id=? and quote_id=?",Integer.class,c.workspace(),f.quote()));
    }

    @Test void middleInvoiceCorrectionRebillsOriginalResidualSliceWithoutReversingShipment() {
        var f=salesFixture(new BigDecimal("3"),BigDecimal.ONE,BigDecimal.ONE,new BigDecimal("7"));
        var c=f.seller(); reserveGoods(f,new BigDecimal("3"));
        long delivered=shipGoods(f,new BigDecimal("3"),DAY.plusDays(3));
        long first=invoiceGoods(f,delivered,"0","1",DAY.plusDays(3));
        long middle=invoiceGoods(f,delivered,"1","2",DAY.plusDays(3));
        long last=invoiceGoods(f,delivered,"2","3",DAY.plusDays(3));
        for(var expected:List.of(new Object[]{first,"0.67","0.33","0.05"},new Object[]{middle,"0.66","0.34","0.04"},new Object[]{last,"0.67","0.33","0.05"})) {
            var actual=db.queryForMap("select net,discount,tax from sales_invoice_lines where invoice_id=?",expected[0]);
            assertEquals(new BigDecimal((String)expected[1]),actual.get("net"));
            assertEquals(new BigDecimal((String)expected[2]),actual.get("discount"));
            assertEquals(new BigDecimal((String)expected[3]),actual.get("tax"));
        }
        var original=db.queryForMap("select slice_start,slice_end,subtotal,discount,net,tax,gross from sales_invoice_lines where invoice_id=?",middle);
        var stock=db.queryForMap("select quantity,value,pool_sequence from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?",c.workspace(),c.entity(),f.product());
        salesInvoices.reverse(c.workspace(),c.entity(),middle,DAY.plusDays(4),"Correct middle source interval","MIDDLE-REVERSAL");
        long rebill=invoiceGoods(f,delivered,"1","2",DAY.plusDays(4));
        assertEquals(original,db.queryForMap("select slice_start,slice_end,subtotal,discount,net,tax,gross from sales_invoice_lines where invoice_id=?",rebill));
        assertEquals(stock,db.queryForMap("select quantity,value,pool_sequence from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=?",c.workspace(),c.entity(),f.product()));
        assertEquals(new BigDecimal("2.00"),db.queryForObject("select sum(l.credit-l.debit) from journal_lines l join journal_entries j on j.id=l.journal_id where j.workspace_id=? and j.entity_id=? and l.account_id=?",BigDecimal.class,c.workspace(),c.entity(),salesReference(c,"GOODS_REVENUE").id()));
    }

    private long receiptGoods(SalesFixture f,String key,LocalDate date,String amount,
            List<dev.labtrial.salestools.finance.CustomerReceiptService.Allocation> allocations) {
        var c=f.seller(); actor(c.user());
        return customerReceipts.save(c.workspace(),c.entity(),null,0,
            new dev.labtrial.salestools.finance.CustomerReceiptService.Draft(key,f.customerAccount(),1,1,1,date,"EUR",
                    new BigDecimal(amount),allocations));
    }

    @Test void fullyAllocatedCustomerCashAndFutureFullReversalPreserveAsOfAndInvoiceCorrectionGate() {
        var f=salesFixture(new BigDecimal("11"),new BigDecimal("10.00"),null,BigDecimal.ZERO);
        var c=f.seller(); reserveGoods(f,new BigDecimal("11"));
        long delivered=shipGoods(f,new BigDecimal("11"),DAY.plusDays(3));
        long a=invoiceGoods(f,delivered,"0","6",DAY.plusDays(3));
        long b=invoiceGoods(f,delivered,"6","11",DAY.plusDays(3));
        long bad=receiptGoods(f,"INVALID-SUM",DAY.plusDays(4),"100.00",List.of(
                new dev.labtrial.salestools.finance.CustomerReceiptService.Allocation(a,new BigDecimal("60.00")),
                new dev.labtrial.salestools.finance.CustomerReceiptService.Allocation(b,new BigDecimal("50.00"))));
        int journals=db.queryForObject("select count(*) from journal_entries where workspace_id=?",Integer.class,c.workspace());
        denied(400,()->customerReceipts.post(c.workspace(),c.entity(),bad,1));
        assertEquals(journals,db.queryForObject("select count(*) from journal_entries where workspace_id=?",Integer.class,c.workspace()));
        long cash=receiptGoods(f,"VALID-100",DAY.plusDays(4),"100.00",List.of(
                new dev.labtrial.salestools.finance.CustomerReceiptService.Allocation(a,new BigDecimal("60.00")),
                new dev.labtrial.salestools.finance.CustomerReceiptService.Allocation(b,new BigDecimal("40.00"))));
        customerReceipts.post(c.workspace(),c.entity(),cash,1);
        assertEquals(new BigDecimal("10.00"),customerReceipts.aging(c.workspace(),c.entity(),DAY.plusDays(4)).stream()
                .map(row->(BigDecimal)row.get("open_amount")).reduce(BigDecimal.ZERO,BigDecimal::add));
        customerReceipts.reverse(c.workspace(),c.entity(),cash,DAY.plusDays(6),"Reverse complete synthetic receipt","CASH-FULL-REVERSAL");
        customerReceipts.reverse(c.workspace(),c.entity(),cash,DAY.plusDays(6),"Reverse complete synthetic receipt","CASH-FULL-REVERSAL");
        assertEquals(new BigDecimal("10.00"),customerReceipts.aging(c.workspace(),c.entity(),DAY.plusDays(5)).stream()
                .map(row->(BigDecimal)row.get("open_amount")).reduce(BigDecimal.ZERO,BigDecimal::add));
        assertEquals(new BigDecimal("110.00"),customerReceipts.aging(c.workspace(),c.entity(),DAY.plusDays(6)).stream()
                .map(row->(BigDecimal)row.get("open_amount")).reduce(BigDecimal.ZERO,BigDecimal::add));
        denied(409,()->salesInvoices.reverse(c.workspace(),c.entity(),a,DAY.plusDays(5),"Cash still effective","EARLY-INVOICE-REVERSAL"));
        assertEquals(0,db.queryForObject("select count(*) from sales_reversals where workspace_id=? and invoice_id=?",Integer.class,c.workspace(),a));
        salesInvoices.reverse(c.workspace(),c.entity(),a,DAY.plusDays(6),"Cash fully reversed first","ALLOWED-INVOICE-REVERSAL");
        assertEquals(new BigDecimal("50.00"),customerReceipts.aging(c.workspace(),c.entity(),DAY.plusDays(6)).stream()
                .map(row->(BigDecimal)row.get("open_amount")).reduce(BigDecimal.ZERO,BigDecimal::add));
    }

    @Test void distinctCustomerReceiptsCompeteForOneBalanceWithoutDoubleCashOrAr() throws Exception {
        var f=salesFixture(BigDecimal.ONE,new BigDecimal("100.00"),null,BigDecimal.ZERO);
        var c=f.seller(); reserveGoods(f,BigDecimal.ONE);
        long delivered=shipGoods(f,BigDecimal.ONE,DAY.plusDays(3));
        long invoice=invoiceGoods(f,delivered,"0","1",DAY.plusDays(3));
        var allocations=List.of(new dev.labtrial.salestools.finance.CustomerReceiptService.Allocation(invoice,new BigDecimal("100.00")));
        long a=receiptGoods(f,"CASH-COMPETE-A",DAY.plusDays(4),"100.00",allocations);
        long b=receiptGoods(f,"CASH-COMPETE-B",DAY.plusDays(4),"100.00",allocations);
        assertEquals(List.of(200,409),competing(c,
                ()->customerReceipts.post(c.workspace(),c.entity(),a,1),
                ()->customerReceipts.post(c.workspace(),c.entity(),b,1)));
        actor(c.user());
        assertEquals(1,db.queryForObject("select count(*) from customer_receipts where workspace_id=? and status='POSTED'",Integer.class,c.workspace()));
        assertEquals(new BigDecimal("0.00"),customerReceipts.aging(c.workspace(),c.entity(),DAY.plusDays(4)).get(0).get("open_amount"));
        assertEquals(new BigDecimal("100.00"),db.queryForObject("select sum(l.debit-l.credit) from journal_lines l join journal_entries j on j.id=l.journal_id where j.workspace_id=? and j.source_type='CUSTOMER_RECEIPT' and l.account_id=?",BigDecimal.class,c.workspace(),salesReference(c,"BANK").id()));
    }

    @Test void reactivationUsesPositiveFulfillmentAtExactBoundaryAndRetainsOneResolvedCycle() {
        var f=salesFixture(BigDecimal.ONE,new BigDecimal("10.00"),null,BigDecimal.ZERO);
        var c=f.seller(); reserveGoods(f,BigDecimal.ONE);
        LocalDate fulfilled=DAY.plusDays(3);
        shipGoods(f,BigDecimal.ONE,fulfilled);
        long customer=db.queryForObject("select customer_id from customer_entity_accounts where id=?",Long.class,f.customerAccount());
        long rule=reactivation.createRule(c.workspace(),c.entity(),"DORMANT",180,"DORMANT-RULE-ONE");
        assertEquals("NOT_DORMANT",reactivation.scan(c.workspace(),c.entity(),rule,fulfilled.plusDays(179)).stream()
                .filter(row->row.customerId()==customer).findFirst().orElseThrow().outcome());
        var candidate=reactivation.scan(c.workspace(),c.entity(),rule,fulfilled.plusDays(180)).stream()
                .filter(row->row.customerId()==customer).findFirst().orElseThrow();
        assertEquals("ELIGIBLE",candidate.outcome());
        long caseId=candidate.caseId();
        assertTrue(reactivation.scan(c.workspace(),c.entity(),rule,fulfilled.plusDays(180)).stream()
                .anyMatch(row->row.outcome().equals("NO_HISTORY")));
        long task=reactivation.followUp(c.workspace(),c.entity(),caseId,1,"Review dormant synthetic customer",
                java.time.OffsetDateTime.parse("2027-08-01T09:00:00Z"));
        reactivation.transition(c.workspace(),c.entity(),caseId,2,"RESOLVED","Customer declined follow-up");
        long newRule=reactivation.createRule(c.workspace(),c.entity(),"DORMANT",181,"DORMANT-RULE-TWO");
        var rescanned=reactivation.scan(c.workspace(),c.entity(),newRule,fulfilled.plusDays(182)).stream()
                .filter(row->row.customerId()==customer).findFirst().orElseThrow();
        assertEquals("HELD",rescanned.outcome());
        assertEquals(caseId,rescanned.caseId());
        assertEquals(1,db.queryForObject("select count(*) from crm_reactivation_cases where workspace_id=? and customer_id=?",Integer.class,c.workspace(),customer));
        assertEquals(task,db.queryForObject("select follow_up_activity_id from crm_reactivation_cases where id=?",Long.class,caseId));
        denied(409,()->reactivation.followUp(c.workspace(),c.entity(),caseId,3,"Duplicate forbidden",null));
        assertEquals(1,db.queryForObject("select count(*) from crm_activities where workspace_id=? and customer_id=?",Integer.class,c.workspace(),customer));
    }

    private long anotherGoodsQuote(SalesFixture f,BigDecimal quantity,BigDecimal override,BigDecimal discount) {
        var c=f.seller(); actor(c.user());
        var p=products.get(c.workspace(),c.entity(),f.product());
        var unit=configuration.get(c.workspace(),c.entity(),ConfigurationService.Kind.UOM,p.uomId());
        var book=db.queryForMap("select id,version from price_books where workspace_id=? and entity_id=? and code='GOODS-BOOK'",c.workspace(),c.entity());
        long bookId=((Number)book.get("id")).longValue();
        long lineVersion=db.queryForObject("select version from price_book_lines where workspace_id=? and entity_id=? and price_book_id=? and product_id=?",Long.class,c.workspace(),c.entity(),bookId,f.product());
        var tax=db.queryForMap("select v.id,v.version from tax_versions v join tax_codes t on t.workspace_id=v.workspace_id and t.entity_id=v.entity_id and t.id=v.tax_code_id where t.workspace_id=? and t.entity_id=? and t.code='GOODS-TAX'",c.workspace(),c.entity());
        return salesQuotes.save(c.workspace(),c.entity(),null,null,new dev.labtrial.salestools.order.QuoteService.Draft(
            "DIRECT_ERP",f.customerAccount(),1,null,DAY.plusDays(2),DAY.plusDays(30),1,null,null,
            List.of(new dev.labtrial.salestools.order.QuoteService.LineInput("GOODS-LINE",p.id(),p.version(),products.setting(c.workspace(),c.entity(),p.id()).version(),
                unit.version(),bookId,((Number)book.get("version")).longValue(),lineVersion,quantity,override,null,discount,
                ((Number)tax.get("id")).longValue(),((Number)tax.get("version")).longValue()))));
    }

    @Test void manualPriceOverrideNeedsIndependentExactRevisionApprovalAndRevisionInvalidatesIt() {
        var f=salesFixture(BigDecimal.ONE,new BigDecimal("100.00"),null,BigDecimal.ZERO);
        var c=f.seller();
        long quote=anotherGoodsQuote(f,BigDecimal.ONE,new BigDecimal("100.00"),null);
        salesQuotes.submit(c.workspace(),c.entity(),quote,1);
        assertEquals("SUBMITTED",salesQuotes.quote(c.workspace(),c.entity(),quote).get("status"));
        denied(403,()->salesQuotes.decide(c.workspace(),c.entity(),quote,2,true,"I cannot approve my own override"));
        denied(409,()->salesQuotes.accept(c.workspace(),c.entity(),quote,2,DAY.plusDays(2),"EARLY-PO"));
        var checker=peer(c,"SALES_MANAGER");
        salesQuotes.decide(c.workspace(),c.entity(),quote,2,true,"Independently reviewed manual override");
        actor(c.user());
        salesQuotes.revise(c.workspace(),c.entity(),quote,3);
        var revised=salesQuotes.quote(c.workspace(),c.entity(),quote);
        assertEquals("DRAFT",revised.get("status"));
        assertEquals(2L,((Number)revised.get("revision")).longValue());
        denied(409,()->salesQuotes.accept(c.workspace(),c.entity(),quote,4,DAY.plusDays(2),"OLD-APPROVAL-PO"));
        assertEquals(0,db.queryForObject("select count(*) from sales_orders where workspace_id=? and quote_id=?",Integer.class,c.workspace(),quote));
        assertEquals(1,db.queryForObject("select count(*) from quote_events e join quote_revisions r on r.workspace_id=e.workspace_id and r.entity_id=e.entity_id and r.id=e.revision_id where r.workspace_id=? and r.quote_id=? and e.kind='APPROVED'",Integer.class,c.workspace(),quote));
    }

    @Test void competingGoodsReservationsCannotOwnTheSameAvailableUnitsTwice() throws Exception {
        var f=salesFixture(new BigDecimal("20"),new BigDecimal("100.00"),null,BigDecimal.ZERO);
        var c=f.seller();
        long ov=db.queryForObject("select version from sales_orders where id=?",Long.class,f.order());
        long lv=db.queryForObject("select version from sales_order_lines where id=?",Long.class,f.orderLine());
        long wv=configuration.get(c.workspace(),c.entity(),ConfigurationService.Kind.WAREHOUSE,f.warehouse()).version();
        assertEquals(List.of(200,409),competing(c,
            ()->fulfillment.reserve(c.workspace(),c.entity(),f.order(),ov,f.orderLine(),lv,f.warehouse(),wv,null,null,new BigDecimal("20"),"RESERVATION-A"),
            ()->fulfillment.reserve(c.workspace(),c.entity(),f.order(),ov,f.orderLine(),lv,f.warehouse(),wv,null,null,new BigDecimal("20"),"RESERVATION-B")));
        actor(c.user());
        assertEquals(new BigDecimal("20.000000"),db.queryForObject("select reserved from inventory_balances where workspace_id=? and entity_id=? and product_id=? and warehouse_id=?",BigDecimal.class,c.workspace(),c.entity(),f.product(),f.warehouse()));
        assertEquals(new BigDecimal("30.000000"),db.queryForObject("select on_hand from inventory_balances where workspace_id=? and entity_id=? and product_id=? and warehouse_id=?",BigDecimal.class,c.workspace(),c.entity(),f.product(),f.warehouse()));
    }

    @Test void oneMultilineShipmentPreservesDuplicateSkuLineEntitlementsAndOneBalancedJournal() {
        var f=salesFixture(new BigDecimal("5"),new BigDecimal("10.00"),null,BigDecimal.ZERO);
        var c=f.seller();
        var original=salesQuotes.lines(c.workspace(),c.entity(),f.quote()).get(0);
        var inputs=new ArrayList<dev.labtrial.salestools.order.QuoteService.LineInput>();
        for(var pair:List.of(new String[]{"FIRST","2"},new String[]{"SECOND","3"}))
            inputs.add(new dev.labtrial.salestools.order.QuoteService.LineInput(pair[0],f.product(),
                ((Number)original.get("product_version")).longValue(),((Number)original.get("product_setting_version")).longValue(),
                ((Number)original.get("uom_version")).longValue(),((Number)original.get("price_book_id")).longValue(),
                ((Number)original.get("price_book_version")).longValue(),((Number)original.get("price_book_line_version")).longValue(),
                new BigDecimal(pair[1]),null,null,null,((Number)original.get("tax_version_id")).longValue(),
                ((Number)original.get("tax_version_version")).longValue()));
        long quote=salesQuotes.save(c.workspace(),c.entity(),null,null,new dev.labtrial.salestools.order.QuoteService.Draft(
                "DIRECT_ERP",f.customerAccount(),1,null,DAY.plusDays(2),DAY.plusDays(30),1,null,null,inputs));
        salesQuotes.submit(c.workspace(),c.entity(),quote,1);
        salesQuotes.accept(c.workspace(),c.entity(),quote,2,DAY.plusDays(2),"MULTILINE-PO");
        var source=salesQuotes.quote(c.workspace(),c.entity(),quote);
        long order=salesHandover.execute(c.workspace(),c.entity(),quote,((Number)source.get("revision_id")).longValue(),3,"MULTILINE-BOOK").orderId();
        var lines=db.queryForList("select id,quantity from sales_order_lines where order_id=? order by id",order);
        long wv=configuration.get(c.workspace(),c.entity(),ConfigurationService.Kind.WAREHOUSE,f.warehouse()).version();
        for(var line:lines) {
            long ov=db.queryForObject("select version from sales_orders where id=?",Long.class,order);
            long lv=db.queryForObject("select version from sales_order_lines where id=?",Long.class,line.get("id"));
            fulfillment.reserve(c.workspace(),c.entity(),order,ov,((Number)line.get("id")).longValue(),lv,f.warehouse(),wv,null,null,(BigDecimal)line.get("quantity"),"MULTILINE-RESERVE-"+line.get("id"));
        }
        var shipping=new ArrayList<dev.labtrial.salestools.order.FulfillmentService.ShipmentLineDraft>();
        for(var line:lines) shipping.add(new dev.labtrial.salestools.order.FulfillmentService.ShipmentLineDraft(
                "SHIP-"+line.get("id"),((Number)line.get("id")).longValue(),
                db.queryForObject("select version from sales_order_lines where id=?",Long.class,line.get("id")),
                f.warehouse(),wv,(BigDecimal)line.get("quantity")));
        long shipment=fulfillment.saveShipment(c.workspace(),c.entity(),null,null,
                new dev.labtrial.salestools.order.FulfillmentService.ShipmentDraft("MULTILINE-SHIP",order,
                        db.queryForObject("select version from sales_orders where id=?",Long.class,order),DAY.plusDays(3),shipping));
        fulfillment.postShipment(c.workspace(),c.entity(),shipment,1);
        assertEquals(2,db.queryForObject("select count(*) from shipment_lines where shipment_id=? and active",Integer.class,shipment));
        assertEquals(new BigDecimal("5.000000"),db.queryForObject("select sum(fulfilled_qty) from sales_order_lines where order_id=?",BigDecimal.class,order));
        assertEquals(new BigDecimal("10.000000"),db.queryForObject("select on_hand from inventory_balances where workspace_id=? and entity_id=? and product_id=? and warehouse_id=?",BigDecimal.class,c.workspace(),c.entity(),f.product(),f.warehouse()));
        long journal=db.queryForObject("select journal_id from shipments where id=?",Long.class,shipment);
        assertEquals(new BigDecimal("350.00"),db.queryForObject("select sum(debit) from journal_lines where journal_id=?",BigDecimal.class,journal));
        assertEquals(new BigDecimal("350.00"),db.queryForObject("select sum(credit) from journal_lines where journal_id=?",BigDecimal.class,journal));
        assertEquals(1,db.queryForObject("select count(*) from journal_entries where workspace_id=? and source_type='SHIPMENT' and source_id=?",Integer.class,c.workspace(),Long.toString(shipment)));
    }

    @Test void deviceAmountDiscountRequiresIndependentApprovalAndReservedSerialCannotTransfer() {
        var f=salesFixture(BigDecimal.ONE,new BigDecimal("10.00"),null,BigDecimal.ZERO);
        var c=f.seller();
        long product=db.queryForObject("select id from products where workspace_id=? and sku='RGA-UNIT-TR120'",Long.class,c.workspace());
        var p=products.get(c.workspace(),c.entity(),product);
        var unit=configuration.get(c.workspace(),c.entity(),ConfigurationService.Kind.UOM,p.uomId());
        long bookId=priceBooks.save(c.workspace(),c.entity(),null,null,"DEVICE-BOOK","Independently reviewed device prices","EUR",DAY,null);
        priceBooks.line(c.workspace(),c.entity(),bookId,1,product,p.version(),null,new BigDecimal("24000.00"));
        var checker=peer(c,"SALES_MANAGER");
        priceBooks.approve(c.workspace(),c.entity(),bookId,2);
        actor(c.user());
        var book=db.queryForMap("select id,version from price_books where id=?",bookId);
        var price=db.queryForMap("select version from price_book_lines where price_book_id=? and product_id=?",bookId,product);
        var tax=db.queryForMap("select v.id,v.version from tax_versions v join tax_codes t on t.id=v.tax_code_id where t.workspace_id=? and t.code='SIM-VAT7'",c.workspace());
        long quote=salesQuotes.save(c.workspace(),c.entity(),null,null,new dev.labtrial.salestools.order.QuoteService.Draft(
                "DIRECT_ERP",f.customerAccount(),1,null,DAY.plusDays(2),DAY.plusDays(30),1,null,null,
                List.of(new dev.labtrial.salestools.order.QuoteService.LineInput("DEVICE",product,p.version(),1,unit.version(),
                        bookId,((Number)book.get("version")).longValue(),((Number)price.get("version")).longValue(),
                        BigDecimal.ONE,null,null,new BigDecimal("2400.01"),((Number)tax.get("id")).longValue(),((Number)tax.get("version")).longValue()))));
        salesQuotes.submit(c.workspace(),c.entity(),quote,1);
        assertEquals("SUBMITTED",salesQuotes.quote(c.workspace(),c.entity(),quote).get("status"));
        denied(403,()->salesQuotes.decide(c.workspace(),c.entity(),quote,2,true,"Maker cannot approve amount discount"));
        actor(checker.user());
        salesQuotes.decide(c.workspace(),c.entity(),quote,2,true,"Independent greater-than-ten-percent discount");
        actor(c.user());
        salesQuotes.accept(c.workspace(),c.entity(),quote,3,DAY.plusDays(2),"DEVICE-PO");
        var source=salesQuotes.quote(c.workspace(),c.entity(),quote);
        long order=salesHandover.execute(c.workspace(),c.entity(),quote,((Number)source.get("revision_id")).longValue(),4,"DEVICE-BOOK").orderId();
        long line=db.queryForObject("select id from sales_order_lines where order_id=?",Long.class,order);
        long serial=db.queryForObject("select id from instrument_serials where workspace_id=? and entity_id=? and product_id=? and status='IN_STOCK'",Long.class,c.workspace(),c.entity(),product);
        long ov=db.queryForObject("select version from sales_orders where id=?",Long.class,order);
        long lv=db.queryForObject("select version from sales_order_lines where id=?",Long.class,line);
        fulfillment.reserve(c.workspace(),c.entity(),order,ov,line,lv,f.warehouse(),1,serial,null,BigDecimal.ONE,"EXACT-SERIAL");
        long target=configuration.save(c.workspace(),c.entity(),ConfigurationService.Kind.WAREHOUSE,null,null,"SECOND-WH","Second owned warehouse",null,null);
        long move=inventory.saveAction(c.workspace(),c.entity(),null,null,new InventoryService.ActionInput(
                "RESERVED-SERIAL-TRANSFER","TRANSFER",product,p.version(),unit.id(),unit.version(),1,
                f.warehouse(),1L,target,1L,BigDecimal.ONE,null,DAY.plusDays(3),"Must not transfer order-reserved serial",
                List.of(new InventoryService.TrackingInput("SIM-RGA-TR120-00002",null,null,BigDecimal.ONE))));
        denied(409,()->inventory.postAction(c.workspace(),c.entity(),move,1));
        assertEquals("IN_STOCK",db.queryForObject("select status from instrument_serials where id=?",String.class,serial));
        var single=new SalesFixture(c,order,quote,line,f.warehouse(),product,f.customerAccount());
        shipGoods(single,BigDecimal.ONE,DAY.plusDays(3));
        assertEquals("ISSUED",db.queryForObject("select status from instrument_serials where id=?",String.class,serial));
        assertEquals(new BigDecimal("-12000.00"),db.queryForObject("select sum(value_delta) from stock_movements where workspace_id=? and entity_id=? and source_type='SHIPMENT' and serial_id=?",BigDecimal.class,c.workspace(),c.entity(),serial));
    }

    @Test void freeGoodsStillIssueCostAndZeroGlInvoiceCannotBypassClosedPeriodOrCorrectionHistory() {
        var f=salesFixture(BigDecimal.ONE,BigDecimal.ZERO,null,BigDecimal.ZERO);
        var c=f.seller(); reserveGoods(f,BigDecimal.ONE);
        long delivered=shipGoods(f,BigDecimal.ONE,DAY.plusDays(3));
        assertEquals(new BigDecimal("60.00"),db.queryForObject("select cost from shipment_lines where id=?",BigDecimal.class,delivered));
        long invoice=salesInvoices.save(c.workspace(),c.entity(),null,0,new dev.labtrial.salestools.finance.SalesInvoiceService.Draft(
                "FREE-GOODS-INVOICE",DAY.plusDays(3),List.of(new dev.labtrial.salestools.finance.SalesInvoiceService.Line("FREE",delivered,BigDecimal.ZERO,BigDecimal.ONE))));
        long period=db.queryForObject("select id from fiscal_periods where workspace_id=? and entity_id=?",Long.class,c.workspace(),c.entity());
        finance.state(c.workspace(),c.entity(),period,1,"CLOSED");
        int journals=db.queryForObject("select count(*) from journal_entries where workspace_id=?",Integer.class,c.workspace());
        denied(409,()->salesInvoices.post(c.workspace(),c.entity(),invoice,1));
        assertEquals("DRAFT",salesInvoices.detail(c.workspace(),c.entity(),invoice).get("status"));
        assertEquals(journals,db.queryForObject("select count(*) from journal_entries where workspace_id=?",Integer.class,c.workspace()));
        finance.state(c.workspace(),c.entity(),period,2,"OPEN");
        salesInvoices.post(c.workspace(),c.entity(),invoice,1);
        assertNull(salesInvoices.detail(c.workspace(),c.entity(),invoice).get("journal_id"));
        assertEquals(journals,db.queryForObject("select count(*) from journal_entries where workspace_id=?",Integer.class,c.workspace()));
        salesInvoices.reverse(c.workspace(),c.entity(),invoice,DAY.plusDays(4),"Free source correction","FREE-SOURCE-CORRECTION");
        assertNull(db.queryForObject("select journal_id from sales_reversals where invoice_id=?",Long.class,invoice));
        long rebill=invoiceGoods(f,delivered,"0","1",DAY.plusDays(4));
        assertNull(salesInvoices.detail(c.workspace(),c.entity(),rebill).get("journal_id"));
        assertEquals(0,db.queryForObject("select count(*) from customer_receipts where workspace_id=?",Integer.class,c.workspace()));
        assertEquals(new BigDecimal("60.00"),db.queryForObject("select sum(l.debit-l.credit) from journal_lines l join journal_entries j on j.id=l.journal_id where j.workspace_id=? and l.account_id=?",BigDecimal.class,c.workspace(),salesReference(c,"COGS").id()));
    }

    @Test void invoiceIntervalsRejectExtremeExponentBeforeHashAndFutureReversalCannotReleaseEarly() {
        var f=salesFixture(BigDecimal.ONE,new BigDecimal("10.00"),null,BigDecimal.ZERO);
        var c=f.seller(); reserveGoods(f,BigDecimal.ONE);
        long delivered=shipGoods(f,BigDecimal.ONE,DAY.plusDays(3));
        denied(400,()->salesInvoices.save(c.workspace(),c.entity(),null,0,new dev.labtrial.salestools.finance.SalesInvoiceService.Draft(
                "HUGE-INTERVAL",DAY.plusDays(3),List.of(new dev.labtrial.salestools.finance.SalesInvoiceService.Line("BAD",delivered,new BigDecimal("1e2147483647"),BigDecimal.ONE)))));
        long original=invoiceGoods(f,delivered,"0","1",DAY.plusDays(3));
        salesInvoices.reverse(c.workspace(),c.entity(),original,DAY.plusDays(6),"Future effective correction","FUTURE-INVOICE-CORRECTION");
        long early=salesInvoices.save(c.workspace(),c.entity(),null,0,new dev.labtrial.salestools.finance.SalesInvoiceService.Draft(
                "EARLY-REBILL",DAY.plusDays(5),List.of(new dev.labtrial.salestools.finance.SalesInvoiceService.Line("EARLY",delivered,BigDecimal.ZERO,BigDecimal.ONE))));
        denied(409,()->salesInvoices.post(c.workspace(),c.entity(),early,1));
        assertEquals("DRAFT",salesInvoices.detail(c.workspace(),c.entity(),early).get("status"));
        long valid=invoiceGoods(f,delivered,"0","1",DAY.plusDays(6));
        assertNotEquals(original,valid);
        assertEquals(new BigDecimal("10.00"),customerReceipts.aging(c.workspace(),c.entity(),DAY.plusDays(5)).get(0).get("open_amount"));
        assertEquals(new BigDecimal("10.00"),customerReceipts.aging(c.workspace(),c.entity(),DAY.plusDays(6)).get(0).get("open_amount"));
    }

    @Test void restrictedRuntimeCannotRewriteM3HistoryOrInsertLinesIntoBookedCommercialSource() throws Exception {
        var f=salesFixture(BigDecimal.ONE,new BigDecimal("10.00"),null,BigDecimal.ZERO);
        var c=f.seller();
        try(var connection=dataSource.getConnection()) {
            for(String table:List.of("price_book_contributors","price_book_approvals","product_contributors","product_publications",
                    "crm_inquiry_events","crm_routing_rules","crm_routing_evaluations","crm_score_rules","crm_score_evaluations",
                    "crm_reactivation_rules","crm_reactivation_evaluations","crm_reactivation_events","crm_opportunity_events",
                    "crm_lifecycle_events","quote_contributors","quote_events","sales_handover_requests","sales_handover_executions",
                    "product_compatibilities","bundle_components","party_addresses","party_address_versions","document_address_snapshots",
                    "inventory_reservation_events","sales_order_cancellations","sales_reversals")) {
                sqlDenied(connection,"delete from app."+table);
                sqlDenied(connection,"update app."+table+" set workspace_id=workspace_id");
            }
            sqlDenied(connection,"update app.sales_orders set accepted_reference='tampered',version=version+1 where id="+f.order());
            sqlDenied(connection,"update app.sales_order_lines set unit_price=1,version=version+1 where id="+f.orderLine());
            sqlDenied(connection,"insert into app.sales_order_lines(workspace_id,entity_id,order_id,quote_line_id,line_key,product_id,product_version,product_setting_version,uom_id,uom_version,tracking,quantity_scale,quantity,unit_price,subtotal,discount,net,tax,gross,tax_version_id,tax_rate_pct,output_account_id,ar_account_id,unbilled_account_id,revenue_account_id,cogs_account_id,inventory_account_id) select workspace_id,entity_id,order_id,quote_line_id,'LATE-APPEND',product_id,product_version,product_setting_version,uom_id,uom_version,tracking,quantity_scale,quantity,unit_price,subtotal,discount,net,tax,gross,tax_version_id,tax_rate_pct,output_account_id,ar_account_id,unbilled_account_id,revenue_account_id,cogs_account_id,inventory_account_id from app.sales_order_lines where id="+f.orderLine());
        }
        assertEquals(1,db.queryForObject("select count(*) from sales_order_lines where order_id=?",Integer.class,f.order()));
        assertEquals("SYNTHETIC-CUSTOMER-PO",db.queryForObject("select accepted_reference from sales_orders where id=?",String.class,f.order()));
        assertEquals(new BigDecimal("10.00"),db.queryForObject("select net from sales_order_lines where id=?",BigDecimal.class,f.orderLine()));
    }

    @Test void wonIsOnlyAReplaySafeBlockedRequestAndQualificationDoesNotGrantCreditOrCreateOrders() {
        var c=context("EUR","INSIDE_SALES","SALES_MANAGER","FINANCE");
        baseline.initialize(c.workspace(),c.entity(),"O2C_GOODS",1);
        long account=db.queryForObject("select id from customer_entity_accounts where workspace_id=? and entity_id=? order by id limit 1",Long.class,c.workspace(),c.entity());
        var command=new dev.labtrial.salestools.crm.OpportunityService.Qualification(account,1,null,null,
                "Explicitly reviewed new opportunity","SUPPLY_REORDER",c.user(),"Synthetic qualification","QUALIFY-ONCE");
        long opportunity=crmOpportunities.qualify(c.workspace(),c.entity(),command);
        assertEquals(opportunity,crmOpportunities.qualify(c.workspace(),c.entity(),command));
        denied(409,()->crmOpportunities.qualify(c.workspace(),c.entity(),new dev.labtrial.salestools.crm.OpportunityService.Qualification(
                account,1,null,null,"Changed requirement","SUPPLY_REORDER",c.user(),"Synthetic qualification","QUALIFY-ONCE")));
        var result=crmOpportunities.changeStage(c.workspace(),c.entity(),opportunity,1,"WON","Customer intends to proceed","WON-ONCE");
        assertEquals("BLOCKED",result.status());
        assertNull(result.orderId());
        assertTrue(result.blockedReasons().contains("An accepted quote is required"));
        var replay=crmOpportunities.changeStage(c.workspace(),c.entity(),opportunity,1,"WON","Customer intends to proceed","WON-ONCE");
        assertEquals(result.requestId(),replay.requestId());
        assertTrue(replay.replayed());
        assertEquals(1,db.queryForObject("select count(*) from crm_opportunities where workspace_id=?",Integer.class,c.workspace()));
        assertEquals(1,db.queryForObject("select count(*) from sales_handover_requests where workspace_id=?",Integer.class,c.workspace()));
        assertEquals(0,db.queryForObject("select count(*) from sales_orders where workspace_id=?",Integer.class,c.workspace()));
    }

    @Test void crmCreationIntentsReplayAfterMutableChangesButRejectChangedPayloadAndRevokedRole() {
        var c=context("EUR","INSIDE_SALES","SALES_MANAGER");
        var received=java.time.OffsetDateTime.parse("2027-01-15T09:00:00Z");
        var draft=new dev.labtrial.salestools.crm.InquiryService.Draft("REPLAY-SOURCE",received,"Persistent source","Original need",
                null,null,null,null,null,"EU","TRANSFER","NORMAL",null);
        long inquiry=crmInquiries.save(c.workspace(),c.entity(),null,null,draft);
        assertEquals(inquiry,crmInquiries.save(c.workspace(),c.entity(),null,null,draft));
        var activity=new dev.labtrial.salestools.crm.ActivityService.Draft(inquiry,1L,null,null,null,null,
                "TASK","Single follow-up",c.user(),received.plusDays(1),"ACTIVITY-INTENT");
        long id=crmActivities.create(c.workspace(),c.entity(),activity);
        crmInquiries.assign(c.workspace(),c.entity(),inquiry,1,null,"",true);
        assertEquals(id,crmActivities.create(c.workspace(),c.entity(),activity));
        denied(409,()->crmActivities.create(c.workspace(),c.entity(),new dev.labtrial.salestools.crm.ActivityService.Draft(
                inquiry,1L,null,null,null,null,"TASK","Changed follow-up",c.user(),received.plusDays(1),"ACTIVITY-INTENT")));
        long rule=crmScoring.createRule(c.workspace(),c.entity(),"REPLAY-RULE",20,30,25,25,"RULE-INTENT");
        assertEquals(rule,crmScoring.createRule(c.workspace(),c.entity(),"REPLAY-RULE",20,30,25,25,"RULE-INTENT"));
        denied(409,()->crmScoring.createRule(c.workspace(),c.entity(),"REPLAY-RULE",10,30,25,25,"RULE-INTENT"));
        assertEquals(1,db.queryForObject("select count(*) from crm_activities where workspace_id=?",Integer.class,c.workspace()));
        assertEquals(1,db.queryForObject("select count(*) from crm_score_rules where workspace_id=?",Integer.class,c.workspace()));
        actor(administrator);
        identity.role(c.workspace(),c.user(),"INSIDE_SALES",1,false);
        identity.role(c.workspace(),c.user(),"SALES_MANAGER",1,false);
        actor(c.user());
        denied(403,()->crmActivities.create(c.workspace(),c.entity(),activity));
        denied(403,()->crmScoring.createRule(c.workspace(),c.entity(),"REPLAY-RULE",20,30,25,25,"RULE-INTENT"));
    }

    @Test void inquiryCustomerChangeCannotOrphanAnOpportunityAndUnlinkedInquiryCanBeCorrected() {
        var c=context("EUR","INSIDE_SALES","SALES_MANAGER","FINANCE");
        baseline.initialize(c.workspace(),c.entity(),"O2C_GOODS",1);
        var accounts=db.queryForList("select id,customer_id from customer_entity_accounts where workspace_id=? and entity_id=? order by id",c.workspace(),c.entity());
        long account=((Number)accounts.get(0).get("id")).longValue();
        long first=((Number)accounts.get(0).get("customer_id")).longValue(), second=((Number)accounts.get(1).get("customer_id")).longValue();
        var received=java.time.OffsetDateTime.parse("2027-01-15T09:00:00Z");
        var original=new dev.labtrial.salestools.crm.InquiryService.Draft("LINKED-INQUIRY",received,"Original customer","Need",
                first,1L,null,null,null,"EU","TRANSFER","NORMAL",null);
        long inquiry=crmInquiries.save(c.workspace(),c.entity(),null,null,original);
        long opportunity=crmOpportunities.qualify(c.workspace(),c.entity(),new dev.labtrial.salestools.crm.OpportunityService.Qualification(
                account,1,inquiry,1L,"Linked opportunity","EQUIPMENT_SALES",c.user(),"Reviewed source","LINKED-QUALIFICATION"));
        var before=crmInquiries.get(c.workspace(),c.entity(),inquiry);
        int events=crmInquiries.history(c.workspace(),c.entity(),inquiry).size();
        int audits=db.queryForObject("select count(*) from audit_events where workspace_id=?",Integer.class,c.workspace());
        for(Long customer:new Long[]{second,null}) {
            var changed=new dev.labtrial.salestools.crm.InquiryService.Draft(original.sourceKey(),received,"Wrong customer","Need",
                    customer,customer==null?null:1L,null,null,null,"EU","TRANSFER","NORMAL",null);
            denied(409,()->crmInquiries.save(c.workspace(),c.entity(),inquiry,1L,changed));
            assertEquals(before,crmInquiries.get(c.workspace(),c.entity(),inquiry));
            assertEquals(events,crmInquiries.history(c.workspace(),c.entity(),inquiry).size());
            assertEquals(audits,db.queryForObject("select count(*) from audit_events where workspace_id=?",Integer.class,c.workspace()));
        }
        crmOpportunities.changeStage(c.workspace(),c.entity(),opportunity,1,"PROPOSAL","Source association still valid",null);
        assertEquals("PROPOSAL",crmOpportunities.get(c.workspace(),c.entity(),opportunity).stage());
        long unlinked=crmInquiries.save(c.workspace(),c.entity(),null,null,new dev.labtrial.salestools.crm.InquiryService.Draft(
                "UNLINKED-INQUIRY",received,"Correctable","Need",first,1L,null,null,null,"EU","TRANSFER","NORMAL",null));
        crmInquiries.save(c.workspace(),c.entity(),unlinked,1L,new dev.labtrial.salestools.crm.InquiryService.Draft(
                "UNLINKED-INQUIRY",received,"Corrected","Need",second,1L,null,null,null,"EU","TRANSFER","NORMAL",null));
        assertEquals(second,crmInquiries.get(c.workspace(),c.entity(),unlinked).customerId());
    }

    @Test void inquiryCustomerChangeCannotMisattributeCompletedResponseAndUnknownPartyCanBeQualified() {
        var c=context("EUR","INSIDE_SALES","SALES_MANAGER","FINANCE");
        baseline.initialize(c.workspace(),c.entity(),"O2C_GOODS",1);
        var ids=db.queryForList("select customer_id from customer_entity_accounts where workspace_id=? and entity_id=? order by id",Long.class,c.workspace(),c.entity());
        long first=ids.get(0), second=ids.get(1);
        var received=java.time.OffsetDateTime.parse("2027-01-15T09:00:00Z");
        long inquiry=crmInquiries.save(c.workspace(),c.entity(),null,null,new dev.labtrial.salestools.crm.InquiryService.Draft(
                "RESPONSE-INQUIRY",received,"Original conversation","Need",first,1L,null,null,null,"EU","TRANSFER","NORMAL",null));
        long activity=crmActivities.create(c.workspace(),c.entity(),new dev.labtrial.salestools.crm.ActivityService.Draft(
                inquiry,1L,null,null,first,1L,"CALL","Completed response",c.user(),null,"RESPONSE-CREATE"));
        crmActivities.complete(c.workspace(),c.entity(),activity,1,"Customer A responded",true);
        var response=crmInquiries.firstResponse(c.workspace(),c.entity(),inquiry);
        denied(409,()->crmInquiries.save(c.workspace(),c.entity(),inquiry,1L,new dev.labtrial.salestools.crm.InquiryService.Draft(
                "RESPONSE-INQUIRY",received,"Reattributed conversation","Need",second,1L,null,null,null,"EU","TRANSFER","NORMAL",null)));
        assertEquals(first,crmInquiries.get(c.workspace(),c.entity(),inquiry).customerId());
        assertEquals(response,crmInquiries.firstResponse(c.workspace(),c.entity(),inquiry));
        assertEquals(activity,response.activityId());
        long unknown=crmInquiries.save(c.workspace(),c.entity(),null,null,new dev.labtrial.salestools.crm.InquiryService.Draft(
                "UNKNOWN-INQUIRY",received,"Unknown party","Need",null,null,null,null,null,"EU","TRANSFER","NORMAL",null));
        crmActivities.create(c.workspace(),c.entity(),new dev.labtrial.salestools.crm.ActivityService.Draft(
                unknown,1L,null,null,null,null,"TASK","Identify party",c.user(),null,"UNKNOWN-TASK"));
        crmInquiries.save(c.workspace(),c.entity(),unknown,1L,new dev.labtrial.salestools.crm.InquiryService.Draft(
                "UNKNOWN-INQUIRY",received,"Known party","Need",first,1L,null,null,null,"EU","TRANSFER","NORMAL",null));
        assertEquals(first,crmInquiries.get(c.workspace(),c.entity(),unknown).customerId());
    }

    @Test void reactivationHoldsOpenInquiriesAndOpportunitiesUntilBothAreClosed() {
        var f=salesFixture(BigDecimal.ONE,new BigDecimal("10"),null,BigDecimal.ZERO);
        var c=f.seller(); reserveGoods(f,BigDecimal.ONE);
        LocalDate fulfilled=DAY.plusDays(3); shipGoods(f,BigDecimal.ONE,fulfilled);
        long customer=db.queryForObject("select customer_id from customer_entity_accounts where id=?",Long.class,f.customerAccount());
        long rule=reactivation.createRule(c.workspace(),c.entity(),"OPEN-WORK",180,"OPEN-WORK-RULE");
        long inquiry=crmInquiries.save(c.workspace(),c.entity(),null,null,new dev.labtrial.salestools.crm.InquiryService.Draft(
                "OPEN-WORK",java.time.OffsetDateTime.parse("2027-01-15T09:00:00Z"),"Open customer inquiry","Need",
                customer,1L,null,null,null,"EU","TRANSFER","NORMAL",null));
        var heldInquiry=reactivation.scan(c.workspace(),c.entity(),rule,fulfilled.plusDays(180)).stream().filter(r->r.customerId()==customer).findFirst().orElseThrow();
        assertEquals("HELD",heldInquiry.outcome()); assertNull(heldInquiry.caseId());
        assertEquals("Open inquiry or opportunity",heldInquiry.reason());
        crmInquiries.transition(c.workspace(),c.entity(),inquiry,1,"RESOLVED",null,"Inquiry resolved");
        long opportunity=crmOpportunities.qualify(c.workspace(),c.entity(),new dev.labtrial.salestools.crm.OpportunityService.Qualification(
                f.customerAccount(),1,null,null,"Open customer opportunity","SUPPLY_REORDER",c.user(),"Explicit qualification","OPEN-OPPORTUNITY"));
        var heldOpportunity=reactivation.scan(c.workspace(),c.entity(),rule,fulfilled.plusDays(180)).stream().filter(r->r.customerId()==customer).findFirst().orElseThrow();
        assertEquals("HELD",heldOpportunity.outcome()); assertNull(heldOpportunity.caseId());
        assertEquals("Open inquiry or opportunity",heldOpportunity.reason());
        assertEquals(0,db.queryForObject("select count(*) from crm_reactivation_cases where workspace_id=? and customer_id=?",Integer.class,c.workspace(),customer));
        crmOpportunities.changeStage(c.workspace(),c.entity(),opportunity,1,"LOST","Opportunity closed",null);
        var eligible=reactivation.scan(c.workspace(),c.entity(),rule,fulfilled.plusDays(180)).stream().filter(r->r.customerId()==customer).findFirst().orElseThrow();
        assertEquals("ELIGIBLE",eligible.outcome()); assertNotNull(eligible.caseId());
        assertEquals(1,db.queryForObject("select count(*) from crm_reactivation_cases where workspace_id=? and customer_id=?",Integer.class,c.workspace(),customer));
    }

    @Test void reactivationIgnoresFutureShipmentsAndUsesUnpaidPositivePartialFulfillment() {
        var f=salesFixture(new BigDecimal("2"),new BigDecimal("10"),null,BigDecimal.ZERO);
        var c=f.seller(); reserveGoods(f,new BigDecimal("2"));
        LocalDate fulfilled=DAY.plusDays(3);
        long oldLine=shipGoods(f,BigDecimal.ONE,fulfilled);
        shipGoods(f,BigDecimal.ONE,fulfilled.plusDays(200));
        long customer=db.queryForObject("select customer_id from customer_entity_accounts where id=?",Long.class,f.customerAccount());
        long rule=reactivation.createRule(c.workspace(),c.entity(),"FUTURE-PURCHASE",180,"FUTURE-PURCHASE-RULE");
        var eligible=reactivation.scan(c.workspace(),c.entity(),rule,fulfilled.plusDays(180)).stream().filter(r->r.customerId()==customer).findFirst().orElseThrow();
        assertEquals("ELIGIBLE",eligible.outcome()); assertEquals(oldLine,eligible.shipmentLineId()); assertEquals(fulfilled,eligible.purchaseDate());
        assertEquals(0,db.queryForObject("select count(*) from customer_receipts where workspace_id=?",Integer.class,c.workspace()));
        var future=salesFixture(BigDecimal.ONE,new BigDecimal("10"),null,BigDecimal.ZERO);
        var other=future.seller(); reserveGoods(future,BigDecimal.ONE); shipGoods(future,BigDecimal.ONE,fulfilled.plusDays(200));
        long futureCustomer=db.queryForObject("select customer_id from customer_entity_accounts where id=?",Long.class,future.customerAccount());
        long futureRule=reactivation.createRule(other.workspace(),other.entity(),"FUTURE-ONLY",180,"FUTURE-ONLY-RULE");
        var noHistory=reactivation.scan(other.workspace(),other.entity(),futureRule,fulfilled.plusDays(180)).stream().filter(r->r.customerId()==futureCustomer).findFirst().orElseThrow();
        assertEquals("NO_HISTORY",noHistory.outcome()); assertNull(noHistory.shipmentLineId()); assertNull(noHistory.caseId());
    }

    @Test void reactivationExcludesFreeGoodsEvenWhenTheirCostWasIssued() {
        var f=salesFixture(BigDecimal.ONE,BigDecimal.ZERO,null,BigDecimal.ZERO);
        var c=f.seller(); reserveGoods(f,BigDecimal.ONE);
        LocalDate fulfilled=DAY.plusDays(3); long line=shipGoods(f,BigDecimal.ONE,fulfilled);
        assertEquals(BigDecimal.ZERO.setScale(2),db.queryForObject("select net from shipment_lines where id=?",BigDecimal.class,line));
        assertTrue(db.queryForObject("select cost from shipment_lines where id=?",BigDecimal.class,line).signum()>0);
        long customer=db.queryForObject("select customer_id from customer_entity_accounts where id=?",Long.class,f.customerAccount());
        long rule=reactivation.createRule(c.workspace(),c.entity(),"FREE-GOODS",180,"FREE-GOODS-RULE");
        var excluded=reactivation.scan(c.workspace(),c.entity(),rule,fulfilled.plusDays(180)).stream().filter(r->r.customerId()==customer).findFirst().orElseThrow();
        assertEquals("NO_HISTORY",excluded.outcome()); assertNull(excluded.shipmentLineId()); assertNull(excluded.caseId());
        assertEquals(0,db.queryForObject("select count(*) from crm_reactivation_cases where workspace_id=? and customer_id=?",Integer.class,c.workspace(),customer));
    }

    private void sqlDenied(Connection connection, String statement) throws Exception {
        connection.setAutoCommit(false);
        try (var sql = connection.createStatement()) {
            SQLException error = assertThrows(SQLException.class, () -> { sql.execute(statement); connection.commit(); });
            assertTrue(List.of("42501", "23514", "P0001").contains(error.getSQLState()), "Denial must be a privilege/immutability guard, not an unrelated syntax or missing-object failure");
        } finally { connection.rollback(); connection.setAutoCommit(true); }
    }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(HttpClient client, String path, Map<String,String> values) throws Exception {
        String form = values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue())).collect(java.util.stream.Collectors.joining("&"));
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String csrf(String html) {
        var inputs = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (inputs.find()) {
            String input = inputs.group();
            if (input.matches("(?s).*name=[\"']_csrf[\"'].*")) {
                var value = Pattern.compile("value=[\"']([^\"']+)[\"']").matcher(input);
                if (value.find()) return value.group(1);
            }
        }
        throw new AssertionError("The actual rendered form must include its CSRF token.");
    }
}
