// This file defines tenant-owned accounts and their normal balance sides.
package io.tallyhook.ledger

enum class Direction {
    DEBIT,
    CREDIT,
    ;

    fun opposite(): Direction = if (this == DEBIT) CREDIT else DEBIT
}

enum class AccountType(
    val normalBalance: Direction,
) {
    ASSET(Direction.DEBIT),
    LIABILITY(Direction.CREDIT),
    EQUITY(Direction.CREDIT),
    REVENUE(Direction.CREDIT),
    EXPENSE(Direction.DEBIT),
}

class Account private constructor(
    val id: AccountId,
    val tenantId: TenantId,
    val type: AccountType,
    val currency: Currency,
) {
    companion object {
        fun create(
            id: AccountId,
            tenantId: TenantId,
            type: AccountType,
            currency: Currency,
        ): Account = Account(id, tenantId, type, currency)
    }
}
