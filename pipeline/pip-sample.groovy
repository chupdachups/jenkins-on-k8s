pipeline { 
	agent any parameters { 
		string(name: 'tag', defaultValue: 'latest', description: 'tag {YYYYMMDD}{HHMMSS}') 
		string(name: 'buildtag', defaultValue: 'v1', description: 'tag {YYYYMMDD}{HHMMSS}') 
		string(name: 'GIT_URL', defaultValue: 'http://192.168.56.101/root/springboot.git', description: 'GIT_URL') 
		booleanParam(name: 'VERBOSE', defaultValue: false, description: '') 
	} 
	environment { 
		GIT_BUSINESS_CD = 'master' 
		GITLAB_CREDENTIAL_ID = 'superuser' 
		IMAGE_REGISTRY = '192.168.56.100:5000' 
		SYSTEM_CODE = 'middleware' 
		SVC_CODE = 'wildfly/wildfly_custom_image' 
		IMAGE_REPO = "${IMAGE_REGISTRY}/${SYSTEM_CODE}/${SVC_CODE}" 
		DOCKER_USERNAME = 'admin' 
		DOCKER_PASSWORD = 'admin123' 
		VERBOSE_FLAG = '-q' 
	} 
	stages{ 
		stage('Preparation') { // for display purposes 
			steps{ 
				script{ 
					env.ymd = sh (returnStdout: true, script: ''' echo `date '+%Y%m%d-%H%M%S'` ''') 
				} 
				echo("params : ${env.ymd} " + params.tag) 
			} 
		} 
		stage('Checkout') { 
			steps{ 
				git(branch: "${env.GIT_BUSINESS_CD}", 
					credentialsId: "${env.GITLAB_CREDENTIAL_ID}", 
					url: params.GIT_URL, 
					changelog: false, 
					poll: false) 
			} 
		} 
		stage('SonarQube analysis') { 
			steps{ 
				withSonarQubeEnv('SonarQube-Server'){ 
					sh "mvn sonar:sonar -Dsonar.projectKey=demo -Dsonar.host.url=http://192.168.123.141:9000 -Dsonar.login=03a3d935387d5a8bb8894ff0a0f282055f39466a" 
				} 
			} 
		} 
//		stage('SonarQube Quality Gate'){ 
//			steps{ 
//				timeout(time: 1, unit: 'MINUTES') { 
//					script{ 
//						echo "Start~~~~" 
//						def qg = waitForQualityGate() 
//						echo "Status: ${qg.status}" 
//						if(qg.status != 'OK') { 
//							echo "NOT OK Status: ${qg.status}" 
//							updateGitlabCommitStatus(name: "SonarQube Quality Gate", state: "failed") error "Pipeline aborted due to quality gate failure: ${qg.status}" 
//						} 
//						else{ 
//							echo "OK Status: ${qg.status}" 
//							updateGitlabCommitStatus(name: "SonarQube Quality Gate", state: "success") 
//						} 
//						echo "End~~~~" 
//					} 
//				} 
//			} 
//		} 
		stage('Build') { 
			steps{ 
				sh 'mvn clean package' 
			} 
		} 
		stage('Docker build') { 
			steps{ 
				script { 
					env.IMAGE_TAG = "${params.tag}" 
					env.IMAGE_LOC = env.IMAGE_REPO + ':' + env.IMAGE_TAG 
				} 
				sh "rm -rf docker ; mkdir docker" 
				sh "cp target/template.war docker/springboot.war" 
				sh "cp wboot.sh docker/wboot.sh" 
				sh "cp Dockerfile docker/" 
				sh "chmod -R 775 docker" 
				dir('docker') { 
					sh "docker login -u ${DOCKER_USERNAME} -p ${DOCKER_PASSWORD} ${IMAGE_REGISTRY}" 
					echo "docker build to ${IMAGE_LOC}" 
					sh "docker build -t ${IMAGE_LOC} ${env.VERBOSE_FLAG} --rm --force-rm --pull --network host ." 
					echo "docker push" 
					sh "docker push ${IMAGE_LOC}" 
				} 
			} 
		} 
		stage('Kubernetes deploy') { 
			steps { 
				kubernetesDeploy configs: "deployment.yaml", 
				kubeconfigId: 'springboot' sh "kubectl --kubeconfig=/root/.jenkins/.kube/config rollout restart deployment/wildfly-deployment" 
			} 
		} 
		stage('Clear') { 
			steps { 
				sh "docker rmi \$(docker images -f 'dangling=true' -q)" 
			} 
		} 
	} 
}
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							
							