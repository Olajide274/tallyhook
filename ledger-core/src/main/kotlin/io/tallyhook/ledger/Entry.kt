// This file defines immutable, strictly positive debit and credit entries.
package io.tallyhook.ledger

class Entry private constructor(
    val id: EntryId,
    val accountId: AccountId,
    val direction: Direction,
    val money: Money,
) {
    internal fun reversed(): Entry = Entry(id, accountId, direction.opposite(), money)

    override fun equals(other: Any?): Boolean =
        other is Entry &&
            id == other.id &&
            accountId == other.accountId &&
            direction == other.direction &&
            money == other.money

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + accountId.hashCode()
        result = 31 * result + direction.hashCode()
        return 31 * result + money.hashCode()
    }

    override fun toString(): String = "Entry(id=$id, accountId=$accountId, direction=$direction, money=$money)"

    companion object {
        fun create(
            id: EntryId,
            accountId: AccountId,
            direction: Direction,
            money: Money,
        ): DomainResult<Entry> {
            if (money.minorUnits <= 0) {
                return DomainResult.Failure(LedgerError.InvalidAmount(money.minorUnits))
            }
            return DomainResult.Success(Entry(id, accountId, direction, money))
        }
    }
}
