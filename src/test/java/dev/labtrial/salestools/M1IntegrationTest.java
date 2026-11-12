package dev.labtrial.salestools;

import dev.labtrial.salestools.baseline.BaselineService;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.finance.Ledger;
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
        long termsId = configuration.save(c.workspace(), c.entity(), ConfigurationService.Kind.TERMS, null, null, tCode, "Net 30 Initial", 30);
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
        configuration.save(c.workspace(), c.entity(), ConfigurationService.Kind.TERMS, termsId, 1L, tCode, "Net 30 Extended", 35);
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
        long termsId = configuration.save(c.workspace(), c.entity(), ConfigurationService.Kind.TERMS, null, null, tCode, "Net 45 Initial", 45);
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
        configuration.save(c.workspace(), c.entity(), ConfigurationService.Kind.TERMS, termsId, 1L, tCode, "Net 45 Extended", 50);
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
                null, null, "EA", "Each", null);
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
                null, null, "EA", "Each", null);
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
