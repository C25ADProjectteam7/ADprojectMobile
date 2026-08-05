package iss.nus.edu.sg.viewbinding.caproject.validation

object BudgetCalculator {

    fun progressPercent(estimatedTotal: Double, budgetTotal: Double): Int {
        if (estimatedTotal <= 0 || budgetTotal <= 0) return 0
        return ((estimatedTotal / budgetTotal) * 100).toInt().coerceIn(0, 100)
    }
}
