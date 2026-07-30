"""
Intelligent budget allocation — splits total travel budget into per-category amounts
====================================================================================
Model: hybrid (rules-based + historical regression)

Input:
- destination city (includes city cost tier)
- trip duration (days)
- total budget
- travel preference (economy / comfort / business)

Output (budget breakdown):
- flight budget (round-trip)
- hotel budget (per night × days)
- meals budget (per day × days)
- local transport budget
- contingency reserve (~10%)

Core logic:
1. Look up city cost tier (tier-1/tier-2/international) → base allocation ratios
2. Adjust flight ratio based on season and advance booking days
3. Fine-tune hotel/meal ratios based on user preference (economy/comfort)
4. Ensure sum <= total budget; reserve ~10% buffer
"""

# TODO: BudgetAllocator class
# TODO: allocate(destination, days, total_budget, preferences) → allocation result
# TODO: _get_city_tier() — city cost level lookup
# TODO: _adjust_for_season() — seasonal adjustment
