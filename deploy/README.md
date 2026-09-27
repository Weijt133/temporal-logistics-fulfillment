# CI/CD and AWS demo deployment

[Project overview](../README.md) · [Architecture](../docs/architecture.md) · [Verification evidence](../docs/testing.md)

This guide deploys the existing modular Spring Boot application and React console. It does not split the application into independent microservices. Temporal uses a private development server with SQLite persistence; use Temporal Cloud or a production self-hosted service before treating this as a production deployment.

Status as of 2026-09-27: local container and configuration checks have passed; a remote GitHub Actions run and actual AWS deployment have not yet been verified. The steps below describe how to obtain that evidence in your account.

## What is included

| File | Purpose |
| --- | --- |
| `.github/workflows/ci.yml` | Java tests, workflow replay tests, frontend lint and production build |
| `.github/workflows/oidc-subject.yml` | Report the exact OIDC subject for your repository/environment |
| `.github/workflows/deploy.yml` | Manual main-branch deployment, gated by CI |
| `order-service/Dockerfile` | Multi-stage Java 21 image |
| `frontend/Dockerfile`, `frontend/nginx.conf` | Static React build and `/api` proxy |
| `deploy/compose.demo.yml` | Separate deployment stack with persistent volumes |
| `deploy/Caddyfile` | HTTPS and password protection for the app and Temporal UI |
| `deploy/cloudformation.yml` | EC2, ECR, S3 release storage, IAM, OIDC and SSM access |
| `deploy/dispatch.py` | Send the deployment command and wait for SSM completion |
| `deploy/release.sh` | Pull a release, verify it, and restore the previous app on failure |
| `deploy/smoke.py` | Create an isolated test SKU/order and verify workflow, stock and shipment |

No Java source, database migrations, or npm dependencies need changing. Keep the existing local `compose.yaml`.

## 1. Review the deployment files

The files listed above are included in this repository. Keep `.github` at the repository root and retain the `.dockerignore` files so local dependencies and build outputs do not enter the Linux build contexts. The existing local `compose.yaml` remains separate from `deploy/compose.demo.yml`.

Use the repository's `main` branch for deployment. If you rename it, change the workflow branch conditions and the GitHub environment restriction together.

## 2. Validate the containers locally (Windows PowerShell)

Run from the repository root:

```powershell
$env:DB_PASSWORD = 'local-only-validation-password'
docker build -t logistics/order-service:local .\order-service
docker build -t logistics/frontend:local .\frontend
docker compose -f .\deploy\compose.demo.yml config --quiet
docker compose -f .\deploy\compose.demo.yml up -d --wait --wait-timeout 240
python .\deploy\smoke.py
```

Visit `http://127.0.0.1:18080`. Python 3 is only needed to run the smoke script. This stack uses different volumes from the original local application. PostgreSQL and gRPC remain internal; the console and Temporal UI bind only to loopback on ports 18080 and 18233. The public HTTPS gateway is an opt-in Compose profile and is not started by this local command. The local console's Temporal link points to `http://localhost:18233`; the AWS deployment overrides it with the protected Temporal hostname.

Expected: all long-running containers are healthy, the one-shot volume initializer has exited with code 0, and the script prints `Smoke test passed`. Smoke orders use a unique `SMOKE-*` SKU and do not deduct `SKU-001` stock.

Stop only this separate stack when finished:

```powershell
docker compose -f .\deploy\compose.demo.yml down
```

Do not add `-v` if you want to keep its data. The original `docker compose up -d` command still uses the original development stack.

## 3. Enable CI in GitHub

Review `git status`, commit the new files, and push them. Example:

```powershell
git add .github .gitattributes deploy order-service/Dockerfile order-service/.dockerignore frontend/Dockerfile frontend/.dockerignore frontend/nginx.conf
git commit -m "Add CI and AWS demo deployment configuration"
git push origin main
```

Open the repository's **Actions** tab. `CI` should finish with both `backend` and `frontend` green. The backend job creates an ephemeral PostgreSQL 17 service on port 15432; the tests use their embedded Temporal test server. You do not need to expose your computer or its database to GitHub.

Recommended repository rules: require the two CI checks before merging to `main`. No AWS credentials are needed by PR jobs. Official actions and deployment infrastructure images are pinned; update their hashes deliberately after checking newer versions.

## 4. Create the GitHub environment and read the OIDC subject

In GitHub: **Settings → Environments → New environment**, name it exactly `demo`.

Under deployment branches/tags, select **Selected branches and tags**, and allow only the branch `main`. This restriction matters because the AWS trust policy is bound to the environment. The manual workflow trigger is the initial release control; required reviewers are optional if your account supports them and you want them.

In **Actions → Read AWS OIDC subject → Run workflow**, select `main`. Copy the printed `GitHubOidcSubject=...` value, excluding the `GitHubOidcSubject=` label. The workflow prints only `sub` and `aud`, not the credential token. Do not guess the subject from the repository name: newer repositories can include immutable owner and repository IDs.

## 5. Prepare your AWS account and DNS

Use one AWS region consistently; `ap-southeast-2` (Sydney) is a suitable default for this project. You need:

- An AWS account with permission to create the resources in the template.
- An existing VPC and a **public** subnet in that VPC, with an Internet Gateway route and DNS resolution enabled. A default VPC/subnet is suitable. If the account has no default VPC, create a VPC with a public subnet, Internet Gateway and `0.0.0.0/0` route first; no NAT Gateway is needed for this single public instance.
- A domain whose DNS you control, with two hostnames, for example `demo.example.com` and `temporal.demo.example.com`.
- A chosen budget for EC2, EBS, public IPv4, ECR, S3, traffic and any domain charges. The template defaults to `t3.medium` (4 GiB RAM) and a 30 GiB encrypted root disk; review current regional pricing and set an AWS Budget before creating it.

In IAM **Identity providers**, check whether `token.actions.githubusercontent.com` already exists. If it exists, copy its ARN for the template parameter. Otherwise leave that parameter blank; the template will create it. An account cannot create a duplicate provider with the same URL.

## 6. Create infrastructure using CloudFormation

In AWS **CloudFormation → Create stack → With new resources → Upload a template file**, upload `deploy/cloudformation.yml`.

Use stack name `logistics-demo`. Fill:

| Parameter | Value |
| --- | --- |
| `VpcId` | Your existing VPC ID |
| `PublicSubnetId` | Public subnet in that VPC |
| `GitHubOidcSubject` | Exact subject from step 4 |
| `ExistingOidcProviderArn` | Existing provider ARN, or blank |
| `AmiId` | Keep the Amazon Linux 2023 SSM parameter default |
| `InstanceType` | Start with `t3.medium`; adjust after measuring |
| `ConfigParameterName` | `/logistics-demo/config` |

Review the resources and acknowledge IAM resource creation before creating the stack. This action creates billable infrastructure. Do not create the stack just to run CI.

Wait for `CREATE_COMPLETE`. The instance's installation script can still be finishing. In **EC2 → instance → Connect → Session Manager**, connect and run:

```bash
sudo cloud-init status --wait
sudo test -f /opt/logistics/bootstrap.ready && echo 'Bootstrap ready'
sudo docker compose version
```

If bootstrap failed, read `sudo tail -n 100 /var/log/cloud-init-output.log`. Session Manager requires outbound HTTPS and the instance role supplied by the stack. The template does not open SSH. It pins and checksum-verifies the official Docker Compose installer.

Record the stack **Outputs**. They include the public Elastic IP, instance ID, ECR repository URIs, S3 bucket, role ARN and configuration parameter name.

At your DNS provider, create two **A** records pointing at `PublicIpAddress`:

| Name | Target |
| --- | --- |
| `demo.example.com` | Stack public IPv4 |
| `temporal.demo.example.com` | Same IPv4 |

Use DNS-only records initially. Remove conflicting AAAA records if you have not configured IPv6. Ports 80 and 443 must reach this EC2 instance for certificate issuance. Do not point these records at `127.0.0.1`.

## 7. Store runtime configuration in Parameter Store

Generate a password hash on your own computer (interactive input):

```powershell
docker run --rm -it caddy:2-alpine caddy hash-password
```

Enter a strong demonstration-site password and retain the resulting bcrypt hash. The same login protects both hostnames. Give a reviewer the demo login separately from the public README.

Open AWS **Systems Manager → Parameter Store → Create parameter**:

- Name: `/logistics-demo/config` (or the name from stack Outputs).
- Tier: Standard.
- Type: `SecureString`.
- KMS key: default `alias/aws/ssm` for this template.
- Value: the entire JSON below, with all five values replaced.

```json
{
  "DOMAIN": "demo.example.com",
  "TEMPORAL_DOMAIN": "temporal.demo.example.com",
  "DEMO_USERNAME": "reviewer",
  "DEMO_PASSWORD_HASH": "YOUR_COMPLETE_BCRYPT_HASH",
  "DB_PASSWORD": "YOUR_RANDOM_DATABASE_PASSWORD_AT_LEAST_20_CHARACTERS"
}
```

Use a separate database password. JSON preserves the dollar signs in the hash; do not paste the hash into an interpolating PowerShell double-quoted string. `deploy/config.example.json` is a template only. Real `deploy/config.json` is ignored by Git and Docker images contain neither password.

The EC2 role can decrypt this one parameter. A customer-managed KMS key would additionally need key-policy/IAM permissions. Changing `DB_PASSWORD` after initialization does not change the existing PostgreSQL user's password; coordinate database password rotation instead of simply editing this value.

## 8. Add GitHub environment variables

In **Settings → Environments → demo → Environment variables**, add:

| GitHub variable | CloudFormation Output |
| --- | --- |
| `AWS_REGION` | `AWSRegion` |
| `AWS_ROLE_ARN` | `AWSRoleArn` |
| `ORDER_REPOSITORY_URI` | `OrderRepositoryUri` |
| `FRONTEND_REPOSITORY_URI` | `FrontendRepositoryUri` |
| `RELEASE_BUCKET` | `ReleaseBucketName` |
| `INSTANCE_ID` | `InstanceId` |
| `CONFIG_PARAMETER` | `ConfigParameter` |

These are identifiers, not passwords. Long-lived AWS access keys and the database/site passwords are not required in GitHub Secrets. The deploy job exchanges an OIDC token for short-lived credentials scoped to this stack.

## 9. Publish the first release

In **Actions → Deploy demo to AWS → Run workflow**, select `main`.

The workflow runs CI again, builds Linux AMD64 images, tags both with the full Git commit SHA, pushes to immutable ECR repositories, archives only tracked `deploy` files to private S3, and sends a checksum-verified deployment through SSM. It waits for the SSM command's final result rather than treating submission as success.

The instance reads its configuration, starts the first database/Temporal stack, updates the application, runs a real fulfillment smoke test, and checks public TLS plus password enforcement on both hostnames. Later application deployments do not upgrade the database or Temporal containers. Those require separately planned infrastructure maintenance.

Expected results:

- GitHub reports a successful deploy, with an SSM command ID and smoke-test order ID.
- `https://demo.example.com` prompts for the demo login, then displays the English console.
- `Open Temporal` points to `https://temporal.demo.example.com/namespaces/default/workflows`.
- The failure lab, retries and compensation work after login.
- A new `deploy-*` order demonstrates the deployed backend, not a static mock page.

You can then add your real HTTPS link and CI badge to the root README:

```markdown
[![CI](https://github.com/Weijt133/temporal-logistics-fulfillment/actions/workflows/ci.yml/badge.svg)](https://github.com/Weijt133/temporal-logistics-fulfillment/actions/workflows/ci.yml)

[Live demo](https://YOUR_REAL_DEMO_DOMAIN)

Request the demo login to try failure recovery scenarios. The carrier is simulated.
```

## 10. Routine updates and rollback

Normal update: commit → PR CI → merge to `main` → manually run `Deploy demo to AWS`. Each deployment is serialized, and the instance also uses a deployment lock. Keep the initial manual release trigger until you are comfortable with the entire process.

To enable automatic release later, add the following event alongside `workflow_dispatch` in `deploy.yml`:

```yaml
  push:
    branches: [main]
```

This will run the reusable CI gate before deployment. The separate CI workflow also runs on main, so you can later remove its main push trigger if you want to avoid duplicate CI runs while preserving PR checks.

If application startup, fulfillment verification, or public TLS verification fails, `release.sh` attempts to restore the previous successful application containers and still reports the deployment as failed. The first deployment has no previous release. An identical-SHA redeploy also cannot roll back to itself. Read the logs before retrying.

For an intentional rollback, connect with Session Manager and run (substitute the four infrastructure values):

```bash
OLD_SHA=$(sudo cat /opt/logistics/previous)
sudo bash "/opt/logistics/releases/$OLD_SHA/deploy/release.sh" \
  "$OLD_SHA" \
  ap-southeast-2 \
  ACCOUNT.dkr.ecr.ap-southeast-2.amazonaws.com/logistics-demo-order-service \
  ACCOUNT.dkr.ecr.ap-southeast-2.amazonaws.com/logistics-demo-frontend \
  /logistics-demo/config
```

Rollback changes application images/configuration, not Flyway migrations or Temporal histories. Keep database changes additive/backward compatible. Workflow changes must preserve compatibility with histories already running in the deployment; replay tests cover their recorded fixtures, not every possible future execution. Do not modify executed migration files. Avoid combining destructive schema changes and an automatic rollback release.

## Operations and troubleshooting

Run these in the EC2 Session Manager shell:

```bash
sudo docker ps --filter label=com.docker.compose.project=logistics-demo
sudo docker logs --tail 100 logistics-demo-order-service-1
sudo docker logs --tail 100 logistics-demo-gateway-1
sudo cat /opt/logistics/current
curl -fsS http://127.0.0.1:18080/api/actuator/health
```

| Symptom | Check |
| --- | --- |
| CI cannot connect to PostgreSQL | Service health, port 15432, and `DB_PASSWORD` in `ci.yml` |
| Frontend native dependency missing | Rerun a clean image build; inspect `npm ci` network errors; do not copy Windows `node_modules` into Linux |
| OIDC access denied | Exact subject, audience, `demo` environment, provider ARN and main branch restriction |
| EC2 not listed in Session Manager | Instance role, SSM agent, public subnet route and outbound HTTPS |
| SSM deployment failed | Open its command ID in Run Command and read the final output |
| HTTPS never becomes ready | Both A records, no conflicting AAAA, ports 80/443, gateway logs |
| API returns 502 | Backend health and Nginx service-name resolution |
| Order stays CREATED | Outbox logs and Temporal connectivity; the smoke test must not be bypassed |
| PostgreSQL login fails after changing the parameter | Existing database passwords do not change automatically |

Logs rotate at 10 MB per file, with three files per container. Persistent volumes survive application updates and instance stop/start. They are on the EC2 root disk: terminating/replacing the instance deletes that disk under this template. Before such an operation, take a backup/snapshot and verify restoration. For a consistent whole-stack EBS snapshot, stop the application, Temporal and PostgreSQL containers first, take the snapshot, then start PostgreSQL and Temporal before the application. Store the snapshot ID outside the instance. This demo does not implement scheduled backups or high availability.

Stopping EC2 stops the app but does not eliminate EBS/public IPv4/storage charges. Deleting the CloudFormation stack intentionally retains the ECR repositories and S3 release bucket; clean them up separately only when their images/releases are no longer needed. Never run `docker compose down -v` as a deployment step.

## Scope and verification

Local verification can validate images, proxy routes, workflow behavior, script syntax, and template structure. Your actual AWS OIDC trust, account permissions, DNS, certificate issuance and end-to-end cloud rollout must be verified in your account after the steps above. This bundle does not imply those cloud steps have already run.

## Official references

- [GitHub PostgreSQL service containers](https://docs.github.com/en/actions/tutorials/use-containerized-services/create-postgresql-service-containers)
- [GitHub OIDC on AWS](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws)
- [OIDC subject formats](https://docs.github.com/en/actions/reference/security/oidc)
- [AWS Systems Manager Run Command](https://docs.aws.amazon.com/systems-manager/latest/userguide/run-command.html)
- [Vite static deployments](https://vite.dev/guide/static-deploy)
- [Caddy automatic HTTPS](https://caddyserver.com/docs/automatic-https)
- [Caddy basic authentication](https://caddyserver.com/docs/caddyfile/directives/basic_auth)
- [Temporal production deployments](https://github.com/temporalio/documentation/blob/main/docs/production-deployment/index.mdx)
