/*
 * Copyright 2018 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 * Copyright 2018 SmartBear Software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openapitools.codegen;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.callbacks.Callback;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.apache.commons.lang3.StringUtils;
import org.openapitools.codegen.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

public class OpenAPIDememoizer {
    protected OpenAPI openAPI;
    protected boolean openapi31;

    protected final Logger LOGGER = LoggerFactory.getLogger(OpenAPIDememoizer.class);

    private Set<Integer> seenObjectIds;

    /**
     * Factory constructor for OpenAPIDememoizer.
     */
    public static OpenAPIDememoizer createDememoizer(OpenAPI openAPI) {
        boolean openapi31 = openAPI.getSpecVersion() == SpecVersion.V31;
        return new OpenAPIDememoizer(openAPI, openapi31);
    }

    /**
     * Initializes OpenAPI Dememoizer
     *
     * @param openAPI   OpenAPI
     * @param openapi31 flag for whether schema is OpenAPI 3.1 compliant
     */
    public OpenAPIDememoizer(OpenAPI openAPI, boolean openapi31) {
        this.openAPI = openAPI;
        this.openapi31 = openapi31;
        this.seenObjectIds = new HashSet<Integer>();
    }

    private boolean shouldCloneSchema(Schema schema) {
        int schemaObjectId = System.identityHashCode(schema);
        if (this.seenObjectIds.contains(schemaObjectId)) {
            return true;
        } else {
            this.seenObjectIds.add(schemaObjectId);
            return false;
        }
    }

    private Schema cloneSchema(Schema schema) {
        Schema clone = ModelUtils.cloneSchema(schema, this.openapi31);
        int schemaObjectId = System.identityHashCode(schema);
        this.seenObjectIds.add(schemaObjectId);
        return clone;
    }

    /**
     * Dememoizes the OpenAPI input, which may reuse same Java objects for
     * different spec objects and/or properties.
     */
    protected void dememoize() {
        dememoizePaths();
        dememoizeComponentsSchemas();
        dememoizeComponentsResponses();
    }

    /**
     * Dememoizes inline models in Paths
     */
    protected void dememoizePaths() {
        Paths paths = openAPI.getPaths();
        if (paths == null) {
            return;
        }

        for (Map.Entry<String, PathItem> pathsEntry : paths.entrySet()) {
            PathItem path = pathsEntry.getValue();
            List<Operation> operations = new ArrayList<>(path.readOperations());

            // Include callback operation as well
            for (Operation operation : path.readOperations()) {
                Map<String, Callback> callbacks = operation.getCallbacks();
                if (callbacks != null) {
                    operations.addAll(callbacks.values().stream()
                            .flatMap(callback -> callback.values().stream())
                            .flatMap(pathItem -> pathItem.readOperations().stream())
                            .collect(Collectors.toList()));
                }
            }

            // dememoize PathItem common parameters
            dememoizeParameters(path.getParameters());

            for (Operation operation : operations) {
                dememoizeRequestBody(operation);
                dememoizeParameters(operation.getParameters());
                dememoizeResponses(operation);
            }
        }
    }

    /**
     * Dememoizes schemas in content
     *
     * @param content target content
     */
    protected void dememoizeContent(Content content) {
        if (content == null || content.isEmpty()) {
            return;
        }

        for (String contentType : content.keySet()) {
            MediaType mediaType = content.get(contentType);
            if (mediaType == null) {
                continue;
            } else if (mediaType.getSchema() == null) {
                continue;
            } else {
                Schema mediaTypeSchema = mediaType.getSchema();
                if (this.shouldCloneSchema(mediaTypeSchema)) {
                    mediaTypeSchema = this.cloneSchema(mediaTypeSchema);
                    mediaType.setSchema(mediaTypeSchema);
                }
                dememoizeSchema(mediaTypeSchema, new HashSet<>());
            }
        }
    }


    /**
     * Dememoizes schemas in RequestBody
     *
     * @param operation target operation
     */
    protected void dememoizeRequestBody(Operation operation) {
        RequestBody requestBody = operation.getRequestBody();
        if (requestBody == null) {
            return;
        }

        // unalias $ref
        if (requestBody.get$ref() != null) {
            String ref = ModelUtils.getSimpleRef(requestBody.get$ref());
            requestBody = openAPI.getComponents().getRequestBodies().get(ref);

            if (requestBody == null) {
                return;
            }
        }

        dememoizeContent(requestBody.getContent());
    }

    /**
     * Dememoizes schemas in parameters
     *
     * @param parameters List parameters
     */
    protected void dememoizeParameters(List<Parameter> parameters) {
        if (parameters == null) {
            return;
        }

        for (Parameter parameter : parameters) {
            // dereference parameter
            if (StringUtils.isNotEmpty(parameter.get$ref())) {
                parameter = ModelUtils.getReferencedParameter(openAPI, parameter);
            }

            Schema parameterSchema = parameter.getSchema();
            if (parameterSchema != null) {
                if (this.shouldCloneSchema(parameterSchema)) {
                    parameterSchema = this.cloneSchema(parameterSchema);
                    parameter.setSchema(parameterSchema);
                }
                dememoizeSchema(parameterSchema, new HashSet<>());
            }
        }
    }

    /**
     * Dememoizes schemas in ApiResponses
     *
     * @param operation target operation
     */
    protected void dememoizeResponses(Operation operation) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            return;
        }

        for (Map.Entry<String, ApiResponse> responsesEntry : responses.entrySet()) {
            dememoizeResponse(responsesEntry.getValue());
        }
    }

    /**
     * Dememoizes schemas in ApiResponse
     *
     * @param apiResponse API response
     */
    protected void dememoizeResponse(ApiResponse apiResponse) {
        if (apiResponse != null) {
            dememoizeContent(ModelUtils.getReferencedApiResponse(openAPI, apiResponse).getContent());
            dememoizeHeaders(ModelUtils.getReferencedApiResponse(openAPI, apiResponse).getHeaders());
        }
    }

    /**
     * Dememoizes schemas in headers
     *
     * @param headers a map of headers
     */
    protected void dememoizeHeaders(Map<String, Header> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }

        for (String headerKey : headers.keySet()) {
            Header h = headers.get(headerKey);
            Schema headerSchema = h.getSchema();
            if (this.shouldCloneSchema(headerSchema)) {
                headerSchema = this.cloneSchema(headerSchema);
                h.setSchema(headerSchema);
            }
            dememoizeSchema(headerSchema, new HashSet<>());
        }
    }

    /**
     * Dememoizes schemas in components
     */
    protected void dememoizeComponentsSchemas() {
        Map<String, Schema> schemas = openAPI.getComponents().getSchemas();
        if (schemas == null) {
            return;
        }

        List<String> schemaNames = new ArrayList<String>(schemas.keySet());
        for (String schemaName : schemaNames) {
            Schema schema = schemas.get(schemaName);
            if (schema == null) {
                LOGGER.warn("{} not found in openapi/components/schemas.", schemaName);
            } else {
                // dememoize the schemas
                if (this.shouldCloneSchema(schema)) {
                    schema = this.cloneSchema(schema);
                    schemas.put(schemaName, schema);
                }
                dememoizeSchema(schema, new HashSet<>());
            }
        }
    }

    /**
     * Dememoizes schemas in component's responses.
     */
    protected void dememoizeComponentsResponses() {
        Map<String, ApiResponse> apiResponses = openAPI.getComponents().getResponses();
        if (apiResponses == null) {
            return;
        }

        for (Map.Entry<String, ApiResponse> entry : apiResponses.entrySet()) {
            dememoizeResponse(entry.getValue());
        }
    }

    /**
     * Dememoizes a schema
     *
     * @param schema         Schema
     * @param visitedSchemas a set of visited schemas
     */
    public void dememoizeSchema(Schema schema, Set<Schema> visitedSchemas) {
        if (skipDememoization(schema, visitedSchemas)) {
            return;
        }
        markSchemaAsVisited(schema, visitedSchemas);

        if (ModelUtils.isArraySchema(schema)) { // array
            dememoizeArraySchema(schema, visitedSchemas);
        } else if (schema.getAdditionalProperties() instanceof Schema) { // map
            dememoizeMapSchema(schema, visitedSchemas);
        } else if (ModelUtils.isOneOf(schema)) { // oneOf
            dememoizeOneOfSchema(schema, visitedSchemas);
        } else if (ModelUtils.isAnyOf(schema)) { // anyOf
            dememoizeAnyOfSchema(schema, visitedSchemas);
        } else if (ModelUtils.isAllOfWithProperties(schema)) { // allOf with properties
            dememoizePropertiesSchema(schema, visitedSchemas);
            dememoizeAllOfSchema(schema, visitedSchemas);
        } else if (ModelUtils.isAllOf(schema)) { // allOf
            dememoizeAllOfSchema(schema, visitedSchemas);
        } else if (ModelUtils.isComposedSchema(schema)) { // composed schema
            if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
                dememoizeAllOfSchema(schema, visitedSchemas);
            }
            if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
                dememoizeOneOfSchema(schema, visitedSchemas);
            }
            if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
                dememoizeAnyOfSchema(schema, visitedSchemas);
            }
            if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
                dememoizePropertiesSchema(schema, visitedSchemas);
            }
            if (schema.getAdditionalProperties() != null) {
                dememoizeMapSchema(schema, visitedSchemas);
            }
        } else if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            dememoizePropertiesSchema(schema, visitedSchemas);
        }
    }


    /**
     * Check if dememoization is needed.
     *
     * No dememoization needed if the schema is null or already processed.
     *
     * @param schema         Schema
     * @param visitedSchemas a set of visited schemas
     * @return false if dememoization is needed
     */
    protected boolean skipDememoization(Schema schema, Set<Schema> visitedSchemas) {
        if (schema == null) {
            return true;
        }

        if (visitedSchemas.contains(schema)) {
            return true; // skip due to circular reference
        } else {
            return false;
        }
    }

    /**
     * Add the schema to the collection of visited schemas.
     *
     * @param schema schema to add
     * @param visitedSchemas current visited schemas
     */
    protected void markSchemaAsVisited(Schema schema, Set<Schema> visitedSchemas) {
        if (schema != null) {
            visitedSchemas.add(schema);
        }
    }

    protected void dememoizeArraySchema(Schema schema, Set<Schema> visitedSchemas) {
        Schema itemsSchema = schema.getItems();
        if (this.shouldCloneSchema(itemsSchema)) {
            itemsSchema = this.cloneSchema(itemsSchema);
            schema.setItems(itemsSchema);
        }
        dememoizeSchema(itemsSchema, visitedSchemas);
    }

    protected void dememoizeMapSchema(Schema schema, Set<Schema> visitedSchemas) {
        Schema additionalPropertiesSchema = (Schema) schema.getAdditionalProperties();
        if (this.shouldCloneSchema(additionalPropertiesSchema)) {
            additionalPropertiesSchema = this.cloneSchema(additionalPropertiesSchema);
            schema.setAdditionalProperties(additionalPropertiesSchema);
        }
        dememoizeSchema(additionalPropertiesSchema, visitedSchemas);
    }

    protected void dememoizeOneOfSchema(Schema schema, Set<Schema> visitedSchemas) {
        // loop through the sub-schemas
        List<Schema> oneOf = schema.getOneOf();
        for (int i = 0; i < oneOf.size(); i++) {
            // dememoize oneOf sub schemas one by one
            Object oneOfItemObject = oneOf.get(i);

            if (oneOfItemObject == null) {
                continue;
            }
            if (!(oneOfItemObject instanceof Schema)) {
                throw new RuntimeException("Error! oneOf schema is not of the type Schema: " + oneOfItemObject);
            }
            Schema oneOfItemSchema = (Schema) oneOfItemObject;
            
            if (this.shouldCloneSchema(oneOfItemSchema)) {
                oneOfItemSchema = this.cloneSchema(oneOfItemSchema);
                oneOf.set(i, oneOfItemSchema);
            }
            dememoizeSchema(oneOfItemSchema, visitedSchemas);
        }
    }

    protected void dememoizeAnyOfSchema(Schema schema, Set<Schema> visitedSchemas) {
        // loop through the sub-schemas
        List<Schema> anyOf = schema.getAnyOf();
        for (int i = 0; i < anyOf.size(); i++) {
            // dememoize oneOf sub schemas one by one
            Object anyOfItemObject = anyOf.get(i);

            if (anyOfItemObject == null) {
                continue;
            }
            if (!(anyOfItemObject instanceof Schema)) {
                throw new RuntimeException("Error! anyOf schema is not of the type Schema: " + anyOfItemObject);
            }
            Schema anyOfItemSchema = (Schema) anyOfItemObject;
            
            if (this.shouldCloneSchema(anyOfItemSchema)) {
                anyOfItemSchema = this.cloneSchema(anyOfItemSchema);
                anyOf.set(i, anyOfItemSchema);
            }
            dememoizeSchema(anyOfItemSchema, visitedSchemas);
        }
    }

    protected void dememoizeAllOfSchema(Schema schema, Set<Schema> visitedSchemas) {
        // loop through the sub-schemas
        List<Schema> allOf = schema.getAllOf();
        for (int i = 0; i < allOf.size(); i++) {
            // dememoize oneOf sub schemas one by one
            Object allOfItemObject = allOf.get(i);

            if (allOfItemObject == null) {
                continue;
            }
            if (!(allOfItemObject instanceof Schema)) {
                throw new RuntimeException("Error! allOf schema is not of the type Schema: " + allOfItemObject);
            }
            Schema allOfItemSchema = (Schema) allOfItemObject;
            
            if (this.shouldCloneSchema(allOfItemSchema)) {
                allOfItemSchema = this.cloneSchema(allOfItemSchema);
                allOf.set(i, allOfItemSchema);
            }
            dememoizeSchema(allOfItemSchema, visitedSchemas);
        }
    }

    protected void dememoizePropertiesSchema(Schema schema, Set<Schema> visitedSchemas) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        for (Map.Entry<String, Schema> propertiesEntry : properties.entrySet()) {
            Schema property = propertiesEntry.getValue();
            if (this.shouldCloneSchema(property)) {
                property = this.cloneSchema(property);
                propertiesEntry.setValue(property);
            }
            dememoizeSchema(property, visitedSchemas);
        }
    }
}
