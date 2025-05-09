pipeline {
    agent any
    parameters {
        string(name: 'Git_Hub_URL', description: 'Enter the GitHub URL')
        string(name: 'AWS_Account_Id', description: 'Enter the AWS Account Id')
        string(name: 'MailToRecipients', description: 'Enter the Mail Id for Approval')
        string(name: 'Docker_File_Name', description: 'Enter the Name of Your Dockerfile (Eg:DockerFile)')
        choice(choices: ["us-east-1","us-east-2","us-west-1","us-west-2","ap-south-1","ap-northeast-3","ap-northeast-2","ap-southeast-1","ap-southeast-2","ap-northeast-1","ca-central-1","eu-central-1","eu-west-1","eu-west-2","eu-west-3","eu-north-1","sa-east-1"],
               description: 'Select your Region Name',
               name: 'Region_Name')  
        string(name: 'ECR_Repo_Name', defaultValue: 'ecr_default_repo', description: 'ECR Repositary (Default: ecr_default_repo)') 
        string(name: 'Version_Number', defaultValue: '1.0', description: 'Enter the Version Number for ECR Image (Default: 1.0)')
        string(name: 'Workspace_name', defaultValue: 'Jenkins_ECR_with_SonarQube_CodeTest', description: 'Workspace name')      
        string(name: 'AWS_Credentials_Id', defaultValue: 'AWS_Credentials', description: 'AWS Credentials Id')
        string(name: 'Git_Credentials_Id', defaultValue: 'Github_Credentials', description: 'Git Credentials Id')
        string(name: 'SONAR_PROJECT_NAME', defaultValue: 'SonarScannerCheck', description: 'Sonar Project Name (Default: SonarScannerCheck)')
        string(name: 'SONAR_TOKEN_ID', defaultValue: 'sonarqube-token', description: 'SonarQube Token Credentials ID')
    }
    
    environment {
        ECR_Credentials = "ecr:${Region_Name}:AWS_Credentials"
        S3_Url = 'https://yamlclusterecs1.s3.amazonaws.com/master.yaml'
        SONAR_HOST_URL = 'http://localhost:9000'
    }
    
    stages {
        stage('Clone the Git Repository') {
            steps {
                git branch: 'main', 
                       credentialsId: "${Git_Credentials_Id}", 
                       url: "${Git_Hub_URL}"
            }
        }
        
        stage('Docker Setup') {
            steps {
                script {
                    sh '''
                    # Ensure Docker access
                    sudo chmod 666 /var/run/docker.sock || true
                    
                    # Start containers
                    docker start sonarqube || docker run -d --name sonarqube -p 9000:9000 -e SONAR_FORCEAUTHENTICATION=false sonarqube
                    docker start zaproxy || docker run -dt --name zaproxy -p 8082:8080 zaproxy/zap-stable:latest /bin/bash
                    docker exec zaproxy mkdir -p /zap/wrk || true
                    curl -s ipinfo.io/ip > ip.txt
                    '''
                }
            }
        }
        
        stage('Wait for SonarQube') {
            steps {
                script {
                    waitUntil {
                        try {
                            def status = sh(script: 'curl -s -o /dev/null -w "%{http_code}" ${SONAR_HOST_URL}/api/system/status', returnStdout: true).trim()
                            return status == "200"
                        } catch (Exception e) {
                            sleep 10
                            return false
                        }
                    }
                }
            }
        }
        
        stage('SonarQube Analysis') {
            steps {
                script {
                    def scannerHome = tool 'sonarqube'
                    
                    // Using token authentication
                    withCredentials([string(credentialsId: "${SONAR_TOKEN_ID}", variable: 'SONAR_TOKEN')]) {
                        withSonarQubeEnv('Default') {
                            sh """
                            ${scannerHome}/bin/sonar-scanner \
                            -Dsonar.projectKey=${SONAR_PROJECT_NAME} \
                            -Dsonar.host.url=${SONAR_HOST_URL} \
                            -Dsonar.login=${SONAR_TOKEN} \
                            -Dsonar.projectBaseDir=${WORKSPACE} \
                            -Dsonar.sources=. \
                            -Dsonar.sourceEncoding=UTF-8
                            """
                        }
                    }
                }
            }
        }
        
        stage('Send Sonar Analysis Report') {
            steps {
                script {
                    def Jenkins_IP = sh(returnStdout: true, script: 'cat ip.txt').trim()
                    emailext (
                        subject: "Approval Needed: ${SONAR_PROJECT_NAME} Analysis",
                        body: """
                        <h2>SonarQube Analysis Report</h2>
                        <p>URL: <a href="http://${Jenkins_IP}:9000/dashboard?id=${SONAR_PROJECT_NAME}">http://${Jenkins_IP}:9000/dashboard?id=${SONAR_PROJECT_NAME}</a></p>
                        <p>Please approve the build process:</p>
                        <p><a href="${BUILD_URL}input/">Approve Build</a></p>
                        """,
                        mimeType: 'text/html',
                        to: "${MailToRecipients}",
                        from: "jenkins@example.com"
                    )
                }
            }
        }
        
        stage('Approval-Build Image') {
            steps {
                timeout(time: 30, unit: 'MINUTES') {
                    input message: 'Approve Docker image build?', 
                          ok: 'Proceed',
                          parameters: [
                              string(name: 'VERSION', description: 'Version to deploy', defaultValue: "${Version_Number}")
                          ]
                }
            }
        }
        
        stage('Create ECR Repository') {
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    credentialsId: "${AWS_Credentials_Id}",
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) 
                {
                    sh """
                    aws ecr create-repository \
                        --repository-name ${ECR_Repo_Name} \
                        --region ${Region_Name} \
                        --image-scanning-configuration scanOnPush=true \
                        --image-tag-mutability MUTABLE || true
                    """
                }
            }
        }
        
        stage('Build and Push to ECR') {
            steps {
                script {
                    def DockerfilePath = sh(script: "find . -name ${Docker_File_Name} | head -1", returnStdout: true).trim()
                    DockerfilePath = DockerfilePath.replaceAll('^\\./', '')
                    
                    withDockerRegistry(credentialsId: "${ECR_Credentials}", 
                                      url: "https://${AWS_Account_Id}.dkr.ecr.${Region_Name}.amazonaws.com") 
                    {
                        sh """
                        # Login to ECR
                        aws ecr get-login-password --region ${Region_Name} | \
                        docker login --username AWS --password-stdin ${AWS_Account_Id}.dkr.ecr.${Region_Name}.amazonaws.com
                        
                        # Build and tag
                        docker build -t ${ECR_Repo_Name} -f ${DockerfilePath} .
                        docker tag ${ECR_Repo_Name}:latest \
                            ${AWS_Account_Id}.dkr.ecr.${Region_Name}.amazonaws.com/${ECR_Repo_Name}:${Version_Number}
                        
                        # Push
                        docker push ${AWS_Account_Id}.dkr.ecr.${Region_Name}.amazonaws.com/${ECR_Repo_Name}:${Version_Number}
                        """
                    }
                }
            }
        }
    }
    
    post {
        always {
            script {
                // Clean up containers
                sh 'docker stop sonarqube zaproxy || true'
                sh 'docker rm sonarqube zaproxy || true'
            }
        }
        failure {
            emailext (
                subject: "FAILED: ${currentBuild.fullDisplayName}",
                body: "Build failed. Check: ${BUILD_URL}",
                to: "${MailToRecipients}"
            )
        }
        success {
            emailext (
                subject: "SUCCESS: ${currentBuild.fullDisplayName}",
                body: "Image pushed to ECR: ${AWS_Account_Id}.dkr.ecr.${Region_Name}.amazonaws.com/${ECR_Repo_Name}:${Version_Number}",
                to: "${MailToRecipients}"
            )
        }
    }
}
