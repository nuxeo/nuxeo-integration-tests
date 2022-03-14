/*
* (C) Copyright 2022 Nuxeo (http://nuxeo.com/) and others.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
* Contributors:
*     Thomas Fowley
*/
NUXEO_MAJOR_VERSION = '2021'
DEFAULT_CONTAINER = 'ftests'
SLACK_CHANNEL = 'platform-notifs'
NUXEO_WEB_UI_CLONE = 'nuxeo-web-ui-clone'
WEB_UI_BRANCH = 'maintenance-3.0.x'
TEST_ENVIRONMENT = 'default'
TEST_HELM_RELEASE = 'nuxeo'

properties([
  [$class: 'GithubProjectProperty', projectUrlStr: 'https://github.com/nuxeo/nuxeo-integration-tests'],
  [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', daysToKeepStr: '60', numToKeepStr: '60', artifactNumToKeepStr: '5']],
  disableConcurrentBuilds(),
])

def cloneRepo(name, branch, relativePath = name) {
  checkout([$class: 'GitSCM',
    branches: [[name: branch]],
    browser: [$class: 'GithubWeb', repoUrl: 'https://github.com/nuxeo/' + name],
    doGenerateSubmoduleConfigurations: false,
    extensions: [
      [$class: 'RelativeTargetDirectory', relativeTargetDir: relativePath],
      [$class: 'WipeWorkspace'],
      [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false, timeout: 60],
      [$class: 'CheckoutOption', timeout: 60],
      [$class: 'LocalBranch']
    ],
    submoduleCfg: [],
    userRemoteConfigs: [[credentialsId: 'jx-pipeline-git-github-git', url: 'https://github.com/nuxeo/' + name]]
  ])
}

void helmfileSync(namespace, environment) {
  withEnv(["NAMESPACE=${namespace}"]) {
    sh """
      ${HELMFILE_COMMAND} deps
      ${HELMFILE_COMMAND} --environment ${environment} sync
    """
  }
}

void helmfileTemplate(namespace, environment, outputDir) {
  withEnv(["NAMESPACE=${namespace}"]) {
    sh """
      ${HELMFILE_COMMAND} deps
      ${HELMFILE_COMMAND} --environment ${environment} template --output-dir ${outputDir}
    """
  }
}

void helmfileDestroy(namespace, environment) {
  withEnv(["NAMESPACE=${namespace}"]) {
    sh """
      ${HELMFILE_COMMAND} --environment ${environment} destroy
    """
  }
}

void setGitHubBuildStatus(String context, String message, String state) {
  if (env.DRY_RUN != "true") {
    step([
      $class            : 'GitHubCommitStatusSetter',
      reposSource       : [$class: 'ManuallyEnteredRepositorySource', url: repositoryUrl],
      contextSource     : [$class: 'ManuallyEnteredCommitContextSource', context: context],
      statusResultSource: [$class: 'ConditionalStatusResultSource', results: [[$class: 'AnyBuildResult', message: message, state: state]]],
    ])
  }
}

def getNuxeoVersion(isBuild) {
  return isBuild ? "${NUXEO_MAJOR_VERSION}.x" : NUXEO_MAJOR_VERSION
}

def getConnectURL(isBuild) {
  return isBuild ? CONNECT_PREPROD_SITE_URL : CONNECT_PROD_SITE_URL
}

def getWebUIPackageVersion(packageName, isBuild, nuxeoVersion) {
  def targetPlatform = "lts-${nuxeoVersion}"
  // e.g. "nuxeo-web-ui:3.0.8-rc.001" (preprod) or "nuxeo-web-ui:3.0.7" (prod)
  def credentialsId = isBuild ? 'connect-preprod' : 'connect-prod'
  withCredentials([usernameColonPassword(credentialsId: credentialsId, variable: 'CONNECT_AUTH')]) {
    def connectURL = getConnectURL(isBuild)
    def latestPackageURL = "${connectURL}nos-marketplace/orgs/nuxeo/packages/${packageName}/last/${targetPlatform}"
    echo "Marketplace URL to fetch the latest Web UI package for target platform ${targetPlatform} = ${latestPackageURL}"

    def latestPackage = sh(
      returnStdout: true,
      script: 'curl --fail -u $CONNECT_AUTH' + " ${latestPackageURL}"
    )
    echo "Latest Web UI package = ${latestPackage}"

    def version = latestPackage.replaceAll('"', '').tokenize(':')[1]
    echo "Latest Web UI package version = ${version}"
    return version
  }
}

String getCurrentNamespace() {
  container("${DEFAULT_CONTAINER}") {
    return sh(returnStdout: true, script: "kubectl get pod ${NODE_NAME} -ojsonpath='{..namespace}'")
  }
}

String getChromeVersion() {
  container("${DEFAULT_CONTAINER}") {
    return sh(returnStdout: true, script: "google-chrome -version")
  }
}

String getNodeVersion() {
  container("${DEFAULT_CONTAINER}") {
    return sh(returnStdout: true, script: "node -v")
  }
}

void archiveHelmTemplates() {
  archiveArtifacts allowEmptyArchive: true, artifacts: '**/target/**/*.yaml'
}

void archiveScreenshots() {
  archiveArtifacts allowEmptyArchive: true, artifacts: '**/target/screenshots/*.png'
}

void archiveServerLogs(namespace, app, logFile) {
  // don't fail if pod doesn't exist
  sh "kubectl --namespace=${namespace} logs --selector=app=${app} --tail=-1 > ${logFile} || true"
  archiveArtifacts allowEmptyArchive: true, artifacts: "${logFile}"
}

pipeline {
  agent {
    label 'nuxeo-web-ui-ftests'
  }
  environment {
    NUXEO_WEB_UI = 'nuxeo-web-ui'
    DOCKER_DIRECTORY = "${NUXEO_WEB_UI}/docker"
    HELMFILE_COMMAND = "helmfile --file ${NUXEO_WEB_UI}/helm/helmfile.yaml --helm-binary /usr/bin/helm3"
    NUXEO_WEB_UI_VERSION = getWebUIPackageVersion(NUXEO_WEB_UI, params.WEB_UI_BUILD, NUXEO_MAJOR_VERSION)
    NUXEO_IMAGE_VERSION = getNuxeoVersion(params.SERVER_BUILD)
    TEST_IMAGE_VERSION = "${NUXEO_IMAGE_VERSION}-${NUXEO_WEB_UI_VERSION}"
    CURRENT_NAMESPACE = getCurrentNamespace()
    NUXEO_CONNECT_URL = getConnectURL(params.SERVER_BUILD)
    WEB_UI_CONNECT_URL = getConnectURL(params.WEB_UI_BUILD)
    GOOGLE_CHROME_VERSION = getChromeVersion()
    NODE_VERSION = getNodeVersion()
  }
  stages {
    stage('Initialization') {
      steps {
        cloneRepo(NUXEO_WEB_UI, WEB_UI_BRANCH, NUXEO_WEB_UI_CLONE)
      }
    }
   stage('Build Docker Image') {
     steps {
       setGitHubBuildStatus('docker/build', 'Build Docker image', 'PENDING')
       container("${DEFAULT_CONTAINER}") {
         echo """
         ------------------------------------------
         Build nuxeo-web-ui-ftest-sample package
         ------------------------------------------
         """
         echo 'Build package'
         dir(NUXEO_WEB_UI_CLONE) {
           sh 'mvn -B -nsu install -Pftest -pl plugin/itests/addon,plugin/itests/marketplace'
         }
         echo 'Move package to docker directory'
         sh "mv ${NUXEO_WEB_UI_CLONE}/plugin/itests/marketplace/target/nuxeo-web-ui-marketplace-itests-*.zip ${DOCKER_DIRECTORY}"

         echo """
         ------------------------------------------
         Build Docker image
         ------------------------------------------
         Image tag: ${TEST_IMAGE_VERSION}
         Registry: ${DOCKER_REGISTRY}
         Nuxeo image version: ${NUXEO_IMAGE_VERSION}
         Web UI package version: ${NUXEO_WEB_UI_VERSION}
         """
         withCredentials([string(credentialsId: 'instance-clid', variable: 'INSTANCE_CLID')]) {
           script {
             sh "envsubst < ${DOCKER_DIRECTORY}/skaffold.yaml > ${DOCKER_DIRECTORY}/skaffold.yaml~gen"
             sh '''#!/bin/bash +x
               CLID=\$(echo -e "$INSTANCE_CLID" | sed ':a;N;\$!ba;s/\\n/--/g') skaffold build -f $NUXEO_WEB_UI/docker/skaffold.yaml~gen
             '''
           }
         }
       }
     }
     post {
       success {
         setGitHubBuildStatus('docker/build', 'Build Docker image', 'SUCCESS')
       }
       unsuccessful {
         setGitHubBuildStatus('docker/build', 'Build Docker image', 'FAILURE')
       }
     }
   }
    stage('Deploy/Test Docker Image') {
      steps {
        setGitHubBuildStatus('docker/deploy', 'Deploy docker image', 'PENDING')
        container("${DEFAULT_CONTAINER}") {
          script {
            echo """
            ----------------------------------------
            Deploy Docker Image
            ----------------------------------------"""
            // Kubernetes namespace, requires lower case alphanumeric characters
            def testNamespace = "${CURRENT_NAMESPACE}-server-build-web-ui-release-${NUXEO_MAJOR_VERSION.toLowerCase()}"

            // Create namespace
            echo 'Create test namespace'
            sh "kubectl create namespace ${testNamespace}"

            try {
              echo 'Copy image pull secret to test namespace'
              sh "kubectl --namespace=platform get secret kubernetes-docker-cfg -ojsonpath='{.data.\\.dockerconfigjson}' | base64 --decode > /tmp/config.json"
              sh """kubectl create secret generic kubernetes-docker-cfg \
                --namespace=${testNamespace} \
                --from-file=.dockerconfigjson=/tmp/config.json \
                --type=kubernetes.io/dockerconfigjson --dry-run -o yaml | kubectl apply -f -"""


              echo 'Deploy nuxeo and external services'
              try {
                helmfileTemplate("${testNamespace}", "${TEST_ENVIRONMENT}", 'target')
                archiveHelmTemplates()
                helmfileSync("${testNamespace}", "${TEST_ENVIRONMENT}")
              } catch (e) {
                sh """
                  kubectl --namespace=${testNamespace} get event --sort-by .lastTimestamp
                  kubectl --namespace=${testNamespace} get all,configmaps,secrets
                  kubectl --namespace=${testNamespace} describe pod --selector=app=${TEST_HELM_RELEASE}
                  kubectl --namespace=${testNamespace} logs --selector=app=${TEST_HELM_RELEASE}
                """
                throw e
              }

              echo """
              ------------------------------------------
              Run Functional Tests
              ------------------------------------------
              Nuxeo image version: ${NUXEO_IMAGE_VERSION}
              Web UI package version: ${NUXEO_WEB_UI_VERSION}
              Web UI Branch: ${WEB_UI_BRANCH}
              Node Version: ${NODE_VERSION}
              Google Chrome Version: ${GOOGLE_CHROME_VERSION}
              """

              dir(NUXEO_WEB_UI_CLONE){
                def nuxeoServerUrl = "http://nuxeo.${testNamespace}.svc.cluster.local/nuxeo"
                sh """ 
                  npm install
                  npm run ftest -- --nuxeoUrl=${nuxeoServerUrl}
                """
              }
              setGitHubBuildStatus('docker/deploy', 'Deploy docker image', 'SUCCESS')
            } catch (e) {
              archiveScreenshots()
              setGitHubBuildStatus('docker/deploy', 'Deploy docker image', 'FAILURE')
              throw e
            } finally {
              try {
                cucumber buildStatus: 'FAILURE',
                  reportTitle: "Ftests - Nuxeo: ${NUXEO_IMAGE_VERSION}, Web UI: ${NUXEO_WEB_UI_VERSION}",
                  fileIncludePattern: "${NUXEO_WEB_UI_CLONE}/**/*/cucumber-reports/*.json",
                  trendsLimit: 10,
                  classifications: [
                    [
                      'key': 'Browser',
                      'value': "Chrome: ${GOOGLE_CHROME_VERSION}"
                    ],
                    [
                      'key': 'Node Version',
                      'value': "${NODE_VERSION}"
                    ]
                  ]
                archiveServerLogs("${testNamespace}", "${TEST_HELM_RELEASE}", 'server.log')
              } finally {
                echo "Clean up test namespace: ${testNamespace}"
                try {
                  helmfileDestroy("${testNamespace}", "${TEST_ENVIRONMENT}")
                } finally {
                   sh "kubectl delete namespace ${testNamespace} --ignore-not-found=true"
                }
              }
            }
          }
        }
      }
    }
  }
  post {
    success {
      script {
        if (env.DRY_RUN != 'true'
          && !hudson.model.Result.SUCCESS.toString().equals(currentBuild.getPreviousBuild()?.getResult())){
          slackSend(channel: "${SLACK_CHANNEL}", color: 'good', message: "Nuxeo-Integration-Tests: All tests passed for Nuxeo: ${NUXEO_IMAGE_VERSION} & Web UI: ${NUXEO_WEB_UI_VERSION}. #${BUILD_NUMBER}: ${BUILD_URL}")
      }
    }
    unsuccessful {
      script {
        if (env.DRY_RUN != 'true'
          && !hudson.model.Result.SUCCESS.toString().equals(currentBuild.getPreviousBuild()?.getResult())){
          slackSend(channel: "${SLACK_CHANNEL}", color: 'danger', message: "Nuxeo-Integration-Tests: Tests failing for Nuxeo: ${NUXEO_IMAGE_VERSION} & Web UI: ${NUXEO_WEB_UI_VERSION}. #${BUILD_NUMBER}: ${BUILD_URL}")
        }
      }
    }
  }
}
