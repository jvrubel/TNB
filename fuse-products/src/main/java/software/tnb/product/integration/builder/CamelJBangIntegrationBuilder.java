package software.tnb.product.integration.builder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.regex.Pattern;

public class CamelJBangIntegrationBuilder extends AbstractIntegrationBuilder<CamelJBangIntegrationBuilder> {
    private static final String SCRIPT_NAME = "camel";
    private static boolean hasCamel = false;

    public CamelJBangIntegrationBuilder(String name) {
        super(name);

        if (!hasCamel) {
            // todo check windows
            if (!Arrays.stream(System.getenv("PATH").split(Pattern.quote(File.pathSeparator))).map(Paths::get)
                .anyMatch(dir -> Files.exists(dir.resolve(SCRIPT_NAME)))) {
                throw new RuntimeException("To use CamelJBangIntegrationBuilder a script named '" + SCRIPT_NAME + "' must be in your PATH");
            }
            hasCamel = true;
        }

    }

    @Override
    protected CamelJBangIntegrationBuilder self() {
        return this;
    }
}
