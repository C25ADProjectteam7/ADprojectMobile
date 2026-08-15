"""
Re-export shim — the canonical implementation now lives in production code.

Training and inference MUST share one normalization implementation. That file
is `ml/india_serving_features.py`; this module only forwards to it so the
existing training/audit scripts keep working unchanged.
"""
from ml.india_serving_features import (  # noqa: F401
    BREAKFAST_CATEGORIES, CANCELLATION_CATEGORIES, CATEGORICAL, FEATURES,
    ROOM_CATEGORIES, breakfast_category, breakfast_category_from_liteapi,
    calendar_features, cancellation_category, cancellation_category_from_liteapi,
    normalize_name, room_category,
)
