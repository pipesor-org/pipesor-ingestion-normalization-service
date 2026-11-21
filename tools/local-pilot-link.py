#!/usr/bin/env python3
"""Bind the local operator to an expiring user grant issued by the configured GitHub App."""
import hashlib
import json
import os
import stat
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import httpx
import jwt


def private(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600:
        raise SystemExit("An operator-owned 0600 configuration file is required")
    return path.read_bytes()


def write_atomic(path, value):
    temp = path.with_name("." + path.name + "-" + str(uuid.uuid4()))
    try:
        with temp.open("x") as file:
            json.dump(value, file)
            file.flush()
            os.fsync(file.fileno())
        temp.replace(path)
    finally:
        temp.unlink(missing_ok=True)


def api(client, path, token):
    response = client.get("https://api.github.com" + path, headers={
        "Authorization": "Bearer " + token, "X-GitHub-Api-Version": "2022-11-28",
    })
    if response.status_code != 200:
        raise SystemExit(f"GitHub verification returned HTTP {response.status_code}")
    return response


def pages(client, path, token, field):
    for number in range(1, 9):
        reply = api(client, f"{path}?per_page=100&page={number}", token)
        yield from reply.json()[field]
        if 'rel="next"' not in reply.headers.get("Link", ""):
            return
    raise SystemExit("GitHub membership verification exceeded the local limit")


def flow(client, client_id):
    endpoint = "https://github.com/login"
    reply = client.post(endpoint + "/device/code", data={"client_id": client_id, "scope": "offline_access"})
    device = reply.json()
    if reply.status_code != 200:
        reason = device.get("error")
        raise SystemExit("GitHub device authorization is disabled" if reason == "device_flow_disabled"
                         else f"GitHub device authorization failed with HTTP {reply.status_code}")
    if device["verification_uri"] != "https://github.com/login/device":
        raise SystemExit("Unexpected GitHub verification host")
    print("Open https://github.com/login/device and approve this App with the code shown:", device["user_code"], flush=True)
    remaining = time.monotonic() + min(device["expires_in"], 900)
    interval = max(device["interval"], 5)
    while time.monotonic() + interval < remaining:
        time.sleep(interval)
        grant = client.post(endpoint + "/oauth/access_token", data={
            "client_id": client_id, "device_code": device["device_code"],
            "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
        }).json()
        if grant.get("error") == "authorization_pending":
            continue
        if grant.get("error") == "slow_down":
            interval = max(interval + 5, grant.get("interval", 0))
            continue
        return grant
    raise SystemExit("The GitHub user approval window expired")


def main():
    os.umask(0o077)
    root = Path.home() / ".config" / "pipesor-local"
    info = root.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
        raise SystemExit("The local pilot directory must be operator-owned with mode 0700")
    authority_path = root / "authority.json"
    authority = json.loads(private(authority_path))
    scope, = authority["machineScopes"]
    identity = json.loads(private(root / "session-identity.json"))
    if identity["tenant"] != scope["tenantId"] or "completed" not in scope["allowedActions"]:
        raise SystemExit("The local session does not match the approved machine scope")
    settings = dict(line.split("=", 1) for line in private(root / "ingestion-capture.properties").decode().splitlines()
                    if "=" in line)
    app_key = private(Path(settings["ingestion.local-capture.signing-key-file"]))
    if hashlib.sha256(app_key).hexdigest() != settings["ingestion.local-capture.signing-key-sha256"]:
        raise SystemExit("The App key has changed")
    now = int(time.time())
    app_jwt = jwt.encode({"iss": scope["appId"], "iat": now - 30, "exp": now + 240}, app_key, algorithm="RS256")
    with httpx.Client(timeout=15, follow_redirects=False, headers={"Accept": "application/json"}) as client:
        app = api(client, "/app", app_jwt).json()
        if str(app["id"]) != scope["appId"]:
            raise SystemExit("Wrong GitHub App identity")
        grant = flow(client, app["client_id"])
        token = grant.get("access_token", "")
        lifetime = grant.get("expires_in", 0)
        if not token.startswith("ghu_") or not isinstance(lifetime, int) or not 0 < lifetime <= 28800:
            raise SystemExit("An expiring GitHub App user grant is required")
        issued = int(time.time())
        user = api(client, "/user", token).json()
        installation = next((item for item in pages(client, "/user/installations", token, "installations")
                             if str(item["id"]) == scope["installationId"]), None)
        if (not installation or str(installation["app_id"]) != scope["appId"]
                or str(installation["account"]["id"]) != scope["accountId"] or installation.get("suspended_at")):
            raise SystemExit("The authenticated user has no access to the approved installation")
        repository = next((item for item in pages(client, f"/user/installations/{scope['installationId']}/repositories",
                                                  token, "repositories")
                           if str(item["id"]) == scope["repositoryId"]), None)
        if not repository or repository.get("permissions", {}).get("admin") is not True:
            raise SystemExit("The authenticated user lacks repository administration")
    if json.loads(private(authority_path)) != authority:
        raise SystemExit("The capture authority changed during user approval")
    admission = {field: scope[field] for field in ("tenantId", "appId", "accountId", "installationId")}
    admission["repositoryIds"] = [scope["repositoryId"]]
    existing = authority["humanAdmissions"]
    if admission not in existing:
        if any(x["tenantId"] == admission["tenantId"] and scope["repositoryId"] in x["repositoryIds"] for x in existing):
            raise SystemExit("Existing human admission conflicts with this pilot")
        existing.append(admission)
        write_atomic(authority_path, authority)
    write_atomic(root / "github-user-link.json", {
        "app": scope["appId"], "tenant": identity["tenant"], "actor": identity["actor"],
        "githubUser": str(user["id"]), "generation": str(uuid.uuid4()), "accessToken": token,
        "issuedAt": datetime.fromtimestamp(issued, timezone.utc).isoformat(),
        "expiresAt": datetime.fromtimestamp(issued + lifetime, timezone.utc).isoformat(),
    })
    print("GitHub App user and repository membership verified. Link stored locally.")


if __name__ == "__main__":
    try:
        main()
    except (httpx.HTTPError, KeyError, ValueError, OSError):
        raise SystemExit("GitHub App user authorization or local custody is unavailable") from None
