#!/usr/bin/env python3
"""Mint a short-lived local Pipesor login. GitHub attestation is a separate step."""
import json
import os
import stat
import time
import uuid
from pathlib import Path

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

os.umask(0o077)
directory = Path.home() / ".config" / "pipesor-local"
if directory.is_symlink() or stat.S_IMODE(directory.stat().st_mode) != 0o700:
    raise SystemExit("The local configuration directory must be owned by the operator with mode 0700")

scope, = json.loads((directory / "authority.json").read_text())["machineScopes"]
state_path = directory / "session-identity.json"
key_path = directory / "session-signing.pem"
public_path = directory / "session-public.der"
if not state_path.exists():
    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    with key_path.open("xb") as out:
        out.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                    serialization.NoEncryption()))
    public_path.write_bytes(key.public_key().public_bytes(serialization.Encoding.DER,
                                                          serialization.PublicFormat.SubjectPublicKeyInfo))
    state = {name: str(uuid.uuid4()) for name in ("actor", "browser", "pipeline")}
    state["tenant"] = scope["tenantId"]
    state_path.write_text(json.dumps(state))
else:
    state = json.loads(state_path.read_text())
    if state["tenant"] != scope["tenantId"] or key_path.is_symlink() or stat.S_IMODE(key_path.stat().st_mode) != 0o600:
        raise SystemExit("The existing local JWT identity is incompatible with the configured capture scope")

issuer = "https://pipesor.local/session"
audience = "pipesor-local-ingestion"
now = int(time.time())
token = jwt.encode({"iss": issuer, "aud": audience, "tid": state["tenant"], "oid": state["actor"],
                    "azp": state["browser"], "ver": "2.0", "scp": "GitHub.Self",
                    "iat": now, "nbf": now, "exp": now + 3600}, key_path.read_bytes(), algorithm="RS256")
(directory / "browser-session.url").write_text(
    "http://127.0.0.1:5173/operations?queue=active&pilot=1#local-session=" + token + "\n")
settings = {
    "ingestion.local-capture.session-public-key": public_path,
    "ingestion.api.security.issuer": issuer,
    "ingestion.api.security.jwk-set-uri": issuer + "/keys",
    "ingestion.api.security.tenant": state["tenant"],
    "ingestion.api.security.audience": audience,
    "ingestion.api.security.token-version": "2.0",
    "ingestion.api.security.self-scope": "GitHub.Self",
    "ingestion.api.security.internal-scope": "GitHub.Internal",
    "ingestion.api.security.self-callers": state["browser"],
    "ingestion.api.security.pipeline-callers": state["pipeline"],
    "ingestion.api.security.clock-skew": "PT0S",
    "ingestion.api.limits.request-timeout": "PT100S",
    "ingestion.api.limits.max-batch-size": 5,
    "ingestion.api.limits.max-page-size": 100,
    "ingestion.api.limits.max-request-bytes": 8192,
    "ingestion.api.limits.max-response-bytes": 65536,
    "ingestion.api.limits.max-context-length": 128,
}
(directory / "ingestion-session.properties").write_text("".join(f"{k}={v}\n" for k, v in settings.items()))
print("Local session URL written to", directory / "browser-session.url")
