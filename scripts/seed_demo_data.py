"""
Fills a running BloodNet with realistic demo data, going through the real REST API so every
business rule applies (donations create units, compatibility, locking, state machines).

What it creates:
  - 3 extra banks + 3 hospitals (SQL, via the mysql container)
  - one staff user per bank and one user per hospital
  - ~30 donors per bank, most with one donation backdated 0-40 days
    (so some platelets are close to expiry and some are already past it)
  - hospital requests in every state: pending, reserved, issued, cancelled
  - bank-to-bank transfers in every state
  - a couple of discarded units for the wastage report

Safe to re-run: donors are keyed by phone, users by email, and the request/transfer/discard
steps skip themselves if demo data is already there. Random choices use a fixed seed.

Usage (from the BLOODNET folder, with `docker compose up -d` running):
    python scripts/seed_demo_data.py
    python scripts/seed_demo_data.py --base-url http://localhost:8081 --skip-sql

Passwords are read from .env (SEED_PASSWORD, ADMIN_EMAIL, ADMIN_PASSWORD) and never printed.
"""
from __future__ import annotations

import argparse
import json
import random
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

FIRST = ["Aarav", "Vivaan", "Aditya", "Arjun", "Sai", "Reyansh", "Ishaan", "Rohan", "Kabir", "Omkar",
         "Ananya", "Diya", "Saanvi", "Aadhya", "Kavya", "Isha", "Meera", "Pooja", "Sneha", "Riya",
         "Pratik", "Nikhil", "Tanvi", "Shruti", "Varun", "Neha", "Siddharth", "Aishwarya", "Yash", "Gauri"]
LAST = ["Patil", "Deshmukh", "Kulkarni", "Joshi", "Shinde", "Pawar", "Jadhav", "More", "Gaikwad", "Bhosale",
        "Sharma", "Mishra", "Iyer", "Nair", "Reddy", "Khan", "Fernandes", "Mehta", "Shah", "Chavan"]

# Rough Indian distribution, negatives nudged up a little so they show up in a demo.
GROUP_WEIGHTS = {"O_POS": 34, "B_POS": 30, "A_POS": 21, "AB_POS": 7,
                 "O_NEG": 3, "B_NEG": 2, "A_NEG": 2, "AB_NEG": 1}

# bank code -> staff email; staff1/staff2 already exist from the app's own seeder
STAFF = {"BB-PUN-01": "staff1@bloodnet.local", "BB-PUN-02": "staff2@bloodnet.local",
         "BB-PUN-03": "staff3@bloodnet.local", "BB-PCM-01": "staff4@bloodnet.local",
         "BB-PUN-04": "staff5@bloodnet.local"}
HOSPITAL_USERS = {"City General Hospital": "hospital@bloodnet.local",
                  "Sahyadri Care Hospital": "hospital2@bloodnet.local",
                  "Riverside Children's Hospital": "hospital3@bloodnet.local",
                  "Pimpri Metro Hospital": "hospital4@bloodnet.local"}


# ------------------------------------------------------------------ plumbing

def load_env(path: Path) -> dict[str, str]:
    env = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    return env


class Api:
    def __init__(self, base: str):
        self.base = base.rstrip("/")

    def call(self, method: str, path: str, token: str | None = None, body: dict | None = None):
        """Returns (status, parsed JSON or None). Never raises on HTTP errors."""
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                return e.code, json.loads(raw) if raw else None
            except ValueError:
                return e.code, None

    def login(self, email: str, password: str) -> str:
        status, body = self.call("POST", "/api/auth/login", body={"email": email, "password": password})
        if status != 200:
            sys.exit(f"login failed for {email} (HTTP {status}). Is the stack running and .env correct?")
        return body["token"]


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def weighted(rng: random.Random, weights: dict[str, int]) -> str:
    return rng.choices(list(weights), weights=list(weights.values()))[0]


def problem(body) -> str:
    return (body or {}).get("detail") or (body or {}).get("title") or "error"


# ------------------------------------------------------------------ steps

def load_reference_sql(env: dict[str, str]) -> None:
    sql = (ROOT / "scripts" / "demo_reference_data.sql").read_text(encoding="utf-8")
    pw = env.get("DB_PASSWORD", "bloodnet")
    r = subprocess.run(["docker", "compose", "exec", "-T", "mysql", "mysql", "-ubloodnet", f"-p{pw}", "bloodnet"],
                       input=sql, text=True, capture_output=True, cwd=ROOT)
    if r.returncode != 0:
        sys.exit("could not load demo banks/hospitals: " + r.stderr.strip().splitlines()[-1])
    print("banks/hospitals: extra demo rows loaded")


def ensure_users(api: Api, admin: str, banks: dict, hospitals: dict, password: str) -> None:
    created = 0
    for code, email in STAFF.items():
        if code not in banks:
            continue
        s, _ = api.call("POST", "/api/users", admin, {"email": email, "fullName": f"{banks[code]['name']} Staff",
                                                       "password": password, "role": "BANK_STAFF",
                                                       "bankId": banks[code]["id"]})
        created += s == 201
    for name, email in HOSPITAL_USERS.items():
        if name not in hospitals:
            continue
        s, _ = api.call("POST", "/api/users", admin, {"email": email, "fullName": f"{name} Desk",
                                                       "password": password, "role": "HOSPITAL",
                                                       "hospitalId": hospitals[name]["id"]})
        created += s == 201
    print(f"users: {created} created (others already existed)")


def donors_and_donations(api: Api, staff_tokens: dict, banks: dict, rng: random.Random) -> None:
    now = datetime.now(timezone.utc)
    registered = donated = units = skipped = 0
    for b_index, code in enumerate(STAFF, start=1):
        if code not in banks:
            continue
        token = staff_tokens[code]
        for i in range(1, 31):
            phone = f"9{b_index}{i:08d}"
            age_days = rng.randint(18 * 366, 60 * 365)
            body = {"fullName": f"{rng.choice(FIRST)} {rng.choice(LAST)}",
                    "bloodGroup": weighted(rng, GROUP_WEIGHTS),
                    "gender": rng.choice(["MALE", "FEMALE"]),
                    "dateOfBirth": (date.today() - timedelta(days=age_days)).isoformat(),
                    "phone": phone}
            # keep the RNG sequence identical on re-runs, even for donors we skip
            gap = timedelta(days=rng.randint(0, 40), hours=rng.randint(0, 23))
            gives = rng.random() < 0.85
            comps = ["RED_CELLS", "PLATELETS", "PLASMA"] if rng.random() < 0.85 else ["RED_CELLS", "PLASMA"]
            volume = rng.choice([350, 450, 450])

            s, donor = api.call("POST", "/api/donors", token, body)
            if s == 409:
                skipped += 1
                continue
            if s != 201:
                print(f"  donor {phone}: HTTP {s} {problem(donor)}")
                continue
            registered += 1
            if not gives:
                continue
            s, d = api.call("POST", f"/api/donors/{donor['id']}/donations", token,
                            {"collectedAt": iso(now - gap), "volumeMl": volume, "components": comps})
            if s == 201:
                donated += 1
                units += len(d["units"])
            else:
                print(f"  donation for {phone}: HTTP {s} {problem(d)}")
    print(f"donors: {registered} registered, {skipped} already there; donations: {donated} -> {units} units")


def requests(api: Api, hospital_tokens: dict, staff_tokens: dict, banks: dict, hospitals: dict,
             rng: random.Random) -> None:
    bank_codes = [c for c in STAFF if c in banks]
    by_id = {banks[c]["id"]: c for c in bank_codes}
    made = allocated = issued = cancelled = 0
    for name, token in hospital_tokens.items():
        s, page = api.call("GET", "/api/requests?size=100", token)
        if s == 200 and any(r["patientRef"].startswith("DEMO-") for r in page["content"]):
            print(f"requests: {name} already has demo requests, skipping")
            continue
        h = hospitals[name]
        for n in range(8):
            # hospitals mostly ask the nearest banks that have stock
            s, near = api.call("GET", f"/api/banks/nearby?lat={h['latitude']}&lng={h['longitude']}&radiusKm=40", token)
            choices = [b["bankId"] for b in (near or []) if b["bankId"] in by_id] or [banks[c]["id"] for c in bank_codes]
            bank_id = rng.choice(choices[:3])
            body = {"bankId": bank_id, "bloodGroup": weighted(rng, GROUP_WEIGHTS),
                    "componentType": rng.choices(["RED_CELLS", "PLATELETS", "PLASMA"], weights=[70, 15, 15])[0],
                    "units": rng.randint(1, 4),
                    "urgency": rng.choices(["ROUTINE", "URGENT", "EMERGENCY"], weights=[50, 35, 15])[0],
                    "patientRef": f"DEMO-{h['id']}{n:02d}{rng.randint(100, 999)}"}
            s, req = api.call("POST", "/api/requests", token, body)
            if s != 201:
                print(f"  request: HTTP {s} {problem(req)}")
                continue
            made += 1
            staff = staff_tokens[by_id[bank_id]]
            roll = rng.random()
            if roll < 0.08:
                cancelled += api.call("POST", f"/api/requests/{req['id']}/cancel", token)[0] == 200
            elif roll < 0.70:
                s, res = api.call("POST", f"/api/requests/{req['id']}/allocate", staff)
                if s == 200 and res["newlyReserved"] > 0:
                    allocated += 1
                    if rng.random() < 0.5:
                        issued += api.call("POST", f"/api/requests/{req['id']}/issue", staff)[0] == 200
    print(f"requests: {made} created, {allocated} allocated, {issued} issued, {cancelled} cancelled")


def pick_group_with_stock(api: Api, sender_token: str, component: str, units: int, preferred: str) -> str | None:
    """Earlier steps (hospital requests) may already have used up a group, so look at what the
    sending bank really has: available units with more than 2 days left (dispatch needs 24h)."""
    s, page = api.call("GET", f"/api/inventory/units?status=AVAILABLE&component={component}&size=200", sender_token)
    if s != 200:
        return None
    cutoff = datetime.now(timezone.utc) + timedelta(days=2)
    counts: dict[str, int] = {}
    for u in page["content"]:
        if datetime.fromisoformat(u["expiresAt"].replace("Z", "+00:00")) > cutoff:
            counts[u["bloodGroup"]] = counts.get(u["bloodGroup"], 0) + 1
    if counts.get(preferred, 0) >= units:
        return preferred
    enough = sorted((g for g, c in counts.items() if c >= units), key=lambda g: -counts[g])
    return enough[0] if enough else None


def transfers(api: Api, staff_tokens: dict, banks: dict) -> None:
    to_code = "BB-PUN-03"
    if to_code not in banks:
        return
    s, existing = api.call("GET", "/api/transfers?direction=INCOMING", staff_tokens[to_code])
    if s == 200 and existing:
        print("transfers: demo transfers already there, skipping")
        return
    # (receiving bank, sending bank, group, component, units, how far to take it)
    plan = [("BB-PUN-03", "BB-PUN-01", "O_POS", "RED_CELLS", 2, "RECEIVED"),
            ("BB-PUN-03", "BB-PUN-02", "B_POS", "RED_CELLS", 1, "DISPATCHED"),
            ("BB-PCM-01", "BB-PUN-01", "A_POS", "PLASMA", 2, "APPROVED"),
            ("BB-PUN-04", "BB-PCM-01", "O_POS", "PLASMA", 1, "REQUESTED"),
            ("BB-PUN-02", "BB-PUN-04", "AB_POS", "RED_CELLS", 3, "REJECTED")]
    steps = {"REQUESTED": [], "APPROVED": ["approve"], "DISPATCHED": ["approve", "dispatch"],
             "RECEIVED": ["approve", "dispatch", "receive"], "REJECTED": ["reject"]}
    done = 0
    for to, frm, group, comp, n, target in plan:
        if to not in banks or frm not in banks:
            continue
        if target in ("DISPATCHED", "RECEIVED"):
            group = pick_group_with_stock(api, staff_tokens[frm], comp, n, group)
            if group is None:
                print(f"  transfer {frm}->{to}: no {comp} group with {n}+ units left, skipped")
                continue
        s, t = api.call("POST", "/api/transfers", staff_tokens[to],
                        {"fromBankId": banks[frm]["id"], "bloodGroup": group, "componentType": comp, "units": n,
                         "notes": "Demo transfer"})
        if s != 201:
            print(f"  transfer {frm}->{to}: HTTP {s} {problem(t)}")
            continue
        for action in steps[target]:
            actor = staff_tokens[to] if action == "receive" else staff_tokens[frm]
            s, body = api.call("POST", f"/api/transfers/{t['id']}/{action}", actor)
            if s != 200:
                print(f"  transfer #{t['id']} {action}: HTTP {s} {problem(body)}")
                break
        done += 1
    print(f"transfers: {done} created")


def discards(api: Api, staff_tokens: dict) -> None:
    token = staff_tokens["BB-PUN-01"]
    s, page = api.call("GET", "/api/inventory/units?status=DISCARDED&size=1", token)
    if s == 200 and page["totalElements"] > 0:
        return
    s, page = api.call("GET", "/api/inventory/units?status=AVAILABLE&component=PLASMA&size=2", token)
    n = sum(api.call("POST", f"/api/inventory/units/{u['id']}/discard", token)[0] == 200 for u in (page or {}).get("content", []))
    print(f"discarded: {n} units (for the wastage report)")


# ------------------------------------------------------------------ main

def main() -> None:
    ap = argparse.ArgumentParser(description="Load demo data into a running BloodNet")
    ap.add_argument("--base-url", default="http://localhost:8081")
    ap.add_argument("--env-file", default=str(ROOT / ".env"))
    ap.add_argument("--skip-sql", action="store_true", help="don't add the extra banks/hospitals")
    args = ap.parse_args()

    env = load_env(Path(args.env_file))
    seed_pw = env.get("SEED_PASSWORD", "Bloodnet@123")
    api = Api(args.base_url)
    rng = random.Random(42)

    if not args.skip_sql:
        load_reference_sql(env)

    admin_email = env.get("ADMIN_EMAIL") or "admin@bloodnet.local"
    admin_pw = env.get("ADMIN_PASSWORD") if env.get("ADMIN_EMAIL") else seed_pw
    admin = api.login(admin_email, admin_pw)

    banks = {b["code"]: b for b in api.call("GET", "/api/banks", admin)[1]}
    hospitals = {h["name"]: h for h in api.call("GET", "/api/hospitals", admin)[1]}
    ensure_users(api, admin, banks, hospitals, seed_pw)

    staff_tokens = {code: api.login(email, seed_pw) for code, email in STAFF.items() if code in banks}
    hospital_tokens = {name: api.login(email, seed_pw) for name, email in HOSPITAL_USERS.items() if name in hospitals}

    donors_and_donations(api, staff_tokens, banks, rng)
    requests(api, hospital_tokens, staff_tokens, banks, hospitals, rng)
    transfers(api, staff_tokens, banks)
    discards(api, staff_tokens)

    s, r = api.call("POST", "/api/dashboard/refresh", admin)
    print(f"dashboard: summary refreshed ({(r or {}).get('cells', '?')} stock cells)" if s == 200
          else f"dashboard refresh: HTTP {s}")
    print("done. Expired units show up after the expiry job runs (every 10 minutes).")


if __name__ == "__main__":
    main()
