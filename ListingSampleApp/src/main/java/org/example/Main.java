package org.example;
import com.google.gson.Gson;
import com.amazon.SellingPartnerAPIAA.LWAAuthorizationCredentials;
import com.amazon.SellingPartnerAPIAA.LWAException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.reflect.TypeToken;
import com.networknt.schema.*;
import org.yaml.snakeyaml.Yaml;
import software.amazon.spapi.ApiException;
import software.amazon.spapi.api.catalogitems.v2022_04_01.CatalogApi;
import software.amazon.spapi.api.producttypedefinitions.v2020_09_01.DefinitionsApi;
import software.amazon.spapi.api.sellers.v1.SellersApi;
import software.amazon.spapi.models.catalogitems.v2022_04_01.Item;
import software.amazon.spapi.models.listings.items.v2021_08_01.ListingsItemPutRequest;
import software.amazon.spapi.models.listings.items.v2021_08_01.ListingsItemSubmissionResponse;
import software.amazon.spapi.models.listings.restrictions.v2021_08_01.Restriction;
import software.amazon.spapi.models.listings.restrictions.v2021_08_01.RestrictionList;
import software.amazon.spapi.models.producttypedefinitions.v2020_09_01.ProductType;
import software.amazon.spapi.models.producttypedefinitions.v2020_09_01.ProductTypeDefinition;
import software.amazon.spapi.models.producttypedefinitions.v2020_09_01.ProductTypeList;
import software.amazon.spapi.models.sellers.v1.GetMarketplaceParticipationsResponse;
import software.amazon.spapi.models.catalogitems.v2022_04_01.ItemSearchResults;
import software.amazon.spapi.api.listings.items.v2021_08_01.ListingsApi;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/***
 * This class shows an example to create a new listing from the beginning. It covers
 * How to get the product type
 * How to extract the product type meta schema json and schema json
 * How to validate the payload against product type schema
 * How to submit listing under preview mode
 * More details about SP-API product type definitions and schema check details can be found here:
 * https://developer-docs.amazon.com/sp-api/docs/product-type-definition-meta-schema
 * More details about listing cane be found here
 * https://developer-docs.amazon.com/sp-api/docs/building-listings-management-workflows-guide
 */
public class Main {

    private static String clientId;
    private static String clientSecret;
    private static String refreshToken;
    private static String endpoint;
    private static List<String> marketPlaceIds;
    private static String sellerId;
    private static String sku;
    private static LWAAuthorizationCredentials lwaAuthorizationCredentials;

    public static void main(String[] args) throws LWAException, ApiException, IOException, InterruptedException {

        // Initialize config values such as sellerId and credentials
        System.out.println("Make sure populate all your credentials and config values here");
        String configPath = "./config.yml";
        populateConfigs(configPath);
        

        // Configure your LWA credentials
        lwaAuthorizationCredentials = LWAAuthorizationCredentials.builder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .refreshToken(refreshToken)
                .endpoint("https://api.amazon.com/auth/o2/token")
                .build();


        // check the product already exist in the catalog or not
        List<String> keywords = Arrays.asList("luggage");
        List<String> catalogIncludedData = Arrays.asList("summaries", "attributes");
        boolean productExist = false;
        List<Item> catalogItems = isProductInCatalogAlready(keywords, catalogIncludedData);


        // If product already exist in catalog, check listing restrictions
        if (catalogItems != null) {
            String sampleAsin = catalogItems.get(0).getAsin().toString();
            findListingRestrictions(sampleAsin);
        }


        // get the Product Type using the keywords you like, there could be multiple product type show up
        // pick the one you think most appropriate, here I just pick first one
        ProductTypeList productTypes = searchProductTypes(keywords);
        String productType = productTypes.getProductTypes().get(0).getName();


        // Get Product Type Definition
        ProductTypeDefinition definition = getProductTypeDefinition(productType);


        // extract out the payload schema url and meta schema url
        // meta schema serve as a blueprint such as what vocabulary are needed and what can be ignored
        // payload schema is the actual schema that tell seller what they need to provide values for
        // such as title, description, color, size, etc
        String metaSchemaUrl = definition.getMetaSchema().getLink().getResource();
        String schemaUrl = definition.getSchema().getLink().getResource();


        // Store both json schema file into locally
        String metaSchemaLocalPath = "./metaSchema.json";
        String schemaLocalPath = "./schema.json";
        getSchemaAndStoreLocally(metaSchemaUrl, metaSchemaLocalPath);
        getSchemaAndStoreLocally(schemaUrl, schemaLocalPath);


        // get the meta schema validator
        JsonSchemaFactory metaSchemaFactory = getMetaSchemaJsonFactory(metaSchemaLocalPath);


        // build customized schema based on luggage product type
        JsonSchema luggageSchema = metaSchemaFactory.getSchema(new String(Files.readAllBytes(Paths.get(schemaLocalPath))));


        // validate the payload against the luggage schema to find out syntax error if any
        // The payload values can be populated using website like https://rjsf-team.github.io/react-jsonschema-form/ given the schema json
        String payloadLocalPath = "./payload.json";
        String payloadStr = validatePayload(payloadLocalPath, luggageSchema);

        // Put listing item in validation mode to check non syntax error
        putListingsItemValidationMode(payloadStr, productType);

    }

    public static void populateConfigs(String configPath) throws IOException {
        InputStream inputStream = Files.newInputStream(Paths.get(configPath));
        Yaml yaml = new Yaml();
        Map<String, Object> yamlData = yaml.load(inputStream);
        clientId = (String) yamlData.get("clientId");
        clientSecret = (String) yamlData.get("clientSecret");
        refreshToken = (String) yamlData.get("refreshToken");
        endpoint = (String) yamlData.get("endpoint");
        // For the list of marketplace IDs
        marketPlaceIds = (List<String>) yamlData.get("marketPlaceIds");
        sellerId = (String) yamlData.get("sellerId");
        sku = (String) yamlData.get("sku");
    }

    public static List<Item> isProductInCatalogAlready(List<String> keywords, List<String> catalogIncludedData) throws LWAException, ApiException {
        CatalogApi catalogApi = new CatalogApi.Builder()
                .lwaAuthorizationCredentials(lwaAuthorizationCredentials)
                .endpoint(endpoint)
                .build();

       // Search for the product in the catalog
        try {
            ItemSearchResults results = catalogApi.searchCatalogItems(
                    marketPlaceIds,
                    null,
                    null, // identifiers not used together with keywords
                    catalogIncludedData,
                    null, // locale
                    null, // sellerId
                    keywords,
                    null, // brandNames
                    null,
                    1,
                    null,
                    null);

            // Check if any items were found
            if (results != null && results.getItems() != null && !results.getItems().isEmpty()) {
                System.out.println("Product found in catalog with ASIN: " + results.getItems().get(0).getAsin());
                return results.getItems();
            }
            return null;
        } catch (ApiException e) {
            System.err.println("Exception when calling searchCatalogItems");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            for (StackTraceElement element : e.getStackTrace()) {
                System.out.println(element.toString());
            }
            System.err.println("Response headers: " + e.getResponseHeaders());
            throw e;
        }
    }

    public static void findListingRestrictions(String asin) throws LWAException, ApiException  {
        software.amazon.spapi.api.listings.restrictions.v2021_08_01.ListingsApi restrictionsApi = new software.amazon.spapi.api.listings.restrictions.v2021_08_01.ListingsApi.Builder()
                .lwaAuthorizationCredentials(lwaAuthorizationCredentials)
                .endpoint(endpoint)
                .build();
        String reasonLocale = "en_US"; // Optional: for localized restriction messages

        // Call the API to get listing restrictions
        try {
            RestrictionList restrictions = restrictionsApi.getListingsRestrictions(
                    asin,
                    sellerId,
                    marketPlaceIds,
                    null, // Can be null to check all conditions
                    reasonLocale
            );

            // Process the restrictions
            if (restrictions != null && restrictions.getRestrictions() != null && !restrictions.getRestrictions().isEmpty()) {
                System.out.println("Found " + restrictions.getRestrictions().size() + " restrictions for ASIN: " + asin);

                // Print details of each restriction
                for (Restriction restriction : restrictions.getRestrictions()) {
                    System.out.println("Marketplace: " + restriction.getMarketplaceId());
                    System.out.println("Condition: " + restriction.getConditionType());

                    // Print reasons for restrictions
                    restriction.getReasons().forEach(reason -> {
                        System.out.println("Reason Code: " + reason.getReasonCode());
                        System.out.println("Message: " + reason.getMessage());

                        // Print links for approval if available
                        if (reason.getLinks() != null && !reason.getLinks().isEmpty()) {
                            reason.getLinks().forEach(link -> {
                                System.out.println("Approval Link: " + link.getResource());
                                System.out.println("Link Title: " + link.getTitle());
                            });
                        }
                    });
                    System.out.println("-------------------");
                }
            } else {
                System.out.println("No restrictions found for ASIN: " + asin);
            }
        } catch (ApiException e) {
            System.err.println("Exception when calling getRestrictions");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            throw e;
        }
    }

    public static ProductTypeList searchProductTypes(List<String> ptKeywords) throws LWAException, ApiException {
        // Initialize the Product Type Definitions API client
        DefinitionsApi definitionsApi = new DefinitionsApi.Builder()
                .lwaAuthorizationCredentials(lwaAuthorizationCredentials)
                .endpoint(endpoint)
                .build();

        // Set up request parameters
        String locale = "en_US"; // For localized attribute names
        String searchLocale = "en_US"; // For search terms
        try {
            // Call the API with keywords
            System.out.println("Calling searchDefinitionsProductTypes with keywords: " + String.join(", ", ptKeywords));
            return definitionsApi.searchDefinitionsProductTypes(
                    marketPlaceIds,
                    ptKeywords,
                    null, // itemName (not used when searching by keywords)
                    locale,
                    searchLocale
            );
        } catch (ApiException e) {
            System.err.println("Exception when calling ProductTypeDefinitionsApi#searchDefinitionsProductTypes");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            throw e;
        }
    }

    public static ProductTypeDefinition getProductTypeDefinition(String productType) throws LWAException, ApiException {

        String requirementsEnforced = "NOT_ENFORCED"; // ENFORCED or NOT_ENFORCED
        String requirements = "LISTING_PRODUCT_ONLY";
        // Call the API
        DefinitionsApi definitionsApi = new DefinitionsApi.Builder()
                .lwaAuthorizationCredentials(lwaAuthorizationCredentials)
                .endpoint(endpoint)
                .build();
        System.out.println("Calling getDefinitionsProductType for product type: " + productType);
        try {
            ProductTypeDefinition definition = definitionsApi.getDefinitionsProductType(
                    productType,
                    marketPlaceIds,
                    null,
                    null,
                    requirements,
                    requirementsEnforced,
                    "en_US");
            return definition;
        } catch (ApiException e) {
            System.err.println("Exception when calling ProductTypeDefinitionsApi#getDefinitionsProductType");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            throw e;
        }
    }

    public static void getSchemaAndStoreLocally(String url, String localPath) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest schemaRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<String> schemaResponse = client.send(schemaRequest, HttpResponse.BodyHandlers.ofString());
        String schemaJson = schemaResponse.body();
        Files.write(Paths.get(localPath), schemaJson.getBytes());
    }

    /**
     * Because the sp-api has its own customized rule and vocabulary, so we need to build a customized
     * json schema validator on top of the standardized one
     * @param metaSchemaLocalPath
     * @return
     * @throws IOException
     */
    public static JsonSchemaFactory getMetaSchemaJsonFactory(String metaSchemaLocalPath) throws IOException {
        String schemaId = "https://schemas.amazon.com/selling-partners/definitions/product-types/meta-schema/v1";
        String customMetaSchemaJson = new String(Files.readAllBytes(Paths.get(metaSchemaLocalPath)));

        // Keywords that are informational only and do not require validation.
        Set<String> excludeKeywords = new HashSet<>(Arrays.asList("editable", "enumNames"));
        JsonMetaSchema standardMetaSchema = JsonMetaSchema.getV201909();
        JsonMetaSchema metaSchema = JsonMetaSchema.builder(schemaId, standardMetaSchema)
                .addKeywords(standardMetaSchema.getKeywords().entrySet().stream()
                        .filter(entry -> !excludeKeywords.contains(entry.getKey()))
                        .map(Map.Entry::getValue)
                        .collect(Collectors.toSet()))
                .addKeyword(new MaxUniqueItemsKeyword())
                .addKeyword(new MaxUtf8ByteLengthKeyword())
                .addKeyword(new MinUtf8ByteLengthKeyword())
                .build();

        JsonMetaSchema customMetaSchema = new JsonMetaSchema.Builder(customMetaSchemaJson)
                .addKeywords(metaSchema.getKeywords().values()) // Include standard keywords
                .build();

        JsonSchemaFactory schemaFactory = new JsonSchemaFactory.Builder()
                .defaultMetaSchemaIri(schemaId)
                .addMetaSchema(metaSchema)
                .addMetaSchema(customMetaSchema)
                .build();
        return schemaFactory;
    }

    /**
     * Validate payload and only return values when validation passed
     * @param payloadLocalPath
     * @param schema
     * @return
     * @throws IOException
     */
    public static String validatePayload(String payloadLocalPath, JsonSchema schema) throws IOException {
        JsonNode payload = new ObjectMapper().readValue(new File(payloadLocalPath), JsonNode.class);
        String payloadStr = new String(Files.readAllBytes(Paths.get(payloadLocalPath)));
        Set<ValidationMessage> messages = schema.validate(payload);
        if (messages.size() == 0) {
            return payloadStr;
        }
        for (ValidationMessage message : messages) {
            System.out.println(message.getError());
            System.out.println(message.getSchemaLocation());
            System.out.println(message.getEvaluationPath());
            System.out.println(message.getInstanceLocation());
            System.out.println(message.getInstanceNode());
            System.out.println(message.getMessageKey());
        }
        return null;
    }

    public static ListingsItemSubmissionResponse putListingsItemValidationMode(String payloadStr, String productType) throws IOException, ApiException, LWAException {
        ListingsApi listingsApi = new ListingsApi.Builder()
                .lwaAuthorizationCredentials(lwaAuthorizationCredentials)
                .endpoint(endpoint)
                .build();
        Gson gson = new Gson();
        Type type = new TypeToken<HashMap<String, Object>>(){}.getType();
        ListingsItemPutRequest putRequest = new ListingsItemPutRequest();
        putRequest.setAttributes(gson.fromJson(payloadStr, type));
        putRequest.setRequirements(ListingsItemPutRequest.RequirementsEnum.LISTING_PRODUCT_ONLY);
        putRequest.setProductType(productType);
        try {
            List<String> putIncludedData = Arrays.asList("identifiers", "issues");
            ListingsItemSubmissionResponse listingsItem = listingsApi.putListingsItem(
                    putRequest,
                    sellerId,
                    sku,
                    marketPlaceIds,
                    putIncludedData,
                    "VALIDATION_PREVIEW",
                    "en_US"
            );
            System.out.println("validation mode Listing Status: " + listingsItem.getStatus());
            System.out.println("validation mode Listing issues: " + listingsItem.getIssues());
            return listingsItem;
        } catch (ApiException e) {
            System.err.println("Exception when calling listingsApi.putListingsItem");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            throw e;
        }
    }


}