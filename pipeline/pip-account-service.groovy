pipeline {
  agent {
    kubernetes {
      label 'account-service'
      defaultContainer 'jnlp'
      yaml """
apiVersion: v1
kind: Pod
metadata:
labels:
  component: cicd
spec:
  # Use service account that can deploy to all namespaces
  serviceAccountName: jenkins-worker
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
  - name: curl
    image: alpine/curl:3.14
    command:
    - cat
    tty: true
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
		     git clone -b k8s https://github.com/chupdachups/account-backend-app.git account-service
		   """
		 }
	   }
	}
    stage('SrcBuild') {
      steps {
        container('maven') {
          sh """
			mvn -f account-service/pom.xml package -DskipTests
          """
        }
      }
    }
    stage('ImageBuild') {
      steps {
        container('docker') {
          sh """
             docker build -t chupdachups/account-service:$BUILD_NUMBER -f account-service/dockerfile account-service
             docker tag chupdachups/account-service:$BUILD_NUMBER chupdachups/account-service:latest
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
             docker push chupdachups/account-service:$BUILD_NUMBER
             docker push chupdachups/account-service:latest
          """
        }
      }
    }
    stage('deploy-delete') {
	  steps {
	    container('curl') {
		  sh """
		    #?????? API ?????? ????????? ????????? ????????????
			APISERVER=https://kubernetes.default.svc
			SERVICEACCOUNT=/var/run/secrets/kubernetes.io/serviceaccount
			NAMESPACE=\$(cat \${SERVICEACCOUNT}/namespace)
			TOKEN=\$(cat \${SERVICEACCOUNT}/token)
			CACERT=\${SERVICEACCOUNT}/ca.crt
			curl --cacert \${CACERT} \\
			--header "Authorization: Bearer \${TOKEN}" \\
			-X DELETE \${APISERVER}/apis/apps/v1/namespaces/msa-service/deployments/account-service \\
		  """
		}
	  }
	}
	stage('deploy-deploy') {
	  steps {
	    container('curl') {
		  sh """
		    #?????? API ?????? ????????? ????????? ????????????
			APISERVER=https://kubernetes.default.svc

			#?????????????????????(ServiceAccount) ?????? ??????
			SERVICEACCOUNT=/var/run/secrets/kubernetes.io/serviceaccount

			#??? ????????? ????????????????????? ?????????
			NAMESPACE=\$(cat \${SERVICEACCOUNT}/namespace)

			#????????????????????? ????????? ????????? ?????????
			TOKEN=\$(cat \${SERVICEACCOUNT}/token)

			#?????? ?????? ??????(CA)??? ????????????
			CACERT=\${SERVICEACCOUNT}/ca.crt
			
			# TOKEN?????? API??? ????????????
			curl --cacert \${CACERT} \\
			--header "Authorization: Bearer \${TOKEN}" \\
			-X POST \${APISERVER}/apis/apps/v1/namespaces/msa-service/deployments \\
			--header 'Content-Type: application/json' \\
			--data-raw '{
  "apiVersion": "apps/v1",
  "kind": "Deployment",
  "metadata": {
    "name": "account-service",
    "namespace": "msa-service",
    "labels": {
      "app": "account-service"
    }
  },
  "spec": {
    "replicas": 1,
    "selector": {
      "matchLabels": {
        "app": "account-service"
      }
    },
    "template": {
      "metadata": {
        "labels": {
          "app": "account-service"
        }
      },
      "spec": {
        "containers": [
          {
            "name": "account-service",
            "image": "chupdachups/account-service:latest",
            "ports": [
              {
                "containerPort": 8070
              }
            ]
          }
        ]
      }
    }
  }
}'
		  """
		}
	  }
	}
	stage('deploy-service') {
	  steps {
	    container('curl') {
		  sh """
			APISERVER=https://kubernetes.default.svc
			SERVICEACCOUNT=/var/run/secrets/kubernetes.io/serviceaccount
			NAMESPACE=\$(cat \${SERVICEACCOUNT}/namespace)
			TOKEN=\$(cat \${SERVICEACCOUNT}/token)
			CACERT=\${SERVICEACCOUNT}/ca.crt
			curl --cacert \${CACERT} \\
			--header "Authorization: Bearer \${TOKEN}" \\
			-X POST \${APISERVER}/api/v1/namespaces/msa-service/services \\
			--header 'Content-Type: application/json' \\
			--data-raw '{
  "apiVersion": "v1",
  "kind": "Service",
  "metadata": {
    "name": "account-service",
    "namespace": "msa-service"
  },
  "spec": {
    "type": "ClusterIP",
    "ports": [
      {
        "port": 8070,
        "targetPort": 8070
      }
    ],
    "selector": {
      "app": "account-service"
    }
  }
}'
		  """
		}
	  }
	}
    stage('rollout') {
	  steps {
	    container('curl') {
		  sh """
			APISERVER=https://kubernetes.default.svc
			SERVICEACCOUNT=/var/run/secrets/kubernetes.io/serviceaccount
			NAMESPACE=\$(cat \${SERVICEACCOUNT}/namespace)
			TOKEN=\$(cat \${SERVICEACCOUNT}/token)
			CACERT=\${SERVICEACCOUNT}/ca.crt
			curl --cacert \${CACERT} \\
			--header "Authorization: Bearer \${TOKEN}" \\
			-X PATCH \${APISERVER}/apis/apps/v1/namespaces/msa-service/deployments/account-service \\
			--header 'Content-Type: application/strategic-merge-patch+json' \\
			--data-raw '{
    "spec": {
        "template": {
            "metadata": {
                "annotations": {
                    "kubectl.kubernetes.io/restartedAt": "<time.Now()>"
                }
            }
        }
    }
}'
		  """
		}
      }
	}
  }
}