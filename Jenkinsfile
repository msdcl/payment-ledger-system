// =============================================================================
// Jenkins Declarative Pipeline
// =============================================================================
//
// WHAT IS A JENKINSFILE?
// A Jenkinsfile defines your build pipeline as code, committed alongside your
// application code. This is "Pipeline as Code" - the same concept as
// .github/workflows/*.yml in GitHub Actions.
//
// BEFORE JENKINSFILES (the old way):
// Developers configured jobs manually in Jenkins UI (click, click, click...).
// This was fragile: settings weren't version-controlled, couldn't be reviewed
// in PRs, and were lost if Jenkins crashed. Jenkinsfile solved all of this.
//
// DECLARATIVE vs SCRIPTED:
// - Declarative (this file): Structured, opinionated, easier to read/maintain
// - Scripted: Full Groovy language, more flexible but complex
// Most new projects use Declarative. You'll see Scripted in legacy codebases.
//
// HOW JENKINS FINDS THIS FILE:
// When you create a "Multibranch Pipeline" job in Jenkins, it scans your
// repository for a file named "Jenkinsfile" in the root. It automatically
// creates a pipeline for each branch that has one.
//
// =============================================================================

pipeline {

    // -------------------------------------------------------------------------
    // AGENT
    // -------------------------------------------------------------------------
    // `agent` tells Jenkins WHERE to run this pipeline.
    //
    // - `agent any`: Run on any available agent/executor (simplest)
    // - `agent { label 'linux' }`: Run only on agents with the "linux" label
    // - `agent { docker { image 'gradle:8-jdk20' } }`: Run inside a Docker container
    // - `agent { kubernetes { ... } }`: Run as a Kubernetes pod (Phase 4!)
    //
    // REAL-WORLD: At scale, teams label agents by capability:
    //   - "linux", "windows", "macos" (for platform-specific builds)
    //   - "gpu" (for ML workloads)
    //   - "high-memory" (for large builds)
    //
    // GitHub Actions equivalent: `runs-on: ubuntu-latest`
    // -------------------------------------------------------------------------
    agent any

    // -------------------------------------------------------------------------
    // TOOLS
    // -------------------------------------------------------------------------
    // No `tools` block needed! Our Dockerfile uses a multi-stage build:
    //   Stage 1 (gradle:8-jdk20): Compiles the code inside the Docker build
    //   Stage 2 (eclipse-temurin:20-jre): Runs the compiled JAR
    //
    // This means Jenkins doesn't need JDK or Gradle installed locally.
    // All it needs is the Docker CLI (to run `docker build`).
    //
    // REAL-WORLD: This is actually the preferred approach in modern CI/CD.
    // Instead of installing build tools on every Jenkins agent, you let
    // Docker handle the build environment. This ensures:
    // - Consistent builds (same Gradle/JDK version everywhere)
    // - No agent configuration drift
    // - Any agent with Docker can build any project
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // ENVIRONMENT
    // -------------------------------------------------------------------------
    // Variables available to ALL stages. Jenkins provides many built-in variables:
    // - BUILD_NUMBER: Auto-incrementing build counter (1, 2, 3...)
    // - GIT_COMMIT: Full SHA of the commit being built
    // - GIT_BRANCH: Branch name (e.g., "origin/master")
    // - WORKSPACE: Absolute path to the build workspace
    //
    // `credentials()`: Securely injects secrets stored in Jenkins Credential Manager.
    // The actual password is never printed in logs (masked with ****).
    //
    // GitHub Actions equivalent: `env:` block + `${{ secrets.MY_SECRET }}`
    // -------------------------------------------------------------------------
    environment {
        APP_NAME        = 'payment-ledger-app'
        DOCKER_IMAGE    = "payment-ledger-app:${BUILD_NUMBER}"
        KIND_CLUSTER    = 'desktop'
        K8S_NAMESPACE   = 'payment-ledger'
    }

    // -------------------------------------------------------------------------
    // OPTIONS
    // -------------------------------------------------------------------------
    // Pipeline-level settings that control behavior.
    //
    // `timestamps()`: Adds timestamps to console output (invaluable for debugging
    //   "the build was slow" - you can see which step took how long)
    //
    // `timeout()`: Kills the build if it exceeds the limit. Without this,
    //   a stuck build could block an agent forever.
    //
    // `disableConcurrentBuilds()`: Prevents two builds of the same branch
    //   from running at once. Important for deployments - you don't want
    //   two deploys racing each other.
    //
    // `buildDiscarder()`: Auto-deletes old builds to save disk space.
    //   Jenkins stores every build's logs, artifacts, and test results.
    //   Without cleanup, disk fills up fast.
    // -------------------------------------------------------------------------
    options {
        timestamps()                                // Prefix console output with timestamps
        timeout(time: 30, unit: 'MINUTES')          // Kill build after 30 minutes
        disableConcurrentBuilds()                   // One build at a time per branch
        buildDiscarder(logRotator(numToKeepStr: '10'))  // Keep only last 10 builds
    }

    // =========================================================================
    // STAGES
    // =========================================================================
    // Stages are the major phases of your pipeline. Each stage appears as a
    // column in Jenkins' "Stage View" visualization - a blue/green/red block
    // showing pass/fail at a glance.
    //
    // PIPELINE FLOW:
    // Checkout → Build → Unit Test → Code Quality → Docker Build → Deploy Dev → Deploy Staging
    //                                                                              ↑
    //                                                                      (manual approval)
    // =========================================================================
    stages {

        // =====================================================================
        // STAGE 1: CHECKOUT
        // =====================================================================
        // In a Multibranch Pipeline, Jenkins automatically checks out your code.
        // This explicit stage is useful when you need to do something extra
        // (like fetching submodules or checking out multiple repos).
        // =====================================================================
        stage('Checkout') {
            steps {
                // `checkout scm` uses the SCM configured in the Jenkins job
                checkout scm
                echo "Building branch: ${env.GIT_BRANCH}"
                echo "Commit: ${env.GIT_COMMIT}"
            }
        }

        // =====================================================================
        // STAGE 2: DOCKER BUILD
        // =====================================================================
        // Builds the Docker image using multi-stage Dockerfile.
        //
        // WHY NO SEPARATE BUILD/TEST STAGES?
        // Our Dockerfile uses a multi-stage build:
        //   Stage 1 (gradle:8-jdk20): Runs `gradle clean build -x test`
        //   Stage 2 (eclipse-temurin:20-jre): Copies the JAR, creates runtime image
        //
        // The Docker build IS the compile step. This is the modern approach:
        // Jenkins doesn't need JDK or Gradle - just Docker.
        //
        // WHY BUILD_NUMBER AS TAG?
        // Jenkins increments BUILD_NUMBER for every build (1, 2, 3...).
        // Combined with the git SHA, you have full traceability:
        //   "Build #42 = commit abc1234 = image payment-ledger-app:42"
        // =====================================================================
        stage('Docker Build') {
            steps {
                sh "docker build -t ${DOCKER_IMAGE} ."
                sh "docker tag ${DOCKER_IMAGE} ${APP_NAME}:latest"
                echo "Built Docker image: ${DOCKER_IMAGE}"
            }
        }

        // =====================================================================
        // STAGE 3: LOAD IMAGE TO KIND
        // =====================================================================
        // Kind (Kubernetes in Docker) runs K8s nodes as Docker containers.
        // These nodes can't pull images from the host's Docker daemon.
        // `kind load docker-image` copies the image from the host Docker
        // into each Kind node's containerd runtime.
        //
        // Without this step, K8s pods would fail with "ErrImagePull" because
        // the deployment uses `imagePullPolicy: Never` (local images only).
        //
        // REAL-WORLD: In production, you'd push to a container registry
        // (Docker Hub, ECR, GCR) instead. Kind load is a local-dev shortcut.
        // =====================================================================
        stage('Load Image to Kind') {
            steps {
                sh "kind load docker-image ${APP_NAME}:latest --name ${KIND_CLUSTER}"
                echo "Image loaded into Kind cluster: ${KIND_CLUSTER}"
            }
        }

        // =====================================================================
        // STAGE 4: DEPLOY TO DEV (Automatic)
        // =====================================================================
        // Deploys to the dev environment using Kustomize overlays.
        //
        // `kubectl apply -k` reads the Kustomization file and applies all
        // resources with environment-specific patches (1 replica, debug logging,
        // smaller resource limits for dev).
        //
        // `kubectl rollout restart` forces K8s to pull the new image.
        // Since we use `imagePullPolicy: Never` and tag `latest`, K8s won't
        // know the image changed unless we trigger a restart.
        //
        // `kubectl rollout status` waits until all new pods are ready,
        // confirming the deployment succeeded.
        // =====================================================================
        stage('Deploy Dev') {
            steps {
                echo "Deploying ${DOCKER_IMAGE} to DEV environment..."
                sh "kubectl apply -k k8s/overlays/dev/"
                sh "kubectl rollout restart deployment/payment-ledger -n ${K8S_NAMESPACE}"
                sh "kubectl rollout status deployment/payment-ledger -n ${K8S_NAMESPACE} --timeout=120s"
                echo "DEV deployment complete!"
            }
        }

        // =====================================================================
        // STAGE 5: DEPLOY TO STAGING (Manual Approval)
        // =====================================================================
        // This is where Jenkins shines with its `input` step.
        //
        // `input` PAUSES the pipeline and waits for a human to click
        // "Proceed" or "Abort" in the Jenkins UI. This is a manual gate
        // for deployments that need human verification.
        //
        // REAL-WORLD USE CASES:
        // - QA team verifies dev deployment before promoting to staging
        // - Tech lead approves staging → production promotion
        // - Change management approval for production deployments
        //
        // GitHub Actions equivalent: "Environment protection rules" with
        // required reviewers (configured in repo settings, not in YAML).
        // =====================================================================
        stage('Deploy Staging') {
            steps {
                input message: 'Deploy to STAGING?', ok: 'Deploy'
                echo "Deploying ${DOCKER_IMAGE} to STAGING environment..."
                sh "kubectl apply -k k8s/overlays/staging/"
                sh "kubectl rollout restart deployment/payment-ledger -n ${K8S_NAMESPACE}"
                sh "kubectl rollout status deployment/payment-ledger -n ${K8S_NAMESPACE} --timeout=120s"
                echo "STAGING deployment complete!"
            }
        }
    }

    // =========================================================================
    // POST-PIPELINE ACTIONS
    // =========================================================================
    // These run after ALL stages complete. Think of this as a "finally" block.
    //
    // Conditions:
    // - `always`: Runs no matter what (cleanup, notifications)
    // - `success`: Only on green build
    // - `failure`: Only on red build
    // - `unstable`: Tests passed but coverage/quality thresholds failed
    // - `changed`: Status changed from previous build (failure→success or vice versa)
    //
    // REAL-WORLD:
    // Most teams send Slack/Teams notifications here. The `changed` condition
    // is particularly useful - it notifies when a build breaks AND when it's fixed,
    // without spamming on every green build.
    // =========================================================================
    post {
        always {
            echo "Pipeline completed. Build #${BUILD_NUMBER}"
        }
        success {
            echo "Build SUCCEEDED! All stages passed."
        }
        failure {
            echo "Build FAILED! Check the logs for details."
        }
    }
}

// =============================================================================
// JENKINS vs GITHUB ACTIONS: SIDE-BY-SIDE COMPARISON
// =============================================================================
//
// | Feature              | Jenkins (this file)            | GitHub Actions              |
// |----------------------|--------------------------------|-----------------------------|
// | File format          | Groovy (Jenkinsfile)           | YAML (.yml)                 |
// | Pipeline definition  | `pipeline { stages { } }`     | `jobs:` in YAML             |
// | Agent/Runner         | `agent any`                    | `runs-on: ubuntu-latest`    |
// | Build steps          | `sh './gradlew build'`         | `run: ./gradlew build`      |
// | Test reporting       | Built-in `junit` step          | Third-party actions         |
// | Manual approval      | `input` step (in pipeline)     | Environment protection rules|
// | Secrets              | `credentials()` from UI        | `${{ secrets.NAME }}`       |
// | Caching              | Plugin-based                   | `actions/cache` or built-in |
// | Parallel execution   | `parallel { }` block           | Multiple jobs (default)     |
// | Conditional stages   | `when { branch 'master' }`     | `if:` conditions            |
// | Post-build actions   | `post { always/failure/... }`  | `if: always()` per step     |
// | Hosting              | Self-hosted (you manage)       | Cloud (GitHub manages)      |
// | Cost model           | Free + infra costs             | Free tier + per-minute      |
//
// WHEN TO USE WHICH?
// - Jenkins: Enterprise, on-prem requirements, complex pipelines, existing Jenkins infra
// - GitHub Actions: Open source, cloud-native, quick setup, GitHub-centric workflow
// - Both: Many companies use Jenkins for core CI/CD and GitHub Actions for auxiliary tasks
//
// =============================================================================
