"""One-off inspection script — not part of the trained pipeline.
Run: .venv/bin/python training/inspect_dataset.py
"""
import pandas as pd

df = pd.read_csv("training/data/hotel_bookings.csv")

print("=== shape ===")
print(df.shape)

print("\n=== dtypes ===")
print(df.dtypes)

print("\n=== missing values (columns with any NaN) ===")
missing = df.isna().sum()
print(missing[missing > 0].sort_values(ascending=False))

print("\n=== target variable: adr ===")
print(df["adr"].describe())
print("adr <= 0 count:", (df["adr"] <= 0).sum())
print("adr negative count:", (df["adr"] < 0).sum())
print("adr top 5 values:", df["adr"].sort_values(ascending=False).head(5).tolist())

print("\n=== hotel (location proxy) value counts ===")
print(df["hotel"].value_counts())

print("\n=== is_canceled value counts ===")
print(df["is_canceled"].value_counts())

print("\n=== reserved_room_type value counts ===")
print(df["reserved_room_type"].value_counts())

print("\n=== assigned_room_type value counts ===")
print(df["assigned_room_type"].value_counts())

print("\n=== country: unique count + top 5 ===")
print(df["country"].nunique(), "unique")
print(df["country"].value_counts().head(5))
