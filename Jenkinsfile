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
    // `tools` auto-installs the specified tools on the agent.
    // Jenkins must have these tools configured in Global Tool Configuration.
    //
    // This is one area where Jenkins differs from GitHub Actions:
    // - GitHub Actions: `uses: actions/setup-java@v4` (downloaded on the fly)
    // - Jenkins: Tools pre-configured in Jenkins settings (persistent)
    // -------------------------------------------------------------------------
    tools {
        jdk 'JDK20'                // Must match name in Jenkins Global Tool Configuration
        gradle 'Gradle8'           // Must match name in Jenkins Global Tool Configuration
    }

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
        APP_NAME        = 'payment-ledger'
        DOCKER_IMAGE    = "payment-ledger:${BUILD_NUMBER}"
        // credentials() pulls secrets from Jenkins Credential Manager
        // You'd create these in Jenkins UI: Manage Jenkins → Credentials
        // DOCKER_CREDS = credentials('docker-registry-credentials')
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
        // STAGE 2: BUILD
        // =====================================================================
        // Compiles the code and creates the JAR artifact.
        // `-x test` skips tests here - we run them in a dedicated stage.
        //
        // WHY SEPARATE BUILD AND TEST?
        // Same reason as in GitHub Actions: clearer failure diagnosis.
        // If this stage fails → compilation error.
        // If next stage fails → test failure.
        // =====================================================================
        stage('Build') {
            steps {
                sh './gradlew clean build -x test --no-daemon'
            }
        }

        // =====================================================================
        // STAGE 3: UNIT TESTS
        // =====================================================================
        // Runs tests and publishes results to Jenkins UI.
        //
        // KEY DIFFERENCE FROM GITHUB ACTIONS:
        // Jenkins has BUILT-IN test result visualization. The `junit` step
        // parses JUnit XML reports and shows:
        // - Test trend graphs (pass rate over time)
        // - Failure details with stack traces
        // - Test duration tracking
        //
        // In GitHub Actions, you need third-party actions for this.
        // This is one area where Jenkins genuinely excels.
        // =====================================================================
        stage('Unit Tests') {
            steps {
                sh './gradlew test --no-daemon'
            }
            // `post` runs AFTER the steps, regardless of pass/fail
            post {
                always {
                    // Publish JUnit test results to Jenkins UI
                    // Jenkins parses the XML and creates interactive test reports
                    junit testResults: '**/build/test-results/test/*.xml',
                         allowEmptyResults: true

                    // Archive the HTML test report for download
                    archiveArtifacts artifacts: 'build/reports/tests/**',
                                     allowEmptyArchive: true
                }
            }
        }

        // =====================================================================
        // STAGE 4: CODE QUALITY
        // =====================================================================
        // Generates JaCoCo coverage report and publishes it to Jenkins.
        //
        // Jenkins + JaCoCo plugin shows:
        // - Coverage trend graphs (how coverage changes over time)
        // - Per-class/method coverage breakdown
        // - Diff coverage (what's covered in THIS commit vs previous)
        //
        // REAL-WORLD: Many teams also integrate SonarQube here for
        // static code analysis (code smells, security vulnerabilities, duplication).
        // =====================================================================
        stage('Code Quality') {
            steps {
                sh './gradlew jacocoTestReport --no-daemon'
            }
            post {
                always {
                    // Publish JaCoCo coverage report to Jenkins UI
                    // The JaCoCo plugin must be installed in Jenkins
                    jacoco(
                        execPattern: '**/build/jacoco/test.exec',
                        classPattern: '**/build/classes',
                        sourcePattern: '**/src/main/java',
                        changeBuildStatus: true,
                        minimumLineCoverage: '30'         // Fail if below 30%
                    )
                }
            }
        }

        // =====================================================================
        // STAGE 5: DOCKER BUILD
        // =====================================================================
        // Builds the Docker image and tags it with the Jenkins BUILD_NUMBER.
        //
        // WHY BUILD_NUMBER AS TAG?
        // Jenkins increments BUILD_NUMBER for every build (1, 2, 3...).
        // This gives you a simple, human-readable version number.
        // Combined with the git SHA, you have full traceability:
        //   "Build #42 = commit abc1234 = image payment-ledger:42"
        //
        // REAL-WORLD: Some teams tag with both:
        //   payment-ledger:42
        //   payment-ledger:sha-abc1234
        //   payment-ledger:latest
        // =====================================================================
        stage('Docker Build') {
            steps {
                sh "docker build -t ${DOCKER_IMAGE} ."
                sh "docker tag ${DOCKER_IMAGE} ${APP_NAME}:latest"
                echo "Built Docker image: ${DOCKER_IMAGE}"
            }
        }

        // =====================================================================
        // STAGE 6: DEPLOY TO DEV (Automatic)
        // =====================================================================
        // Automatically deploys to the dev environment after a successful build.
        //
        // `when { branch 'master' }`: Only run this stage on the master branch.
        // Feature branches build and test, but don't deploy.
        //
        // GitHub Actions equivalent: `if: github.ref == 'refs/heads/master'`
        // =====================================================================
        stage('Deploy Dev') {
            when {
                branch 'master'
            }
            steps {
                echo "Deploying ${DOCKER_IMAGE} to DEV environment..."
                // In a real setup, this would be:
                // sh 'kubectl apply -k k8s/overlays/dev/'
                // or
                // sh 'docker compose -f docker-compose.yml up -d app'
                echo "DEV deployment complete!"
            }
        }

        // =====================================================================
        // STAGE 7: DEPLOY TO STAGING (Manual Approval)
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
        //
        // JENKINS ADVANTAGE: The input step is inline in the pipeline code,
        // making the approval flow visible and version-controlled. In GitHub
        // Actions, approval rules are configured separately in the UI.
        // =====================================================================
        stage('Deploy Staging') {
            when {
                branch 'master'
            }
            steps {
                // `input` blocks the pipeline until someone clicks Proceed/Abort
                input message: 'Deploy to STAGING?',
                      ok: 'Deploy',
                      submitter: 'admin'    // Only 'admin' user can approve
                echo "Deploying ${DOCKER_IMAGE} to STAGING environment..."
                // sh 'kubectl apply -k k8s/overlays/staging/'
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
            // Clean up workspace to save disk space on the agent
            cleanWs()
        }
        success {
            echo "Build SUCCEEDED! All stages passed."
            // In a real setup:
            // slackSend channel: '#builds',
            //           color: 'good',
            //           message: "✅ ${APP_NAME} Build #${BUILD_NUMBER} succeeded"
        }
        failure {
            echo "Build FAILED! Check the logs for details."
            // In a real setup:
            // slackSend channel: '#builds',
            //           color: 'danger',
            //           message: "❌ ${APP_NAME} Build #${BUILD_NUMBER} failed"
        }
        changed {
            // This is the most useful notification: "something changed"
            // - Build was failing, now it's fixed → notify team
            // - Build was passing, now it broke → notify team
            echo "Build status CHANGED from previous build!"
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
