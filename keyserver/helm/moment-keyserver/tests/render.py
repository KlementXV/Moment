#!/usr/bin/env python3
"""Offline Helm regression checks; --crds additionally downloads official CRD schemas."""
import argparse
import copy
import json
import pathlib
import subprocess
import tempfile
import urllib.request

import jsonschema
import yaml

CHART = pathlib.Path(__file__).resolve().parents[1]


def render(overrides=None, success=True):
    with tempfile.TemporaryDirectory(prefix="moment-helm-") as directory:
        values = pathlib.Path(directory) / "values.json"
        values.write_text(json.dumps(overrides or {}))
        result = subprocess.run(
            ["helm", "template", "test", str(CHART), "--namespace", "moment", "-f", str(values)],
            capture_output=True, text=True,
        )
    assert (result.returncode == 0) == success, result.stderr
    if not success:
        return []
    return [doc for doc in yaml.safe_load_all(result.stdout) if doc]


def find(docs, kind):
    matches = [doc for doc in docs if doc["kind"] == kind]
    assert len(matches) == 1, (kind, len(matches))
    return matches[0]


def validate_common(docs):
    assert not any(doc["kind"] == "Secret" for doc in docs)
    deployment = find(docs, "Deployment")
    spec = deployment["spec"]["template"]["spec"]
    assert spec["automountServiceAccountToken"] is False
    assert spec["securityContext"]["runAsUser"] == 10001
    container = spec["containers"][0]
    assert container["securityContext"]["readOnlyRootFilesystem"] is True
    assert container["securityContext"]["capabilities"]["drop"] == ["ALL"]
    assert container["readinessProbe"]["httpGet"]["path"] == "/readyz"
    assert container["livenessProbe"]["httpGet"]["path"] == "/healthz"
    assert all("valueFrom" in entry for entry in container["env"])
    cfg = find(docs, "ConfigMap")["data"]
    assert all(isinstance(value, str) for value in cfg.values())
    assert "TRUSTED_PROXY_CIDRS" in cfg
    assert cfg["PGSSLMODE"] == "verify-full"
    assert cfg["PGSSLROOTCERT"] == "/var/run/postgresql-ca/ca.crt"
    assert "DATABASE_PATH" not in cfg
    assert "RPC_URL" not in cfg and "KEY_ENCRYPTION_KEY" not in cfg
    for volume in spec["volumes"]:
        if "secret" in volume:
            assert volume["secret"]["defaultMode"] == 0o440
    assert find(docs, "Service")["spec"]["type"] == "ClusterIP"


def strict_schema(node):
    if not isinstance(node, dict):
        return
    # CRD metadata and intentionally arbitrary maps must remain open.
    if node.get("properties") and "additionalProperties" not in node and not node.get("x-kubernetes-preserve-unknown-fields"):
        node["additionalProperties"] = False
    # OpenAPI nullable -> JSON schema type union.
    if node.get("nullable") and isinstance(node.get("type"), str):
        node["type"] = [node["type"], "null"]
    for value in node.values():
        if isinstance(value, dict):
            strict_schema(value)
        elif isinstance(value, list):
            for item in value:
                strict_schema(item)


def validate_crds(documents):
    sources = {
        "Cluster": "https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-1.28/config/crd/bases/postgresql.cnpg.io_clusters.yaml",
        "ScheduledBackup": "https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-1.28/config/crd/bases/postgresql.cnpg.io_scheduledbackups.yaml",
        "ObjectStore": "https://raw.githubusercontent.com/cloudnative-pg/plugin-barman-cloud/v0.15.0/config/crd/bases/barmancloud.cnpg.io_objectstores.yaml",
    }
    schemas = {}
    for kind, url in sources.items():
        with urllib.request.urlopen(url, timeout=30) as response:
            crd = yaml.safe_load(response)
        schema = copy.deepcopy(next(v for v in crd["spec"]["versions"] if v["name"] == "v1")["schema"]["openAPIV3Schema"])
        strict_schema(schema)
        schemas[kind] = schema
    count = 0
    for doc in documents:
        if doc["kind"] in schemas:
            jsonschema.Draft7Validator(schemas[doc["kind"]]).validate(doc)
            count += 1
    print(f"Official CNPG 1.28 / Barman 0.15 CRD schemas: {count} resources validated")


def main():
    args = argparse.ArgumentParser()
    args.add_argument("--crds", action="store_true")
    options = args.parse_args()
    subprocess.run(["helm", "lint", str(CHART), "--strict"], check=True)
    default = render()
    validate_common(default)
    cluster = find(default, "Cluster")
    assert cluster["spec"]["instances"] == 3
    assert cluster["spec"]["postgresql"]["synchronous"] == {
        "method": "any", "number": 1, "dataDurability": "required"}
    single = render({"postgresql": {"instances": 1, "synchronousReplication": False}})
    assert "synchronous" not in find(single, "Cluster")["spec"]["postgresql"]
    assert cluster["metadata"]["annotations"]["helm.sh/resource-policy"] == "keep"
    assert "secret" not in cluster["spec"]["bootstrap"]["initdb"]
    cfg = find(default, "ConfigMap")["data"]
    assert cfg["PGHOST"] == "test-moment-keyserver-pg-rw.moment.svc"
    assert find(default, "Deployment")["spec"]["replicas"] == 2

    full = render({
        "postgresql": {"existingSecret": "db-creds", "walStorage": {"enabled": True}, "monitoring": {"enabled": True}, "backup": {
            "enabled": True, "destinationPath": "s3://db-backups/test", "endpointURL": "https://account.r2.cloudflarestorage.com"}},
        "ingress": {"enabled": True, "tlsSecretName": "moment-tls"},
        "config": {"trustedProxyCidrs": ["10.1.2.3/32", "fd00:1::/64"]},
        "autoscaling": {"enabled": True}, "networkPolicy": {"enabled": True},
    })
    validate_common(full)
    assert find(full, "ConfigMap")["data"]["TRUSTED_PROXY_CIDRS"] == "10.1.2.3/32,fd00:1::/64"
    assert "replicas" not in find(full, "Deployment")["spec"]
    assert find(full, "Cluster")["spec"]["bootstrap"]["initdb"]["secret"]["name"] == "db-creds"
    assert find(full, "ObjectStore")["spec"]["retentionPolicy"] == "30d"
    assert find(full, "ScheduledBackup")["spec"]["method"] == "plugin"
    assert "app.kubernetes.io/component" not in find(full, "Cluster")["metadata"]["labels"]
    assert find(full, "PodMonitor")["spec"]["selector"]["matchLabels"]["cnpg.io/cluster"] == "test-moment-keyserver-pg"
    assert find(full, "NetworkPolicy")["spec"]["podSelector"]["matchLabels"] == find(full, "Deployment")["spec"]["selector"]["matchLabels"]
    find(full, "HorizontalPodAutoscaler")
    find(full, "Ingress")

    external = render({"postgresql": {"enabled": False}, "externalPostgresql": {
        "host": "postgres.example.com", "existingSecret": "external-db", "caSecret": "external-ca"}})
    validate_common(external)
    assert not any(doc["kind"] == "Cluster" for doc in external)
    assert find(external, "ConfigMap")["data"]["PGHOST"] == "postgres.example.com"
    custom = render({"fullnameOverride": "custom", "postgresql": {"clusterName": "custom-db", "keep": False}, "image": {"digest": "sha256:" + "a" * 64}})
    validate_common(custom)
    assert "annotations" not in find(custom, "Cluster")["metadata"]
    assert find(custom, "ConfigMap")["data"]["PGHOST"] == "custom-db-rw.moment.svc"
    assert "@sha256:" in find(custom, "Deployment")["spec"]["template"]["spec"]["containers"][0]["image"]
    for invalid in [
        {"postgresql": {"instances": 1}},
        {"postgresql": {"parameters": {"synchronous_commit": "off"}}},
        {"postgresql": {"parameters": {"synchronous_commit": "local"}}},
        {"ingress": {"enabled": True, "tlsSecretName": "tls"}},
        {"replicaCount": 0}, {"postgresql": {"enabled": False}},
        {"config": {"network": "mainnet", "allowUncalibratedModeration": True}},
        {"postgresql": {"backup": {"enabled": True}}},
        {"ingress": {"enabled": True}}, {"unknownField": "refused"},
        {"autoscaling": {"enabled": True, "minReplicas": 5, "maxReplicas": 2}},
        {"config": {"authOrigin": "http://insecure.example.com"}},
    ]:
        render(invalid, success=False)
    if options.crds:
        validate_crds(default + full + custom)
    print("PASS: 5 render variants, 12 invalid configurations, secrets/TLS/probes/security invariants")


if __name__ == "__main__":
    main()
