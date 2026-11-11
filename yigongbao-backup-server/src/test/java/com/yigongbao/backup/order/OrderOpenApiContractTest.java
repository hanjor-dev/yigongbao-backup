package com.yigongbao.backup.order;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.yaml.snakeyaml.Yaml;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OrderOpenApiContractTest {

    @Test
    void orderQueryAndResponseSchemasMatchControllerContract() throws Exception {
        Map<String, Object> spec = new Yaml().load(Files.readString(
                Path.of("src/main/resources/openapi/local-backup-v1.yaml")));
        Map<String, Object> paths = map(spec.get("paths"));
        Map<String, Object> listOperation = map(map(paths.get("/api/v1/orders")).get("get"));

        Set<String> documentedParameters = list(listOperation.get("parameters")).stream()
                .map(OrderOpenApiContractTest::map)
                .map(parameter -> String.valueOf(parameter.get("name")))
                .collect(Collectors.toSet());
        Method list = java.util.Arrays.stream(OrderController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("list"))
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .findFirst().orElseThrow();
        Set<String> controllerParameters = java.util.Arrays.stream(list.getParameters())
                .map(parameter -> parameter.getAnnotation(RequestParam.class))
                .map(RequestParam::name)
                .collect(Collectors.toSet());

        assertThat(documentedParameters).isEqualTo(controllerParameters);
        assertThat(ref(listOperation, "responses", "200", "content", "application/json", "schema"))
                .isEqualTo("#/components/schemas/OrderPageEnvelope");

        Map<String, Object> schemas = map(map(spec.get("components")).get("schemas"));
        assertThat(list(map(schemas.get("OrderPageData")).get("required")))
                .containsExactlyInAnyOrder("records", "total", "size", "current", "pages");
        assertThat(ref(schemas, "OrderEnvelope", "allOf", "1", "properties", "data"))
                .isEqualTo("#/components/schemas/OrderSnapshot");
    }

    @Test
    void filterOptionsEndpointAndPayloadAreDocumented() throws Exception {
        Map<String, Object> spec = new Yaml().load(Files.readString(
                Path.of("src/main/resources/openapi/local-backup-v1.yaml")));
        Map<String, Object> paths = map(spec.get("paths"));
        assertThat(paths).containsKey("/api/v1/orders/filter-options");
        Map<String, Object> operation = map(map(paths.get("/api/v1/orders/filter-options")).get("get"));

        assertThat(operation.get("operationId")).isEqualTo("getOrderFilterOptions");
        assertThat(ref(operation, "responses", "200", "content", "application/json", "schema"))
                .isEqualTo("#/components/schemas/OrderFilterOptionsEnvelope");

        Map<String, Object> schemas = map(map(spec.get("components")).get("schemas"));
        assertThat(list(map(schemas.get("OrderFilterOptions")).get("required")))
                .containsExactlyInAnyOrder("orderStatuses", "businessTypes", "physicalDeliveries", "storageStates");
    }

    @Test
    void orderDateBoundaryAcceptsControllerLocalDateTimeFormatWithoutAmbiguousOneOf() throws Exception {
        Map<String, Object> spec = new Yaml().load(Files.readString(
                Path.of("src/main/resources/openapi/local-backup-v1.yaml")));
        Map<String, Object> schemas = map(map(spec.get("components")).get("schemas"));
        Map<String, Object> boundary = map(schemas.get("OrderDateBoundary"));

        assertThat(boundary).containsKey("anyOf").doesNotContainKey("oneOf");
        String localDateTimePattern = list(boundary.get("anyOf")).stream()
                .map(OrderOpenApiContractTest::map)
                .map(value -> value.get("pattern"))
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .findFirst().orElseThrow();
        assertThat(Pattern.compile(localDateTimePattern).matcher("2026-11-10T12:00:00").matches()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static Object ref(Map<String, Object> root, String... path) {
        Object current = root;
        for (String part : path) {
            if (current instanceof Map<?, ?> values) {
                current = values.get(part);
            } else if (current instanceof List<?> values) {
                current = values.get(Integer.parseInt(part));
            } else {
                throw new AssertionError("OpenAPI path missing at " + part);
            }
        }
        return map(current).get("$ref");
    }
}
