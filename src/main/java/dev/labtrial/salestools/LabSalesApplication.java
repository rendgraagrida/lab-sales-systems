package dev.labtrial.salestools;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;
import dev.labtrial.salestools.identity.BootstrapService;
import java.time.LocalDate;
import java.util.Arrays;

@SpringBootApplication
public class LabSalesApplication {
    public static void main(String[] args) {
        if (args.length == 0) {
            SpringApplication.run(LabSalesApplication.class);
            return;
        }
        if (args.length != 1 || !"--bootstrap-admin".equals(args[0])) {
            throw new IllegalArgumentException("Only normal start or explicit --bootstrap-admin is supported.");
        }
        SpringApplication app = new SpringApplication(LabSalesApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        char[] password = required("LAB_BOOTSTRAP_PASSWORD").toCharArray();
        try (var context = app.run()) {
            var result = context.getBean(BootstrapService.class).bootstrap(
                required("LAB_BOOTSTRAP_LOGIN"), password, required("LAB_BOOTSTRAP_WORKSPACE_CODE"),
                required("LAB_BOOTSTRAP_WORKSPACE_NAME"), LocalDate.parse(required("LAB_BOOTSTRAP_BUSINESS_DATE")));
            System.out.println("Explicit bootstrap complete; user=" + result.userId()
                + " workspace=" + result.workspaceId() + " replayed=" + result.replayed());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required local setup input: " + name);
        }
        return value;
    }
}
