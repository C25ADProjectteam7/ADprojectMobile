"""
Booking.com India (March 2020) — cross-sectional conditional quantile model
===========================================================================
FROZEN SCOPE: this does NOT forecast future prices. It estimates, for a given
hotel + room/rate context + stay timing + search timing, the historical fair
price DISTRIBUTION (P25/P50/P75). LiteAPI's currentPrice enters only AFTER the
prediction, as a comparison.

    currentPrice <  P25          -> CHEAP      -> BOOK NOW
    P25 <= currentPrice <= P75   -> FAIR       -> BOOK NOW / reasonable
    currentPrice >  P75          -> EXPENSIVE  -> WAIT AND RECHECK

currentPrice, price_rank and default_rank are NEVER features.

Source: PromptCloud "Travel & Hotel Listing from Booking.com 2020" (Kaggle,
CC0), marketing sample 20200301-20200331, 29,988 documents. All pageurls are
booking.com/hotel/in/ -> single country (India); currency is recorded as an
explicit INR prototype assumption, not a silent one.

Run: .venv/bin/python training/v2mvp_india/experiment_fair_price.py
"""
from __future__ import annotations

import json
import re
import time
import unicodedata
from pathlib import Path

import numpy as np
import pandas as pd
from catboost import CatBoostRegressor, Pool

ROOT = Path(__file__).resolve().parent.parent.parent
RAW = (ROOT / "training" / "data" / "promptcloud" / "price" /
       "marketing_sample_for_booking_com-travel_n_hotel_listing_from_booking_com"
       "__20200301_20200331__30k_data.json")
CLEAN = ROOT / "training" / "data" / "promptcloud" / "india_offers_clean.parquet"
OUT = Path(__file__).resolve().parent / "reference" / "fair_price_experiment.json"

Q = (0.25, 0.50, 0.75)
SEED = 42
CB = dict(loss_function="MultiQuantile:alpha=0.25,0.5,0.75", iterations=500,
          depth=6, learning_rate=0.1, random_seed=SEED, verbose=0, thread_count=-1)

# Documented, not tuned. Chosen from the price distribution audit below:
# the target is right-skewed with a thin implausible tail (max ~1.17M INR).
PRICE_MIN, PRICE_MAX = 200.0, 100_000.0
# A hotel needs this many TRAIN offers before its identity is trusted as a
# categorical level. 5 is the minimum that lets a per-hotel median mean anything.
MIN_HOTEL_TRAIN_OBS = 5

CAT = ["hotel_id", "room_norm", "bf", "canc"]
NUM = ["occ", "lead_time_days", "ci_month", "ci_dow", "ci_weekend", "cr_dow"]
M1_FEATS = CAT + NUM                      # with hotel identity
M0_FEATS = [c for c in CAT if c != "hotel_id"] + NUM   # without


def norm_room(s: str) -> str:
    """Coarse room normalisation: lowercase, strip punctuation/extra spaces.

    Deliberately conservative - 3,356 raw room names collapse only on
    formatting, not on meaning.
    """
    s = unicodedata.normalize("NFKC", str(s)).casefold()
    s = re.sub(r"[^\w\s]", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def build() -> pd.DataFrame:
    pages = json.load(open(RAW))["root"]["page"]
    rows = []
    for pg in pages:
        r = pg["record"]
        for o in (r.get("room_type") or []):
            rows.append({
                "hotel_id": str(r["hotel_id"]), "hotel_name": r.get("hotel_name"),
                "ci": r.get("checkin_date"), "cr": r.get("crawled_date"),
                "room": o.get("room_type_name"), "price": o.get("room_type_price"),
                "occ": o.get("room_type_occupancy"), "bf": o.get("room_type_breakfast"),
                "canc": o.get("room_type_cancellation"),
            })
    d = pd.DataFrame(rows)
    n0 = len(d)
    d["ci"] = pd.to_datetime(d.ci, errors="coerce")
    d["cr"] = pd.to_datetime(d.cr, errors="coerce", utc=True).dt.tz_localize(None).dt.normalize()
    d["price"] = pd.to_numeric(d.price, errors="coerce")

    print(f"[build] expanded offers: {n0:,}")
    print(f"  price null/<=0        : {int((d.price.isna() | (d.price <= 0)).sum()):,}")
    d = d[d.price.notna() & (d.price > 0) & d.ci.notna() & d.cr.notna()].copy()
    print(f"  after validity filter : {len(d):,}")

    print(f"\n[target audit] raw price quantiles (INR assumption):")
    for q in (0.001, .01, .25, .5, .75, .99, .999, 1.0):
        print(f"    P{q*100:6.1f} = {d.price.quantile(q):,.0f}")
    lo, hi = (d.price < PRICE_MIN).sum(), (d.price > PRICE_MAX).sum()
    print(f"  below {PRICE_MIN:,.0f}: {lo:,} ({lo/len(d)*100:.3f}%)  "
          f"above {PRICE_MAX:,.0f}: {hi:,} ({hi/len(d)*100:.3f}%)")
    d = d[d.price.between(PRICE_MIN, PRICE_MAX)].copy()

    d["room_norm"] = norm_room_series(d.room)
    d = d[(d.ci - d.cr).dt.days >= 0].copy()

    # dedup: identical offer repeated inside one crawl date -> one row (median)
    key = ["hotel_id", "room_norm", "occ", "bf", "canc", "ci", "cr"]
    before = len(d)
    d = d.groupby(key, as_index=False).agg(price=("price", "median"),
                                           hotel_name=("hotel_name", "first"))
    print(f"\n[dedup] same-crawl-date duplicate offers collapsed: {before:,} -> {len(d):,} "
          f"(-{before-len(d):,})")

    # derived columns AFTER the dedup groupby (ci/cr survive as keys)
    d["lead_time_days"] = (d.ci - d.cr).dt.days
    d["ci_month"] = d.ci.dt.month
    d["ci_dow"] = d.ci.dt.dayofweek
    d["ci_weekend"] = (d.ci_dow >= 5).astype(int)
    d["cr_dow"] = d.cr.dt.dayofweek
    d["occ"] = pd.to_numeric(d.occ, errors="coerce").fillna(-1)
    for c in ("bf", "canc", "room_norm"):
        d[c] = d[c].fillna("unknown").astype(str)
    d.to_parquet(CLEAN, index=False)
    return d


def norm_room_series(s: pd.Series) -> pd.Series:
    return (s.fillna("unknown").astype(str)
            .map(lambda x: unicodedata.normalize("NFKC", x)).str.casefold()
            .str.replace(r"[^\w\s]", " ", regex=True)
            .str.replace(r"\s+", " ", regex=True).str.strip())


def pin(y, p, a):
    dd = y - p
    return float(np.mean(np.maximum(a * dd, (a - 1) * dd)))


def score(y, P):
    y = np.asarray(y, float)
    if len(y) == 0:
        return None
    return {"mae": float(np.mean(np.abs(y - P[:, 1]))),
            "medae": float(np.median(np.abs(y - P[:, 1]))),
            "pin25": pin(y, P[:, 0], .25), "pin50": pin(y, P[:, 1], .50),
            "pin75": pin(y, P[:, 2], .75),
            "pinball": float(np.mean([pin(y, P[:, i], a) for i, a in enumerate(Q)])),
            "coverage": float(np.mean((y >= P[:, 0]) & (y <= P[:, 2]))),
            "width": float(np.mean(P[:, 2] - P[:, 0])),
            "crossing": float(np.mean((P[:, 0] > P[:, 1]) | (P[:, 1] > P[:, 2]))),
            "n": int(len(y))}


def qtab(tr, keys, min_n):
    g = tr.groupby(keys).price
    t = g.quantile(list(Q)).unstack()
    t.columns = ["p25", "p50", "p75"]
    t["n"] = g.size()
    return t[t.n >= min_n]


def lookup(tr, te, levels, glob):
    P = np.tile(glob, (len(te), 1))
    done = np.zeros(len(te), bool)
    for keys, mn in levels:
        if done.all():
            break
        t = qtab(tr, keys, mn)
        if t.empty:
            continue
        idx = pd.MultiIndex.from_frame(te[keys]) if len(keys) > 1 else pd.Index(te[keys[0]])
        g = t.reindex(idx)
        ok = g.p50.notna().to_numpy() & ~done
        if ok.any():
            P[ok] = g[["p25", "p50", "p75"]].to_numpy()[ok]
            done |= ok
    return P


def run_split(tr, te, label, results):
    glob = tr.price.quantile(list(Q)).to_numpy()
    tr = tr.copy()
    tr["lead_b"] = pd.cut(tr.lead_time_days, [-1, 3, 7, 14, 99], labels=False)
    te = te.copy()
    te["lead_b"] = pd.cut(te.lead_time_days, [-1, 3, 7, 14, 99], labels=False)

    B0 = np.tile(glob, (len(te), 1))
    B1 = lookup(tr, te, [(["room_norm", "occ", "lead_b"], 20),
                         (["room_norm", "occ"], 20), (["room_norm"], 20)], glob)
    B2 = lookup(tr, te, [(["hotel_id", "room_norm"], 5), (["hotel_id"], 5),
                         (["room_norm", "occ"], 20)], glob)

    hcount = tr.groupby("hotel_id").size()
    known = te.hotel_id.isin(hcount[hcount >= MIN_HOTEL_TRAIN_OBS].index).to_numpy()

    preds = {"B0 global": B0, "B1 context": B1, "B2 hotel-own": B2}
    for name, feats in (("M0 no-hotel", M0_FEATS), ("M1 hotel-aware", M1_FEATS)):
        t0 = time.time()
        m = CatBoostRegressor(**CB)
        cats = [f for f in feats if f in CAT]
        m.fit(Pool(tr[feats], tr.price, cat_features=cats))
        preds[name] = m.predict(te[feats])
        if name == "M1 hotel-aware":
            imp = dict(sorted(zip(feats, m.get_feature_importance()), key=lambda kv: -kv[1]))
        print(f"    fitted {name} in {time.time()-t0:.0f}s", flush=True)

    y = te.price.to_numpy()
    res = {k: score(y, P) for k, P in preds.items()}
    seg = {}
    for sn, msk in (("known", known), ("unknown", ~known)):
        if msk.sum() > 200:
            seg[sn] = {k: score(y[msk], P[msk]) for k, P in preds.items()}
    for b, bl in enumerate(["lead 0-3", "lead 4-7", "lead 8-14", "lead 15+"]):
        msk = (te.lead_b == b).to_numpy()
        if msk.sum() > 200:
            seg[bl] = {k: score(y[msk], P[msk]) for k, P in preds.items()}
    for rt in te.room_norm.value_counts().head(4).index:
        msk = (te.room_norm == rt).to_numpy()
        if msk.sum() > 200:
            seg[f"room:{rt[:22]}"] = {k: score(y[msk], P[msk]) for k, P in preds.items()}

    results[label] = {"n_train": int(len(tr)), "n_test": int(len(te)),
                      "known_pct": float(known.mean()), "metrics": res,
                      "segments": seg, "importance": imp}
    print(f"\n  --- {label}: train {len(tr):,} / test {len(te):,} / known {known.mean()*100:.1f}% ---")
    print(f"  {'model':16s} {'pinball':>9s} {'MedAE':>9s} {'MAE':>9s} {'cov':>7s} {'width':>9s} {'cross':>7s}")
    for k in ("B0 global", "B1 context", "B2 hotel-own", "M0 no-hotel", "M1 hotel-aware"):
        r = res[k]
        print(f"  {k:16s} {r['pinball']:9.1f} {r['medae']:9.1f} {r['mae']:9.1f} "
              f"{r['coverage']*100:6.1f}% {r['width']:9.1f} {r['crossing']*100:6.2f}%")
    b2, m1, m0 = res["B2 hotel-own"], res["M1 hotel-aware"], res["M0 no-hotel"]
    print(f"  M1 vs B2: pinball {(1-m1['pinball']/b2['pinball'])*100:+.1f}%  "
          f"MedAE {(1-m1['medae']/b2['medae'])*100:+.1f}%")
    print(f"  M1 vs M0: pinball {(1-m1['pinball']/m0['pinball'])*100:+.1f}%  "
          f"MedAE {(1-m1['medae']/m0['medae'])*100:+.1f}%")
    return seg


def main() -> None:
    d = build() if not CLEAN.exists() else pd.read_parquet(CLEAN)
    print(f"\n=== CLEAN DATASET: {len(d):,} offers | {d.hotel_id.nunique():,} hotels | "
          f"{d.room_norm.nunique():,} room types ===")
    print(f"  crawl dates {d.cr.nunique()} ({d.cr.min().date()}..{d.cr.max().date()}) | "
          f"check-in {d.ci.nunique()} ({d.ci.min().date()}..{d.ci.max().date()})")
    print(f"  lead {d.lead_time_days.min()}..{d.lead_time_days.max()} (median {d.lead_time_days.median():.0f})")
    print(f"  price INR: P25={d.price.quantile(.25):,.0f} P50={d.price.median():,.0f} "
          f"P75={d.price.quantile(.75):,.0f}")
    hc = d.groupby("hotel_id").size()
    print(f"\n  offers per hotel: median={hc.median():.0f} P75={hc.quantile(.75):.0f} max={hc.max()}")
    for n in (1, 3, 5, 10, 20):
        print(f"    hotels with >= {n:2d} offers: {int((hc>=n).sum()):,} ({(hc>=n).mean()*100:5.1f}%)  "
              f"covering {d.hotel_id.isin(hc[hc>=n].index).mean()*100:5.1f}% of rows")

    dates = np.array(sorted(d.cr.unique()))
    results = {}
    cut = dates[int(len(dates) * 0.75)]
    print(f"\n{'='*90}\nPRIMARY CHRONOLOGICAL SPLIT: train crawl < {pd.Timestamp(cut).date()} "
          f"({int(len(dates)*0.75)}/{len(dates)} dates)\n{'='*90}")
    tr, te = d[d.cr < cut], d[d.cr >= cut]
    assert tr.cr.max() < te.cr.min()
    run_split(tr, te, "primary", results)

    print(f"\n{'='*90}\nROLLING WINDOWS (mechanical, expanding train, 3 crawl dates test)\n{'='*90}")
    for k, start in enumerate(range(12, len(dates) - 3 + 1, 3), 1):
        trd, ted = dates[:start], dates[start:start + 3]
        a, b = d[d.cr.isin(trd)], d[d.cr.isin(ted)]
        if len(b) < 1000:
            continue
        assert a.cr.max() < b.cr.min()
        print(f"\nW{k}: train <= {pd.Timestamp(trd[-1]).date()} | "
              f"test {pd.Timestamp(ted[0]).date()}..{pd.Timestamp(ted[-1]).date()}")
        run_split(a, b, f"rolling_{k}", results)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"scope": "Booking.com India, March 2020 sample",
                               "currency_assumption": "INR (100% pageurls are /hotel/in/)",
                               "price_bounds": [PRICE_MIN, PRICE_MAX],
                               "min_hotel_train_obs": MIN_HOTEL_TRAIN_OBS,
                               "catboost": CB, "results": results}, indent=2, default=float))
    print(f"\n[out] wrote {OUT}")


if __name__ == "__main__":
    main()
