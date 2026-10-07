package com.example.customerrewards.controller;

import com.example.customerrewards.dto.request.CreateCustomerRequest;
import com.example.customerrewards.dto.request.EarnPointsRequest;
import com.example.customerrewards.dto.request.RedeemPointsRequest;
import com.example.customerrewards.dto.response.CustomerResponse;
import com.example.customerrewards.dto.response.EarnPointsResponse;
import com.example.customerrewards.dto.response.RewardBalanceResponse;
import com.example.customerrewards.dto.response.RedeemPointsResponse;
import com.example.customerrewards.dto.response.TransactionHistoryResponse;
import com.example.customerrewards.dto.response.TransactionResponse;
import com.example.customerrewards.exception.CustomerNotFoundException;
import com.example.customerrewards.exception.DuplicateCustomerEmailException;
import com.example.customerrewards.exception.InsufficientRewardBalanceException;
import com.example.customerrewards.exception.InvalidRewardPointsException;
import com.example.customerrewards.exception.RewardConcurrencyException;
import com.example.customerrewards.service.CustomerService;
import com.example.customerrewards.service.EarnService;
import com.example.customerrewards.service.RedemptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CustomerRewardsController.class)
class CustomerRewardsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CustomerService customerService;

    @MockitoBean
    private RedemptionService redemptionService;

    @MockitoBean
    private EarnService earnService;

    private UUID customerId;
    private UUID transactionId;
    private Instant now;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        transactionId = UUID.randomUUID();
        now = Instant.now();
    }

    // =========================
    // POST /api/v1/customers
    // =========================

    @Test
    void testCreateCustomer_Success() throws Exception {
        CreateCustomerRequest request = new CreateCustomerRequest("Alice", "Nguyen", "alice@example.com");
        CustomerResponse response = new CustomerResponse(customerId, "Alice", "Nguyen", "alice@example.com", 0, now);

        when(customerService.createCustomer(any(CreateCustomerRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId", notNullValue()))
                .andExpect(jsonPath("$.firstName").value("Alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.rewardBalance").value(0))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testCreateCustomer_DuplicateEmail() throws Exception {
        CreateCustomerRequest request = new CreateCustomerRequest("Alice", "Nguyen", "alice@example.com");

        when(customerService.createCustomer(any(CreateCustomerRequest.class)))
                .thenThrow(new DuplicateCustomerEmailException("alice@example.com"));

        mockMvc.perform(post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CUSTOMER_EMAIL"))
                .andExpect(jsonPath("$.message", containsString("alice@example.com")))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testCreateCustomer_MissingFirstName() throws Exception {
        String json = "{\"firstName\":\"\",\"lastName\":\"Nguyen\",\"email\":\"alice@example.com\"}";

        mockMvc.perform(post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testCreateCustomer_InvalidEmail() throws Exception {
        String json = "{\"firstName\":\"Alice\",\"lastName\":\"Nguyen\",\"email\":\"not-an-email\"}";

        mockMvc.perform(post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testCreateCustomer_WithCorrelationId() throws Exception {
        CreateCustomerRequest request = new CreateCustomerRequest("Alice", "Nguyen", "alice@example.com");
        CustomerResponse response = new CustomerResponse(customerId, "Alice", "Nguyen", "alice@example.com", 0, now);
        String correlationId = "my-correlation-id-123";

        when(customerService.createCustomer(any(CreateCustomerRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("X-Correlation-ID", correlationId))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Correlation-ID", correlationId));
    }

    // =========================
    // GET /api/v1/customers/{customerId}
    // =========================

    @Test
    void testGetCustomer_Success() throws Exception {
        CustomerResponse response = new CustomerResponse(customerId, "Alice", "Nguyen", "alice@example.com", 500, now);

        when(customerService.getCustomer(customerId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/customers/{customerId}", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.firstName").value("Alice"))
                .andExpect(jsonPath("$.rewardBalance").value(500))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testGetCustomer_NotFound() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        when(customerService.getCustomer(nonExistentId))
                .thenThrow(new CustomerNotFoundException(nonExistentId));

        mockMvc.perform(get("/api/v1/customers/{customerId}", nonExistentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
                .andExpect(jsonPath("$.message", containsString("not found")))
                .andExpect(header().exists("X-Correlation-ID"));
    }


    // =========================
    // GET /api/v1/customers/{customerId}/rewards
    // =========================

    @Test
    void testGetRewardBalance_Success() throws Exception {
        RewardBalanceResponse response = new RewardBalanceResponse(customerId, 1000);

        when(customerService.getRewardBalance(customerId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/customers/{customerId}/rewards", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.rewardBalance").value(1000))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testGetRewardBalance_NotFound() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        when(customerService.getRewardBalance(nonExistentId))
                .thenThrow(new CustomerNotFoundException(nonExistentId));

        mockMvc.perform(get("/api/v1/customers/{customerId}/rewards", nonExistentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    // =========================
    // POST /api/v1/customers/{customerId}/rewards/redeem
    // =========================

    @Test
    void testRedeemPoints_Success() throws Exception {
        RedeemPointsRequest request = new RedeemPointsRequest(100, "Redemption for gift card");
        RedeemPointsResponse response = new RedeemPointsResponse(
                transactionId, customerId, 100, 900, "idempotency-key-123", now);
        String idempotencyKey = "idempotency-key-123";

        when(redemptionService.redeemPoints(eq(customerId), eq(idempotencyKey), any(RedeemPointsRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/customers/{customerId}/rewards/redeem", customerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId", notNullValue()))
                .andExpect(jsonPath("$.pointsRedeemed").value(100))
                .andExpect(jsonPath("$.remainingBalance").value(900))
                .andExpect(jsonPath("$.idempotencyKey").value(idempotencyKey))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testRedeemPoints_MissingIdempotencyKey() throws Exception {
        RedeemPointsRequest request = new RedeemPointsRequest(100, "Redemption");

        mockMvc.perform(post("/api/v1/customers/{customerId}/rewards/redeem", customerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("Idempotency-Key")))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testRedeemPoints_BlankIdempotencyKey() throws Exception {
        RedeemPointsRequest request = new RedeemPointsRequest(100, "Redemption");

        mockMvc.perform(post("/api/v1/customers/{customerId}/rewards/redeem", customerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Idempotency-Key", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testRedeemPoints_CustomerNotFound() throws Exception {
        RedeemPointsRequest request = new RedeemPointsRequest(100, "Redemption");
        UUID nonExistentId = UUID.randomUUID();
        String idempotencyKey = "key-123";

        when(redemptionService.redeemPoints(eq(nonExistentId), eq(idempotencyKey), any(RedeemPointsRequest.class)))
                .thenThrow(new CustomerNotFoundException(nonExistentId));

        mockMvc.perform(post("/api/v1/customers/{customerId}/rewards/redeem", nonExistentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
                .andExpect(header().exists("X-Correlation-ID"));
    }


    @Test
    void testRedeemPoints_InsufficientBalance() throws Exception {
        RedeemPointsRequest request = new RedeemPointsRequest(5000, "Too many points");
        String idempotencyKey = "key-123";

        when(redemptionService.redeemPoints(eq(customerId), eq(idempotencyKey), any(RedeemPointsRequest.class)))
                .thenThrow(new InsufficientRewardBalanceException(5000, 500));

        mockMvc.perform(post("/api/v1/customers/{customerId}/rewards/redeem", customerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_REWARD_BALANCE"))
                .andExpect(jsonPath("$.message", containsString("Insufficient")))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testRedeemPoints_ConcurrencyConflict() throws Exception {
        RedeemPointsRequest request = new RedeemPointsRequest(100, "Redeem");
        String idempotencyKey = "key-123";

        when(redemptionService.redeemPoints(eq(customerId), eq(idempotencyKey), any(RedeemPointsRequest.class)))
                .thenThrow(new RewardConcurrencyException(customerId, new RuntimeException("Version mismatch")));

        mockMvc.perform(post("/api/v1/customers/{customerId}/rewards/redeem", customerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REWARD_CONCURRENCY_CONFLICT"))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    // =========================
    // GET /api/v1/customers/{customerId}/rewards/transactions
    // =========================

    @Test
    void testGetTransactionHistory_Success() throws Exception {
        List<TransactionResponse> transactions = List.of(
                new TransactionResponse(UUID.randomUUID(), "REDEEM", 100, "Gift card", now)
        );
        TransactionHistoryResponse response = new TransactionHistoryResponse(customerId, transactions, 0, 20, 1, 1);

        when(customerService.getTransactionHistory(customerId, 0, 20)).thenReturn(response);

        mockMvc.perform(get("/api/v1/customers/{customerId}/rewards/transactions", customerId)
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.transactions").isArray())
                .andExpect(jsonPath("$.transactions[0].points").value(100))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testGetTransactionHistory_DefaultPagination() throws Exception {
        List<TransactionResponse> transactions = List.of();
        TransactionHistoryResponse response = new TransactionHistoryResponse(customerId, transactions, 0, 20, 0, 0);

        when(customerService.getTransactionHistory(customerId, 0, 20)).thenReturn(response);

        mockMvc.perform(get("/api/v1/customers/{customerId}/rewards/transactions", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testGetTransactionHistory_CustomPagination() throws Exception {
        List<TransactionResponse> transactions = List.of();
        TransactionHistoryResponse response = new TransactionHistoryResponse(customerId, transactions, 2, 50, 100, 3);

        when(customerService.getTransactionHistory(customerId, 2, 50)).thenReturn(response);

        mockMvc.perform(get("/api/v1/customers/{customerId}/rewards/transactions", customerId)
                .param("page", "2")
                .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(100))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testGetTransactionHistory_CustomerNotFound() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        when(customerService.getTransactionHistory(nonExistentId, 0, 20))
                .thenThrow(new CustomerNotFoundException(nonExistentId));

        mockMvc.perform(get("/api/v1/customers/{customerId}/rewards/transactions", nonExistentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    // =========================
    // POST /api/v1/customers/{customerId}/rewards/earn
    // =========================

    @Test
    void testEarnPoints_Success() throws Exception {
        EarnPointsRequest request = new EarnPointsRequest(500);
        EarnPointsResponse response = new EarnPointsResponse(
                transactionId, customerId, 500, 500, now);

        when(earnService.earnPoints(customerId, request)).thenReturn(response);

        mockMvc.perform(post("/api/v1/customers/{customerId}/rewards/earn", customerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId", notNullValue()))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.points").value(500))
                .andExpect(jsonPath("$.remainingBalance").value(500))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testEarnPoints_CustomerNotFound() throws Exception {
        EarnPointsRequest request = new EarnPointsRequest(100);
        UUID nonExistentId = UUID.randomUUID();

        when(earnService.earnPoints(nonExistentId, request))
                .thenThrow(new CustomerNotFoundException(nonExistentId));

        mockMvc.perform(post("/api/v1/customers/{customerId}/rewards/earn", nonExistentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testEarnPoints_InvalidPoints() throws Exception {
        // The @Positive annotation on EarnPointsRequest will reject invalid values
        // at the validation level, so service is never called
        String jsonWithZero = "{\"points\":0}";

        mockMvc.perform(post("/api/v1/customers/{customerId}/rewards/earn", customerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonWithZero))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testEarnPoints_IntegerOverflow() throws Exception {
        EarnPointsRequest request = new EarnPointsRequest(Integer.MAX_VALUE);

        when(earnService.earnPoints(customerId, request))
                .thenThrow(new IllegalArgumentException("Adding points would exceed maximum reward balance"));

        mockMvc.perform(post("/api/v1/customers/{customerId}/rewards/earn", customerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("exceed maximum")))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testEarnPoints_ConcurrencyConflict() throws Exception {
        EarnPointsRequest request = new EarnPointsRequest(100);

        when(earnService.earnPoints(customerId, request))
                .thenThrow(new RewardConcurrencyException(customerId, new RuntimeException("Version conflict")));

        mockMvc.perform(post("/api/v1/customers/{customerId}/rewards/earn", customerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REWARD_CONCURRENCY_CONFLICT"))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void testEarnPoints_WithCorrelationId() throws Exception {
        EarnPointsRequest request = new EarnPointsRequest(250);
        EarnPointsResponse response = new EarnPointsResponse(
                transactionId, customerId, 250, 250, now);
        String correlationId = "earn-correlation-456";

        when(earnService.earnPoints(customerId, request)).thenReturn(response);

        mockMvc.perform(post("/api/v1/customers/{customerId}/rewards/earn", customerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("X-Correlation-ID", correlationId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", correlationId));
    }
}
