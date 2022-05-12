pipeline {
  agent {
    kubernetes {
      label 'front-end'
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
  - name: node
    image: node:16.15-alpine
    command:
    - cat
    tty: true
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
"""
}
   }
   stages {
    stage ('CheckOut') {
	   steps {
	     container ('git') {
		   sh """
		     git clone -b k8s https://github.com/chupdachups/vue-prontend-example.git front-end
		   """
		 }
	   }
	}
    stage('SrcBuild') {
      steps {
        container('node') {
          sh """
			cd ./front-end
			npm install
			export NODE_ENV=production
			npm run build
			cd ..
          """
        }
      }
    }
    stage('ImageBuild') {
      steps {
        container('docker') {
          sh """
             docker build -t chupdachups/front-end-app:$BUILD_NUMBER -f front-end/dockerfile front-end
             docker tag chupdachups/front-end-app:$BUILD_NUMBER chupdachups/front-end-app:latest
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
             docker push chupdachups/front-end-app:$BUILD_NUMBER
             docker push chupdachups/front-end-app:latest
          """
        }
      }
    }
    stage('deploy-delete') {
	  steps {
	    container('curl') {
		  sh """
		    #내부 API 서버 호스트 이름을 가리킨다
			APISERVER=https://kubernetes.default.svc
			SERVICEACCOUNT=/var/run/secrets/kubernetes.io/serviceaccount
			NAMESPACE=\$(cat \${SERVICEACCOUNT}/namespace)
			TOKEN=\$(cat \${SERVICEACCOUNT}/token)
			CACERT=\${SERVICEACCOUNT}/ca.crt
			curl --cacert \${CACERT} \\
			--header "Authorization: Bearer \${TOKEN}" \\
			-X DELETE \${APISERVER}/apis/apps/v1/namespaces/msa-service/deployments/front-end-app \\
		  """
		}
	  }
	}
	stage('deploy-deploy') {
	  steps {
	    container('curl') {
		  sh """
		    #내부 API 서버 호스트 이름을 가리킨다
			APISERVER=https://kubernetes.default.svc

			#서비스어카운트(ServiceAccount) 토큰 경로
			SERVICEACCOUNT=/var/run/secrets/kubernetes.io/serviceaccount

			#이 파드의 네임스페이스를 읽는다
			NAMESPACE=\$(cat \${SERVICEACCOUNT}/namespace)

			#서비스어카운트 베어러 토큰을 읽는다
			TOKEN=\$(cat \${SERVICEACCOUNT}/token)

			#내부 인증 기관(CA)을 참조한다
			CACERT=\${SERVICEACCOUNT}/ca.crt
			
			# TOKEN으로 API를 탐색한다
			curl --cacert \${CACERT} \\
			--header "Authorization: Bearer \${TOKEN}" \\
			-X POST \${APISERVER}/apis/apps/v1/namespaces/msa-service/deployments \\
			--header 'Content-Type: application/json' \\
			--data-raw '{
  "apiVersion": "apps/v1",
  "kind": "Deployment",
  "metadata": {
    "name": "front-end-app",
    "namespace": "msa-service",
    "labels": {
      "app": "front-end-app"
    }
  },
  "spec": {
    "replicas": 1,
    "selector": {
      "matchLabels": {
        "app": "front-end-app"
      }
    },
    "template": {
      "metadata": {
        "labels": {
          "app": "front-end-app"
        }
      },
      "spec": {
        "containers": [
          {
            "name": "front-end-app",
            "image": "chupdachups/front-end-app:$BUILD_NUMBER",
            "ports": [
              {
                "containerPort": 80
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
    "name": "front-end-app",
    "namespace": "msa-service"
  },
  "spec": {
    "type": "NodePort",
    "ports": [
      {
        "port": 8080,
        "targetPort": 80,
		"nodePort": 32000
      }
    ],
    "selector": {
      "app": "front-end-app"
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
			-X PATCH \${APISERVER}/apis/apps/v1/namespaces/msa-service/deployments/front-end-app \\
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