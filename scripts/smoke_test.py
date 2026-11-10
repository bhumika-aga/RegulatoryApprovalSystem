#!/usr/bin/env python3
"""End-to-end smoke test for a running Regulatory Approval System.

Drives every route through the approval process over the public REST API and checks
role enforcement, read scoping, the audit trail and the exposed surface (Swagger,
actuator, Camunda engine REST API, H2 console). Uses only the Python standard library.

    python3 scripts/smoke_test.py --base-url http://localhost:8080 --camunda-password <pw>

Options can also come from the environment: SMOKE_BASE_URL, CAMUNDA_ADMIN_PASSWORD
(Camunda admin user, for the engine REST API checks) and AUTH_DEFAULT_PASSWORD (password
of the seeded API users, default ChangeMe123!).

The run creates several workflows (approved, rejected, escalated, terminated, ...), so
point it at a local or test instance rather than one holding real data. Prints one
PASS/FAIL line per check and exits with status 1 if any check failed.
"""
import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
parser.add_argument("--base-url", default=os.environ.get("SMOKE_BASE_URL", "http://localhost:8080"))
parser.add_argument("--camunda-password", default=os.environ.get("CAMUNDA_ADMIN_PASSWORD", "admin"),
                    help="password of the Camunda 'admin' user (engine REST API checks)")
parser.add_argument("--api-password", default=os.environ.get("AUTH_DEFAULT_PASSWORD", "ChangeMe123!"),
                    help="password of the seeded API users")
args = parser.parse_args()

BASE = args.base_url.rstrip("/")
CAMUNDA_PW = args.camunda_password
PASSWORD = args.api_password
results = []


def http(method, path, token=None, body=None, headers=None, basic=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if basic:
        req.add_header("Authorization", "Basic " + base64.b64encode(basic.encode()).decode())
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            raw = res.read()
            return res.status, _json(raw)
    except urllib.error.HTTPError as e:
        return e.code, _json(e.read())


def _json(raw):
    try:
        return json.loads(raw) if raw else None
    except ValueError:
        return raw.decode(errors="replace")[:200]


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  -> {detail}"))


def login(user):
    status, body = http("POST", "/api/v1/auth/token", body={"username": user, "password": PASSWORD})
    if status != 200:
        sys.exit(f"Login as '{user}' failed with HTTP {status}: {body} (check --api-password)")
    return body["data"]["accessToken"]


def start(token, title, request_type="OPERATIONAL_CHANGE"):
    status, body = http("POST", "/api/v1/workflow/start", token,
                        {"requestTitle": title, "requestDescription": "scenario test",
                         "requestType": request_type, "department": "OPERATIONS", "priority": "HIGH"})
    assert status == 201, (status, body)
    return body["data"]["processInstanceId"]


def await_task(token, pid, element, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        _, body = http("GET", "/api/v1/tasks", token)
        for t in (body or {}).get("data") or []:
            if t["processInstanceId"] == pid and t["taskDefinitionKey"] == element:
                return t
        time.sleep(0.5)
    return None


def complete(token, task_id, decision, comment=None):
    body = {"decision": decision}
    if comment:
        body["comment"] = comment
    return http("POST", f"/api/v1/tasks/{task_id}/complete", token, body)


def await_status(token, pid, predicate, timeout=30):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        _, body = http("GET", f"/api/v1/workflow/status/{pid}", token)
        last = (body or {}).get("data")
        if last and predicate(last):
            return last
        time.sleep(0.5)
    return last


def audit_types(token, pid):
    _, body = http("GET", f"/api/v1/audit/process/{pid}", token)
    return [e["eventType"] for e in (body or {}).get("data") or []]


try:
    http("GET", "/actuator/health")
except urllib.error.URLError as exc:
    sys.exit(f"Cannot reach {BASE}: {exc.reason}")

tok = {u: login(u) for u in ["reviewer", "manager", "senior.manager", "compliance", "admin", "auditor"]}
R, M, SM, C, A, AU = (tok[u] for u in ["reviewer", "manager", "senior.manager", "compliance", "admin", "auditor"])

# ---------------------------------------------------------------- 1. Happy path
print("\n== 1. Happy path: Initial Review -> Manager -> automated compliance -> Final Approval")
pid = start(R, "Happy path")
t = await_task(R, pid, "Task_InitialReview")
check("risk scoring ran and Initial Review task created", t is not None and t.get("riskScore") is not None, t)
check("manager cannot complete reviewer's task (403)", complete(M, t["taskId"], "APPROVED")[0] == 403)
check("reviewer approves Initial Review (200)", complete(R, t["taskId"], "APPROVED", "looks fine")[0] == 200)
t = await_task(M, pid, "Task_ManagerApproval")
check("Manager Approval task created", t is not None)
check("manager approves (200)", complete(M, t["taskId"], "APPROVED")[0] == 200)
t = await_task(A, pid, "Task_FinalApproval")
check("compliance auto-passed, Final Approval task created", t is not None)
check("admin approves Final Approval (200)", complete(A, t["taskId"], "APPROVED", "ship it")[0] == 200)
wf = await_status(A, pid, lambda w: w["status"] == "APPROVED")
check("request ends APPROVED / COMPLETED", wf and wf["status"] == "APPROVED" and wf["currentStage"] == "COMPLETED", wf)
types = audit_types(AU, pid)
for ev in ["WORKFLOW_STARTED", "TASK_CREATED", "TASK_CLAIMED", "TASK_COMPLETED", "DECISION_MADE",
           "COMPLIANCE_CHECK_PASSED", "WORKFLOW_COMPLETED"]:
    check(f"audit trail contains {ev}", ev in types, types)

# ---------------------------------------------------------------- 2. Rejection
print("\n== 2. Rejection at Initial Review")
pid = start(R, "Rejected early")
t = await_task(R, pid, "Task_InitialReview")
check("reviewer rejects (200)", complete(R, t["taskId"], "REJECTED", "incomplete dossier")[0] == 200)
wf = await_status(R, pid, lambda w: w["status"] == "REJECTED")
check("request ends REJECTED with reason", wf and wf["status"] == "REJECTED"
      and "Initial Review" in (wf.get("rejectionReason") or ""), wf)

# ---------------------------------------------------------------- 3. Needs info loop
print("\n== 3. NEEDS_INFO loop back to the submitter")
pid = start(M, "Needs info")
t = await_task(R, pid, "Task_InitialReview")
check("reviewer requests more info (200)", complete(R, t["taskId"], "NEEDS_INFO")[0] == 200)
info = await_task(M, pid, "Task_RequestInfo")
check("submitter (manager) gets 'Provide Additional Information'", info is not None)
check("submitter provides info (200)", complete(M, info["taskId"], "APPROVED", "contract attached")[0] == 200)
t2 = await_task(R, pid, "Task_InitialReview")
check("request returns to Initial Review", t2 is not None and t2["taskId"] != t["taskId"])

# ---------------------------------------------------------------- 4. Escalation to senior manager
print("\n== 4. Manager escalates to Senior Manager")
pid = start(R, "Escalated by manager")
complete(R, await_task(R, pid, "Task_InitialReview")["taskId"], "APPROVED")
t = await_task(M, pid, "Task_ManagerApproval")
check("manager escalates (200)", complete(M, t["taskId"], "ESCALATE")[0] == 200)
t = await_task(SM, pid, "Task_SeniorManagerReview")
check("Senior Manager Review task created", t is not None)
check("senior manager approves (200)", complete(SM, t["taskId"], "APPROVED")[0] == 200)
t = await_task(SM, pid, "Task_FinalApproval")
check("senior manager can take Final Approval", t is not None)
complete(SM, t["taskId"], "APPROVED")
wf = await_status(R, pid, lambda w: w["status"] == "APPROVED")
check("request ends APPROVED", wf and wf["status"] == "APPROVED", wf)

# ---------------------------------------------------------------- 5. Manual compliance review
print("\n== 5. High-risk request -> Compliance Manual Review -> rejected at Final Approval")
pid = start(R, "High risk product", "FINANCIAL_PRODUCT")
complete(R, await_task(R, pid, "Task_InitialReview")["taskId"], "APPROVED")
complete(M, await_task(M, pid, "Task_ManagerApproval")["taskId"], "APPROVED")
t = await_task(C, pid, "Task_ComplianceManualReview")
check("Compliance Manual Review task created for high risk", t is not None)
check("compliance passes (200)", complete(C, t["taskId"], "PASS", "manually verified")[0] == 200)
t = await_task(A, pid, "Task_FinalApproval")
check("admin rejects at Final Approval (200)", complete(A, t["taskId"], "REJECTED", "not now")[0] == 200)
wf = await_status(R, pid, lambda w: w["status"] == "REJECTED")
check("request ends REJECTED", wf and wf["status"] == "REJECTED", wf)

# ---------------------------------------------------------------- 6. Claim / unclaim / terminate
print("\n== 6. Claim, unclaim, terminate")
pid = start(R, "Claim and terminate")
t = await_task(R, pid, "Task_InitialReview")
st, body = http("POST", f"/api/v1/tasks/{t['taskId']}/claim", R)
check("reviewer claims task (200, assignee=reviewer)", st == 200 and body["data"]["assignee"] == "reviewer", body)
check("manager cannot claim it (409 already claimed)", http("POST", f"/api/v1/tasks/{t['taskId']}/claim", M)[0] == 409)
check("reviewer unclaims (200)", http("POST", f"/api/v1/tasks/{t['taskId']}/unclaim", R)[0] == 200)
check("non-admin cannot terminate (403)", http("DELETE", f"/api/v1/workflow/{pid}?reason=x", R)[0] == 403)
check("admin terminates (200)", http("DELETE", f"/api/v1/workflow/{pid}?reason=duplicate", A)[0] == 200)
wf = await_status(A, pid, lambda w: w["status"] == "TERMINATED")
check("request is TERMINATED", wf and wf["status"] == "TERMINATED", wf)

# ---------------------------------------------------------------- 7. Read endpoints & scoping
print("\n== 7. Read endpoints and access scoping")
st, body = http("GET", "/api/v1/workflow/my-requests", R)
check("my-requests lists reviewer's submissions", st == 200 and len(body["data"]) >= 4, st)
check("by-status (manager) 200", http("GET", "/api/v1/workflow/by-status/APPROVED", M)[0] == 200)
check("by-status forbidden for reviewer (403)", http("GET", "/api/v1/workflow/by-status/APPROVED", R)[0] == 403)
check("user/{id} (auditor) 200", http("GET", "/api/v1/workflow/user/reviewer", AU)[0] == 200)
check("escalated (senior manager) 200", http("GET", "/api/v1/workflow/escalated", SM)[0] == 200)
mgr_pid = start(M, "Manager's own request")
check("reviewer cannot read manager's request (403)", http("GET", f"/api/v1/workflow/status/{mgr_pid}", R)[0] == 403)
check("auditor can read it (200)", http("GET", f"/api/v1/workflow/status/{mgr_pid}", AU)[0] == 200)
for p in ["/api/v1/audit/user/reviewer", "/api/v1/audit/event-type/WORKFLOW_COMPLETED",
          "/api/v1/audit/date-range?startDate=2026-01-01T00:00:00&endDate=2027-01-01T00:00:00",
          "/api/v1/audit/sla-breaches?since=2026-01-01T00:00:00",
          "/api/v1/audit/count/TASK_CREATED?since=2026-01-01T00:00:00"]:
    check(f"auditor GET {p.split('?')[0]} 200", http("GET", p, AU)[0] == 200)
check("reviewer cannot read audit (403)", http("GET", "/api/v1/audit/user/reviewer", R)[0] == 403)

# ---------------------------------------------------------------- 8. Auth & public surface
print("\n== 8. Authentication and public surface")
check("wrong password -> 401", http("POST", "/api/v1/auth/token", body={"username": "admin", "password": "x"})[0] == 401)
check("no token -> 401", http("GET", "/api/v1/tasks")[0] == 401)
st, body = http("POST", "/api/v1/auth/token", body={"username": "reviewer", "password": PASSWORD})
st2, body2 = http("POST", "/api/v1/auth/refresh", headers={"X-Refresh-Token": body["data"]["refreshToken"]})
check("refresh token -> new tokens with same roles", st2 == 200 and body2["data"]["roles"] == ["REVIEWER"], body2)
check("/actuator/health public UP", http("GET", "/actuator/health")[1].get("status") == "UP")
check("/v3/api-docs public 200", http("GET", "/v3/api-docs")[0] == 200)
check("/swagger-ui/index.html 200", http("GET", "/swagger-ui/index.html")[0] == 200)
check("/h2-console not exposed (non-200)", http("GET", "/h2-console")[0] != 200)
# (/engine-rest/engine, the engine-name listing, is intentionally public in Camunda's auth filter.)
check("/engine-rest data without credentials -> 401", http("GET", "/engine-rest/process-definition")[0] == 401)
check("/engine-rest data with admin credentials -> 200",
      http("GET", "/engine-rest/process-definition", basic="admin:" + CAMUNDA_PW)[0] == 200)
# Last: Camunda delays logins briefly after a failed attempt (brute-force protection).
check("/engine-rest data with wrong password -> 401",
      http("GET", "/engine-rest/process-definition", basic="admin:wrong")[0] == 401)
check("/camunda webapp login page 200", http("GET", "/camunda/app/welcome/default/")[0] == 200)

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
