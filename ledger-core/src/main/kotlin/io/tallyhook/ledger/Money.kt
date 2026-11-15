// This file models ISO currency codes and checked integer-minor-unit arithmetic.
package io.tallyhook.ledger

import java.util.Currency as JavaCurrency

class Currency private constructor(
    val code: String,
) {
    override fun equals(other: Any?): Boolean = other is Currency && code == other.code

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = code

    companion object {
        private val isoCurrencyCodes: Set<String> by lazy {
            JavaCurrency.getAvailableCurrencies().mapTo(mutableSetOf()) { it.currencyCode }
        }

        fun create(code: String): DomainResult<Currency> =
            if (
                code.length == ISO_CODE_LENGTH &&
                code.all { it in 'A'..'Z' } &&
                code in isoCurrencyCodes
            ) {
                DomainResult.Success(Currency(code))
            } else {
                DomainResult.Failure(LedgerError.InvalidCurrencyCode(code))
            }

        private const val ISO_CODE_LENGTH = 3
    }
}

class Money private constructor(
    val minorUnits: Long,
    val currency: Currency,
) {
    fun plus(other: Money): DomainResult<Money> = checkedOperation(other, "addition", Math::addExact)

    fun minus(other: Money): DomainResult<Money> = checkedOperation(other, "subtraction", Math::subtractExact)

    fun negate(): DomainResult<Money> =
        try {
            DomainResult.Success(of(Math.negateExact(minorUnits), currency))
        } catch (_: ArithmeticException) {
            DomainResult.Failure(LedgerError.ArithmeticOverflow("negation", currency))
        }

    override fun equals(other: Any?): Boolean =
        when (other) {
            is Money -> minorUnits == other.minorUnits && currency == other.currency
            else -> false
        }

    override fun hashCode(): Int = 31 * minorUnits.hashCode() + currency.hashCode()

    override fun toString(): String = "$minorUnits ${currency.code}"

    private fun checkedOperation(
        other: Money,
        operation: String,
        calculate: (Long, Long) -> Long,
    ): DomainResult<Money> {
        if (currency != other.currency) {
            return DomainResult.Failure(LedgerError.CurrencyMismatch(currency, other.currency))
        }
        return try {
            DomainResult.Success(of(calculate(minorUnits, other.minorUnits), currency))
        } catch (_: ArithmeticException) {
            DomainResult.Failure(LedgerError.ArithmeticOverflow(operation, currency))
        }
    }

    companion object {
        fun of(
            minorUnits: Long,
            currency: Currency,
        ): Money = Money(minorUnits, currency)

        fun zero(currency: Currency): Money = Money(0, currency)
    }
}
