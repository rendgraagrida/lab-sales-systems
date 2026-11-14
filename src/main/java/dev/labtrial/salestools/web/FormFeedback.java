package dev.labtrial.salestools.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Display-only POST feedback. This object never supplies authority or executes a write. */
public final class FormFeedback {
    private static final Set<String> PUBLIC_FIELDS = Set.of(
            "source", "code", "name", "businessDate", "login", "user", "version", "active", "role", "entity",
            "currency", "id", "segment", "territory", "domain", "parent", "firstName", "lastName", "email", "lifecycle",
            "sku", "category", "productLine", "unit", "tracking", "validFrom", "validTo", "erpCode", "customerVersion",
            "accountVersion", "supplierVersion", "credit", "terms", "contact", "linkVersion", "productVersion",
            "settingVersion", "bookVersion", "product", "lineVersion", "amount", "kind", "days", "q", "status",
            "sourceKey", "revision", "date", "description", "accountId", "debit", "startsOn", "endsOn",
            "accountType", "ratePct", "inclusive", "inputAccount", "outputAccount", "bankAccount", "baselineKey", "count",
            "quantityScale", "inventoryAccount", "apAccount", "grniAccount", "adjustmentGainAccount",
            "adjustmentLossAccount", "serviceExpenseAccount", "purpose", "lineKey", "quantity", "productRef",
            "reference", "invoiceDate", "receiptLineId", "unitCost", "discountPct", "discountAmount",
            "subtotal", "discount", "net", "tax", "gross", "paymentKey", "supplierAccountId", "paymentDate",
            "invoiceId", "allocationAmount", "reason", "reversalDate", "bankAccountVersion", "configVersion", "reversalKey",
            "actionKey", "fromWarehouse", "toWarehouse", "value", "serial", "lot", "expiresOn", "trackingQuantity",
            "supplierRef", "requestLineRef", "previousLineId", "taxRef", "serviceProfile", "orderRef", "orderLineRef",
            "warehouseRef", "trackingLineKey", "intent",
            "arAccount", "unbilledAccount", "revenueAccount", "cogsAccount",
            "deferredAccount", "implementationRevenueAccount", "serviceRevenueAccount", "serviceConfigVersion",
            "sourceType", "sourceId", "targetLineKey", "targetUnit", "targetProfile", "targetMatrix",
            "targetAsset", "targetDeviceKey", "targetDeviceUnit", "targetBasis", "targetStart", "targetEnd", "targetRenewal",
            "postingDate", "intentKey",
            "receivedAt", "subject", "need", "customerId", "contactId", "contactVersion",
            "priority", "followUpDue", "assigneeId", "key", "targetId", "enabled", "ruleId", "asOf",
            "territoryConfirmed", "needConfirmed", "timeframeConfirmed", "budgetConfirmed",
            "territoryPoints", "needPoints", "timeframePoints", "budgetPoints", "inquiryId", "inquiryVersion",
            "customerAccountId", "title", "pipeline", "ownerId", "stage", "opportunityId", "opportunityVersion",
            "dueAt", "outcome", "response", "orderVersion", "lineId", "warehouse", "serialId", "lotId",
            "reservationId", "number", "receiptKey", "paymentConfigVersion", "sliceStart", "sliceEnd",
            "origin", "customerRef", "validUntil", "billingAddress", "shippingAddress", "bookRef", "overridePrice", "revisionId", "action",
            "customer", "address", "first", "second", "label");
    private static final Set<String> HIDDEN_FIELDS = Set.of("id", "version", "customerVersion", "accountVersion",
            "supplierVersion", "linkVersion", "productVersion", "settingVersion", "bookVersion", "lineVersion",
            "bankAccountVersion", "configVersion", "previousLineId", "intent", "contactVersion",
            "inquiryVersion", "opportunityVersion", "orderVersion", "lineId", "reservationId", "paymentConfigVersion", "revisionId", "intentKey");
    private static final FormFeedback EMPTY = new FormFeedback(null, null, Map.of(), Map.of(), "");
    private final String action;
    private final String submittedForm;
    private final Map<String, List<String>> values;
    private final Map<String, String> errors;
    private final String message;
    private boolean matched;

    private FormFeedback(String action, String submittedForm, Map<String, List<String>> values,
                         Map<String, String> errors, String message) {
        this.action = action;
        this.submittedForm = submittedForm;
        this.values = values;
        this.errors = errors;
        this.message = message;
    }

    public static FormFeedback empty() { return EMPTY; }

    public static FormFeedback rejected(HttpServletRequest request, String message, List<String> fields) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        boolean shortened = false;
        for (String name : PUBLIC_FIELDS) {
            String[] submitted = request.getParameterValues(name);
            if (submitted == null) continue;
            if (submitted.length > 100) shortened = true;
            List<String> copy = new ArrayList<>(Math.min(submitted.length, 100));
            for (int i = 0; i < submitted.length && i < 100; i++) {
                // Retain valid business fields intact; bound display of forged oversized payloads.
                String value = submitted[i];
                if (value.length() > 2000) shortened = true;
                copy.add(value.length() <= 2000 ? value : value.substring(0, 2000));
            }
            values.put(name, List.copyOf(copy));
        }
        Map<String, String> errors = new LinkedHashMap<>();
        for (String field : fields) {
            if (field != null && field.matches("[A-Za-z][A-Za-z0-9]*(?:\\[[0-9]{1,2}\\])?")) errors.put(field, message);
        }
        String[] identity = request.getParameterValues("_form");
        if (shortened) message += " Oversized input is not fully redisplayed: at most 100 repeated rows and 2000 characters per field are shown. Review and re-enter the intended values before submitting again.";
        String form = identity != null && identity.length == 1 && identity[0].matches("[A-Za-z][A-Za-z0-9_-]{0,159}") ? identity[0] : null;
        return new FormFeedback(request.getRequestURI(), form, Collections.unmodifiableMap(values),
                Collections.unmodifiableMap(errors), message);
    }

    public boolean hasErrors() { return action != null; }
    public boolean matched() { return matched; }
    public String message() { return message; }

    public FormState form(String stableId, String action, Object... selectorPairs) {
        if (!stableId.matches("[A-Za-z][A-Za-z0-9_-]{0,159}") || (selectorPairs.length & 1) != 0) {
            throw new IllegalArgumentException("Invalid server form definition");
        }
        boolean submitted = action.equals(this.action) && stableId.equals(submittedForm);
        if (submitted) {
            for (int i = 0; i < selectorPairs.length; i += 2) {
                String name = String.valueOf(selectorPairs[i]);
                List<String> actual = values.get(name);
                String selected = actual == null || actual.isEmpty() ? "" : actual.get(0);
                if ((actual != null && actual.size() != 1) || !selected.equals(text(selectorPairs[i + 1]))) {
                    submitted = false;
                    break;
                }
            }
        }
        if (submitted) matched = true;
        return new FormState(this, stableId, submitted);
    }

    private static String text(Object value) { return value == null ? "" : value.toString(); }

    public record Issue(String field, String target, String message) {}

    public static final class FormState {
        private final FormFeedback feedback;
        private final String id;
        private final boolean submitted;
        private FormState(FormFeedback feedback, String id, boolean submitted) {
            this.feedback = feedback;
            this.id = id;
            this.submitted = submitted;
        }
        public String formId() { return id; }
        public boolean submitted() { return submitted; }
        public boolean hasErrors() { return submitted && feedback.hasErrors(); }
        public String message() { return hasErrors() ? feedback.message : ""; }
        public String value(String name, Object defaultValue) { return value(name, 0, defaultValue); }
        public String value(String name, int index, Object defaultValue) {
            if (!PUBLIC_FIELDS.contains(name)) return "";
            if (!submitted) return text(defaultValue);
            List<String> values = feedback.values.get(name);
            return values == null || index < 0 || index >= values.size() ? "" : values.get(index);
        }
        public boolean selected(String name, Object option, Object defaultValue) {
            return selected(name, 0, option, defaultValue);
        }
        public boolean selected(String name, int index, Object option, Object defaultValue) {
            return value(name, index, defaultValue).equals(text(option));
        }
        public boolean missingChoice(String name, List<String> visibleChoices) {
            String selected = value(name, "");
            return submitted && !selected.isBlank() && !visibleChoices.contains(selected);
        }
        public boolean checked(String name, boolean defaultValue) {
            return "true".equals(value(name, defaultValue));
        }
        public boolean invalid(String name) { return invalid(name, 0); }
        public boolean invalid(String name, int index) {
            return hasErrors() && (feedback.errors.containsKey(name) || feedback.errors.containsKey(name + "[" + index + "]"));
        }
        public String errorMessage(String name) { return errorMessage(name, 0); }
        public String errorMessage(String name, int index) {
            if (!hasErrors()) return "";
            return feedback.errors.getOrDefault(name + "[" + index + "]", feedback.errors.getOrDefault(name, ""));
        }
        public String id(String name) { return id(name, 0); }
        public String id(String name, int index) { return id + "-" + name + "-" + index; }
        public String errorId(String name) { return errorId(name, 0); }
        public String errorId(String name, int index) { return id(name, index) + "-error"; }
        public String summaryId() { return id + "-errors"; }
        public List<Issue> issues() {
            if (!hasErrors() || feedback.errors.isEmpty()) return List.of();
            List<Issue> issues = new ArrayList<>(feedback.errors.size());
            feedback.errors.forEach((field, message) -> {
                int start = field.indexOf('[');
                String name = start < 0 ? field : field.substring(0, start);
                if (HIDDEN_FIELDS.contains(name)) return;
                int index = start < 0 ? 0 : Integer.parseInt(field.substring(start + 1, field.length() - 1));
                issues.add(new Issue(field, id(name, index), message));
            });
            return issues;
        }
    }
}
