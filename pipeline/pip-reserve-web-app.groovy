pipeline {
  agent {
    kubernetes {
      label 'reserve-web-app'
      defaultContainer 'jnlp'
      yaml """
apiVersion: v1
kind: Pod
metadata:
labels:
  component: ci
spec:
  # Use service account that can deploy to all namespaces
  serviceAccountName: jenkins-agent
  containers:
  - name: git
    image: alpine/git:v2.34.2
    command:
    - cat
    tty: true
  - name: maven
    image: maven:3.8.4-openjdk-8-slim
    command:
    - cat
    tty: true
    volumeMounts:
      - mountPath: "/root/.m2"
        name: m2
  - name: docker
    image: docker:20.10.14-dind-alpine3.15
    command:
    - cat
    tty: true
    volumeMounts:
    - mountPath: /var/run/docker.sock
      name: docker-sock
  volumes:
    - name: docker-sock
      hostPath:
        path: /var/run/docker.sock
    - name: m2
      persistentVolumeClaim:
        claimName: m2
"""
}
   }
   stages {
    stage ('CheckOut') {
	   steps {
	     container ('git') {
		   sh """
		     git clone -b docker-build https://github.com/chupdachups/reserve-web-app.git
		   """
		 }
	   }
	}
    stage('SrcBuild') {
      steps {
        container('maven') {
          sh """
	mvn -f reserve-web-app/pom.xml package -DskipTests
          """
        }
      }
    }
//    stage('Test') {
//      steps {
//       container('maven') {
//          sh """
//             mvn test
//          """
//        }
//      }
//    }
    stage('ImageBuild') {
      steps {
        container('docker') {
          sh """
             docker build -t chupdachups/reserve-web-app:$BUILD_NUMBER -f reserve-web-app/dockerfile reserve-web-app
          """
        }
      }
    }
    stage('DockerLogin') {
      steps {
        container('docker') {
          sh """
             docker login -u chupdachups -p '!Kamika911'
          """
        }
      }
    }
    stage('ImagePush') {
      steps {
        container('docker') {
          sh """
             docker push chupdachups/reserve-web-app:$BUILD_NUMBER
          """
        }
      }
    }
  }
}
