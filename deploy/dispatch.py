"""Send a bounded deployment command and wait for its actual SSM result."""
import json
import os
import re
import shlex
import subprocess
import time


def aws(*args):
    result = subprocess.run(["aws", *args, "--output", "json"], text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    return json.loads(result.stdout)


def main():
    keys = ["GITHUB_SHA", "RELEASE_CHECKSUM", "RELEASE_BUCKET", "INSTANCE_ID", "AWS_REGION",
            "ORDER_REPOSITORY_URI", "FRONTEND_REPOSITORY_URI", "CONFIG_PARAMETER"]
    values = {key: os.environ[key] for key in keys}
    sha = values["GITHUB_SHA"]
    assert re.fullmatch(r"[a-f0-9]{40}", sha), "Expected a full Git commit SHA"
    assert re.fullmatch(r"[a-f0-9]{64}", values["RELEASE_CHECKSUM"]), "Invalid release checksum"
    assert re.fullmatch(r"i-[a-f0-9]+", values["INSTANCE_ID"]), "Invalid instance ID"
    root = "/opt/logistics/releases/" + sha
    archive = root + ".tgz"
    q = shlex.quote
    command = "\n".join([
        "set -eu",
        "test -f /opt/logistics/bootstrap.ready || { echo 'EC2 bootstrap is not ready; inspect /var/log/cloud-init-output.log'; exit 1; }",
        "install -d -m 700 /opt/logistics/releases",
        "aws s3 cp " + q("s3://" + values["RELEASE_BUCKET"] + "/releases/" + sha + ".tgz") +
        " " + q(archive) + " --region " + q(values["AWS_REGION"]) + " --only-show-errors",
        "printf '%s\\n' " + q(values["RELEASE_CHECKSUM"] + "  " + archive) + " | sha256sum -c -",
        "install -d -m 700 " + q(root),
        "tar -xzf " + q(archive) + " -C " + q(root),
        "bash " + q(root + "/deploy/release.sh") + " " + " ".join(q(values[key]) for key in
          ["GITHUB_SHA", "AWS_REGION", "ORDER_REPOSITORY_URI", "FRONTEND_REPOSITORY_URI", "CONFIG_PARAMETER"])
    ])
    response = aws("ssm", "send-command", "--instance-ids", values["INSTANCE_ID"],
                   "--document-name", "AWS-RunShellScript", "--timeout-seconds", "600",
                   "--parameters", json.dumps({"commands": [command], "executionTimeout": ["1800"]}))
    command_id = response["Command"]["CommandId"]
    print("SSM command: " + command_id, flush=True)
    for _ in range(240):
        time.sleep(5)
        try:
            result = aws("ssm", "get-command-invocation", "--command-id", command_id,
                         "--instance-id", values["INSTANCE_ID"])
        except RuntimeError as error:
            if "InvocationDoesNotExist" in str(error):
                continue
            raise
        if result["Status"] in {"Pending", "InProgress", "Delayed", "Cancelling"}:
            continue
        print(result.get("StandardOutputContent", ""))
        print(result.get("StandardErrorContent", ""))
        if result["Status"] != "Success":
            raise RuntimeError("Deployment finished with status " + result["Status"])
        return
    raise RuntimeError("SSM is still running; inspect command " + command_id + " before retrying")


if __name__ == "__main__":
    main()
