package com.lin.hippyagent.core.model.routing

import kotlinx.serialization.Serializable
import timber.log.Timber

@Serializable
data class BudgetCheckResult(
    val passed: Boolean,
    val warningTriggered: Boolean,
    val usedUsd: Float,
    val budgetUsd: Float? = null
)

class BudgetManager(private var budgetUsd: Float? = null) {

    private var usedUsd: Float = 0f
    private var _budgetWarned: Boolean = false

    fun checkBudget(usedUsd: Float): BudgetCheckResult {
        val budget = budgetUsd
        if (budget == null) {
            return BudgetCheckResult(
                passed = true,
                warningTriggered = false,
                usedUsd = usedUsd,
                budgetUsd = null
            )
        }

        val ratio = usedUsd / budget
        val warningTriggered = ratio >= 0.80f && !_budgetWarned
        if (warningTriggered) {
            _budgetWarned = true
            Timber.w("BudgetManager: 80%% budget warning triggered (%.4f/%.4f USD)".format(usedUsd, budget))
        }

        val passed = usedUsd < budget
        if (!passed) {
            Timber.w("BudgetManager: budget exceeded (%.4f/%.4f USD)".format(usedUsd, budget))
        }

        return BudgetCheckResult(
            passed = passed,
            warningTriggered = warningTriggered,
            usedUsd = usedUsd,
            budgetUsd = budget
        )
    }

    fun setBudget(usd: Float?) {
        budgetUsd = usd
        _budgetWarned = false
        Timber.d("BudgetManager: budget set to %s".format(usd?.toString() ?: "null"))
    }

    fun addCost(costUsd: Float) {
        usedUsd += costUsd
        Timber.d("BudgetManager: added %.6f USD, total=%.4f".format(costUsd, usedUsd))
    }
}
