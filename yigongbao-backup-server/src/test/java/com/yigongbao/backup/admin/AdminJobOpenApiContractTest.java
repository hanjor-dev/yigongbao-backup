package com.yigongbao.backup.admin;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AdminJobOpenApiContractTest {
    @Test
    void taskFilePageParametersAndEnvelopeMatchControllerContract() throws Exception {
        Map<String, Object> spec = new Yaml().load(Files.readString(
                Path.of("src/main/resources/openapi/local-backup-v1.yaml")));
        Map<String, Object> paths = map(spec.get("paths"));
        Map<String, Object> operation = map(map(paths.get("/api/v1/admin/jobs/{id}/files")).get("get"));

        Set<String> documented = list(operation.get("parameters")).stream()
                .map(AdminJobOpenApiContractTest::map)
                .filter(parameter -> "query".equals(parameter.get("in")))
                .map(parameter -> String.valueOf(parameter.get("name")))
                .collect(Collectors.toSet());
        Set<String> controller = Arrays.stream(AdminJobController.class.getDeclaredMethod(
                        "files", long.class, long.class, long.class, Long.class).getParameters())
                .map(parameter -> parameter.getAnnotation(RequestParam.class))
                .filter(java.util.Objects::nonNull)
                .map(RequestParam::name)
                .collect(Collectors.toSet());

        assertThat(documented).isEqualTo(controller)
                .containsExactlyInAnyOrder("current", "size", "sourceOrderId");
        assertThat(ref(operation, "responses", "200", "content", "application/json", "schema"))
                .isEqualTo("#/components/schemas/JobFilePageEnvelope");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) { return (List<Object>) value; }

    private static Object ref(Map<String, Object> root, String... path) {
        Object current = root;
        for (String part : path) current = map(current).get(part);
        return map(current).get("$ref");
    }
}
