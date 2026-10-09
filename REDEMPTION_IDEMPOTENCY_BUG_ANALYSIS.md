# Redemption Idempotency Bug Analysis

## Root Cause

### The Bug
**Error**: `"The provided key element does not match the schema"`  
**Location**: `DynamoRewardTransactionRepositoryImpl.getIdempotencyLock()` line 200  
**Triggering Code**:
```java
DynamoDbTable<IdempotencyLockItem> lockTable = enhancedClient.table(
    tableConfig.getTransactionTableName(),
    TableSchema.fromClass(IdempotencyLockItem.class)
);
IdempotencyLockItem lockItem = lockTable.getItem(
    r -> r.key(k -> k.partitionValue(compositeIdempotencyId))  // ← WRONG
);
```

### Why It Fails
1. **Real AWS Table Schema** (confirmed by user):
   - Table: `reward_transactions`
   - Primary Key: `transactionId` (String, UUID)
   - GSI1: `customerId` + `createdAt`
   - GSI2: Intended for `customerId` + `idempotencyKey` lookup

2. **Code's Design Assumption** (incorrect for real AWS):
   - The code tries to store idempotency lock items in the SAME table with composite PK: `IDEMPOTENCY#<customerId>#<idempotencyKey>`
   - It then uses `table.getItem(r -> r.key(k -> k.partitionValue(compositeIdempotencyId)))`
   - This tells DynamoDB: "Look for an item with partition key = `IDEMPOTENCY#...`"
   - But the table's actual partition key is `transactionId`, not `idempotencyId`
   - **Result**: DynamoDB rejects the key as not matching the schema

3. **Why In-Memory Tests Passed**:
   - `InMemoryDynamoRewardTransactionRepository` manually manages a separate `lockStore` map
   - It never calls `table.getItem()` with a composite key
   - It directly checks `lockStore.containsKey(compositeId)` (works because it's an in-memory HashMap)
   - Tests never expose the schema mismatch

4. **Why Earn Works but Redeem Fails**:
   - **Earn** only calls `transactionRepository.save(transaction)` → uses transactionId as PK → works
   - **Redeem** calls `getIdempotencyLock(compositeIdempotencyId)` first → tries to use composite PK against table with transactionId PK → fails

---

## Current AWS Table Schema Limitation

The existing `reward_transactions` table cannot safely support the current idempotency design because:

1. **Lock items cannot coexist with transaction items in the same table**:
   - Transaction items use PK: `transactionId` (UUID like `550e8400-e29b-41d4-a716-446655440000`)
   - Lock items need PK: `IDEMPOTENCY#<customerId>#<idempotencyKey>` (string like `IDEMPOTENCY#cust-123#req-456`)
   - DynamoDB table has ONE primary key schema: `transactionId`
   - Cannot mix two different key formats in the same table (different schemas)

2. **GSI2 (customerId + idempotencyKey) does NOT enforce uniqueness**:
   - GSI2 is a query index only (no uniqueness guarantee)
   - Two concurrent redemptions with same (customerId, idempotencyKey) can BOTH:
     - Query GSI2 and both see "not found"
     - Both proceed to create transactions
     - Both deduct points
     - Both transactions persist
   - GSI does NOT have `PutItem` condition enforcement like a primary key does

---

## Proposed Fix

### Strategy: Use GSI2 for Idempotency Detection + Separate Lock Table

**Minimal AWS Schema Changes Required**:

#### Option A: Use Separate Lock Table (Recommended)
Create a dedicated lock table:
```
Table: reward_idempotency_locks
Primary Key: lockId (String) = "IDEMPOTENCY#<customerId>#<idempotencyKey>"
Attributes:
  - lockId (PK)
  - transactionId (Reference to transaction)
  - customerId
  - createdAt
  - TTL (optional, for auto-cleanup)
```

**Advantages**:
- No schema conflict with existing transaction table
- Lock items have their own schema matching the PK format
- `table.getItem()` works correctly with composite PK
- Atomic redemption still works via TransactWriteItems across two tables
- Concurrent safe: First to create lock wins, others fail and read lock

**Disadvantages**:
- Adds a second table (minor operational overhead)
- TransactWriteItems now spans two tables (still atomic within single region)

#### Option B: Query GSI2 + Add Global Secondary Index Lock Marker
Keep current table but add a different approach:
```
query GSI2 (customerId + idempotencyKey) to find existing transaction
if found:
  return existing transaction (idempotent replay)
else:
  attempt redemption with conditional write to a "lock" attribute
```

**Disadvantages**:
- GSI still doesn't enforce uniqueness
- Concurrent requests can still both query and not find, then both write
- Much more complex race condition handling
- Not truly safe from double-redemption

---

## Recommended Solution: Separate Lock Table

### Implementation Steps

1. **Create Lock Table in AWS**:
   ```
   Table name: reward_idempotency_locks
   Primary Key: lockId (String)
   Attributes: transactionId, customerId, createdAt
   Billing mode: PAY_PER_REQUEST
   ```

2. **Update Code**:
   - Create `IdempotencyLockRepository` interface + implementation
   - Update `DynamoRewardTransactionRepositoryImpl.getIdempotencyLock()` to query lock table
   - Update `executeRedemptionTransaction()` to write lock to lock table (not transaction table)
   - Update `DynamoDbTableConfig` to include lock table name

3. **Preserve Atomicity**:
   - TransactWriteItems still works across two tables in same region
   - Operation order:
     1. Update customer balance (with version condition)
     2. Put transaction record in transaction table
     3. Put lock in lock table (with `attribute_not_exists(lockId)` condition)
   - All three succeed atomically or all fail

4. **Preserve Idempotency Safety**:
   - Lock creation uses `attribute_not_exists(lockId)` condition
   - First concurrent request to create lock wins
   - Other concurrent requests fail on lock creation → read lock → retrieve winner's transaction
   - Retry requests query lock table first → find lock → return cached result

---

## Will Current Schema Work Without Changes?

**NO. Current AWS table schema CANNOT safely guarantee idempotency without changes.**

**Options**:
1. **Create separate lock table** (5 min AWS work, 1 hour code change) ← **RECOMMENDED**
2. **Move to a different idempotency design** (redesign required, not minimal)
3. **Use only GSI2 queries** (NOT safe; GSI does not prevent double-writes)

---

## Test Coverage Required

Once idempotency is fixed:

1. **First Redemption**:
   - ✓ Lock created
   - ✓ Transaction created
   - ✓ Customer balance deducted
   - ✓ Response contains transaction

2. **Idempotent Replay** (same customerId + idempotencyKey):
   - ✓ Lock found on second request
   - ✓ Cached transaction returned
   - ✓ NO balance deduction
   - ✓ Response matches first response

3. **Concurrent Duplicate** (two requests with same key simultaneously):
   - ✓ First request creates lock + transaction
   - ✓ Second request's lock creation fails
   - ✓ Second request reads lock → finds winner's transaction
   - ✓ Both requests return same result
   - ✓ Balance deducted exactly once
   - ✓ One logical transaction persists

4. **Insufficient Balance**:
   - ✓ Customer update fails (condition fails)
   - ✓ Transaction not created
   - ✓ Lock not created
   - ✓ InsufficientRewardBalanceException thrown

5. **Optimistic Concurrency Conflict**:
   - ✓ Customer update fails (version condition fails)
   - ✓ Transaction not created
   - ✓ Lock not created
   - ✓ RewardConcurrencyException thrown

---

## Summary

| Aspect | Status | Details |
|--------|--------|---------|
| **Root Cause** | Identified | Schema mismatch: table PK is `transactionId`, code tries to `getItem()` with `IDEMPOTENCY#...` composite key |
| **Current Table Safe?** | NO | GSI2 doesn't enforce uniqueness; lock items can't coexist with transactions in single-key-schema table |
| **Fix Complexity** | MINIMAL | Create separate lock table + update code to use it (1-2 hours) |
| **Atomic Redemption** | Preserved | TransactWriteItems works across two tables in same region |
| **Idempotency Safety** | Achievable | Lock table + `attribute_not_exists(lockId)` condition ensures single write per key |
| **Code Changes** | Moderate | Add lock repository, update transaction repository, update service logic |
| **AWS Resource Changes** | Required | Create `reward_idempotency_locks` table (NOT optional) |

---

## Next Steps (After User Approval)

1. User reviews this analysis and confirms approach
2. Create `reward_idempotency_locks` table in AWS
3. Create `IdempotencyLockRepository` interface + implementation
4. Update `DynamoRewardTransactionRepositoryImpl.getIdempotencyLock()` to query lock table
5. Update `DynamoRewardTransactionRepositoryImpl.executeRedemptionTransaction()` to write lock to lock table
6. Add tests for all scenarios (first redemption, replay, concurrent, insufficient balance, version conflict)
7. Run full test suite
8. Test against real AWS DynamoDB

**STOP POINT**: Do not create AWS resources until user approves this approach.
