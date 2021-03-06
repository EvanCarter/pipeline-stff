// This is a Declarative Jenkinsfile to be used in the homework
// It should map very well to what you learned in class.
// Implement the sections marked with "TBD:"


pipeline {
  agent {
    kubernetes {
      label "maven-skopeo-agent"
      cloud "openshift"
      inheritFrom "maven"
      containerTemplate {
        name "jnlp"
        image "image-registry.openshift-image-registry.svc:5000/2c25-jenkins/jenkins-agent-appdev:latest"
        resourceRequestMemory "2Gi"
        resourceLimitMemory "2Gi"
        resourceRequestCpu "2"
        resourceLimitCpu "2"
      }
    }
  }
  environment { 
    // Define global variables
    // Set Maven command to always include Nexus Settings
    // NOTE: Somehow an inline pod template in a declarative pipeline
    //       needs the "scl_enable" before calling maven.
    mvnCmd = "source /usr/local/bin/scl_enable && mvn -s ./nexus_settings.xml"
     GUID = "2c25"
    // Images and Projects
    imageName   = "${GUID}-tasks"
    devProject  = "${GUID}-tasks-dev"
    prodProject = "${GUID}-tasks-prod"

    // Tags
    devTag      = "0.0-0"
    prodTag     = "0.0"
    
    // Blue-Green Settings
    destApp     = "tasks-green"
    activeApp   = ""
  }
  stages {
    // Checkout Source Code.
    stage('Checkout Source') {
      steps {
        checkout scm

        dir('openshift-tasks') {
          script {
	    echo "${GUID} django"
            def pom = readMavenPom file: 'pom.xml'
            def version = pom.version
            
            // Set the tag for the development image: version + build number
            devTag  = "${version}-" + currentBuild.number
            // Set the tag for the production image: version
            prodTag = "${version}"

            // Patch Source artifactId to include GUID
            sh "sed -i 's/GUID/${GUID}/g' ./pom.xml"
          }
        }
      }
    }

    // Build the Tasks Application in the directory 'openshift-tasks'
    stage('Build war') {
      steps {
        dir('openshift-tasks') {
          echo "Building version ${devTag}"
          script {

            //TBD: Execute the Maven Build
             sh "${mvnCmd} clean package -DskipTests=true"
          }
        }
      }
    }

    // Using Maven run the unit tests
    stage('Unit Tests') {
      steps {
        dir('openshift-tasks') {
          echo "Running Unit Tests"

        // TBD: Execute Unit Tests
         sh "${mvnCmd} test"
        }
      }
    }
    // Using Maven call SonarQube for Code Analysis
    stage('Code Analysis') {
      steps {
        dir('openshift-tasks') {
          script {
            echo "Running Code Analysis"
            sh "${mvnCmd} sonar:sonar -Dsonar.host.url=http://homework-sonarqube.apps.shared.na.openshift.opentlc.com/ -Dsonar.projectName=${GUID}-${JOB_BASE_NAME}-${devTag} -Dsonar.projectVersion=${devTag}"
            // TBD: Execute Sonarqube Tests
            //      Your project name should be "${GUID}-${JOB_BASE_NAME}-${devTag}"
            //      Your project version should be ${devTag}

          }
        }
      }
    }
    // Publish the built war file to Nexus
    stage('Publish to Nexus') {
      steps {
        dir('openshift-tasks') {
          echo "Publish to Nexus"
          sh "${mvnCmd} deploy -DskipTests=true -DaltDeploymentRepository=nexus::default::http://homework-nexus.gpte-hw-cicd.svc.cluster.local:8081/repository/releases"
          // TBD: Publish to Nexus

        }
      }
    }
    // Build the OpenShift Image in OpenShift and tag it.
    stage('Build and Tag OpenShift Image') {
      steps {
        dir('openshift-tasks') {
          echo "Building OpenShift container image ${imageName}:${devTag} in project ${devProject}."

          script {
            // TBD: Build Image (binary build), tag Image
            //      Make sure the image name is correct in the tag!

          openshift.withCluster() {
           openshift.withProject("${devProject}") {
            openshift.selector("bc", "tasks").startBuild("--from-file=./target/openshift-tasks.war", "--wait=true")

          // OR use the file you just published into Nexus:

             openshift.tag("${GUID}-tasks:latest", "${imageName}:${devTag}")
          }
        }  
          }
        }
      }
    }
    // Deploy the built image to the Development Environment.
    stage('Deploy to Dev') {
      steps {
        dir('openshift-tasks') {
          echo "Deploying container image to Development Project"
          script {

            // TBD: Deploy to development Project
            //      Set Image, Set VERSION
            //      (Re-) Create ConfigMap
            //      Make sure the application is running and ready before proceeding
		      openshift.withCluster() {
        openshift.withProject("${devProject}") {
          // OpenShift 4
          openshift.set("image", "dc/tasks", "tasks=image-registry.openshift-image-registry.svc:5000/${devProject}/${imageName}:${devTag}")
		  if (openshift.selector("configmap", "tasks-config").exists()){
          openshift.selector("configmap", "tasks-config").delete()
        }
          def configmap = openshift.create('configmap', 'tasks-config', '--from-file=./configuration/application-users.properties', '--from-file=./configuration/application-roles.properties' )

          // Deploy the development application.
          openshift.selector("dc", "tasks").rollout().latest();

          // Wait for application to be deployed
          def dc = openshift.selector("dc", "tasks").object()
          def dc_version = dc.status.latestVersion
          def rc = openshift.selector("rc", "tasks-${dc_version}").object()

          echo "Waiting for ReplicationController tasks-${dc_version} to be ready"
          while (rc.spec.replicas != rc.status.readyReplicas) {
            sleep 5
            rc = openshift.selector("rc", "tasks-${dc_version}").object()
          }
        }
      }
          }}}}
        
      
    

    // Copy Image to Nexus Container Registry
    stage('Copy Image to Nexus Container Registry') {
      steps {
        echo "Copy image to Nexus Container Registry"
        script {
                      sh "skopeo copy --src-tls-verify=false --dest-tls-verify=false --src-creds openshift:\$(oc whoami -t) --dest-creds admin:redhat docker://image-registry.openshift-image-registry.svc.cluster.local:5000/${devProject}/${imageName}:${devTag} docker://homework-nexus-registry.gpte-hw-cicd.svc.cluster.local:5000/tasks:${imageName}"
            
            openshift.withCluster() {
        openshift.withProject("${prodProject}") {
          openshift.tag("${devProject}/${imageName}:${devTag}", "${devProject}/${imageName}:${prodTag}")
        }
            }
          // TBD: Copy image to Nexus container registry

          // TBD: Tag the built image with the production tag.

        }
      }
    }

    // Blue/Green Deployment into Production
    // -------------------------------------
    stage('Blue/Green Production Deployment') {
      steps {
        script {

          // TBD: Determine which application is active
          //      Set Image, Set VERSION
          //      (Re-)create ConfigMap
          //      Deploy into the other application
          //      Make sure the application is running and ready before proceeding
                       openshift.withCluster() {
                openshift.withProject("${prodProject}") {
                    activeApp = openshift.selector("route", "tasks").object().spec.to.name
                    if (activeApp == "tasks-green") {
                        destApp = "tasks-blue"
                    }
                    echo "Active Application:      " + activeApp
                    echo "Destination Application: " + destApp
                    
                    def dc = openshift.selector("dc/${destApp}").object()
                    echo "1"
                    dc.spec.template.spec.containers[0].image="image-registry.openshift-image-registry.svc:5000/${devProject}/${imageName}:${prodTag}"
                    echo "2"
                    openshift.apply(dc)
                    echo "3"
				 if (openshift.selector("configmap", "${destApp}-config").exists()){
                    openshift.selector("configmap", "${destApp}-config").delete()
				}
					echo "x"
					echo "pwd"
                    def configmap = openshift.create("configmap", "${destApp}-config", "--from-file=openshift-tasks/configuration/application-users.properties", "--from-file=openshift-tasks/configuration/application-roles.properties" )
                    echo "4"
                    openshift.selector("dc", "${destApp}").rollout().latest();
                    def dc_prod = openshift.selector("dc", "${destApp}").object()
                    def dc_version = dc_prod.status.latestVersion
                    echo "5"
                    def rc_prod = openshift.selector("rc", "${destApp}-${dc_version}").object()
                    echo "6"
                    echo "Waiting for ${destApp} to be ready"
                    
                    while (rc_prod.spec.replicas != rc_prod.status.readyReplicas) {
                        sleep 5
                        rc_prod = openshift.selector("rc", "${destApp}-${dc_version}").object()
                        }
                }
            }
        }
      }
    }

    stage('Switch over to new Version') {
      steps{
        echo "Switching Production application to ${destApp}."
        script {
          // TBD: Execute switch
          //      Hint: sleep 5 seconds after the switch
          //            for the route to be fully switched over
                openshift.withCluster() {
        openshift.withProject("${prodProject}") {
        }
      }
        }
      }
    }
  }
}