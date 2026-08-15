"""
Serving-parity retrain — one approved run, schema changes only
==============================================================
Nothing about the research question changes. Dataset, target, task, model
family, quantiles and the temporal validation boundaries are all frozen. The
ONLY reason for retraining is that the production serving audit found the
previous M1 used features that cannot be reproduced from a LiteAPI response.

Changes, all schema-level:
  * `occ` REMOVED entirely (capacity != requested guests; cannot be served)
  * `hotel_id` -> `hotel_key` = normalize_name(hotel_name), with ambiguous
    names (one normalized name mapping to >1 PromptCloud hotel_id) excluded
    from the B2 known-hotel path
  * raw `room_norm` (3,279 levels) -> frozen 8-class `room_category`
  * vendor strings -> frozen `breakfast_category` / `cancellation_category`

All normalization comes from serving_features.py, which production inference
imports unchanged.

Run: .venv/bin/python training/v2mvp_india/experiment_serving_parity.py
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd
from catboost import CatBoostRegressor, Pool

sys.path.insert(0, str(Path(__file__).resolve().parent))
from serving_features import (  # noqa: E402
    CATEGORICAL, FEATURES, breakfast_category, cancellation_category,
    normalize_name, room_category,
)

ROOT = Path(__file__).resolve().parent.parent.parent
CLEAN = ROOT / "training" / "data" / "promptcloud" / "india_offers_clean.parquet"
OUT = Path(__file__).resolve().parent / "reference" / "serving_parity.json"

Q = (0.25, 0.50, 0.75)
SEED = 42
CB = dict(loss_function="MultiQuantile:alpha=0.25,0.5,0.75", iterations=500,
          depth=6, learning_rate=0.1, random_seed=SEED, verbose=0, thread_count=-1)
MIN_HOTEL_TRAIN_OBS = 5

M1_FEATS = FEATURES
M0_FEATS = [f for f in FEATURES if f != "hotel_key"]
M1_CAT = CATEGORICAL
M0_CAT = [c for c in CATEGORICAL if c != "hotel_key"]


def pin(y, p, a):
    dd = y - p
    return float(np.mean(np.maximum(a * dd, (a - 1) * dd)))


def score(y, P):
    y = np.asarray(y, float)
    if len(y) == 0:
        return None
    return {"pinball": float(np.mean([pin(y, P[:, i], a) for i, a in enumerate(Q)])),
            "medae": float(np.median(np.abs(y - P[:, 1]))),
            "mae": float(np.mean(np.abs(y - P[:, 1]))),
            "coverage": float(np.mean((y >= P[:, 0]) & (y <= P[:, 2]))),
            "width": float(np.mean(P[:, 2] - P[:, 0])),
            "crossing": float(np.mean((P[:, 0] > P[:, 1]) | (P[:, 1] > P[:, 2]))),
            "n": int(len(y))}


def qtab(tr, keys, mn):
    g = tr.groupby(keys).price
    t = g.quantile(list(Q)).unstack()
    t.columns = ["p25", "p50", "p75"]
    t["n"] = g.size()
    return t[t.n >= mn]


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


def prepare() -> pd.DataFrame:
    d = pd.read_parquet(CLEAN)
    d["hotel_key"] = d.hotel_name.map(normalize_name)
    d["room_category"] = d.room_norm.map(room_category)
    d["breakfast_category"] = d.bf.map(breakfast_category)
    d["cancellation_category"] = d.canc.map(cancellation_category)

    # ambiguity: one normalized name covering more than one source hotel_id
    amb = d.groupby("hotel_key").hotel_id.nunique()
    d["hotel_ambiguous"] = d.hotel_key.map(amb > 1)
    print(f"[schema] offers {len(d):,} | hotel_key {d.hotel_key.nunique():,} "
          f"| ambiguous keys {int((amb>1).sum()):,} "
          f"({(amb>1).mean()*100:.2f}% of keys, {d.hotel_ambiguous.mean()*100:.2f}% of rows)")
    print(f"[schema] features = {FEATURES}")
    print(f"[schema] occ REMOVED (present in source: {'occ' in d.columns})")
    for c in ("room_category", "breakfast_category", "cancellation_category"):
        vc = d[c].value_counts()
        print(f"\n  {c}:")
        for k, v in vc.items():
            print(f"    {k:16s} {v:>7,} ({v/len(d)*100:5.1f}%)")
    return d


def run(tr, te, label, results):
    glob = tr.price.quantile(list(Q)).to_numpy()
    tr, te = tr.copy(), te.copy()
    for x in (tr, te):
        x["lead_b"] = pd.cut(x.lead_time_days, [-1, 3, 7, 14, 99], labels=False)

    # KNOWN = unambiguous normalized name AND >= 5 train observations
    ok_keys = tr[~tr.hotel_ambiguous].groupby("hotel_key").size()
    ok_keys = set(ok_keys[ok_keys >= MIN_HOTEL_TRAIN_OBS].index)
    known = (te.hotel_key.isin(ok_keys) & ~te.hotel_ambiguous).to_numpy()

    B0 = np.tile(glob, (len(te), 1))
    B1 = lookup(tr, te, [(["room_category", "breakfast_category", "lead_b"], 20),
                         (["room_category", "breakfast_category"], 20),
                         (["room_category"], 20)], glob)
    tr_ok = tr[tr.hotel_key.isin(ok_keys) & ~tr.hotel_ambiguous]
    B2 = lookup(tr_ok, te, [(["hotel_key", "room_category"], 5), (["hotel_key"], 5)], glob)
    # rows that are not KNOWN must not be served by a hotel-specific lookup
    B2[~known] = B1[~known]

    preds = {"B0 global": B0, "B1 context": B1, "B2 hotel-own": B2}
    imp = {}
    for name, feats, cats in (("M0 no-hotel", M0_FEATS, M0_CAT),
                              ("M1 hotel-aware", M1_FEATS, M1_CAT)):
        t0 = time.time()
        m = CatBoostRegressor(**CB)
        m.fit(Pool(tr[feats], tr.price, cat_features=cats))
        preds[name] = m.predict(te[feats])
        if name == "M1 hotel-aware":
            imp = dict(sorted(zip(feats, m.get_feature_importance()), key=lambda kv: -kv[1]))
        print(f"    {name} fitted in {time.time()-t0:.0f}s", flush=True)

    y = te.price.to_numpy()
    res = {k: score(y, P) for k, P in preds.items()}
    seg = {}
    for sn, msk in (("known", known), ("unknown", ~known)):
        if msk.sum() > 200:
            seg[sn] = {k: score(y[msk], P[msk]) for k, P in preds.items()}

    # HYBRID: B2 on known, M1 on unknown
    H = np.where(known[:, None], preds["B2 hotel-own"], preds["M1 hotel-aware"])
    res["HYBRID"] = score(y, H)

    results[label] = {"n_train": int(len(tr)), "n_test": int(len(te)),
                      "known_pct": float(known.mean()), "metrics": res,
                      "segments": seg, "importance": imp}

    print(f"\n  --- {label}: train {len(tr):,} / test {len(te):,} / KNOWN {known.mean()*100:.1f}% ---")
    print(f"  {'model':16s} {'pinball':>9s} {'MedAE':>9s} {'MAE':>9s} {'cov':>7s} {'width':>9s} {'cross':>7s}")
    for k in ("B0 global", "B1 context", "B2 hotel-own", "M0 no-hotel", "M1 hotel-aware", "HYBRID"):
        r = res[k]
        print(f"  {k:16s} {r['pinball']:9.1f} {r['medae']:9.1f} {r['mae']:9.1f} "
              f"{r['coverage']*100:6.1f}% {r['width']:9.1f} {r['crossing']*100:6.2f}%")
    b2, m1, m0, h = res["B2 hotel-own"], res["M1 hotel-aware"], res["M0 no-hotel"], res["HYBRID"]
    print(f"  M1 vs M0      : pinball {(1-m1['pinball']/m0['pinball'])*100:+6.1f}%")
    print(f"  HYBRID vs B2  : pinball {(1-h['pinball']/b2['pinball'])*100:+6.1f}%  "
          f"MedAE {(1-h['medae']/b2['medae'])*100:+6.1f}%")
    print(f"  HYBRID vs M1  : pinball {(1-h['pinball']/m1['pinball'])*100:+6.1f}%  "
          f"MedAE {(1-h['medae']/m1['medae'])*100:+6.1f}%")
    for sn in ("known", "unknown"):
        if sn in seg:
            a_, b_ = seg[sn]["B2 hotel-own"], seg[sn]["M1 hotel-aware"]
            print(f"  [{sn:7s}] n={a_['n']:>7,}  B2 pin={a_['pinball']:7.1f}  M1 pin={b_['pinball']:7.1f}  "
                  f"M1 vs B2 {(1-b_['pinball']/a_['pinball'])*100:+6.1f}%")


def main() -> None:
    d = prepare()
    dates = np.array(sorted(d.cr.unique()))
    results = {}

    cut = dates[int(len(dates) * 0.75)]
    print(f"\n{'='*94}\nPRIMARY SPLIT (unchanged): train crawl < {pd.Timestamp(cut).date()}\n{'='*94}")
    tr, te = d[d.cr < cut], d[d.cr >= cut]
    assert tr.cr.max() < te.cr.min()
    run(tr, te, "primary", results)

    print(f"\n{'='*94}\nROLLING WINDOWS W1-W4 (unchanged boundaries)\n{'='*94}")
    for k, start in enumerate(range(12, len(dates) - 3 + 1, 3), 1):
        trd, ted = dates[:start], dates[start:start + 3]
        a, b = d[d.cr.isin(trd)], d[d.cr.isin(ted)]
        if len(b) < 1000:
            continue
        assert a.cr.max() < b.cr.min()
        print(f"\nW{k}: train <= {pd.Timestamp(trd[-1]).date()} | "
              f"test {pd.Timestamp(ted[0]).date()}..{pd.Timestamp(ted[-1]).date()}")
        run(a, b, f"W{k}", results)

    print(f"\n{'='*94}\nAGGREGATE\n{'='*94}")
    ks = list(results)
    for pair in (("HYBRID", "B2 hotel-own"), ("HYBRID", "M1 hotel-aware"),
                 ("M1 hotel-aware", "M0 no-hotel")):
        v = [(1 - results[s]["metrics"][pair[0]]["pinball"] /
              results[s]["metrics"][pair[1]]["pinball"]) * 100 for s in ks]
        print(f"  {pair[0]:14s} vs {pair[1]:14s} pinball mean {np.mean(v):+6.2f}%  "
              f"wins {sum(1 for x in v if x>0)}/{len(v)}  worst {min(v):+6.2f}%")
    for sn in ("known", "unknown"):
        v = [(1 - results[s]["segments"][sn]["M1 hotel-aware"]["pinball"] /
              results[s]["segments"][sn]["B2 hotel-own"]["pinball"]) * 100
             for s in ks if sn in results[s]["segments"]]
        print(f"  [{sn:7s}] M1 vs B2 pinball mean {np.mean(v):+6.2f}%  "
              f"M1 wins {sum(1 for x in v if x>0)}/{len(v)}")
    print(f"  HYBRID coverage mean {np.mean([results[s]['metrics']['HYBRID']['coverage'] for s in ks])*100:.1f}%"
          f"  width mean {np.mean([results[s]['metrics']['HYBRID']['width'] for s in ks]):.0f}")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"features": FEATURES, "removed": ["occ"],
                               "min_hotel_train_obs": MIN_HOTEL_TRAIN_OBS,
                               "catboost": CB, "results": results}, indent=2, default=float))
    print(f"\n[out] wrote {OUT}")


if __name__ == "__main__":
    main()
